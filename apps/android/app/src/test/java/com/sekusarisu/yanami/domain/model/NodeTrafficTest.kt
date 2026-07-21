package com.sekusarisu.yanami.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeTrafficTest {
    @Test fun counterDeltaHandlesFirstSampleGrowthAndAgentRestart() {
        assertEquals(0, resetAwareCounterDelta(null, 1_000))
        assertEquals(250, resetAwareCounterDelta(1_000, 1_250))
        assertEquals(40, resetAwareCounterDelta(1_250, 40))
        assertEquals(0, resetAwareCounterDelta(1_250, -1))
    }

    @Test fun trafficLimitKeepsActualOveragePercentage() {
        val node = sampleNode(
                netTotalUp = 900,
                netTotalDown = 600,
                trafficLimit = 1_000,
                trafficLimitType = "sum"
        )
        val usage = node.calculateTrafficLimitUsage()!!
        assertEquals(1_500, usage.currentUsage)
        assertEquals(150.0, usage.usagePercent, 0.001)
    }

    @Test fun disabledTrafficLimitProducesNoUsage() {
        assertNull(sampleNode(trafficLimit = 0).calculateTrafficLimitUsage())
    }

    @Test fun trafficLimitModesClampNegativeCountersAndSaturateSum() {
        assertEquals(
                Long.MAX_VALUE,
                sampleNode(
                                netTotalUp = Long.MAX_VALUE,
                                netTotalDown = 1,
                                trafficLimit = 1,
                                trafficLimitType = "sum"
                        )
                        .calculateTrafficLimitUsage()!!
                        .currentUsage
        )
        assertEquals(
                9L,
                sampleNode(-5, 9, 1, "max").calculateTrafficLimitUsage()!!.currentUsage
        )
        assertEquals(
                0L,
                sampleNode(-5, 9, 1, "min").calculateTrafficLimitUsage()!!.currentUsage
        )
        assertEquals(
                0L,
                sampleNode(-5, 9, 1, "up").calculateTrafficLimitUsage()!!.currentUsage
        )
        assertEquals(
                0L,
                sampleNode(9, -5, 1, "down").calculateTrafficLimitUsage()!!.currentUsage
        )
    }

    private fun sampleNode(
            netTotalUp: Long = 0,
            netTotalDown: Long = 0,
            trafficLimit: Long = 0,
            trafficLimitType: String = "sum"
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
            netIn = 0,
            netOut = 0,
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
            trafficLimit = trafficLimit,
            trafficLimitType = trafficLimitType
    )
}
