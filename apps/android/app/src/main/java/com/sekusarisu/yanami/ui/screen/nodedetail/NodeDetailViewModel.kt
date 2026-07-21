package com.sekusarisu.yanami.ui.screen.nodedetail

import android.content.Context
import cafe.adriel.voyager.core.model.screenModelScope
import com.sekusarisu.yanami.R
import com.sekusarisu.yanami.data.repository.mergeLatestNodeStatus
import com.sekusarisu.yanami.data.repository.reconcileRefreshedNodes
import com.sekusarisu.yanami.data.repository.shouldAcceptLatestNodeStatus
import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.model.LoadRecord
import com.sekusarisu.yanami.domain.model.Node
import com.sekusarisu.yanami.domain.model.PingRecord
import com.sekusarisu.yanami.domain.model.ServerInstance
import com.sekusarisu.yanami.domain.repository.NodeRepository
import com.sekusarisu.yanami.domain.repository.Requires2FAException
import com.sekusarisu.yanami.domain.repository.ServerRepository
import com.sekusarisu.yanami.domain.repository.SessionExpiredException
import com.sekusarisu.yanami.mvi.MviViewModel
import com.sekusarisu.yanami.ui.screen.isCurrentRequestGeneration
import com.sekusarisu.yanami.ui.screen.isSessionAuthError
import com.sekusarisu.yanami.ui.screen.isTwoFaHint
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 节点详情 ViewModel */
class NodeDetailViewModel(
        private val uuid: String,
        private val nodeRepository: NodeRepository,
        private val serverRepository: ServerRepository,
        private val context: Context
) :
        MviViewModel<NodeDetailContract.State, NodeDetailContract.Event, NodeDetailContract.Effect>(
                NodeDetailContract.State()
        ) {

        private var wsJob: Job? = null
        private var resumeStreamingJob: Job? = null
        private var seedFetchJob: Job? = null
        private var detailLoadJob: Job? = null
        private var detailLoadGeneration = 0L
        private val realtimeLoadRecordBuffer = ArrayDeque<LoadRecord>(MAX_REALTIME_RECORDS)
        private val realtimeTimeLabelBuffer = ArrayDeque<String>(MAX_REALTIME_RECORDS)
        private val realtimeCpuSeriesBuffer = ArrayDeque<Double>(MAX_REALTIME_RECORDS)
        private val realtimeRamSeriesBuffer = ArrayDeque<Double>(MAX_REALTIME_RECORDS)
        private val realtimeNetInSeriesBuffer = ArrayDeque<Double>(MAX_REALTIME_RECORDS)
        private val realtimeNetOutSeriesBuffer = ArrayDeque<Double>(MAX_REALTIME_RECORDS)
        private val realtimeTrafficUpSeriesBuffer = ArrayDeque<Double>(MAX_REALTIME_RECORDS)
        private val realtimeTrafficDownSeriesBuffer = ArrayDeque<Double>(MAX_REALTIME_RECORDS)
        private val realtimeTcpSeriesBuffer = ArrayDeque<Int>(MAX_REALTIME_RECORDS)
        private val realtimeUdpSeriesBuffer = ArrayDeque<Int>(MAX_REALTIME_RECORDS)
        private val realtimeProcessSeriesBuffer = ArrayDeque<Double>(MAX_REALTIME_RECORDS)
        private var isScreenStarted = false
        private var activeStreamServerId: Long? = null
        private var activeStreamRequires2fa = false
        private var activeStreamAuthType = AuthType.PASSWORD

        companion object {
                /** 实时模式最大数据点数（约 4 分钟，每 2 秒一个） */
                private const val MAX_REALTIME_RECORDS = 120
        }

        init {
                loadNodeDetail()
        }

        fun onScreenStarted() {
                if (isScreenStarted) return
                isScreenStarted = true
                resumeStreamingIfNeeded()
        }

        fun onScreenStopped() {
                if (!isScreenStarted) return
                isScreenStarted = false
                resumeStreamingJob?.cancel()
                resumeStreamingJob = null
                wsJob?.cancel()
                wsJob = null
        }

        override fun onEvent(event: NodeDetailContract.Event) {
                when (event) {
                        is NodeDetailContract.Event.Refresh -> loadNodeDetail()
                        is NodeDetailContract.Event.Retry -> loadNodeDetail()
                        is NodeDetailContract.Event.LoadHoursChanged -> {
                                val switchingToRealtime = event.hours == 0
                                seedFetchJob?.cancel()
                                if (switchingToRealtime) {
                                        replaceRealtimeRecords(emptyList())
                                }
                                setState {
                                        copy(
                                                selectedLoadHours = event.hours,
                                                isLoadRecordsLoading = event.hours > 0,
                                                realtimeLoadChartData =
                                                        snapshotRealtimeLoadChartData()
                                        )
                                }
                                if (switchingToRealtime) {
                                        seedFetchJob = screenModelScope.launch {
                                                val recentRecords = fetchRecentAsSeed()
                                                replaceRealtimeRecords(recentRecords)
                                                setState {
                                                        copy(
                                                                realtimeLoadChartData =
                                                                        snapshotRealtimeLoadChartData()
                                                        )
                                                }
                                                startWebSocket()
                                        }
                                } else {
                                        startWebSocket()
                                }
                        }
                        is NodeDetailContract.Event.PingHoursChanged -> {
                                setState {
                                        copy(
                                                selectedPingHours = event.hours,
                                                isPingRecordsLoading = true
                                        )
                                }
                                startWebSocket()
                        }
                }
        }

        /** 加载节点详情：基本信息 + 负载记录 + Ping 记录 */
        private fun loadNodeDetail() {
                val generation = ++detailLoadGeneration
                detailLoadJob?.cancel()
                seedFetchJob?.cancel()
                seedFetchJob = null
                resumeStreamingJob?.cancel()
                resumeStreamingJob = null
                wsJob?.cancel()
                wsJob = null
                setState { copy(isLoading = true, error = null) }
                val previousNode = currentState.node
                detailLoadJob = screenModelScope.launch {
                        var activeServerId: Long? = null
                        var activeRequires2fa = false
                        var activeAuthType = AuthType.PASSWORD
                        try {
                                val server =
                                        serverRepository.getActive()
                                                ?: throw Exception(
                                                        context.getString(
                                                                R.string.error_no_server_selected
                                                        )
                                                )
                                activeServerId = server.id
                                activeRequires2fa = server.requires2fa
                                activeAuthType = server.authType

                                val sessionToken = ensureSession(server)
                                if (!isCurrentDetailLoadForServer(generation, server.id)) {
                                        loadNodeDetail()
                                        return@launch
                                }

                                // 获取节点基本信息（包含实时状态）
                                val allNodes =
                                        nodeRepository.getNodeInfos(
                                                baseUrl = server.baseUrl,
                                                sessionToken = sessionToken,
                                                authType = server.authType,
                                                customHeaders = server.customHeaders.toList(),
                                                previousNodes = listOfNotNull(previousNode)
                                        )
                                if (!isCurrentDetailLoadForServer(generation, server.id)) {
                                        loadNodeDetail()
                                        return@launch
                                }
                                val refreshedNode =
                                        allNodes.find { it.uuid == uuid }
                                                ?: throw Exception(
                                                        context.getString(
                                                                R.string.node_detail_not_found
                                                        )
                                                )
                                val node =
                                        reconcileRefreshedNodes(
                                                        previousNodes =
                                                                listOfNotNull(currentState.node),
                                                        refreshedNodes = listOf(refreshedNode)
                                                )
                                                .single()

                                setState {
                                        copy(
                                                node = node,
                                                isLoading = false,
                                                error = null,
                                                isLoadRecordsLoading = selectedLoadHours > 0,
                                                isPingRecordsLoading = true,
                                                authType = server.authType
                                        )
                                }
                                if (!isCurrentDetailLoad(generation)) return@launch
                                activeStreamServerId = server.id
                                activeStreamRequires2fa = server.requires2fa
                                activeStreamAuthType = server.authType

                                // 实时模式（默认）：先拉取最近 1 分钟数据作为图表 seed
                                if (currentState.selectedLoadHours == 0) {
                                        val recentRecords = try {
                                                nodeRepository.getNodeRecentStatus(
                                                        baseUrl = server.baseUrl,
                                                        sessionToken = sessionToken,
                                                        uuid = uuid,
                                                        authType = server.authType,
                                                        customHeaders =
                                                                server.customHeaders.toList()
                                                )
                                        } catch (e: CancellationException) {
                                                throw e
                                        } catch (_: Exception) {
                                                emptyList()
                                        }
                                        if (!isCurrentDetailLoadForServer(
                                                        generation,
                                                        server.id
                                                )
                                        ) {
                                                loadNodeDetail()
                                                return@launch
                                        }
                                        replaceRealtimeRecords(recentRecords)
                                        setState {
                                                copy(
                                                        realtimeLoadChartData =
                                                                snapshotRealtimeLoadChartData()
                                                )
                                        }
                                }

                                // 启动复用的 WebSocket 获取实时状态和历史记录
                                if (!isCurrentDetailLoad(generation)) return@launch
                                startWebSocket()
                        } catch (e: CancellationException) {
                                throw e
                        } catch (e: Exception) {
                                if (!isCurrentDetailLoad(generation)) return@launch
                                if (activeServerId != null &&
                                                serverRepository.getActive()?.id != activeServerId
                                ) {
                                        loadNodeDetail()
                                        return@launch
                                }
                                if (activeServerId != null &&
                                                handleSessionExpired(
                                                        activeServerId,
                                                        activeRequires2fa,
                                                        e,
                                                        activeAuthType
                                                )
                                ) {
                                        return@launch
                                }
                                setState {
                                        copy(
                                                isLoading = false,
                                                error =
                                                        context.getString(
                                                                R.string.node_detail_load_failed,
                                                                e.message
                                                )
                                        )
                                }
                                if (activeServerId == activeStreamServerId) {
                                        resumeStreamingIfNeeded()
                                }
                        } finally {
                                if (isCurrentDetailLoad(generation)) detailLoadJob = null
                        }
                }
        }

        private fun isCurrentDetailLoad(generation: Long): Boolean =
                isCurrentRequestGeneration(generation, detailLoadGeneration)

        private suspend fun isCurrentDetailLoadForServer(
                generation: Long,
                serverId: Long
        ): Boolean =
                isCurrentDetailLoad(generation) && serverRepository.getActive()?.id == serverId

        /** 调用 common:getNodeRecentStatus 获取最近 1 分钟未降采样数据，用作实时图表 seed */
        private suspend fun fetchRecentAsSeed(): List<LoadRecord> {
                val server = serverRepository.getActive() ?: return emptyList()
                return try {
                        val sessionToken = ensureSession(server)
                        val records = nodeRepository.getNodeRecentStatus(
                                baseUrl = server.baseUrl,
                                sessionToken = sessionToken,
                                uuid = uuid,
                                authType = server.authType,
                                customHeaders = server.customHeaders.toList()
                        )
                        if (serverRepository.getActive()?.id != server.id) {
                                throw CancellationException("Active server changed")
                        }
                        records
                } catch (e: CancellationException) {
                        throw e
                } catch (_: Exception) {
                        emptyList()
                }
        }

        private fun startWebSocket() {
                if (!isScreenStarted) return
                wsJob?.cancel()
                val currentStateSnapshot = currentState
                currentStateSnapshot.node ?: return
                val isRealtime = currentStateSnapshot.selectedLoadHours == 0

                wsJob =
                        screenModelScope.launch {
                                var activeServer: ServerInstance? =
                                        null
                                 try {
                                         val server = serverRepository.getActive() ?: return@launch
                                         activeServer = server
                                        val sessionToken = ensureSession(server)
                                        if (serverRepository.getActive()?.id != server.id) {
                                                return@launch
                                        }

                                        nodeRepository.observeNodeDetailWs(
                                                        server.baseUrl,
                                                        sessionToken,
                                                        uuid,
                                                        // 实时模式不请求历史数据
                                                        loadHours =
                                                                if (isRealtime) null
                                                                else
                                                                        currentStateSnapshot
                                                                                .selectedLoadHours,
                                                        pingHours =
                                                                currentStateSnapshot
                                                                        .selectedPingHours,
                                                        authType = server.authType,
                                                        customHeaders =
                                                                server.customHeaders.toList()
                                                )
                                                .collect { event ->
                                                        if (serverRepository.getActive()?.id !=
                                                                        server.id
                                                        ) {
                                                                throw CancellationException(
                                                                        "Active server changed"
                                                                )
                                                        }
                                                        when (event) {
                                                                is NodeRepository.NodeDetailWsEvent.Status -> {
                                                                        val statusMap =
                                                                                event.statusMap
                                                                        val status = statusMap[uuid]
                                                                        if (status != null) {
                                                                                val currentNode =
                                                                                        currentState
                                                                                                .node
                                                                                                ?: return@collect
                                                                                val advancesMetrics =
                                                                                        shouldAcceptLatestNodeStatus(
                                                                                                currentNode,
                                                                                                status
                                                                                        )
                                                                                val updatedNode =
                                                                                        mergeLatestNodeStatus(
                                                                                                currentNode,
                                                                                                status
                                                                                        )
                                                                                if (updatedNode == currentNode) {
                                                                                        return@collect
                                                                                }
                                                                                if (advancesMetrics &&
                                                                                                currentState.selectedLoadHours ==
                                                                                                        0
                                                                                ) {
                                                                                        appendRealtimeRecord(
                                                                                                buildRealtimeRecord(
                                                                                                        updatedNode
                                                                                                )
                                                                                        )
                                                                                }
                                                                                setState {
                                                                                        copy(
                                                                                                node =
                                                                                                        updatedNode,
                                                                                                realtimeLoadChartData =
                                                                                                        snapshotRealtimeLoadChartData()
                                                                                        )
                                                                                }
                                                                        }
                                                                }
                                                                is NodeRepository.NodeDetailWsEvent.LoadRecords -> {
                                                                        setState {
                                                                                copy(
                                                                                        loadChartData =
                                                                                                buildLoadChartData(
                                                                                                        event.records
                                                                                                ),
                                                                                        isLoadRecordsLoading =
                                                                                                false
                                                                                )
                                                                        }
                                                                }
                                                                is NodeRepository.NodeDetailWsEvent.PingRecords -> {
                                                                        setState {
                                                                                copy(
                                                                                        pingTasks =
                                                                                                event.tasks,
                                                                                        pingChartByTaskId =
                                                                                                buildPingChartByTaskId(
                                                                                                        event.records
                                                                                                ),
                                                                                        isPingRecordsLoading =
                                                                                                false
                                                                                )
                                                                        }
                                                                }
                                                        }
                                                }
                                } catch (e: CancellationException) {
                                        throw e
                                } catch (e: Exception) {
                                        if (activeServer?.id != null &&
                                                        handleSessionExpired(
                                                                activeServer.id,
                                                                activeServer.requires2fa,
                                                                e,
                                                                activeServer.authType
                                                        )
                                        ) {
                                                return@launch
                                        }
                                        setState {
                                                copy(
                                                        isLoadRecordsLoading = false,
                                                        isPingRecordsLoading = false
                                                )
                                        }
                                        sendEffect(
                                                NodeDetailContract.Effect.ShowToast(
                                                        context.getString(
                                                                R.string.node_detail_records_failed,
                                                                e.message
                                                        )
                                                )
                                        )
                                }
                        }
        }

        private fun resumeStreamingIfNeeded() {
                val serverId = activeStreamServerId ?: return
                if (!isScreenStarted || currentState.isLoading || currentState.node == null || wsJob != null) {
                        return
                }

                resumeStreamingJob?.cancel()
                resumeStreamingJob =
                        screenModelScope.launch {
                                try {
                                        val activeServer = serverRepository.getActive() ?: return@launch
                                        if (activeServer.id != serverId) {
                                                loadNodeDetail()
                                                return@launch
                                        }
                                        startWebSocket()
                                } catch (e: CancellationException) {
                                        throw e
                                } catch (e: Exception) {
                                        if (!handleSessionExpired(
                                                                serverId,
                                                                activeStreamRequires2fa,
                                                                e,
                                                                activeStreamAuthType
                                                        )
                                        ) {
                                                android.util.Log.w(
                                                        "NodeDetailVM",
                                                        "Failed to resume detail stream (${e::class.java.simpleName})"
                                                )
                                        }
                                }
                        }
        }

        private fun buildRealtimeRecord(node: Node): LoadRecord {
                val ramPercent =
                        if (node.memTotal > 0) {
                                node.memUsed.toDouble() / node.memTotal * 100
                        } else {
                                0.0
                        }
                return LoadRecord(
                        time = node.statusTime.ifBlank { Instant.now().toString() },
                        cpu = node.cpuUsage,
                        ramPercent = ramPercent,
                        diskPercent =
                                if (node.diskTotal > 0) {
                                    node.diskUsed.toDouble() / node.diskTotal * 100
                                } else 0.0,
                        netIn = node.netIn,
                        netOut = node.netOut,
                        load = node.load1,
                        process = node.process,
                        connections = node.connectionsTcp,
                        connectionsUdp = node.connectionsUdp,
                        netTotalUp = node.netTotalUp,
                        netTotalDown = node.netTotalDown,
                        trafficUp = node.trafficUp,
                        trafficDown = node.trafficDown
                )
        }

        private fun replaceRealtimeRecords(records: List<LoadRecord>) {
                realtimeLoadRecordBuffer.clear()
                clearRealtimeChartBuffers()
                records.takeLast(MAX_REALTIME_RECORDS).forEach { record ->
                        realtimeLoadRecordBuffer.addLast(record)
                        appendRealtimeChartPoint(record)
                }
        }

        private fun appendRealtimeRecord(record: LoadRecord) {
                if (realtimeLoadRecordBuffer.lastOrNull()?.time == record.time) return
                if (realtimeLoadRecordBuffer.size == MAX_REALTIME_RECORDS) {
                        realtimeLoadRecordBuffer.removeFirst()
                        dropOldestRealtimeChartPoint()
                }
                realtimeLoadRecordBuffer.addLast(record)
                appendRealtimeChartPoint(record)
        }

        private fun clearRealtimeChartBuffers() {
                realtimeTimeLabelBuffer.clear()
                realtimeCpuSeriesBuffer.clear()
                realtimeRamSeriesBuffer.clear()
                realtimeNetInSeriesBuffer.clear()
                realtimeNetOutSeriesBuffer.clear()
                realtimeTrafficUpSeriesBuffer.clear()
                realtimeTrafficDownSeriesBuffer.clear()
                realtimeTcpSeriesBuffer.clear()
                realtimeUdpSeriesBuffer.clear()
                realtimeProcessSeriesBuffer.clear()
        }

        private fun appendRealtimeChartPoint(record: LoadRecord) {
                realtimeTimeLabelBuffer.addLast(record.time)
                realtimeCpuSeriesBuffer.addLast(record.cpu)
                realtimeRamSeriesBuffer.addLast(record.ramPercent)
                realtimeNetInSeriesBuffer.addLast(record.netIn.toDouble())
                realtimeNetOutSeriesBuffer.addLast(record.netOut.toDouble())
                realtimeTrafficUpSeriesBuffer.addLast(record.trafficUp.toDouble())
                realtimeTrafficDownSeriesBuffer.addLast(record.trafficDown.toDouble())
                realtimeTcpSeriesBuffer.addLast(record.connections)
                realtimeUdpSeriesBuffer.addLast(record.connectionsUdp)
                realtimeProcessSeriesBuffer.addLast(record.process.toDouble())
        }

        private fun dropOldestRealtimeChartPoint() {
                realtimeTimeLabelBuffer.removeFirstOrNull()
                realtimeCpuSeriesBuffer.removeFirstOrNull()
                realtimeRamSeriesBuffer.removeFirstOrNull()
                realtimeNetInSeriesBuffer.removeFirstOrNull()
                realtimeNetOutSeriesBuffer.removeFirstOrNull()
                realtimeTrafficUpSeriesBuffer.removeFirstOrNull()
                realtimeTrafficDownSeriesBuffer.removeFirstOrNull()
                realtimeTcpSeriesBuffer.removeFirstOrNull()
                realtimeUdpSeriesBuffer.removeFirstOrNull()
                realtimeProcessSeriesBuffer.removeFirstOrNull()
        }

        private fun snapshotRealtimeLoadChartData(): NodeDetailContract.LoadChartData {
                return NodeDetailContract.LoadChartData(
                        timeLabels = realtimeTimeLabelBuffer.toList(),
                        cpuSeries = realtimeCpuSeriesBuffer.toList(),
                        ramSeries = realtimeRamSeriesBuffer.toList(),
                        netInSeries = realtimeNetInSeriesBuffer.toList(),
                        netOutSeries = realtimeNetOutSeriesBuffer.toList(),
                        trafficUpSeries = realtimeTrafficUpSeriesBuffer.toList(),
                        trafficDownSeries = realtimeTrafficDownSeriesBuffer.toList(),
                        tcpSeries = realtimeTcpSeriesBuffer.toList(),
                        udpSeries = realtimeUdpSeriesBuffer.toList(),
                        processSeries = realtimeProcessSeriesBuffer.toList()
                )
        }

        private fun buildLoadChartData(records: List<LoadRecord>): NodeDetailContract.LoadChartData {
                if (records.isEmpty()) {
                        return NodeDetailContract.LoadChartData()
                }

                val timeLabels = ArrayList<String>(records.size)
                val cpuSeries = ArrayList<Double>(records.size)
                val ramSeries = ArrayList<Double>(records.size)
                val netInSeries = ArrayList<Double>(records.size)
                val netOutSeries = ArrayList<Double>(records.size)
                val trafficUpSeries = ArrayList<Double>(records.size)
                val trafficDownSeries = ArrayList<Double>(records.size)
                val tcpSeries = ArrayList<Int>(records.size)
                val udpSeries = ArrayList<Int>(records.size)
                val processSeries = ArrayList<Double>(records.size)

                records.forEach { record ->
                        timeLabels.add(record.time)
                        cpuSeries.add(record.cpu)
                        ramSeries.add(record.ramPercent)
                        netInSeries.add(record.netIn.toDouble())
                        netOutSeries.add(record.netOut.toDouble())
                        trafficUpSeries.add(record.trafficUp.toDouble())
                        trafficDownSeries.add(record.trafficDown.toDouble())
                        tcpSeries.add(record.connections)
                        udpSeries.add(record.connectionsUdp)
                        processSeries.add(record.process.toDouble())
                }

                return NodeDetailContract.LoadChartData(
                        timeLabels = timeLabels,
                        cpuSeries = cpuSeries,
                        ramSeries = ramSeries,
                        netInSeries = netInSeries,
                        netOutSeries = netOutSeries,
                        trafficUpSeries = trafficUpSeries,
                        trafficDownSeries = trafficDownSeries,
                        tcpSeries = tcpSeries,
                        udpSeries = udpSeries,
                        processSeries = processSeries
                )
        }

        private fun buildPingChartByTaskId(
                records: List<PingRecord>
        ): Map<Int, NodeDetailContract.PingChartData> {
                if (records.isEmpty()) {
                        return emptyMap()
                }

                return records.groupBy { it.taskId }.mapValues { (_, taskRecords) ->
                        val sortedRecords = taskRecords.sortedBy { it.time }
                        val values = ArrayList<Double>(sortedRecords.size)
                        val times = ArrayList<String>(sortedRecords.size)
                        sortedRecords.forEach { record ->
                                values.add(record.value)
                                times.add(record.time)
                        }
                        NodeDetailContract.PingChartData(values = values, times = times)
                }
        }

        private suspend fun ensureSession(
                server: ServerInstance
        ): String {
                return try {
                        serverRepository.ensureSessionToken(server)
                } catch (e: Requires2FAException) {
                        serverRepository.updateRequires2fa(server, true)
                        throw SessionExpiredException(
                                e.message ?: context.getString(R.string.error_no_session)
                        )
                } catch (e: Exception) {
                        if (e.isSessionAuthError()) {
                                throw SessionExpiredException(
                                        e.message ?: context.getString(R.string.error_no_session)
                                )
                        }
                        throw e
                }
        }

        private fun handleSessionExpired(
                serverId: Long,
                requires2fa: Boolean,
                error: Throwable,
                authType: AuthType = AuthType.PASSWORD
        ): Boolean {
                if (!error.isSessionAuthError()) return false
                if (authType == AuthType.GUEST) return false
                wsJob?.cancel()
                setState {
                        copy(
                                isLoading = false,
                                isLoadRecordsLoading = false,
                                isPingRecordsLoading = false,
                                error = null
                        )
                }
                sendEffect(
                        NodeDetailContract.Effect.ShowToast(
                                error.message ?: context.getString(R.string.error_no_session)
                        )
                )
                if (authType == AuthType.API_KEY) {
                        sendEffect(NodeDetailContract.Effect.NavigateToServerEdit(serverId))
                } else {
                        val forceTwoFa = requires2fa || error.isTwoFaHint()
                        sendEffect(
                                NodeDetailContract.Effect.NavigateToServerRelogin(
                                        serverId,
                                        forceTwoFa
                                )
                        )
                }
                return true
        }
}
