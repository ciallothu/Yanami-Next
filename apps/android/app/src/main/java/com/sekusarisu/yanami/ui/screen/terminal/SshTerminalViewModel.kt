package com.sekusarisu.yanami.ui.screen.terminal

import android.util.Log
import cafe.adriel.voyager.core.model.screenModelScope
import com.sekusarisu.yanami.data.local.preferences.UserPreferencesRepository
import com.sekusarisu.yanami.data.remote.buildKomariWebSocketEndpoint
import com.sekusarisu.yanami.data.remote.runKomariWebSocketLifecycle
import com.sekusarisu.yanami.domain.model.TerminalSnippet
import com.sekusarisu.yanami.domain.model.AuthType
import com.sekusarisu.yanami.domain.repository.ServerRepository
import com.sekusarisu.yanami.mvi.MviViewModel
import com.sekusarisu.yanami.ui.screen.isSessionAuthError
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
 * - 原生 Termux 输入/终端响应 → transport → [sendQueue] → WebSocket Binary Frame
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

    /** 单一 FIFO 队列保证软键盘、硬件键、粘贴、终端回复和 resize 的发送顺序。 */
    private val sendQueue = Channel<WsOutMessage>(SEND_QUEUE_CAPACITY)

    private var wsJob: Job? = null

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
                    onClose = { wsJob?.cancel() }
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
                wsJob?.cancel()
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

    private fun connect() {
        wsJob =
                screenModelScope.launch {
                    val server = serverRepository.getActive()
                    if (server == null) {
                        setState { copy(isConnecting = false, error = "未选择服务器") }
                        return@launch
                    }

                    // GUEST 模式不支持 SSH 终端
                    if (server.authType == AuthType.GUEST) {
                        setState { copy(isConnecting = false, error = "游客模式不支持 SSH 终端") }
                        return@launch
                    }

                    val sessionToken =
                            try {
                                serverRepository.ensureSessionToken(server)
                            } catch (_: Exception) {
                                setState {
                                    copy(
                                            isConnecting = false,
                                            isConnected = false,
                                            error = "无法建立安全会话，请重新登录"
                                    )
                                }
                                sendEffect(
                                        SshTerminalContract.Effect.ShowToast(
                                                "SSH 鉴权失败，请重新登录"
                                        )
                                )
                                return@launch
                            }
                    if (sessionToken.isBlank() && server.authType != AuthType.GUEST) {
                        setState { copy(isConnecting = false, error = "无法获取有效认证凭据") }
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

                    try {
                        val wsBlock: suspend DefaultClientWebSocketSession.() -> Unit = {
                            Log.d(TAG, "WebSocket connected")
                            val wsSession = this
                            withContext(Dispatchers.Main.immediate) {
                                setState {
                                    copy(isConnecting = false, isConnected = true, error = null)
                                }
                            }

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

                            // 发送协程：从 sendQueue 取出消息发往 WebSocket
                            val senderJob = launch {
                                for (msg in sendQueue) {
                                    if (!isActive) break
                                    when (msg) {
                                        is WsOutMessage.Binary ->
                                                wsSession.send(Frame.Binary(true, msg.data))
                                        is WsOutMessage.Text -> wsSession.send(Frame.Text(msg.text))
                                    }
                                }
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
                                heartbeatJob.cancel()
                                senderJob.cancel()
                                withContext(NonCancellable) {
                                    outputPump.closeAndDrain()
                                    withContext(Dispatchers.Main.immediate) {
                                        setState { copy(isConnected = false) }
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
                                block = wsBlock
                        )
                        // 连接正常关闭
                        sendEffect(SshTerminalContract.Effect.NavigateBack)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "WebSocket error: ${e.message}")
                        if (e.isSessionAuthError()) {
                            setState {
                                copy(isConnecting = false, isConnected = false, error = "鉴权失败，请重新登录")
                            }
                            sendEffect(SshTerminalContract.Effect.ShowToast("SSH 连接被拒绝，请重新登录"))
                        } else {
                            setState {
                                copy(
                                        isConnecting = false,
                                        isConnected = false,
                                        error = e.message ?: "连接失败"
                                )
                            }
                            sendEffect(
                                    SshTerminalContract.Effect.ShowToast("SSH 连接失败: ${e.message}")
                            )
                        }
                    }
                }
    }

    override fun onDispose() {
        super.onDispose()
        terminalBridge.session.finishIfRunning()
        sendQueue.close()
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
        val result = sendQueue.trySend(message)
        if (result.isFailure) {
            Log.e(TAG, "Terminal send queue closed or full; closing connection to preserve order")
            setState {
                copy(
                        isConnecting = false,
                        isConnected = false,
                        error = "终端输入缓冲区已满，连接已关闭"
                )
            }
            wsJob?.cancel()
            return false
        }
        return true
    }

    // ─── 内部类型 ───

    private sealed interface WsOutMessage {
        data class Binary(val data: ByteArray) : WsOutMessage
        data class Text(val text: String) : WsOutMessage
    }

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
