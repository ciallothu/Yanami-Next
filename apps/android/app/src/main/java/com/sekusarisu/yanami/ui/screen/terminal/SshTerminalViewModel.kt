package com.sekusarisu.yanami.ui.screen.terminal

import android.util.Log
import cafe.adriel.voyager.core.model.screenModelScope
import com.sekusarisu.yanami.R
import com.sekusarisu.yanami.data.local.preferences.UserPreferencesRepository
import com.sekusarisu.yanami.data.remote.buildKomariWebSocketEndpoint
import com.sekusarisu.yanami.data.remote.isValidSensitiveTwoFactorCode
import com.sekusarisu.yanami.data.remote.runKomariWebSocketLifecycle
import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.model.ServerInstance
import com.sekusarisu.yanami.domain.model.TerminalSnippet
import com.sekusarisu.yanami.domain.repository.Requires2FAException
import com.sekusarisu.yanami.domain.repository.ServerRepository
import com.sekusarisu.yanami.mvi.MviViewModel
import com.sekusarisu.yanami.ui.screen.isSessionAuthError
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * SSH 终端 ViewModel
 *
 * 管理 WebSocket 连接生命周期，以及 [WsTerminalBridge] 与 WebSocket 之间的双向数据路由：
 * - 原生 Termux 输入/终端响应 → transport → 代际隔离发送队列 → WebSocket Binary Frame
 * - WebSocket Text/Binary Frame → bounded output pump → TerminalEmulator → 重绘
 * - 原生 TerminalView 尺寸变化 → transport → resize JSON
 * - 定时 WebSocket ping 保活
 */
class SshTerminalViewModel(
        val uuid: String,
        private val serverRepository: ServerRepository,
        private val httpClient: HttpClient,
        private val userPreferencesRepository: UserPreferencesRepository
) :
        MviViewModel<SshTerminalContract.State, SshTerminalContract.Event, SshTerminalContract.Effect>(
                SshTerminalContract.State()
        ) {

    companion object {
        private const val TAG = "SshTerminalVM"
        // Keep below the shared HttpClient's 15-second socket timeout.
        private const val HEARTBEAT_INTERVAL_MS = 10_000L
        private const val SEND_QUEUE_CAPACITY = 128
        private const val MAX_INPUT_CHUNK_BYTES = 32 * 1024
    }

    private val connectionLock = Any()
    private var wsJob: Job? = null
    @Volatile
    private var connectionGeneration = 0L
    @Volatile
    private var activeSendQueue: ActiveSendQueue? = null
    private var passwordHandshakeRequiresTwoFactorHint = false

    /**
     * TerminalSession 客户端桥接，Screen 创建 TerminalView 后通过
     * [TerminalClientBridge.onTextChanged] 注册重绘回调。
     */
    val clientBridge = TerminalClientBridge()

    /** WebSocket ↔ TerminalEmulator 桥接器，向 Screen 暴露 session 供 TerminalView 附加。 */
    val terminalBridge =
            WsTerminalBridge(
                    sessionClient = clientBridge,
                    onInput = ::sendRawInput,
                    onResize = ::sendResize,
                    onClose = ::cancelCurrentConnection
            )

    /** 缓存 onSizeChanged 最后报告的终端尺寸，建连后作为首次 resize 发送 */
    private var lastCols: Int = 80
    private var lastRows: Int = 24

    init {
        // 从 DataStore 读取上次保存的字号，恢复用户偏好
        screenModelScope.launch {
            userPreferencesRepository.terminalFontSize.collect { size ->
                setState { copy(fontSize = size) }
            }
        }
        screenModelScope.launch {
            userPreferencesRepository.terminalSnippets.collect { snippets ->
                setState { copy(snippets = snippets) }
            }
        }
        connect()
    }

    override fun onEvent(event: SshTerminalContract.Event) {
        when (event) {
            is SshTerminalContract.Event.Disconnect -> {
                cancelCurrentConnection()
                sendEffect(SshTerminalContract.Effect.NavigateBack)
            }
            is SshTerminalContract.Event.FontSizeChanged -> {
                val newSize = (currentState.fontSize + event.delta).coerceIn(8, 32)
                setState { copy(fontSize = newSize) }
                screenModelScope.launch { userPreferencesRepository.setTerminalFontSize(newSize) }
            }
            is SshTerminalContract.Event.ToggleCtrl ->
                    setState { copy(ctrlActive = !ctrlActive, altActive = false) }
            is SshTerminalContract.Event.ToggleAlt ->
                    setState { copy(altActive = !altActive, ctrlActive = false) }
            is SshTerminalContract.Event.ToggleFn -> setState { copy(fnMode = !fnMode) }
            is SshTerminalContract.Event.Retry -> connect()
            is SshTerminalContract.Event.SubmitTwoFactorCode -> {
                val normalizedCode = event.code.trim()
                if (isValidSensitiveTwoFactorCode(normalizedCode)) {
                    connect(normalizedCode)
                } else {
                    setState {
                        copy(
                                showTwoFactorPrompt = true,
                                hasTwoFactorError = true
                        )
                    }
                }
            }
            is SshTerminalContract.Event.CancelTwoFactorPrompt -> {
                setState { copy(showTwoFactorPrompt = false, hasTwoFactorError = false) }
                sendEffect(SshTerminalContract.Effect.NavigateBack)
            }
            is SshTerminalContract.Event.ToggleSnippetsPanel ->
                    setState { copy(isSnippetsPanelOpen = !isSnippetsPanelOpen) }
            is SshTerminalContract.Event.SetSnippetsPanelOpen ->
                    setState { copy(isSnippetsPanelOpen = event.open) }
            is SshTerminalContract.Event.Resize -> {
                // 由 sendResize() 直接处理
            }
        }
    }

    /** 将工具栏按键字节路由到 WebSocket。
     *
     * 若 CTRL/ALT 修饰键激活，自动应用修饰后发送，并将修饰键重置为未激活状态。
     * - CTRL + 单字节字符 → 该字符 & 0x1F（控制码）
     * - ALT + 任意字节 → ESC 前缀 + 原始字节
     */
    fun sendInput(bytes: ByteArray) {
        val ctrl = currentState.ctrlActive
        val alt = currentState.altActive
        if (ctrl) setState { copy(ctrlActive = false) }
        if (alt) setState { copy(altActive = false) }
        val modifiedBytes = when {
            ctrl && bytes.size == 1 -> byteArrayOf((bytes[0].toInt() and 0x1f).toByte())
            alt -> byteArrayOf(27) + bytes
            else -> bytes
        }
        enqueueBinary(modifiedBytes)
    }

    /** 直接将原始有序字节发送到 WebSocket（由 Termux transport 和命令片段调用）。 */
    fun sendRawInput(bytes: ByteArray) {
        enqueueBinary(bytes)
    }

    private fun enqueueBinary(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + MAX_INPUT_CHUNK_BYTES, bytes.size)
            if (!enqueueMessage(WsOutMessage.Binary(bytes.copyOfRange(offset, end)))) return
            offset = end
        }
    }

    fun sendSnippet(snippet: TerminalSnippet) {
        val normalizedContent = normalizeSnippetContent(snippet.content)
        if (normalizedContent.isEmpty()) return
        val payload =
                buildString {
                    append(normalizedContent.replace("\n", "\r"))
                    if (snippet.appendEnter && !normalizedContent.endsWith("\n")) {
                        append('\r')
                    }
                }
        sendRawInput(payload.toByteArray(Charsets.UTF_8))
    }

    fun saveSnippet(
            snippetId: String?,
            title: String,
            content: String,
            appendEnter: Boolean
    ): Boolean {
        val normalizedTitle = title.trim()
        val normalizedContent = normalizeSnippetContent(content)
        if (normalizedTitle.isEmpty()) return false
        if (normalizedContent.isBlank()) return false

        val snippet =
                TerminalSnippet(
                        id = snippetId ?: UUID.randomUUID().toString(),
                        title = normalizedTitle,
                        content = normalizedContent,
                        appendEnter = appendEnter
                )
        val updatedSnippets =
                currentState.snippets
                        .filterNot { it.id == snippet.id }
                        .plus(snippet)
                        .sortedBy { it.title.lowercase() }
        persistSnippets(updatedSnippets)
        return true
    }

    fun deleteSnippet(snippetId: String) {
        persistSnippets(currentState.snippets.filterNot { it.id == snippetId })
    }

    /** 将终端尺寸变化发送到 WebSocket（由 Screen 层 Modifier.onSizeChanged 触发）
     *
     * 若 WebSocket 尚未建连，仅缓存最新尺寸；建连后立即作为初始 resize 发送。
     */
    fun sendResize(cols: Int, rows: Int) {
        val safeCols = cols.coerceAtLeast(1)
        val safeRows = rows.coerceAtLeast(1)
        if (lastCols == safeCols && lastRows == safeRows) return
        lastCols = safeCols
        lastRows = safeRows
        if (!currentState.isConnected) return
        enqueueMessage(WsOutMessage.Text(resizeMessage(safeCols, safeRows)))
    }

    internal fun isVirtualCtrlActive(): Boolean = currentState.ctrlActive

    internal fun isVirtualAltActive(): Boolean = currentState.altActive

    internal fun consumeVirtualModifiers() {
        if (currentState.ctrlActive || currentState.altActive) {
            setState { copy(ctrlActive = false, altActive = false) }
        }
    }

    private fun connect(oneTimeTwoFactorCode: String? = null) {
        val normalizedTwoFactorCode = oneTimeTwoFactorCode?.trim()
        val previousJob: Job?
        val attemptGeneration: Long
        synchronized(connectionLock) {
            previousJob = wsJob
            attemptGeneration = ++connectionGeneration
            activeSendQueue?.channel?.close()
            activeSendQueue = null
        }
        setState {
            copy(
                    isConnecting = true,
                    isConnected = false,
                    error = null,
                    showTwoFactorPrompt = false,
                    hasTwoFactorError = false
            )
        }
        val attemptJob =
                screenModelScope.launch(start = CoroutineStart.LAZY) {
                    // A retry never overlaps an old sender/receiver. Its per-generation queue is
                    // closed above, then the old connection must finish cancellation before any
                    // profile lookup, session refresh, or new handshake can begin.
                    previousJob?.cancelAndJoin()
                    if (!isCurrentAttempt(attemptGeneration)) return@launch

                    val server = serverRepository.getActive()
                    if (server == null) {
                        updateCurrentAttempt(attemptGeneration) {
                            copy(isConnecting = false, error = R.string.error_no_server_selected)
                        }
                        return@launch
                    }

                    // GUEST 模式不支持 SSH 终端
                    if (server.authType == AuthType.GUEST) {
                        updateCurrentAttempt(attemptGeneration) {
                            copy(
                                    isConnecting = false,
                                    error = R.string.terminal_error_guest_unsupported
                            )
                        }
                        return@launch
                    }

                    val requiresSensitiveTwoFactor =
                            requiresTerminalSensitiveTwoFactor(
                                    authType = server.authType,
                                    profileRequiresTwoFactor = server.requires2fa,
                                    passwordAuthenticationWasRejected =
                                            passwordHandshakeRequiresTwoFactorHint
                            )
                    if (requiresSensitiveTwoFactor &&
                                    !isValidSensitiveTwoFactorCode(
                                            normalizedTwoFactorCode.orEmpty()
                                    )
                    ) {
                        updateCurrentAttempt(attemptGeneration) {
                            copy(
                                    isConnecting = false,
                                    isConnected = false,
                                    error = null,
                                    showTwoFactorPrompt = true,
                                    hasTwoFactorError = normalizedTwoFactorCode != null
                            )
                        }
                        return@launch
                    }

                    val sessionToken =
                            try {
                                // If the cached password session expired, the same transient code
                                // can refresh it atomically under the repository's authentication
                                // mutex. It is never persisted; the terminal handshake below is
                                // the only header use for this attempt.
                                serverRepository.ensureSessionToken(
                                        server,
                                        twoFaCode =
                                                normalizedTwoFactorCode.takeIf {
                                                    server.authType == AuthType.PASSWORD
                                                }
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Requires2FAException) {
                                if (isCurrentAttempt(attemptGeneration)) {
                                    passwordHandshakeRequiresTwoFactorHint = true
                                    rememberTwoFactorRequirement(server)
                                    updateCurrentAttempt(attemptGeneration) {
                                        copy(
                                                isConnecting = false,
                                                isConnected = false,
                                                error = null,
                                                showTwoFactorPrompt = true,
                                                hasTwoFactorError =
                                                        normalizedTwoFactorCode != null
                                        )
                                    }
                                }
                                return@launch
                            } catch (_: Exception) {
                                updateCurrentAttempt(attemptGeneration) {
                                    copy(
                                            isConnecting = false,
                                            isConnected = false,
                                            error = R.string.terminal_error_secure_session
                                    )
                                }
                                if (isCurrentAttempt(attemptGeneration)) {
                                    sendEffect(
                                            SshTerminalContract.Effect.ShowToast(
                                                    R.string.terminal_error_authentication
                                            )
                                        )
                                }
                                return@launch
                            }
                    if (sessionToken.isBlank() && server.authType != AuthType.GUEST) {
                        updateCurrentAttempt(attemptGeneration) {
                            copy(
                                    isConnecting = false,
                                    error = R.string.terminal_error_missing_credential
                            )
                        }
                        return@launch
                    }

                    val endpoint =
                            buildKomariWebSocketEndpoint(
                                    server.baseUrl,
                                    "/api/admin/client/$uuid/terminal"
                            )
                    val authType = server.authType
                    val customHeaders = server.customHeaders.toList()

                    Log.d(TAG, "Preparing terminal WebSocket (authType=$authType)")

                    val attemptSendQueue = Channel<WsOutMessage>(SEND_QUEUE_CAPACITY)
                    try {
                        val wsBlock: suspend DefaultClientWebSocketSession.() -> Unit = {
                            Log.d(TAG, "WebSocket connected")
                            val wsSession = this

                            // 建连后立即发送初始 resize（使用 onSizeChanged 已缓存的实际尺寸）
                            wsSession.send(Frame.Text(resizeMessage(lastCols, lastRows)))
                            Log.d(TAG, "Sent initial resize: ${lastCols}x${lastRows}")

                            // 标准 WebSocket ping 由服务端协议栈回复 pong，不污染终端 JSON 协议。
                            val heartbeatJob = launch {
                                while (isActive) {
                                    delay(HEARTBEAT_INTERVAL_MS)
                                    wsSession.send(Frame.Ping(byteArrayOf()))
                                }
                            }

                            // Each handshake owns a fresh queue. No item from an old server or
                            // retry generation can ever be observed by this sender.
                            val senderJob = launch {
                                for (msg in attemptSendQueue) {
                                    if (!isActive) break
                                    when (msg) {
                                        is WsOutMessage.Binary ->
                                                wsSession.send(Frame.Binary(true, msg.data))
                                        is WsOutMessage.Text -> wsSession.send(Frame.Text(msg.text))
                                    }
                                }
                            }

                            if (!activateSendQueue(attemptGeneration, attemptSendQueue)) {
                                throw CancellationException("Superseded terminal connection")
                            }
                            withContext(Dispatchers.Main.immediate) {
                                updateCurrentAttempt(attemptGeneration) {
                                    copy(isConnecting = false, isConnected = true, error = null)
                                }
                            }
                            if (!isCurrentAttempt(attemptGeneration)) {
                                throw CancellationException("Superseded terminal connection")
                            }

                            val outputPump =
                                    TerminalOutputPump(
                                            scope = this,
                                            consume = terminalBridge::feedOutput
                                    )

                            // Komari terminal output is normally binary, while status/errors are text.
                            try {
                                for (frame in incoming) {
                                    when (frame) {
                                        is Frame.Binary -> outputPump.enqueue(frame.data)
                                        is Frame.Text -> outputPump.enqueue(frame.data)
                                        else -> {}
                                    }
                                }
                            } finally {
                                deactivateSendQueue(attemptGeneration, attemptSendQueue)
                                attemptSendQueue.close()
                                withContext(NonCancellable) {
                                    heartbeatJob.cancelAndJoin()
                                    senderJob.cancelAndJoin()
                                    outputPump.closeAndDrain()
                                    withContext(Dispatchers.Main.immediate) {
                                        updateCurrentAttempt(attemptGeneration) {
                                            copy(isConnected = false)
                                        }
                                    }
                                }
                            }
                        }

                        httpClient.runKomariWebSocketLifecycle(
                                endpoint = endpoint,
                                sessionToken = sessionToken,
                                authType = authType,
                                customHeaders = customHeaders,
                                loggerTag = TAG,
                                requiresSensitiveTwoFactor = requiresSensitiveTwoFactor,
                                oneTimeTwoFactorCode = normalizedTwoFactorCode,
                                block = wsBlock
                        )
                        // 连接正常关闭
                        if (isCurrentAttempt(attemptGeneration)) {
                            sendEffect(SshTerminalContract.Effect.NavigateBack)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "WebSocket failed (${e::class.java.simpleName})")
                        if (!isCurrentAttempt(attemptGeneration)) return@launch
                        if (e.isSessionAuthError()) {
                            if (shouldRememberTerminalTwoFactorHint(authType, true)) {
                                // Compatibility for profiles created before requires2fa was stored:
                                // the next retry must offer a fresh sensitive-operation code.
                                passwordHandshakeRequiresTwoFactorHint = true
                                rememberTwoFactorRequirement(server)
                            }
                            val message =
                                    if (requiresSensitiveTwoFactor) {
                                        R.string.terminal_error_sensitive_2fa
                                    } else if (authType == AuthType.PASSWORD) {
                                        R.string.terminal_error_password_rejected
                                    } else {
                                        R.string.terminal_error_authentication
                                    }
                            updateCurrentAttempt(attemptGeneration) {
                                copy(isConnecting = false, isConnected = false, error = message)
                            }
                            sendEffect(SshTerminalContract.Effect.ShowToast(message))
                        } else {
                            updateCurrentAttempt(attemptGeneration) {
                                copy(
                                        isConnecting = false,
                                        isConnected = false,
                                        error = R.string.terminal_error_connection_failed
                                )
                            }
                            sendEffect(
                                    SshTerminalContract.Effect.ShowToast(
                                            R.string.terminal_error_connection_failed
                                    )
                            )
                        }
                    } finally {
                        deactivateSendQueue(attemptGeneration, attemptSendQueue)
                        attemptSendQueue.close()
                    }
                }
        synchronized(connectionLock) {
            if (attemptGeneration == connectionGeneration) {
                wsJob = attemptJob
            } else {
                attemptJob.cancel()
            }
        }
        attemptJob.invokeOnCompletion {
            synchronized(connectionLock) {
                if (wsJob === attemptJob) wsJob = null
            }
        }
        attemptJob.start()
    }

    private fun updateCurrentAttempt(
            attemptGeneration: Long,
            transform: SshTerminalContract.State.() -> SshTerminalContract.State
    ) {
        if (isCurrentAttempt(attemptGeneration)) setState(transform)
    }

    private suspend fun rememberTwoFactorRequirement(server: ServerInstance) {
        try {
            serverRepository.updateRequires2fa(server, true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A concurrent profile identity change must prevent this old request from updating
            // the replacement row. Keep only the in-memory compatibility hint for this screen.
            Log.w(TAG, "Skipped stale 2FA capability update (${e::class.java.simpleName})")
        }
    }

    override fun onDispose() {
        super.onDispose()
        cancelCurrentConnection()
        terminalBridge.session.finishIfRunning()
    }

    private fun persistSnippets(snippets: List<TerminalSnippet>) {
        setState { copy(snippets = snippets) }
        screenModelScope.launch { userPreferencesRepository.setTerminalSnippets(snippets) }
    }

    private fun normalizeSnippetContent(content: String): String =
            content.replace("\r\n", "\n").replace('\r', '\n')

    private fun resizeMessage(cols: Int, rows: Int): String =
            buildJsonObject {
                        put("type", "resize")
                        put("cols", cols)
                        put("rows", rows)
                    }
                    .toString()

    private fun enqueueMessage(message: WsOutMessage): Boolean {
        val (target, result) =
                synchronized(connectionLock) {
                    val active = activeSendQueue ?: return false
                    if (!canEnqueueTerminalMessage(
                                    currentGeneration = connectionGeneration,
                                    queueGeneration = active.generation,
                                    isConnected = currentState.isConnected
                            )
                    ) {
                        return false
                    }
                    active to active.channel.trySend(message)
                }
        if (result.isFailure) {
            Log.e(TAG, "Terminal send queue closed or full; closing connection to preserve order")
            val jobToCancel =
                    synchronized(connectionLock) {
                        if (target.generation != connectionGeneration ||
                                        activeSendQueue !== target
                        ) {
                            null
                        } else {
                            activeSendQueue = null
                            target.channel.close()
                            wsJob
                        }
                    }
            if (jobToCancel != null) {
                updateCurrentAttempt(target.generation) {
                    copy(
                            isConnecting = false,
                            isConnected = false,
                            error = R.string.terminal_error_send_buffer_full
                    )
                }
                jobToCancel.cancel()
            }
            return false
        }
        return true
    }

    private fun activateSendQueue(generation: Long, channel: Channel<WsOutMessage>): Boolean =
            synchronized(connectionLock) {
                if (generation != connectionGeneration) {
                    false
                } else {
                    activeSendQueue = ActiveSendQueue(generation, channel)
                    true
                }
            }

    private fun deactivateSendQueue(generation: Long, channel: Channel<WsOutMessage>) {
        synchronized(connectionLock) {
            val active = activeSendQueue
            if (active?.generation == generation && active.channel === channel) {
                activeSendQueue = null
            }
        }
    }

    private fun isCurrentAttempt(generation: Long): Boolean =
            generation == connectionGeneration

    private fun cancelCurrentConnection() {
        val jobToCancel =
                synchronized(connectionLock) {
                    connectionGeneration++
                    activeSendQueue?.channel?.close()
                    activeSendQueue = null
                    wsJob
                }
        jobToCancel?.cancel()
    }

    // ─── 内部类型 ───

    private sealed interface WsOutMessage {
        data class Binary(val data: ByteArray) : WsOutMessage
        data class Text(val text: String) : WsOutMessage
    }

    private data class ActiveSendQueue(
            val generation: Long,
            val channel: Channel<WsOutMessage>
    )

    /**
     * TerminalSession 客户端桥接器
     *
     * [onTextChanged] 由 Screen 在创建 TerminalView 后注册，触发 View 重绘。
     */
    class TerminalClientBridge : TerminalSessionClient {

        var onTextChanged: () -> Unit = {}
        var onCopyText: (String) -> Unit = {}
        var onPasteRequested: () -> Unit = {}

        override fun onTextChanged(changedSession: TerminalSession) = onTextChanged()

        override fun onTitleChanged(updatedSession: TerminalSession) {}

        override fun onSessionFinished(finishedSession: TerminalSession) {}

        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = onCopyText(text)

        override fun onPasteTextFromClipboard(session: TerminalSession?) = onPasteRequested()

        override fun onBell(session: TerminalSession) {}

        override fun onColorsChanged(session: TerminalSession) {}

        override fun onTerminalCursorStateChange(state: Boolean) {}

        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}

        override fun getTerminalCursorStyle(): Int = 0

        override fun logError(tag: String?, message: String?) {}

        override fun logWarn(tag: String?, message: String?) {}

        override fun logInfo(tag: String?, message: String?) {}

        override fun logDebug(tag: String?, message: String?) {}

        override fun logVerbose(tag: String?, message: String?) {}

        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}

        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}
