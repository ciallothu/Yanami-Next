package com.sekusarisu.yanami.data.repository

import com.sekusarisu.yanami.data.remote.dto.NodeStatusDto
import com.sekusarisu.yanami.domain.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeStatusMergeTest {
    @Test fun duplicateAndOlderSamplesPreserveDynamicValuesAndTraffic() {
        val current = sampleNode(
                statusTime = "2026-07-21T10:00:00Z",
                cpuUsage = 25.0,
                netTotalUp = 1_000,
                netTotalDown = 2_000,
                trafficUp = 100,
                trafficDown = 200,
                uptime = 500
        )

        val duplicate = sampleStatus(
                time = "2026-07-21T10:00:00Z",
                cpu = 99.0,
                netTotalUp = 1_000,
                netTotalDown = 2_000,
                uptime = 500
        )
        val older = sampleStatus(
                time = "2026-07-21T09:59:59Z",
                cpu = 1.0,
                netTotalUp = 900,
                netTotalDown = 1_900,
                uptime = 499
        )

        assertEquals(current, mergeLatestNodeStatus(current, duplicate))
        assertEquals(current, mergeLatestNodeStatus(current, older))
    }

    @Test fun newerSampleComputesDeltaAndAdvancesTimestamp() {
        val current = sampleNode(
                statusTime = "2026-07-21T10:00:00Z",
                netTotalUp = 1_000,
                netTotalDown = 2_000,
                trafficUp = 100,
                trafficDown = 200,
                uptime = 500
        )
        val merged = mergeLatestNodeStatus(
                current,
                sampleStatus(
                        time = "2026-07-21T10:00:02Z",
                        netTotalUp = 1_075,
                        netTotalDown = 2_125,
                        uptime = 502
                )
        )

        assertEquals("2026-07-21T10:00:02Z", merged.statusTime)
        assertEquals(75, merged.trafficUp)
        assertEquals(125, merged.trafficDown)
    }

    @Test fun newerSampleTreatsCounterDecreaseAsAgentReset() {
        val current = sampleNode(
                statusTime = "2026-07-21T10:00:02Z",
                netTotalUp = 10_000,
                netTotalDown = 20_000,
                uptime = 502
        )
        val merged = mergeLatestNodeStatus(
                current,
                sampleStatus(
                        time = "2026-07-21T10:00:04Z",
                        netTotalUp = 40,
                        netTotalDown = 60,
                        uptime = 504
                )
        )

        assertEquals(40, merged.trafficUp)
        assertEquals(60, merged.trafficDown)
    }

    @Test fun undatedSamplesRequireMonotonicEvidenceAndNeverOverrideDatedState() {
        val undated = sampleNode(
                statusTime = "",
                netTotalUp = 1_000,
                netTotalDown = 2_000,
                trafficUp = 10,
                trafficDown = 20,
                uptime = 500
        )
        val duplicate = sampleStatus(
                time = "",
                netTotalUp = 1_000,
                netTotalDown = 2_000,
                uptime = 500
        )
        val progressed = duplicate.copy(cpu = 50.0, uptime = 501)

        assertFalse(shouldAcceptLatestNodeStatus(undated, duplicate))
        assertTrue(shouldAcceptLatestNodeStatus(undated, progressed))
        assertFalse(
                shouldAcceptLatestNodeStatus(
                        undated.copy(statusTime = "2026-07-21T10:00:00Z"),
                        progressed
                )
        )
    }

    private fun sampleStatus(
            time: String,
            cpu: Double = 10.0,
            netTotalUp: Long,
            netTotalDown: Long,
            uptime: Long
    ) = NodeStatusDto(
            cpu = cpu,
            netTotalUp = netTotalUp,
            netTotalDown = netTotalDown,
            uptime = uptime,
            online = true,
            time = time
    )

    private fun sampleNode(
            statusTime: String,
            cpuUsage: Double = 10.0,
            netTotalUp: Long,
            netTotalDown: Long,
            trafficUp: Long = 0,
            trafficDown: Long = 0,
            uptime: Long
    ) = Node(
            uuid = "node",
            name = "Node",
            region = "",
            group = "",
            isOnline = true,
            cpuUsage = cpuUsage,
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
            uptime = uptime,
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
            statusTime = statusTime,
            trafficUp = trafficUp,
            trafficDown = trafficDown
    )
}
