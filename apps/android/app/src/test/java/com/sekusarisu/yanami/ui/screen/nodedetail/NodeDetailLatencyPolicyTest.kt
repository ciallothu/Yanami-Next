package com.sekusarisu.yanami.ui.screen.nodedetail

import com.sekusarisu.yanami.domain.model.PingRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeDetailLatencyPolicyTest {
    @Test
    fun packetLossNeverBecomesANegativeLatencyPoint() {
        val charts =
                buildPingChartByTaskId(
                        listOf(
                                record(1, "2026-01-01T00:03:00Z", 30.0),
                                record(1, "2026-01-01T00:01:00Z", -1.0),
                                record(1, "2026-01-01T00:02:00Z", 20.0),
                                record(1, "2026-01-01T00:04:00Z", Double.NaN),
                                record(2, "2026-01-01T00:01:00Z", -1.0)
                        )
                )

        with(charts.getValue(1)) {
            assertEquals(listOf(20.0, 30.0), values)
            assertEquals(
                    listOf("2026-01-01T00:02:00Z", "2026-01-01T00:03:00Z"),
                    times
            )
            assertEquals(3, totalSamples)
            assertEquals(1, packetLossSamples)
            assertEquals(1, segments.size)
            assertEquals(listOf(1.0, 2.0), segments[0].xValues)
            assertEquals(listOf(0.0), packetLossXValues)
            assertTrue(values.all { it >= 0.0 })
        }
        with(charts.getValue(2)) {
            assertTrue(values.isEmpty())
            assertEquals(1, totalSamples)
            assertEquals(1, packetLossSamples)
            assertTrue(segments.isEmpty())
            assertEquals(listOf(0.0), packetLossXValues)
        }
    }

    @Test
    fun leadingLossIsMarkedWithoutBreakingFollowingSuccessfulRun() {
        val chart =
                buildPingChartByTaskId(
                                listOf(
                                        record(1, "2026-01-01T00:00:00Z", -1.0),
                                        record(1, "2026-01-01T00:01:00Z", 10.0),
                                        record(1, "2026-01-01T00:02:00Z", 20.0)
                                )
                        )
                        .getValue(1)

        assertEquals(1, chart.segments.size)
        assertEquals(listOf(1.0, 2.0), chart.segments.single().xValues)
        assertEquals(listOf(0.0), chart.packetLossXValues)
    }

    @Test
    fun lossBetweenSuccessfulSamplesCreatesAVisibleGap() {
        val chart =
                buildPingChartByTaskId(
                                listOf(
                                        record(1, "2026-01-01T00:00:00Z", 10.0),
                                        record(1, "2026-01-01T00:01:00Z", -1.0),
                                        record(1, "2026-01-01T00:02:00Z", 20.0)
                                )
                        )
                        .getValue(1)

        assertEquals(2, chart.segments.size)
        assertEquals(listOf(0.0), chart.segments[0].xValues)
        assertEquals(listOf(2.0), chart.segments[1].xValues)
        assertEquals(listOf(1.0), chart.packetLossXValues)
    }

    @Test
    fun summarySamplesKeepChronologicalSuccessAndLossValues() {
        val samples =
                buildPingSamplesByTaskId(
                        listOf(
                                record(2, "2026-01-01T00:01:00Z", 50.0),
                                record(1, "2026-01-01T00:02:00Z", -1.0),
                                record(1, "2026-01-01T00:00:00Z", 10.0)
                        )
                )

        assertEquals(listOf(10.0, -1.0), samples.getValue(1))
        assertEquals(listOf(50.0), samples.getValue(2))
    }

    private fun record(taskId: Int, time: String, value: Double) =
            PingRecord(taskId = taskId, taskName = "Task $taskId", time = time, value = value)
}
