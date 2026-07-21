package com.sekusarisu.yanami.ui.screen.terminal

/**
 * A terminal input belongs to exactly one live WebSocket generation.
 *
 * The runtime additionally gives every generation its own Channel. This small pure predicate keeps
 * the fail-closed admission rule explicit and unit-testable without Android or a live socket.
 */
internal fun canEnqueueTerminalMessage(
        currentGeneration: Long,
        queueGeneration: Long,
        isConnected: Boolean
): Boolean = isConnected && queueGeneration == currentGeneration
