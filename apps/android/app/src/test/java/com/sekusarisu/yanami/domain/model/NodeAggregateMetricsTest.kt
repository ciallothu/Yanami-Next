package com.sekusarisu.yanami.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeAggregateMetricsTest {
    @Test fun cumulativeUsageIncludesOfflineNodesWhileRealtimeMetricsDoNot() {
        val online = sampleNode(
                uuid = "online",
                isOnline = true,
                netIn = 10,
                netOut = 20,
                netTotalUp = 100,
                netTotalDown = 200,
                trafficUp = 3,
                trafficDown = 4
        )
        val offline = sampleNode(
                uuid = "offline",
                isOnline = false,
                netIn = 1_000,
                netOut = 2_000,
                netTotalUp = 300,
                netTotalDown = 400,
                trafficUp = 30,
                trafficDown = 40
        )

        val metrics = aggregateNodeMetrics(listOf(online, offline))

        assertEquals(1, metrics.onlineCount)
        assertEquals(1, metrics.offlineCount)
        assertEquals(10L, metrics.netIn)
        assertEquals(20L, metrics.netOut)
        assertEquals(400L, metrics.trafficUsageUp)
        assertEquals(600L, metrics.trafficUsageDown)
        assertEquals(3L, metrics.currentTrafficUp)
        assertEquals(4L, metrics.currentTrafficDown)
    }

    @Test fun untrustedNegativeAndOverflowingCountersAreSafelyBounded() {
        val maximum = sampleNode(
                uuid = "maximum",
                isOnline = true,
                netIn = Long.MAX_VALUE,
                netOut = Long.MAX_VALUE,
                netTotalUp = Long.MAX_VALUE,
                netTotalDown = Long.MAX_VALUE,
                trafficUp = Long.MAX_VALUE,
                trafficDown = Long.MAX_VALUE
        )
        val extra = sampleNode(
                uuid = "extra",
                isOnline = true,
                netIn = 1,
                netOut = -1,
                netTotalUp = 1,
                netTotalDown = -1,
                trafficUp = 1,
                trafficDown = -1
        )

        val metrics = aggregateNodeMetrics(listOf(maximum, extra))

        assertEquals(Long.MAX_VALUE, metrics.netIn)
        assertEquals(Long.MAX_VALUE, metrics.netOut)
        assertEquals(Long.MAX_VALUE, metrics.trafficUsageUp)
        assertEquals(Long.MAX_VALUE, metrics.trafficUsageDown)
        assertEquals(Long.MAX_VALUE, metrics.currentTrafficUp)
        assertEquals(Long.MAX_VALUE, metrics.currentTrafficDown)
    }

    private fun sampleNode(
            uuid: String,
            isOnline: Boolean,
            netIn: Long,
            netOut: Long,
            netTotalUp: Long,
            netTotalDown: Long,
            trafficUp: Long,
            trafficDown: Long
    ) = Node(
            uuid = uuid,
            name = uuid,
            region = "",
            group = "",
            isOnline = isOnline,
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
