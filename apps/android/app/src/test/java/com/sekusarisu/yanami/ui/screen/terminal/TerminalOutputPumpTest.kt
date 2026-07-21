package com.sekusarisu.yanami.ui.screen.terminal

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalOutputPumpTest {

    @Test
    fun preservesByteOrderAndCapsEveryBatch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val consumed = ByteArrayOutputStream()
        val batches = mutableListOf<Int>()
        val complete = CompletableDeferred<Unit>()
        val expected = ByteArray(37) { it.toByte() }
        val pump =
                TerminalOutputPump(
                        scope = scope,
                        consume = { batch ->
                            synchronized(consumed) {
                                consumed.write(batch)
                                batches += batch.size
                                if (consumed.size() == expected.size) complete.complete(Unit)
                            }
                        },
                        batchSizeBytes = 8,
                        queueCapacity = 2,
                        frameIntervalMs = 0
                )

        pump.enqueue(expected)
        withTimeout(2_000) { complete.await() }

        assertArrayEquals(expected, synchronized(consumed) { consumed.toByteArray() })
        assertTrue(batches.all { it in 1..8 })
        pump.cancel()
        scope.cancel()
    }

    @Test
    fun fullQueueSuspendsProducerInsteadOfDroppingBytes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val allowConsumption = CompletableDeferred<Unit>()
        val allConsumed = CompletableDeferred<Unit>()
        val consumed = ByteArrayOutputStream()
        val pump =
                TerminalOutputPump(
                        scope = scope,
                        consume = { batch ->
                            allowConsumption.await()
                            synchronized(consumed) {
                                consumed.write(batch)
                                if (consumed.size() == 12) allConsumed.complete(Unit)
                            }
                        },
                        batchSizeBytes = 4,
                        queueCapacity = 1,
                        frameIntervalMs = 0
                )
        val producer = launch { pump.enqueue(ByteArray(12) { it.toByte() }) }

        delay(50)
        assertFalse(producer.isCompleted)
        allowConsumption.complete(Unit)
        withTimeout(2_000) {
            producer.join()
            allConsumed.await()
        }

        assertArrayEquals(
                ByteArray(12) { it.toByte() },
                synchronized(consumed) { consumed.toByteArray() }
        )
        pump.cancel()
        scope.cancel()
    }
}
