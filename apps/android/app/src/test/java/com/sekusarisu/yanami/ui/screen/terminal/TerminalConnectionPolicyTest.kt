package com.sekusarisu.yanami.ui.screen.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalConnectionPolicyTest {
    @Test
    fun onlyCurrentConnectedGenerationAcceptsInput() {
        assertTrue(
                canEnqueueTerminalMessage(
                        currentGeneration = 7,
                        queueGeneration = 7,
                        isConnected = true
                )
        )
        assertFalse(
                canEnqueueTerminalMessage(
                        currentGeneration = 8,
                        queueGeneration = 7,
                        isConnected = true
                )
        )
        assertFalse(
                canEnqueueTerminalMessage(
                        currentGeneration = 7,
                        queueGeneration = 7,
                        isConnected = false
                )
        )
    }

    @Test
    fun retryGenerationRejectsEveryOldServerQueue() {
        val oldServerQueueGeneration = 41L
        val retryGeneration = oldServerQueueGeneration + 1

        assertFalse(
                canEnqueueTerminalMessage(
                        currentGeneration = retryGeneration,
                        queueGeneration = oldServerQueueGeneration,
                        isConnected = true
                )
        )
    }
}
