package com.sekusarisu.yanami.data.repository

import com.sekusarisu.yanami.data.remote.dto.NodeInfoDto
import com.sekusarisu.yanami.data.remote.dto.NodeStatusDto
import com.sekusarisu.yanami.domain.model.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeStatusMergeTest {
    @Test fun firstStatusSampleDoesNotTreatLifetimeCountersAsIntervalTraffic() {
        val first =
                reconcileNodeInfoSnapshots(
                                infoMap = mapOf("node" to NodeInfoDto(name = "Node")),
                                statusMap =
                                        mapOf(
                                                "node" to
                                                        sampleStatus(
                                                                time = "2026-07-21T10:00:00Z",
                                                                netTotalUp = 10_000,
                                                                netTotalDown = 20_000,
                                                                uptime = 500
                                                        )
                                        ),
                                previousNodes = emptyList()
                        )
                        .single()

        assertEquals(10_000, first.netTotalUp)
        assertEquals(20_000, first.netTotalDown)
        assertEquals(0, first.trafficUp)
        assertEquals(0, first.trafficDown)

        val second =
                mergeLatestNodeStatus(
                        first,
                        sampleStatus(
                                time = "2026-07-21T10:00:02Z",
                                netTotalUp = 10_075,
                                netTotalDown = 20_125,
                                uptime = 502
                        )
                )
        assertEquals(75, second.trafficUp)
        assertEquals(125, second.trafficDown)
    }

    @Test fun establishedZeroCounterBaselineStillCountsSubsequentBytes() {
        val baseline =
                reconcileNodeInfoSnapshots(
                                infoMap = mapOf("node" to NodeInfoDto(name = "Node")),
                                statusMap =
                                        mapOf(
                                                "node" to
                                                        sampleStatus(
                                                                time = "2026-07-21T10:00:00Z",
                                                                netTotalUp = 0,
                                                                netTotalDown = 0,
                                                                uptime = 0
                                                        )
                                        ),
                                previousNodes = emptyList()
                        )
                        .single()

        val progressed =
                mergeLatestNodeStatus(
                        baseline,
                        sampleStatus(
                                time = "2026-07-21T10:00:02Z",
                                netTotalUp = 75,
                                netTotalDown = 125,
                                uptime = 2
                        )
                )

        assertEquals(75, progressed.trafficUp)
        assertEquals(125, progressed.trafficDown)
    }

    @Test fun duplicateAndOlderSamplesOnlyUpdateOnlineState() {
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
                uptime = 500,
                online = false
        )
        val older = sampleStatus(
                time = "2026-07-21T09:59:59Z",
                cpu = 1.0,
                netTotalUp = 900,
                netTotalDown = 1_900,
                uptime = 499,
                online = false
        )

        assertEquals(current.copy(isOnline = false), mergeLatestNodeStatus(current, duplicate))
        assertEquals(current.copy(isOnline = false), mergeLatestNodeStatus(current, older))
    }

    @Test fun repeatedTimestampNeverRecalculatesPreviouslyDerivedTraffic() {
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
                        // Same instant, different ISO-8601 representation.
                        time = "2026-07-21T10:00:00+00:00",
                        netTotalUp = 1_075,
                        netTotalDown = 2_125,
                        uptime = 500,
                        online = false
                )
        )

        assertFalse(merged.isOnline)
        assertEquals(1_000, merged.netTotalUp)
        assertEquals(2_000, merged.netTotalDown)
        assertEquals(100, merged.trafficUp)
        assertEquals(200, merged.trafficDown)
    }

    @Test fun failedStatusFetchKeepsPresenceAndMetricsWhileRefreshingStaticInfo() {
        val previous =
                sampleNode(
                                statusTime = "2026-07-21T10:00:00Z",
                                cpuUsage = 25.0,
                                netTotalUp = 1_000,
                                netTotalDown = 2_000,
                                uptime = 500
                        )
                        .copy(name = "Old name", memTotal = 100, ipv4 = "192.0.2.1")

        val merged =
                reconcileNodeInfoSnapshots(
                                infoMap =
                                        mapOf(
                                                "node" to
                                                        NodeInfoDto(
                                                                name = "Fresh name",
                                                                memTotal = 200,
                                                                ipv4 = "192.0.2.2"
                                                        )
                                        ),
                                statusMap = null,
                                previousNodes = listOf(previous)
                        )
                        .single()

        assertTrue(merged.isOnline)
        assertEquals(25.0, merged.cpuUsage, 0.0)
        assertEquals(1_000, merged.netTotalUp)
        assertEquals("2026-07-21T10:00:00Z", merged.statusTime)
        assertEquals("Fresh name", merged.name)
        assertEquals(200, merged.memTotal)
        assertEquals("192.0.2.2", merged.ipv4)
    }

    @Test fun successfulStatusFetchMissingNodeMarksOfflineButKeepsMetrics() {
        val previous =
                sampleNode(
                        statusTime = "2026-07-21T10:00:00Z",
                        cpuUsage = 25.0,
                        netTotalUp = 1_000,
                        netTotalDown = 2_000,
                        uptime = 500
                )

        val merged =
                reconcileNodeInfoSnapshots(
                                infoMap = mapOf("node" to NodeInfoDto(name = "Fresh name")),
                                statusMap = emptyMap(),
                                previousNodes = listOf(previous)
                        )
                        .single()

        assertFalse(merged.isOnline)
        assertEquals(25.0, merged.cpuUsage, 0.0)
        assertEquals(1_000, merged.netTotalUp)
        assertEquals("2026-07-21T10:00:00Z", merged.statusTime)
    }

    @Test fun completedRefreshKeepsConcurrentNewerMetricsAndFreshStaticInfo() {
        val concurrent =
                sampleNode(
                                statusTime = "2026-07-21T10:00:05Z",
                                cpuUsage = 55.0,
                                netTotalUp = 1_500,
                                netTotalDown = 2_500,
                                uptime = 505
                        )
                        .copy(name = "Old static", ipv4 = "192.0.2.1")
        val staleRefresh =
                sampleNode(
                                statusTime = "2026-07-21T10:00:02Z",
                                cpuUsage = 20.0,
                                netTotalUp = 1_200,
                                netTotalDown = 2_200,
                                uptime = 502
                        )
                        .copy(name = "Fresh static", ipv4 = "192.0.2.2", isOnline = false)

        val merged = reconcileRefreshedNodes(listOf(concurrent), listOf(staleRefresh)).single()

        assertFalse(merged.isOnline)
        assertEquals(55.0, merged.cpuUsage, 0.0)
        assertEquals(1_500, merged.netTotalUp)
        assertEquals("2026-07-21T10:00:05Z", merged.statusTime)
        assertEquals("Fresh static", merged.name)
        assertEquals("192.0.2.2", merged.ipv4)
    }

    @Test fun connectionCountsAreClampedWithoutSignedOverflow() {
        assertEquals(NormalizedConnections(0, 0), normalizeLatestConnections(-1, -1))
        assertEquals(
                NormalizedConnections(Int.MAX_VALUE, 0),
                normalizeLatestConnections(Int.MAX_VALUE, Int.MIN_VALUE)
        )
        assertEquals(
                NormalizedConnections(0, Int.MAX_VALUE),
                normalizeLatestConnections(Int.MIN_VALUE, Int.MAX_VALUE)
        )
        assertEquals(
                NormalizedConnections(0, Int.MAX_VALUE),
                normalizeLatestConnections(Int.MAX_VALUE, Int.MAX_VALUE)
        )

        val initial =
                reconcileNodeInfoSnapshots(
                                infoMap = mapOf("node" to NodeInfoDto(name = "Node")),
                                statusMap =
                                        mapOf(
                                                "node" to
                                                        sampleStatus(
                                                                        time =
                                                                                "2026-07-21T10:00:00Z",
                                                                        netTotalUp = 1,
                                                                        netTotalDown = 1,
                                                                        uptime = 1
                                                                )
                                                                .copy(
                                                                        connections = Int.MIN_VALUE,
                                                                        connectionsUdp =
                                                                                Int.MAX_VALUE
                                                                )
                                        ),
                                previousNodes = emptyList()
                        )
                        .single()
        assertEquals(0, initial.connectionsTcp)
        assertEquals(Int.MAX_VALUE, initial.connectionsUdp)

        val updated =
                mergeLatestNodeStatus(
                        initial,
                        sampleStatus(
                                        time = "2026-07-21T10:00:01Z",
                                        netTotalUp = 2,
                                        netTotalDown = 2,
                                        uptime = 2
                                )
                                .copy(
                                        connections = Int.MAX_VALUE,
                                        connectionsUdp = Int.MIN_VALUE
                                )
                )
        assertEquals(Int.MAX_VALUE, updated.connectionsTcp)
        assertEquals(0, updated.connectionsUdp)
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

    @Test fun malformedFirstTimestampCannotPoisonLaterValidSamples() {
        val current = sampleNode(
                statusTime = "",
                netTotalUp = 1_000,
                netTotalDown = 2_000,
                trafficUp = 100,
                trafficDown = 200,
                uptime = 500
        )
        val malformed = sampleStatus(
                time = "not-a-timestamp",
                cpu = 99.0,
                netTotalUp = 1_900,
                netTotalDown = 2_900,
                uptime = 501,
                online = false
        )

        assertFalse(shouldAcceptLatestNodeStatus(current, malformed))
        assertEquals(current.copy(isOnline = false), mergeLatestNodeStatus(current, malformed))
        assertEquals("", validatedStatusTimeOrEmpty(malformed.time))

        val recovered = mergeLatestNodeStatus(
                current.copy(isOnline = false),
                sampleStatus(
                        time = "2026-07-21T10:00:02Z",
                        netTotalUp = 1_075,
                        netTotalDown = 2_125,
                        uptime = 502
                )
        )
        assertEquals("2026-07-21T10:00:02Z", recovered.statusTime)
        assertEquals(75, recovered.trafficUp)
        assertEquals(125, recovered.trafficDown)
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
            uptime: Long,
            online: Boolean = true
    ) = NodeStatusDto(
            cpu = cpu,
            netTotalUp = netTotalUp,
            netTotalDown = netTotalDown,
            uptime = uptime,
            online = online,
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
