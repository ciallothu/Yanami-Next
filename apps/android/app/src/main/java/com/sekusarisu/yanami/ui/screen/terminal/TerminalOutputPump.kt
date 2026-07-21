package com.sekusarisu.yanami.ui.screen.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ordered, bounded terminal-output pipeline.
 *
 * Incoming frames are split before entering the bounded channel, so the maximum queued payload is
 * approximately `batchSizeBytes * queueCapacity`. A full queue suspends the WebSocket receiver and
 * applies network backpressure instead of growing memory or dropping terminal control sequences.
 */
internal class TerminalOutputPump(
        scope: CoroutineScope,
        private val consume: suspend (ByteArray) -> Unit,
        private val batchSizeBytes: Int = DEFAULT_BATCH_SIZE_BYTES,
        queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
        private val frameIntervalMs: Long = DEFAULT_FRAME_INTERVAL_MS
) {
    private val chunks = Channel<ByteArray>(queueCapacity)
    private val worker: Job = scope.launch { consumeLoop() }

    init {
        require(batchSizeBytes > 0) { "batchSizeBytes must be positive" }
        require(queueCapacity > 0) { "queueCapacity must be positive" }
        require(frameIntervalMs >= 0) { "frameIntervalMs must not be negative" }
    }

    suspend fun enqueue(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + batchSizeBytes, data.size)
            chunks.send(data.copyOfRange(offset, end))
            offset = end
        }
    }

    fun cancel() {
        chunks.cancel()
        worker.cancel()
    }

    /** Drain already accepted output on a normal socket close, with a bounded shutdown time. */
    suspend fun closeAndDrain(timeoutMs: Long = DEFAULT_DRAIN_TIMEOUT_MS) {
        chunks.close()
        if (withTimeoutOrNull(timeoutMs) { worker.join() } == null) {
            worker.cancelAndJoin()
        }
    }

    private suspend fun consumeLoop() {
        var carry: ByteArray? = null
        var carryOffset = 0

        while (currentCoroutineContext().isActive) {
            if (carry == null) {
                carry = chunks.receiveCatching().getOrNull() ?: break
                carryOffset = 0
                if (frameIntervalMs > 0) delay(frameIntervalMs)
            }

            val batch = ByteArray(batchSizeBytes)
            var batchLength = 0

            while (batchLength < batchSizeBytes) {
                val current = carry ?: chunks.tryReceive().getOrNull() ?: break
                val available = current.size - carryOffset
                val copied = minOf(available, batchSizeBytes - batchLength)
                current.copyInto(
                        destination = batch,
                        destinationOffset = batchLength,
                        startIndex = carryOffset,
                        endIndex = carryOffset + copied
                )
                batchLength += copied
                carryOffset += copied

                if (carryOffset == current.size) {
                    carry = null
                    carryOffset = 0
                } else {
                    carry = current
                }
            }

            if (batchLength > 0) {
                consume(if (batchLength == batch.size) batch else batch.copyOf(batchLength))
            }
        }
    }

    companion object {
        internal const val DEFAULT_BATCH_SIZE_BYTES = 32 * 1024
        internal const val DEFAULT_QUEUE_CAPACITY = 32
        internal const val DEFAULT_FRAME_INTERVAL_MS = 16L
        internal const val DEFAULT_DRAIN_TIMEOUT_MS = 500L
    }
}
