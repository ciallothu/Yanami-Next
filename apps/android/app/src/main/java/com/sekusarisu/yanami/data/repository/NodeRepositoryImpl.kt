package com.sekusarisu.yanami.data.repository

import com.sekusarisu.yanami.data.remote.KomariRpcService
import com.sekusarisu.yanami.data.remote.dto.NodeInfoDto
import com.sekusarisu.yanami.data.remote.dto.NodeStatusDto
import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.model.CustomHeader
import com.sekusarisu.yanami.domain.model.LoadRecord
import com.sekusarisu.yanami.domain.model.Node
import com.sekusarisu.yanami.domain.model.PingRecord
import com.sekusarisu.yanami.domain.model.PingTask
import com.sekusarisu.yanami.domain.model.resetAwareCounterDelta
import com.sekusarisu.yanami.domain.repository.NodeRepository
import com.sekusarisu.yanami.domain.repository.NodeRepository.NodeDetailWsEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Merge a Komari latest-status sample without allowing duplicate or out-of-order responses to
 * rewind counters. A timestamped sample must be strictly newer. For legacy undated samples we
 * require monotonic evidence (uptime or cumulative counters) before accepting an update.
 */
internal fun mergeLatestNodeStatus(node: Node, status: NodeStatusDto): Node {
    if (!shouldAcceptLatestNodeStatus(node, status)) return node

    val incomingTime = status.time.trim()
    return node.copy(
            isOnline = status.online,
            statusTime = incomingTime.ifBlank { node.statusTime },
            cpuUsage = status.cpu,
            memUsed = status.ram,
            memTotal = if (status.ramTotal > 0) status.ramTotal else node.memTotal,
            swapUsed = status.swap,
            swapTotal = if (status.swapTotal > 0) status.swapTotal else node.swapTotal,
            diskUsed = status.disk,
            diskTotal = if (status.diskTotal > 0) status.diskTotal else node.diskTotal,
            netIn = status.netIn,
            netOut = status.netOut,
            netTotalUp = status.netTotalUp,
            netTotalDown = status.netTotalDown,
            trafficUp = resetAwareCounterDelta(node.netTotalUp, status.netTotalUp),
            trafficDown = resetAwareCounterDelta(node.netTotalDown, status.netTotalDown),
            uptime = status.uptime,
            load1 = status.load,
            load5 = status.load5,
            load15 = status.load15,
            process = status.process,
            connectionsTcp = (status.connections - status.connectionsUdp).coerceAtLeast(0),
            connectionsUdp = status.connectionsUdp
    )
}

internal fun shouldAcceptLatestNodeStatus(node: Node, status: NodeStatusDto): Boolean {
    val storedTime = node.statusTime.trim()
    val incomingTime = status.time.trim()

    if (incomingTime.isNotEmpty()) {
        if (storedTime.isEmpty()) return true
        if (incomingTime == storedTime) return false
        val storedInstant = parseStatusInstant(storedTime) ?: return false
        val incomingInstant = parseStatusInstant(incomingTime) ?: return false
        return incomingInstant.isAfter(storedInstant)
    }
    if (storedTime.isNotEmpty()) return false

    if (!node.hasStatusBaseline()) return true
    if (status.uptime > node.uptime) return true
    return status.uptime == node.uptime &&
            status.netTotalUp >= node.netTotalUp &&
            status.netTotalDown >= node.netTotalDown &&
            (status.netTotalUp > node.netTotalUp || status.netTotalDown > node.netTotalDown)
}

private fun Node.hasStatusBaseline(): Boolean =
        isOnline ||
                uptime != 0L ||
                netTotalUp != 0L ||
                netTotalDown != 0L ||
                netIn != 0L ||
                netOut != 0L ||
                memUsed != 0L ||
                diskUsed != 0L ||
                cpuUsage != 0.0

private fun parseStatusInstant(value: String): Instant? {
    return runCatching { Instant.parse(value) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
            ?: runCatching {
                        LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                .toInstant(ZoneOffset.UTC)
                }
                    .getOrNull()
            ?: runCatching {
                        LocalDateTime.parse(
                                        value,
                                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                )
                                .toInstant(ZoneOffset.UTC)
                }
                    .getOrNull()
            ?: value.toLongOrNull()?.let { numeric ->
                runCatching {
                            if (numeric in -99_999_999_999L..99_999_999_999L) {
                                Instant.ofEpochSecond(numeric)
                            } else {
                                Instant.ofEpochMilli(numeric)
                            }
                        }
                        .getOrNull()
            }
}

/**
 * 节点数据仓库实现
 *
 * - 通过 HTTP POST RPC2 获取节点基本信息（一次性）
 * - 通过 WebSocket RPC 实时流式获取节点状态
 * - 通过 HTTP POST RPC2 获取负载/Ping 历史记录
 * - 所有请求使用 session_token 进行认证
 */
class NodeRepositoryImpl(private val rpcService: KomariRpcService) : NodeRepository {

    override suspend fun getNodeInfos(
            baseUrl: String,
            sessionToken: String,
            authType: AuthType,
            customHeaders: List<CustomHeader>
    ): List<Node> {
        val requestHeaders = customHeaders.toList()
        val infoMap = rpcService.getNodes(baseUrl, sessionToken, authType, requestHeaders)
        // 首次也尝试获取一次状态（HTTP）
        val statusMap =
                try {
                    rpcService.getNodesLatestStatus(
                            baseUrl,
                            sessionToken,
                            authType,
                            requestHeaders
                    )
                } catch (_: Exception) {
                    emptyMap()
                }
        return mergeAndSort(infoMap, statusMap)
    }

    override suspend fun getNodeRecentStatus(
            baseUrl: String,
            sessionToken: String,
            uuid: String,
            authType: AuthType,
            customHeaders: List<CustomHeader>
    ): List<LoadRecord> {
        var previousTotalUp: Long? = null
        var previousTotalDown: Long? = null
        return rpcService
                .getNodeRecentStatus(
                        baseUrl,
                        sessionToken,
                        uuid,
                        authType,
                        customHeaders.toList()
                )
                .map { dto ->
            val ramPercent =
                    if (dto.ram.total > 0) dto.ram.used.toDouble() / dto.ram.total * 100 else 0.0
            val diskPercent =
                    if (dto.disk.total > 0) dto.disk.used.toDouble() / dto.disk.total * 100 else 0.0
            val trafficUp = resetAwareCounterDelta(previousTotalUp, dto.network.totalUp)
            val trafficDown = resetAwareCounterDelta(previousTotalDown, dto.network.totalDown)
            previousTotalUp = dto.network.totalUp
            previousTotalDown = dto.network.totalDown
            LoadRecord(
                    time = dto.updatedAt,
                    cpu = dto.cpu.usage,
                    ramPercent = ramPercent,
                    diskPercent = diskPercent,
                    netIn = dto.network.down,
                    netOut = dto.network.up,
                    load = dto.load.load1,
                    process = dto.process,
                    connections = dto.connections.tcp,
                    connectionsUdp = dto.connections.udp,
                    netTotalUp = dto.network.totalUp,
                    netTotalDown = dto.network.totalDown,
                    trafficUp = trafficUp,
                    trafficDown = trafficDown
            )
        }
    }

    override fun observeNodeStatus(
            baseUrl: String,
            sessionToken: String,
            baseNodes: List<Node>,
            authType: AuthType,
            customHeaders: List<CustomHeader>
    ): Flow<List<Node>> {
        val currentNodes = baseNodes.associateBy { it.uuid }.toMutableMap()
        return rpcService
                .observeNodesLatestStatus(
                        baseUrl = baseUrl,
                        sessionToken = sessionToken,
                        authType = authType,
                        customHeaders = customHeaders.toList()
                )
                .mapNotNull { event ->
            if (event is KomariRpcService.KomariWsEvent.Status) {
                val statusMap = event.statusMap
                val updated =
                        baseNodes.map { baseNode ->
                            val node = currentNodes[baseNode.uuid] ?: baseNode
                            val status = statusMap[node.uuid]
                            if (status != null) {
                                mergeLatestNodeStatus(node, status)
                            } else {
                                node.copy(isOnline = false)
                            }
                                    .also { currentNodes[node.uuid] = it }
                        }
                sortNodes(updated)
            } else {
                null
            }
        }
    }

    override fun observeNodeDetailWs(
            baseUrl: String,
            sessionToken: String,
            uuid: String,
            loadHours: Int?,
            pingHours: Int,
            authType: AuthType,
            customHeaders: List<CustomHeader>
    ): Flow<NodeRepository.NodeDetailWsEvent> {
        return rpcService.observeNodesLatestStatus(
                        baseUrl = baseUrl,
                        sessionToken = sessionToken,
                        detailUuid = uuid,
                        loadHours = loadHours,
                        pingHours = pingHours,
                        authType = authType,
                        customHeaders = customHeaders.toList()
                )
                .mapNotNull { event ->
                    when (event) {
                        is KomariRpcService.KomariWsEvent.Status -> {
                            NodeDetailWsEvent.Status(event.statusMap)
                        }
                        is KomariRpcService.KomariWsEvent.LoadRecords -> {
                            val records = event.records.records[uuid] ?: emptyList()
                            var previousTotalUp: Long? = null
                            var previousTotalDown: Long? = null
                            val mapped =
                                    records.map { dto ->
                                        val ramPercent =
                                                if (dto.ramTotal > 0)
                                                        (dto.ram.toDouble() / dto.ramTotal * 100)
                                                else 0.0
                                        val diskPercent =
                                                if (dto.diskTotal > 0)
                                                        (dto.disk.toDouble() / dto.diskTotal * 100)
                                                else 0.0
                                        val trafficUp =
                                                dto.trafficUp
                                                        ?: resetAwareCounterDelta(
                                                                previousTotalUp,
                                                                dto.netTotalUp
                                                        )
                                        val trafficDown =
                                                dto.trafficDown
                                                        ?: resetAwareCounterDelta(
                                                                previousTotalDown,
                                                                dto.netTotalDown
                                                        )
                                        previousTotalUp = dto.netTotalUp
                                        previousTotalDown = dto.netTotalDown
                                        LoadRecord(
                                                time = dto.time,
                                                cpu = dto.cpu,
                                                ramPercent = ramPercent,
                                                diskPercent = diskPercent,
                                                netIn = dto.netIn,
                                                netOut = dto.netOut,
                                                load = dto.load,
                                                process = dto.process,
                                                connections = dto.connections,
                                                connectionsUdp = dto.connectionsUdp,
                                                netTotalUp = dto.netTotalUp,
                                                netTotalDown = dto.netTotalDown,
                                                trafficUp = trafficUp,
                                                trafficDown = trafficDown
                                        )
                                    }
                            NodeDetailWsEvent.LoadRecords(mapped)
                        }
                        is KomariRpcService.KomariWsEvent.PingRecords -> {
                            val tasks =
                                    event.records.tasks.map { dto ->
                                        PingTask(
                                                id = dto.id,
                                                name = dto.name,
                                                interval = dto.interval,
                                                min = dto.min,
                                                max = dto.max,
                                                avg = dto.avg,
                                                loss =  dto.loss,
                                                latest = dto.latest,
                                                p50 = dto.p50,
                                                p99 = dto.p99
                                        )
                                    }

                            val taskNameMap = tasks.associate { it.id to it.name }

                            val nodeRecords = event.records.records.filter { it.client == uuid }
                            val mapped =
                                    nodeRecords.map { dto ->
                                        PingRecord(
                                                taskId = dto.taskId,
                                                taskName = taskNameMap[dto.taskId]
                                                                ?: "Task ${dto.taskId}",
                                                time = dto.time,
                                                value = dto.value
                                        )
                                    }

                            NodeDetailWsEvent.PingRecords(tasks, mapped)
                        }
                    }
                }
    }

    // ─── 内部方法 ───

    private fun mergeAndSort(
            infoMap: Map<String, NodeInfoDto>,
            statusMap: Map<String, NodeStatusDto>
    ): List<Node> {
        val nodes =
                infoMap.map { (uuid, info) ->
                    val status = statusMap[uuid]
                    Node(
                            uuid = uuid,
                            name = info.name,
                            region = info.region,
                            group = info.group,
                            isOnline = status?.online ?: false,
                            cpuUsage = status?.cpu ?: 0.0,
                            memUsed = status?.ram ?: 0,
                            memTotal = info.memTotal,
                            swapUsed = status?.swap ?: 0,
                            swapTotal = info.swapTotal,
                            diskUsed = status?.disk ?: 0,
                            diskTotal = info.diskTotal,
                            netIn = status?.netIn ?: 0,
                            netOut = status?.netOut ?: 0,
                            netTotalUp = status?.netTotalUp ?: 0,
                            netTotalDown = status?.netTotalDown ?: 0,
                            uptime = status?.uptime ?: 0,
                            os = info.os,
                            cpuName = info.cpuName,
                            cpuCores = info.cpuCores,
                            weight = info.weight,
                            load1 = status?.load ?: 0.0,
                            load5 = status?.load5 ?: 0.0,
                            load15 = status?.load15 ?: 0.0,
                            process = status?.process ?: 0,
                            connectionsTcp =
                                    status?.let {
                                        (it.connections - it.connectionsUdp).coerceAtLeast(0)
                                    }
                                            ?: 0,
                            connectionsUdp = status?.connectionsUdp ?: 0,
                            // 详情页额外字段
                            kernelVersion = info.kernelVersion,
                            virtualization = info.virtualization,
                            arch = info.arch,
                            gpuName = info.gpuName,
                            trafficLimit = info.trafficLimit,
                            trafficLimitType = info.trafficLimitType,
                            expiredAt = info.expiredAt,
                            ipv4 = info.ipv4,
                            ipv6 = info.ipv6,
                            statusTime = status?.time?.trim().orEmpty(),
                            cpuPhysicalCores = info.cpuPhysicalCores,
                            agentVersion = info.version,
                            tags = info.tags,
                            remark = info.remark,
                            publicRemark = info.publicRemark
                    )
                }
        return sortNodes(nodes)
    }

    /** 按 weight 升序排列，离线节点放到末尾 */
    private fun sortNodes(nodes: List<Node>): List<Node> {
        return nodes.sortedWith(
                compareByDescending<Node> { it.isOnline }.thenBy { it.weight }.thenBy { it.name }
        )
    }
}
