package com.sekusarisu.yanami.ui.screen.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sekusarisu.yanami.R
import com.sekusarisu.yanami.domain.model.TerminalSnippet
import com.sekusarisu.yanami.ui.screen.rememberAdaptiveLayoutInfo
import com.sekusarisu.yanami.ui.screen.soundClick
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient


@Composable
internal fun SnippetsSidebar(
        visible: Boolean,
        snippets: List<TerminalSnippet>,
        isConnected: Boolean,
        onDismiss: () -> Unit,
        onStartCreate: () -> Unit,
        onStartEdit: (TerminalSnippet) -> Unit,
        onDeleteRequest: (TerminalSnippet) -> Unit,
        onSend: (TerminalSnippet) -> Unit
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val sidebarWidthFraction = if (adaptiveInfo.isTabletLandscape) 0.33f else 0.8f

    AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                    modifier =
                            Modifier.fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.42f))
                                    .clickable(onClick = onDismiss)
            )

            AnimatedVisibility(
                    visible = visible,
                    enter = slideInHorizontally { it },
                    exit = slideOutHorizontally { it },
                    modifier =
                            Modifier.align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .widthIn(min = 360.dp, max = 360.dp)
                                    .fillMaxWidth(sidebarWidthFraction)
            ) {
                Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 8.dp,
                        shadowElevation = 12.dp,
                        shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                                modifier =
                                        Modifier.fillMaxWidth()
                                                .padding(start = 20.dp, top = 20.dp, end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                        stringResource(R.string.terminal_snippets_title),
                                        style = MaterialTheme.typography.titleLarge
                                )
//                                Spacer(modifier = Modifier.height(4.dp))
//                                Text(
//                                        stringResource(R.string.terminal_snippets_desc),
//                                        style = MaterialTheme.typography.bodySmall,
//                                        color = MaterialTheme.colorScheme.onSurfaceVariant
//                                )
                            }
                            IconButton(onClick = soundClick { onStartCreate() }) {
                                Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription =
                                                stringResource(R.string.terminal_snippet_create)
                                )
                            }
                            IconButton(onClick = soundClick { onDismiss() }) {
                                Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription =
                                                stringResource(R.string.terminal_snippets_close)
                                )
                            }
                        }

//                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                        Text(
                                text = stringResource(R.string.terminal_snippets_saved_commands),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )

                        if (snippets.isEmpty()) {
                            Box(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                                    contentAlignment = Alignment.Center
                            ) {
                                Text(
                                        stringResource(R.string.terminal_snippets_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding =
                                            PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(snippets, key = { it.id }) { snippet ->
                                    SnippetListItem(
                                            snippet = snippet,
                                            isConnected = isConnected,
                                            onEdit = { onStartEdit(snippet) },
                                            onDelete = { onDeleteRequest(snippet) },
                                            onSend = { onSend(snippet) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SnippetEditorDialog(
        editingSnippetId: String?,
        snippetTitle: String,
        snippetContent: String,
        snippetAppendEnter: Boolean,
        onDismiss: () -> Unit,
        onTitleChange: (String) -> Unit,
        onContentChange: (String) -> Unit,
        onAppendEnterChange: (Boolean) -> Unit,
        onSave: () -> Unit
) {
    AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                        stringResource(
                                if (editingSnippetId == null) {
                                    R.string.terminal_snippet_create
                                } else {
                                    R.string.terminal_snippet_edit
                                }
                        )
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                            value = snippetTitle,
                            onValueChange = onTitleChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.terminal_snippet_name_label)) },
                            singleLine = true
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                            value = snippetContent,
                            onValueChange = onContentChange,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.terminal_snippet_content_label)) },
                            minLines = 4,
                            maxLines = 8
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                    stringResource(R.string.terminal_snippet_append_enter),
                                    style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                    stringResource(R.string.terminal_snippet_append_enter_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = snippetAppendEnter, onCheckedChange = onAppendEnterChange)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = soundClick { onSave() }) {
                    Text(
                            stringResource(
                                    if (editingSnippetId == null) {
                                        R.string.terminal_snippet_save
                                    } else {
                                        R.string.terminal_snippet_update
                                    }
                            )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = soundClick { onDismiss() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
    )
}

@Composable
private fun SnippetListItem(
        snippet: TerminalSnippet,
        isConnected: Boolean,
        onEdit: () -> Unit,
        onDelete: () -> Unit,
        onSend: () -> Unit
) {
    Card(
            colors =
                    CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                        text = snippet.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                )
                if (snippet.appendEnter) {
                    Text(
                            text = stringResource(R.string.terminal_snippet_auto_enter),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                    text = snippet.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                        onClick = soundClick { onSend() },
                        enabled = isConnected,
                        modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                            stringResource(
                                    if (isConnected) {
                                        R.string.terminal_snippet_send
                                    } else {
                                        R.string.terminal_snippet_disconnected
                                    }
                            )
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = soundClick { onEdit() }) {
                    Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.action_edit)
                    )
                }
                IconButton(onClick = soundClick { onDelete() }) {
                    Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.action_delete)
                    )
                }
            }
        }
    }
}

@Composable
internal fun TerminalContent(viewModel: SshTerminalViewModel, fontSize: Int) {
    DisposableEffect(Unit) {
        onDispose {
            viewModel.clientBridge.onTextChanged = {}
            viewModel.clientBridge.onCopyText = {}
            viewModel.clientBridge.onPasteRequested = {}
        }
    }

    AndroidView(
            factory = { ctx ->
                val clipboard = ctx.getSystemService(ClipboardManager::class.java)
                lateinit var terminalView: TerminalView
                terminalView =
                        TerminalView(ctx, null).apply {
                            setTextSize(fontSize)
                            setTerminalCursorBlinkerRate(500)
                            setTerminalViewClient(
                                    buildNativeTerminalClient(
                                            viewModel = viewModel,
                                            requestKeyboard = {
                                                requestFocus()
                                                ctx.getSystemService(InputMethodManager::class.java)
                                                        .showSoftInput(
                                                                this,
                                                                InputMethodManager.SHOW_IMPLICIT
                                                        )
                                            },
                                            postModifierReset = {
                                                post { viewModel.consumeVirtualModifiers() }
                                            },
                                            onEmulatorSet = {
                                                setTerminalCursorBlinkerState(true, true)
                                            },
                                            isSelected = { hasFocus() }
                                    )
                            )
                            attachSession(viewModel.terminalBridge.session)
                            isFocusable = true
                            isFocusableInTouchMode = true
                            requestFocus()
                        }

                viewModel.clientBridge.onTextChanged = { terminalView.post { terminalView.invalidate() } }
                viewModel.clientBridge.onCopyText = { text ->
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Terminal text", text))
                }
                viewModel.clientBridge.onPasteRequested = {
                    val text =
                            clipboard?.primaryClip
                                    ?.getItemAt(0)
                                    ?.coerceToText(ctx)
                                    ?.toString()
                                    ?.take(MAX_CLIPBOARD_PASTE_CHARS)
                    if (!text.isNullOrEmpty()) {
                        terminalView.currentSession?.emulator?.paste(text)
                    }
                }
                terminalView
            },
            update = { view ->
                view.setTextSize(fontSize)
                view.setTerminalCursorBlinkerState(true, true)
            },
            modifier = Modifier.fillMaxSize()
    )
}

private const val MAX_CLIPBOARD_PASTE_CHARS = 1_000_000

private fun buildNativeTerminalClient(
        viewModel: SshTerminalViewModel,
        requestKeyboard: () -> Unit,
        postModifierReset: () -> Unit,
        onEmulatorSet: () -> Unit,
        isSelected: () -> Boolean
): TerminalViewClient =
        object : TerminalViewClient {

            override fun onScale(scale: Float): Float {
                return when {
                    scale < 0.9f -> {
                        viewModel.onEvent(SshTerminalContract.Event.FontSizeChanged(-1))
                        1f
                    }
                    scale > 1.1f -> {
                        viewModel.onEvent(SshTerminalContract.Event.FontSizeChanged(1))
                        1f
                    }
                    else -> scale
                }
            }

            override fun onSingleTapUp(e: MotionEvent) = requestKeyboard()

            override fun shouldBackButtonBeMappedToEscape(): Boolean = false

            override fun shouldEnforceCharBasedInput(): Boolean = true

            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

            override fun isTerminalViewSelected(): Boolean = isSelected()

            override fun copyModeChanged(copyMode: Boolean) {}

            override fun onKeyDown(
                    keyCode: Int,
                    e: KeyEvent,
                    session: TerminalSession
            ): Boolean {
                if (viewModel.isVirtualCtrlActive() || viewModel.isVirtualAltActive()) {
                    // The native handler reads modifiers later in this same event dispatch.
                    postModifierReset()
                }
                return false
            }

            override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

            override fun onLongPress(e: MotionEvent): Boolean = false

            override fun readControlKey(): Boolean = viewModel.isVirtualCtrlActive()

            override fun readAltKey(): Boolean = viewModel.isVirtualAltActive()

            override fun readFnKey(): Boolean = false

            override fun readShiftKey(): Boolean = false

            override fun onCodePoint(
                    codePoint: Int,
                    ctrlDown: Boolean,
                    session: TerminalSession
            ): Boolean {
                viewModel.consumeVirtualModifiers()
                return false
            }

            override fun onEmulatorSet() = onEmulatorSet.invoke()

            override fun logError(tag: String?, message: String?) {}

            override fun logWarn(tag: String?, message: String?) {}

            override fun logInfo(tag: String?, message: String?) {}

            override fun logDebug(tag: String?, message: String?) {}

            override fun logVerbose(tag: String?, message: String?) {}

            override fun logStackTraceWithMessage(
                    tag: String?,
                    message: String?,
                    e: Exception?
            ) {}

            override fun logStackTrace(tag: String?, e: Exception?) {}
        }

private fun resolveCursorKeyBytes(bytes: ByteArray, cursorApp: Boolean): ByteArray {
    if (!cursorApp || bytes.size != 3) return bytes
    if (bytes[0] == 27.toByte() && bytes[1] == 91.toByte()) {
        when (bytes[2]) {
            65.toByte(),
            66.toByte(),
            67.toByte(),
            68.toByte() -> return byteArrayOf(27, 79, bytes[2])
        }
    }
    return bytes
}

private data class ToolbarKey(val label: String, val bytes: ByteArray)

private val ROW1_KEYS =
        listOf(
                ToolbarKey("ESC", byteArrayOf(27)),
                ToolbarKey("/", byteArrayOf('/'.code.toByte())),
                ToolbarKey("|", byteArrayOf('|'.code.toByte())),
                ToolbarKey("-", byteArrayOf('-'.code.toByte())),
                ToolbarKey("HOME", byteArrayOf(27, 91, 72)),
                ToolbarKey("↑", byteArrayOf(27, 91, 65)),
                ToolbarKey("END", byteArrayOf(27, 91, 70)),
                ToolbarKey("PGUP", byteArrayOf(27, 91, 53, 126)),
        )

private val ROW2_ARROW_KEYS =
        listOf(
                ToolbarKey("←", byteArrayOf(27, 91, 68)),
                ToolbarKey("↓", byteArrayOf(27, 91, 66)),
                ToolbarKey("→", byteArrayOf(27, 91, 67)),
                ToolbarKey("PGDN", byteArrayOf(27, 91, 54, 126)),
        )

private val FN_ROW1_KEYS =
        listOf(
                ToolbarKey("F1", byteArrayOf(27, 79, 80)),
                ToolbarKey("F2", byteArrayOf(27, 79, 81)),
                ToolbarKey("F3", byteArrayOf(27, 79, 82)),
                ToolbarKey("F4", byteArrayOf(27, 79, 83)),
                ToolbarKey("F5", byteArrayOf(27, 91, 49, 53, 126)),
                ToolbarKey("F6", byteArrayOf(27, 91, 49, 55, 126)),
        )

private val FN_ROW2_KEYS =
        listOf(
                ToolbarKey("F7", byteArrayOf(27, 91, 49, 56, 126)),
                ToolbarKey("F8", byteArrayOf(27, 91, 49, 57, 126)),
                ToolbarKey("F9", byteArrayOf(27, 91, 50, 48, 126)),
                ToolbarKey("F10", byteArrayOf(27, 91, 50, 49, 126)),
                ToolbarKey("F11", byteArrayOf(27, 91, 50, 51, 126)),
                ToolbarKey("F12", byteArrayOf(27, 91, 50, 52, 126)),
        )

private val BTN_PADDING = PaddingValues(horizontal = 1.dp)
private val BTN_HEIGHT = 44.dp

@Composable
internal fun SpecialKeysToolbar(
        ctrlActive: Boolean,
        altActive: Boolean,
        fnMode: Boolean,
        onKey: (ByteArray) -> Unit,
        onToggleCtrl: () -> Unit,
        onToggleAlt: () -> Unit,
        onToggleFn: () -> Unit,
        isCursorAppMode: () -> Boolean = { false },
        modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activeColor = MaterialTheme.colorScheme.primary
    val normalColor = MaterialTheme.colorScheme.onPrimaryContainer

    @Composable
    fun KeyBtn(key: ToolbarKey, modifier: Modifier) {
        TextButton(
                onClick = soundClick { onKey(resolveCursorKeyBytes(key.bytes, isCursorAppMode())) },
                modifier = modifier.height(BTN_HEIGHT),
                contentPadding = BTN_PADDING
        ) {
            Text(
                    key.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = normalColor,
                    maxLines = 1
            )
        }
    }

    @Composable
    fun ToggleBtn(label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
        TextButton(
                onClick = soundClick { onClick() },
                modifier = modifier.height(BTN_HEIGHT),
                contentPadding = BTN_PADDING,
                border = if (active) BorderStroke(1.dp, activeColor) else null
        ) {
            Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) activeColor else normalColor,
                    maxLines = 1
            )
        }
    }

    @Composable
    fun KbdBtn(modifier: Modifier) {
        IconButton(
                onClick = soundClick {
                    val imm = context.getSystemService(InputMethodManager::class.java)
                    @Suppress("DEPRECATION")
                    imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
                },
                modifier = modifier.height(BTN_HEIGHT)
        ) {
            Icon(
                    imageVector = Icons.Filled.Keyboard,
                    contentDescription = stringResource(R.string.terminal_key_show_keyboard),
                    tint = normalColor,
                    modifier = Modifier.size(18.dp)
            )
        }
    }

    Column(modifier = modifier.padding(horizontal = 2.dp, vertical = 2.dp)) {
        if (fnMode) {
            Row(Modifier.fillMaxWidth()) {
                FN_ROW1_KEYS.forEach { KeyBtn(it, Modifier.weight(1f)) }
                ToggleBtn("FN", active = true, onClick = onToggleFn, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                FN_ROW2_KEYS.forEach { KeyBtn(it, Modifier.weight(1f)) }
                KbdBtn(Modifier.weight(1f))
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                ROW1_KEYS.take(5).forEach { KeyBtn(it, Modifier.weight(0.8f)) }
                ROW1_KEYS.drop(5).forEach { KeyBtn(it, Modifier.weight(1f)) }
                ToggleBtn("FN", active = false, onClick = onToggleFn, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                KeyBtn(ToolbarKey("TAB", byteArrayOf(9)), Modifier.weight(1f))
                ToggleBtn("CTRL", ctrlActive, onToggleCtrl, Modifier.weight(1f))
                ToggleBtn("ALT", altActive, onToggleAlt, Modifier.weight(1f))
                ROW2_ARROW_KEYS.forEach { KeyBtn(it, Modifier.weight(1f)) }
                KbdBtn(Modifier.weight(1f))
            }
        }
    }
}
