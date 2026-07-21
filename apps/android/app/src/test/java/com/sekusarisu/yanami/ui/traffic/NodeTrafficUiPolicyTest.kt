package com.sekusarisu.yanami.ui.traffic

import com.sekusarisu.yanami.domain.model.Node
import org.junit.Assert.assertEquals
import org.junit.Test

class NodeTrafficUiPolicyTest {
    @Test
    fun rateUsageAndSampleDeltaCannotBeInterchanged() {
        val values =
                sampleNode(
                                netIn = 11,
                                netOut = 12,
                                netTotalUp = 21,
                                netTotalDown = 22,
                                trafficUp = 31,
                                trafficDown = 32
                        )
                        .toTrafficUiValues()

        assertEquals(12, values.uploadRateBytesPerSecond)
        assertEquals(11, values.downloadRateBytesPerSecond)
        assertEquals(21, values.cumulativeUploadBytes)
        assertEquals(22, values.cumulativeDownloadBytes)
        assertEquals(31, values.sampleUploadUsageBytes)
        assertEquals(32, values.sampleDownloadUsageBytes)
    }

    @Test
    fun untrustedNegativeCountersAreNeverPresentedAsTraffic() {
        val values =
                sampleNode(
                                netIn = -1,
                                netOut = -2,
                                netTotalUp = -3,
                                netTotalDown = -4,
                                trafficUp = -5,
                                trafficDown = -6
                        )
                        .toTrafficUiValues()

        assertEquals(NodeTrafficUiValues(0, 0, 0, 0, 0, 0), values)
    }

    private fun sampleNode(
            netIn: Long,
            netOut: Long,
            netTotalUp: Long,
            netTotalDown: Long,
            trafficUp: Long,
            trafficDown: Long
    ) = Node(
            uuid = "node",
            name = "Node",
            region = "",
            group = "",
            isOnline = true,
            cpuUsage = 0.0,
            memUsed = 0,
            memTotal = 0,
            swapUsed = 0,
            swapTotal = 0,
            diskUsed = 0,
            diskTotal = 0,
            netIn = netIn,
            netOut = netOut,
            netTotalUp = netTotalUp,
            netTotalDown = netTotalDown,
            uptime = 0,
            os = "",
            cpuName = "",
            cpuCores = 0,
            weight = 0,
            load1 = 0.0,
            load5 = 0.0,
            load15 = 0.0,
            process = 0,
            connectionsTcp = 0,
            connectionsUdp = 0,
            trafficUp = trafficUp,
            trafficDown = trafficDown
    )
}
