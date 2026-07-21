package com.sekusarisu.yanami.data.repository

import com.sekusarisu.yanami.data.remote.dto.PingRecordDto
import com.sekusarisu.yanami.data.remote.dto.PingRecordsResponseDto
import com.sekusarisu.yanami.data.remote.dto.PingTaskDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodePingHistoryMappingTest {
    @Test
    fun keepsOnlyRequestedNodeAndBoundTasksWhilePreservingServerStats() {
        val response =
                PingRecordsResponseDto(
                        tasks =
                                listOf(
                                        PingTaskDto(
                                                id = 7,
                                                name = " Tokyo ",
                                                interval = 60,
                                                total = 120,
                                                loss = 20.7,
                                                latest = 192.0,
                                                min = 20.0,
                                                max = 400.0,
                                                avg = 88.0,
                                                p50 = 70.0,
                                                p99 = 300.0,
                                                p99p50Ratio = 2.8
                                        )
                                ),
                        records =
                                listOf(
                                        PingRecordDto(7, "2026-01-01T00:00:00Z", 192.0, "node-a"),
                                        PingRecordDto(7, "2026-01-01T00:01:00Z", -1.0, "node-a"),
                                        PingRecordDto(7, "2026-01-01T00:02:00Z", 50.0, "node-b"),
                                        PingRecordDto(99, "2026-01-01T00:03:00Z", 30.0, "node-a")
                                )
                )

        val history = response.toNodePingHistory("node-a")

        assertEquals(1, history.tasks.size)
        with(history.tasks.single()) {
            assertEquals("Tokyo", name)
            assertEquals(120, total)
            assertEquals(20.7, loss!!, 0.001)
            assertEquals(192.0, latest!!, 0.001)
            assertEquals(2.8, jitterRatio!!, 0.001)
        }
        assertEquals(listOf(192.0, -1.0), history.records.map { it.value })
        assertTrue(history.records.all { it.taskId == 7 && it.taskName == "Tokyo" })
    }

    @Test
    fun rejectsMalformedOptionalSummaryValuesAndMarksMissingLatest() {
        val history =
                PingRecordsResponseDto(
                                tasks =
                                        listOf(
                                                PingTaskDto(
                                                        id = 1,
                                                        name = "",
                                                        interval = -1,
                                                        total = -2,
                                                        loss = 120.0,
                                                        latest = Double.NaN,
                                                        min = -1.0,
                                                        max = Double.POSITIVE_INFINITY,
                                                        avg = -3.0,
                                                        p50 = -4.0,
                                                        p99 = -5.0,
                                                        p99p50Ratio = -6.0
                                                )
                                        )
                        )
                        .toNodePingHistory("node")

        with(history.tasks.single()) {
            assertEquals("Task 1", name)
            assertEquals(0, interval)
            assertNull(total)
            assertNull(loss)
            assertNull(latest)
            assertNull(min)
            assertNull(max)
            assertNull(avg)
            assertNull(p50)
            assertNull(p99)
            assertNull(jitterRatio)
            assertFalse(statisticsAreAvailable)
        }
    }

    @Test
    fun legacyTaskWithoutTotalFallsBackToItsRealNodeRecords() {
        val history =
                PingRecordsResponseDto(
                                tasks = listOf(PingTaskDto(id = 3, name = "Legacy")),
                                records =
                                        listOf(
                                                PingRecordDto(
                                                        3,
                                                        "2026-01-01T00:00:00Z",
                                                        10.0,
                                                        "node"
                                                ),
                                                PingRecordDto(
                                                        3,
                                                        "2026-01-01T00:01:00Z",
                                                        -1.0,
                                                        "node"
                                                ),
                                                PingRecordDto(
                                                        3,
                                                        "2026-01-01T00:02:00Z",
                                                        30.0,
                                                        "node"
                                                )
                                        )
                        )
                        .toNodePingHistory("node")

        with(history.tasks.single()) {
            assertEquals(3, total)
            assertEquals(20.0, avg!!, 0.001)
            assertEquals(100.0 / 3.0, loss!!, 0.001)
            assertEquals(30.0, latest!!, 0.001)
            assertEquals(10.0, min!!, 0.001)
            assertEquals(30.0, max!!, 0.001)
            assertEquals(20.0, p50!!, 0.001)
            assertTrue(!statisticsAreServerCalculated)
        }
    }

    @Test
    fun serverSummaryRemainsAuthoritativeWhenTotalIsPresent() {
        val history =
                PingRecordsResponseDto(
                                tasks =
                                        listOf(
                                                PingTaskDto(
                                                        id = 4,
                                                        name = "Modern",
                                                        total = 100,
                                                        avg = 55.0,
                                                        loss = 12.0
                                                )
                                        ),
                                records =
                                        listOf(
                                                PingRecordDto(
                                                        4,
                                                        "2026-01-01T00:00:00Z",
                                                        -1.0,
                                                        "node"
                                                )
                                        )
                        )
                        .toNodePingHistory("node")

        with(history.tasks.single()) {
            assertEquals(100, total)
            assertEquals(55.0, avg!!, 0.001)
            assertEquals(12.0, loss!!, 0.001)
            assertNull(p99)
            assertTrue(statisticsAreServerCalculated)
        }
    }

    @Test
    fun missingFluctuationIsUnavailableInsteadOfZero() {
        val history =
                PingRecordsResponseDto(
                                tasks =
                                        listOf(
                                                PingTaskDto(
                                                        id = 5,
                                                        name = "No ratio",
                                                        total = 10,
                                                        loss = 0.0
                                                )
                                        )
                        )
                        .toNodePingHistory("node")

        with(history.tasks.single()) {
            assertNull(jitterRatio)
            assertEquals(0.0, loss!!, 0.001)
            assertTrue(statisticsAreServerCalculated)
        }
    }

    @Test
    fun invalidServerLossFallsBackToRealRecordsWithoutClamping() {
        val history =
                PingRecordsResponseDto(
                                tasks =
                                        listOf(
                                                PingTaskDto(
                                                        id = 6,
                                                        name = "Bad loss",
                                                        total = 100,
                                                        loss = 120.0,
                                                        avg = 999.0,
                                                        p50 = 999.0,
                                                        p99 = 999.0
                                                )
                                        ),
                                records =
                                        listOf(
                                                PingRecordDto(
                                                        6,
                                                        "2026-01-01T00:00:00Z",
                                                        10.0,
                                                        "node"
                                                ),
                                                PingRecordDto(
                                                        6,
                                                        "2026-01-01T00:01:00Z",
                                                        11.0,
                                                        "node"
                                                ),
                                                PingRecordDto(
                                                        6,
                                                        "2026-01-01T00:02:00Z",
                                                        -1.0,
                                                        "node"
                                                )
                                        )
                        )
                        .toNodePingHistory("node")

        with(history.tasks.single()) {
            assertEquals(3, total)
            assertEquals(100.0 / 3.0, loss!!, 0.001)
            assertEquals(10.0, avg!!, 0.001)
            assertEquals(11.0, latest!!, 0.001)
            assertEquals(10.0, min!!, 0.001)
            assertEquals(11.0, max!!, 0.001)
            assertEquals(11.0, p50!!, 0.001)
            assertEquals(11.0, p99!!, 0.001)
            assertEquals(0.0, jitterRatio!!, 0.001)
            assertFalse(statisticsAreServerCalculated)
        }
    }

    @Test
    fun invalidServerLossWithoutRecordsRemainsUnavailable() {
        val history =
                PingRecordsResponseDto(
                                tasks =
                                        listOf(
                                                PingTaskDto(
                                                        id = 8,
                                                        total = 100,
                                                        loss = -3.0
                                                )
                                        )
                        )
                        .toNodePingHistory("node")

        with(history.tasks.single()) {
            assertNull(total)
            assertNull(loss)
            assertNull(latest)
            assertFalse(statisticsAreServerCalculated)
            assertFalse(statisticsAreAvailable)
        }
    }

    @Test
    fun missingRecordValueOrTimeIsDiscardedInsteadOfBecomingZeroLatency() {
        val history =
                PingRecordsResponseDto(
                                tasks = listOf(PingTaskDto(id = 9)),
                                records =
                                        listOf(
                                                PingRecordDto(
                                                        taskId = 9,
                                                        time = "2026-01-01T00:00:00Z",
                                                        client = "node"
                                                ),
                                                PingRecordDto(
                                                        taskId = 9,
                                                        value = 12.0,
                                                        client = "node"
                                                ),
                                                PingRecordDto(
                                                        taskId = 9,
                                                        time = "2026-01-01T00:02:00Z",
                                                        value = 13.0,
                                                        client = "node"
                                                )
                                        )
                        )
                        .toNodePingHistory("node")

        assertEquals(listOf(13.0), history.records.map { it.value })
        with(history.tasks.single()) {
            assertEquals(1, total)
            assertEquals(13.0, latest!!, 0.001)
            assertEquals(0.0, loss!!, 0.001)
        }
    }

    @Test
    fun totalAndLossStayAuthoritativeWhenPercentileIsMissing() {
        val history =
                PingRecordsResponseDto(
                                tasks =
                                        listOf(
                                                PingTaskDto(
                                                        id = 10,
                                                        total = 400,
                                                        loss = 2.5,
                                                        latest = 21.0,
                                                        avg = 20.0,
                                                        p50 = 19.0
                                                )
                                        ),
                                records =
                                        listOf(
                                                PingRecordDto(
                                                        10,
                                                        "2026-01-01T00:00:00Z",
                                                        -1.0,
                                                        "node"
                                                )
                                        )
                        )
                        .toNodePingHistory("node")

        with(history.tasks.single()) {
            assertEquals(400, total)
            assertEquals(2.5, loss!!, 0.001)
            assertEquals(21.0, latest!!, 0.001)
            assertEquals(20.0, avg!!, 0.001)
            assertEquals(19.0, p50!!, 0.001)
            assertNull(p99)
            assertTrue(statisticsAreServerCalculated)
        }
    }

    @Test
    fun completeLossHidesContradictoryLatencyStatistics() {
        val task =
                PingRecordsResponseDto(
                                tasks =
                                        listOf(
                                                PingTaskDto(
                                                        id = 11,
                                                        total = 20,
                                                        loss = 100.0,
                                                        latest = 9.0,
                                                        min = 8.0,
                                                        max = 10.0,
                                                        avg = 9.0,
                                                        p50 = 9.0,
                                                        p99 = 10.0,
                                                        p99p50Ratio = 0.1
                                                )
                                        )
                        )
                        .toNodePingHistory("node")
                        .tasks
                        .single()

        assertEquals(20, task.total)
        assertEquals(100.0, task.loss!!, 0.001)
        assertNull(task.latest)
        assertNull(task.min)
        assertNull(task.max)
        assertNull(task.avg)
        assertNull(task.p50)
        assertNull(task.p99)
        assertNull(task.jitterRatio)
    }

    @Test
    fun recordFallbackUsesOverflowSafeIntegerAverage() {
        val task =
                PingRecordsResponseDto(
                                tasks = listOf(PingTaskDto(id = 12)),
                                records =
                                        listOf(
                                                PingRecordDto(
                                                        12,
                                                        "2026-01-01T00:00:00Z",
                                                        2_000_000_000.0,
                                                        "node"
                                                ),
                                                PingRecordDto(
                                                        12,
                                                        "2026-01-01T00:01:00Z",
                                                        2_000_000_000.0,
                                                        "node"
                                                )
                                        )
                        )
                        .toNodePingHistory("node")
                        .tasks
                        .single()

        assertEquals(2_000_000_000.0, task.avg!!, 0.001)
    }
}
