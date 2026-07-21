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
import kotlin.coroutines.cancellation.CancellationException

/**
 * Merge a Komari latest-status sample without allowing duplicate or out-of-order responses to
 * rewind counters. A timestamped sample must be strictly newer. For legacy undated samples we
 * require monotonic evidence (uptime or cumulative counters) before accepting an update. Komari's
 * online flag is live presence computed independently from the sample timestamp, so a rejected
 * sample may still update presence while every sampled metric remains unchanged.
 */
internal fun mergeLatestNodeStatus(node: Node, status: NodeStatusDto): Node {
    if (!shouldAcceptLatestNodeStatus(node, status)) {
        return if (node.isOnline == status.online) node else node.copy(isOnline = status.online)
    }

    val incomingTime = status.time.trim()
    val connections = normalizeLatestConnections(status.connections, status.connectionsUdp)
    val hadStatusBaseline = node.statusTime.isNotBlank() || node.hasStatusBaseline()
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
            trafficUp =
                    resetAwareCounterDelta(
                            node.netTotalUp.takeIf { hadStatusBaseline },
                            status.netTotalUp
                    ),
            trafficDown =
                    resetAwareCounterDelta(
                            node.netTotalDown.takeIf { hadStatusBaseline },
                            status.netTotalDown
                    ),
            uptime = status.uptime,
            load1 = status.load,
            load5 = status.load5,
            load15 = status.load15,
            process = status.process,
            connectionsTcp = connections.tcp,
            connectionsUdp = connections.udp
    )
}

internal data class NormalizedConnections(val tcp: Int, val udp: Int)

/** Komari reports total and UDP counts as untrusted signed Int values. */
internal fun normalizeLatestConnections(total: Int, udp: Int): NormalizedConnections {
    val safeTotal = total.coerceAtLeast(0)
    val safeUdp = udp.coerceAtLeast(0)
    return NormalizedConnections(
            tcp = if (safeTotal > safeUdp) safeTotal - safeUdp else 0,
            udp = safeUdp
    )
}

internal fun shouldAcceptLatestNodeStatus(node: Node, status: NodeStatusDto): Boolean {
    val storedTime = node.statusTime.trim()
    val incomingTime = status.time.trim()

    if (incomingTime.isNotEmpty()) {
        val incomingInstant = parseStatusInstant(incomingTime) ?: return false
        if (storedTime.isEmpty()) return true
        if (incomingTime == storedTime) return false
        // Recover from a malformed legacy/initial baseline once the server supplies a valid time.
        val storedInstant = parseStatusInstant(storedTime) ?: return true
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

internal fun validatedStatusTimeOrEmpty(value: String?): String {
    val candidate = value?.trim().orEmpty()
    return candidate.takeIf { it.isNotEmpty() && parseStatusInstant(it) != null }.orEmpty()
}

/**
 * Reconcile a fresh static-node response with the last accepted dynamic samples. A null status map
 * means the latest-status HTTP request failed, so existing status is retained. A successful map
 * missing a node marks only its live presence offline. Duplicate, malformed, and older samples
 * cannot erase or rewind accepted sampled metrics.
 */
internal fun reconcileNodeInfoSnapshots(
        infoMap: Map<String, NodeInfoDto>,
        statusMap: Map<String, NodeStatusDto>?,
        previousNodes: List<Node>
): List<Node> {
    val previousByUuid = previousNodes.associateBy { it.uuid }
    val nodes =
            infoMap.map { (uuid, info) ->
                val previous = previousByUuid[uuid]
                val staticNode =
                        if (previous == null) nodeFromInfo(uuid, info)
                        else previous.withFreshStaticInfo(uuid, info)
                when {
                    statusMap == null -> staticNode
                    statusMap[uuid] == null -> staticNode.copy(isOnline = false)
                    else -> mergeLatestNodeStatus(staticNode, statusMap.getValue(uuid))
                }
            }
    return sortNodes(nodes)
}

/**
 * Reconcile a completed refresh against state that may have advanced concurrently (for example,
 * via WebSocket). Fresh static fields win; dynamic fields only advance with a newer sample.
 */
internal fun reconcileRefreshedNodes(
        previousNodes: List<Node>,
        refreshedNodes: List<Node>
): List<Node> {
    val previousByUuid = previousNodes.associateBy { it.uuid }
    return sortNodes(
            refreshedNodes.map { fresh ->
                val previous = previousByUuid[fresh.uuid] ?: return@map fresh
                val withFreshStatic = previous.withFreshStaticFields(fresh)
                if (fresh.statusTime.isBlank() && !fresh.hasStatusBaseline()) {
                    withFreshStatic
                } else {
                    mergeLatestNodeStatus(withFreshStatic, fresh.asLatestStatus())
                }
            }
    )
}

private fun nodeFromInfo(uuid: String, info: NodeInfoDto): Node =
        Node(
                uuid = uuid,
                name = info.name,
                region = info.region,
                group = info.group,
                isOnline = false,
                cpuUsage = 0.0,
                memUsed = 0,
                memTotal = info.memTotal,
                swapUsed = 0,
                swapTotal = info.swapTotal,
                diskUsed = 0,
                diskTotal = info.diskTotal,
                netIn = 0,
                netOut = 0,
                netTotalUp = 0,
                netTotalDown = 0,
                uptime = 0,
                os = info.os,
                cpuName = info.cpuName,
                cpuCores = info.cpuCores,
                weight = info.weight,
                load1 = 0.0,
                load5 = 0.0,
                load15 = 0.0,
                process = 0,
                connectionsTcp = 0,
                connectionsUdp = 0,
                kernelVersion = info.kernelVersion,
                virtualization = info.virtualization,
                arch = info.arch,
                gpuName = info.gpuName,
                trafficLimit = info.trafficLimit,
                trafficLimitType = info.trafficLimitType,
                expiredAt = info.expiredAt,
                ipv4 = info.ipv4,
                ipv6 = info.ipv6,
                cpuPhysicalCores = info.cpuPhysicalCores,
                agentVersion = info.version,
                tags = info.tags,
                remark = info.remark,
                publicRemark = info.publicRemark
        )

private fun Node.withFreshStaticInfo(uuid: String, info: NodeInfoDto): Node =
        copy(
                uuid = uuid,
                name = info.name,
                region = info.region,
                group = info.group,
                memTotal = info.memTotal,
                swapTotal = info.swapTotal,
                diskTotal = info.diskTotal,
                os = info.os,
                cpuName = info.cpuName,
                cpuCores = info.cpuCores,
                weight = info.weight,
                kernelVersion = info.kernelVersion,
                virtualization = info.virtualization,
                arch = info.arch,
                gpuName = info.gpuName,
                trafficLimit = info.trafficLimit,
                trafficLimitType = info.trafficLimitType,
                expiredAt = info.expiredAt,
                ipv4 = info.ipv4,
                ipv6 = info.ipv6,
                cpuPhysicalCores = info.cpuPhysicalCores,
                agentVersion = info.version,
                tags = info.tags,
                remark = info.remark,
                publicRemark = info.publicRemark,
                expiryInstant = nodeFromInfo(uuid, info).expiryInstant
        )

private fun Node.withFreshStaticFields(fresh: Node): Node =
        copy(
                name = fresh.name,
                region = fresh.region,
                group = fresh.group,
                memTotal = fresh.memTotal,
                swapTotal = fresh.swapTotal,
                diskTotal = fresh.diskTotal,
                os = fresh.os,
                cpuName = fresh.cpuName,
                cpuCores = fresh.cpuCores,
                weight = fresh.weight,
                kernelVersion = fresh.kernelVersion,
                virtualization = fresh.virtualization,
                arch = fresh.arch,
                gpuName = fresh.gpuName,
                trafficLimit = fresh.trafficLimit,
                trafficLimitType = fresh.trafficLimitType,
                expiredAt = fresh.expiredAt,
                ipv4 = fresh.ipv4,
                ipv6 = fresh.ipv6,
                cpuPhysicalCores = fresh.cpuPhysicalCores,
                agentVersion = fresh.agentVersion,
                tags = fresh.tags,
                remark = fresh.remark,
                publicRemark = fresh.publicRemark,
                expiryInstant = fresh.expiryInstant
        )

private fun Node.asLatestStatus(): NodeStatusDto {
    val connectionsTotal =
            connectionsTcp.coerceAtLeast(0).toLong() + connectionsUdp.coerceAtLeast(0).toLong()
    return NodeStatusDto(
            cpu = cpuUsage,
            ram = memUsed,
            ramTotal = memTotal,
            swap = swapUsed,
            swapTotal = swapTotal,
            disk = diskUsed,
            diskTotal = diskTotal,
            netIn = netIn,
            netOut = netOut,
            netTotalUp = netTotalUp,
            netTotalDown = netTotalDown,
            uptime = uptime,
            load = load1,
            load5 = load5,
            load15 = load15,
            process = process,
            connections = connectionsTotal.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            connectionsUdp = connectionsUdp.coerceAtLeast(0),
            online = isOnline,
            time = statusTime
    )
}

private fun sortNodes(nodes: List<Node>): List<Node> =
        nodes.sortedWith(
                compareByDescending<Node> { it.isOnline }.thenBy { it.weight }.thenBy { it.name }
        )

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
            customHeaders: List<CustomHeader>,
            previousNodes: List<Node>
    ): List<Node> {
        val requestHeaders = customHeaders.toList()
        val infoMap = rpcService.getNodes(baseUrl, sessionToken, authType, requestHeaders)
        // A failed latest-status request must not turn previously-online nodes into zeroed/offline
        // snapshots. Keep failure distinct from a successful empty response.
        val statusMap =
                try {
                    rpcService.getNodesLatestStatus(
                            baseUrl,
                            sessionToken,
                            authType,
                            requestHeaders
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
        return reconcileNodeInfoSnapshots(infoMap, statusMap, previousNodes)
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
                                        val connections =
                                                normalizeLatestConnections(
                                                        dto.connections,
                                                        dto.connectionsUdp
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
                                                connections = connections.tcp,
                                                connectionsUdp = connections.udp,
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

}
