package com.sekusarisu.yanami.ui.screen.terminal

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TerminalTransport
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Connects Termux's terminal emulator to the Komari WebSocket transport.
 *
 * Unlike the previous bridge, this session never starts a local shell. Termux's own input,
 * keyboard-mode, paste, mouse, and terminal-response paths all write through [TerminalTransport],
 * so there is one ordered route to the remote PTY.
 */
class WsTerminalBridge(
        private val sessionClient: TerminalSessionClient,
        onInput: (ByteArray) -> Unit,
        onResize: (columns: Int, rows: Int) -> Unit,
        onClose: () -> Unit
) {
    private val emulatorReady = CompletableDeferred<Unit>()
    private val transportClosed = AtomicBoolean(false)

    private val transport =
            object : TerminalTransport {
                override fun write(data: ByteArray, offset: Int, count: Int) {
                    if (transportClosed.get() || count <= 0) return
                    onInput(data.copyOfRange(offset, offset + count))
                }

                override fun resize(
                        columns: Int,
                        rows: Int,
                        cellWidthPixels: Int,
                        cellHeightPixels: Int
                ) {
                    if (transportClosed.get()) return
                    emulatorReady.complete(Unit)
                    onResize(columns, rows)
                }

                override fun close() {
                    if (transportClosed.compareAndSet(false, true)) {
                        onClose()
                    }
                }
            }

    /** Native Termux session attached directly to [com.termux.view.TerminalView]. */
    val session = TerminalSession(transport, TRANSCRIPT_ROWS, sessionClient)

    /**
     * Feed an ordered, bounded output batch into the emulator.
     *
     * Output waits for the first real layout rather than being discarded before the emulator has a
     * column/row size. TerminalEmulator is confined to the main thread as required by Termux.
     */
    suspend fun feedOutput(data: ByteArray) {
        if (data.isEmpty() || transportClosed.get()) return
        emulatorReady.await()
        withContext(Dispatchers.Main.immediate) {
            if (transportClosed.get()) return@withContext
            val emulator = session.emulator ?: return@withContext
            emulator.append(data, data.size)
            sessionClient.onTextChanged(session)
        }
    }

    companion object {
        private const val TRANSCRIPT_ROWS = 3_000
    }
}
