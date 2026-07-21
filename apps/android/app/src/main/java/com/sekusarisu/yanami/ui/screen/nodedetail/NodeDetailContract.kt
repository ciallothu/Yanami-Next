package com.sekusarisu.yanami.ui.screen.nodedetail

import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.model.Node
import com.sekusarisu.yanami.domain.model.PingTask
import com.sekusarisu.yanami.mvi.UiEffect
import com.sekusarisu.yanami.mvi.UiEvent
import com.sekusarisu.yanami.mvi.UiState

/** 节点详情 — MVI 契约 */
object NodeDetailContract {

    data class LoadChartData(
            val timeLabels: List<String> = emptyList(),
            val cpuSeries: List<Double> = emptyList(),
            val ramSeries: List<Double> = emptyList(),
            val netInSeries: List<Double> = emptyList(),
            val netOutSeries: List<Double> = emptyList(),
            val sampleUsageUpSeries: List<Double> = emptyList(),
            val sampleUsageDownSeries: List<Double> = emptyList(),
            val tcpSeries: List<Int> = emptyList(),
            val udpSeries: List<Int> = emptyList(),
            val processSeries: List<Double> = emptyList()
    )

    data class PingChartData(
            val values: List<Double> = emptyList(),
            val times: List<String> = emptyList(),
            val segments: List<PingChartSegment> = emptyList(),
            val allTimes: List<String> = emptyList(),
            val packetLossXValues: List<Double> = emptyList(),
            val totalSamples: Int = 0,
            val packetLossSamples: Int = 0
    )

    data class PingChartSegment(
            val xValues: List<Double> = emptyList(),
            val values: List<Double> = emptyList()
    )

    data class State(
            val isLoading: Boolean = true,
            val node: Node? = null,
            val loadChartData: LoadChartData = LoadChartData(),
            val realtimeLoadChartData: LoadChartData = LoadChartData(),
            val pingTasks: List<PingTask> = emptyList(),
            val pingChartByTaskId: Map<Int, PingChartData> = emptyMap(),
            val latency24hTasks: List<PingTask> = emptyList(),
            val latency24hSamplesByTaskId: Map<Int, List<Double>> = emptyMap(),
            val isLatency24hLoading: Boolean = true,
            val hasLatency24hError: Boolean = false,
            val selectedLoadHours: Int = 0,
            val selectedPingHours: Int = 1,
            val isLoadRecordsLoading: Boolean = false,
            val isPingRecordsLoading: Boolean = false,
            val error: String? = null,
            val authType: AuthType = AuthType.PASSWORD
    ) : UiState

    sealed interface Event : UiEvent {
        data object Refresh : Event
        data object Retry : Event
        data class LoadHoursChanged(val hours: Int) : Event
        data class PingHoursChanged(val hours: Int) : Event
    }

    sealed interface Effect : UiEffect {
        data class ShowToast(val message: String) : Effect
        data class NavigateToServerRelogin(val serverId: Long, val forceTwoFa: Boolean) : Effect
        data class NavigateToServerEdit(val serverId: Long) : Effect
    }
}
