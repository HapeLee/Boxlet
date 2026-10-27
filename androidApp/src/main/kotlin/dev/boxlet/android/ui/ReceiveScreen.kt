package dev.boxlet.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import dev.boxlet.core.engine.FileTransferStatus
import dev.boxlet.core.engine.TransferFileState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.boxlet.android.ui.components.GlassPage
import dev.boxlet.android.ui.components.IconBubble
import dev.boxlet.android.ui.components.MatterCardPadded
import dev.boxlet.android.ui.components.MatterTag
import dev.boxlet.android.ui.components.SectionHeader
import dev.boxlet.android.ui.components.SectionTitle
import dev.boxlet.android.ui.components.TopBarActionButton
import dev.boxlet.core.engine.TransferStatus
import dev.boxlet.shared.BoxletApp
import dev.boxlet.shared.UiState
import dev.boxlet.shared.repository.HistoryEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreen(
    app: BoxletApp,
    state: UiState,
    listState: LazyListState,
    backdrop: LayerBackdrop?,
    bottomBarPadding: Dp,
) {
    val context = LocalContext.current
    val settings = state.settings
    var pinDialog by remember { mutableStateOf(false) }
    var viewedFiles by remember { mutableStateOf<List<TransferFileState>?>(null) }
    var viewedText by remember { mutableStateOf<ReceivedText?>(null) }
    var confirmClearHistory by remember { mutableStateOf(false) }

    GlassPage(
        title = "接收",
        listState = listState,
        backdrop = backdrop,
        bottomBarPadding = bottomBarPadding,
        topBarActions = { scroll ->
            TopBarActionButton(
                imageVector = Icons.Rounded.Lock,
                contentDescription = "接收 PIN",
                scroll = scroll,
                backdrop = backdrop,
                onClick = { pinDialog = true },
            )
        },
    ) {
        item(key = "receive-status") {
            ReceiveStatusCard(
                app,
                state,
                onViewFiles = {
                    viewedFiles = state.incoming?.files.orEmpty()
                        .filter { it.status == FileTransferStatus.COMPLETED && !it.savedPath.isNullOrBlank() }
                },
                onViewMessage = {
                    viewedText = state.incoming?.files.orEmpty()
                        .firstOrNull { it.isMessage && !it.message.isNullOrBlank() }
                        ?.let { ReceivedText(textTitle(state.incoming?.peer?.alias), it.message.orEmpty()) }
                },
            )
        }
        state.incoming?.let { incoming ->
            item(key = "incoming-details") {
                TransferCard(
                    modifier = Modifier.animateItem(),
                    title = "来自 ${incoming.peer.alias}",
                    subtitle = when (incoming.status) {
                        TransferStatus.WAITING_FOR_ACCEPT -> "等待你确认…"
                        TransferStatus.TRANSFERRING -> "正在接收"
                        TransferStatus.PAUSED -> "已暂停"
                        TransferStatus.COMPLETED -> "接收完成"
                        TransferStatus.FAILED -> "部分文件接收失败"
                        TransferStatus.CANCELLED -> "传输已取消"
                        else -> ""
                    },
                    progress = incoming.progress,
                    status = incoming.status,
                    error = incoming.error,
                    files = incoming.files,
                    onTogglePause = if (incoming.adapterId == "boxlet-bp2" && incoming.status in listOf(TransferStatus.TRANSFERRING, TransferStatus.PAUSED))
                        ({ app.engine.setTransferPaused(false, incoming.status != TransferStatus.PAUSED) }) else null,
                    footer = if (incoming.status != TransferStatus.COMPLETED) {
                        null
                    } else if (incoming.files.any { it.isMessage }) {
                        "文本不写入文件目录，可在下方接收记录中查看或复制"
                    } else {
                        "已保存到 ${settings.destination?.let(::formatDestination) ?: "下载/Boxlet"}"
                    },
                    onCancel = if (incoming.status == TransferStatus.TRANSFERRING || incoming.status == TransferStatus.PAUSED) {
                        { app.cancelReceive() }
                    } else if (incoming.status == TransferStatus.COMPLETED ||
                        incoming.status == TransferStatus.FAILED ||
                        incoming.status == TransferStatus.CANCELLED
                    ) {
                        { app.clearIncoming() }
                    } else {
                        null
                    },
                )
            }
        }

        item {
            MatterCardPadded(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(
                    title = "自动接收",
                    description = "选择哪些设备的传输请求可以免确认直接接收。",
                )
                Spacer(Modifier.height(16.dp))
                AutoReceiveSelector(
                    mode = when {
                        settings.quickSave -> AutoReceiveMode.ALL
                        settings.quickSaveFromFavorites -> AutoReceiveMode.FAVORITES
                        else -> AutoReceiveMode.OFF
                    },
                    onChange = { mode ->
                        app.updateSettings {
                            it.copy(
                                quickSave = mode == AutoReceiveMode.ALL,
                                quickSaveFromFavorites = mode == AutoReceiveMode.FAVORITES,
                            )
                        }
                    },
                )
            }
        }

        item(key = "history-title") {
            SectionTitle(
                title = "接收记录",
                count = state.history.size.takeIf { it > 0 },
                trailing = {
                    if (state.history.isNotEmpty()) {
                        IconButton(onClick = { confirmClearHistory = true }) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = "清空接收记录",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
            )
        }
        if (state.history.isEmpty()) {
            item(key = "history-empty") {
                EmptyState(
                    icon = Icons.Rounded.History,
                    title = "暂无记录",
                    description = "成功接收的文件和文本消息会显示在这里。",
                )
            }
        } else {
            items(state.history, key = { it.id }) { entry ->
                HistoryRow(
                    modifier = Modifier.animateItem(),
                    entry = entry,
                    onOpen = {
                        if (!openFile(context, entry.path, entry.fileType)) notifyOpenFailed(context)
                    },
                    onOpenText = {
                        viewedText = ReceivedText(textTitle(entry.senderAlias), entry.fileName)
                    },
                    onRemove = { app.removeHistory(entry.id) },
                )
            }
        }

    }

    viewedFiles?.let { received ->
        ModalBottomSheet(
            onDismissRequest = { viewedFiles = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                Text("已接收的文件", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("${received.size} 个文件 · 点击选择打开方式", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 420.dp)) {
                    items(received, key = { it.id }) { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    if (!openFile(context, file.savedPath, file.mimeType)) notifyOpenFailed(context)
                                }
                                .padding(vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(mimeIcon(file.mimeType), null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.fileName.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(formatBytes(file.size), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                TextButton(onClick = { viewedFiles = null }, modifier = Modifier.fillMaxWidth()) { Text("关闭") }
            }
        }
    }

    viewedText?.let { received ->
        ReceivedTextSheet(
            title = received.title,
            text = received.text,
            onDismiss = { viewedText = null },
        )
    }

    if (pinDialog) {
        ReceivePinDialog(
            current = settings.receivePin,
            onDismiss = { pinDialog = false },
            onConfirm = { value ->
                app.updateSettings { it.copy(receivePin = value) }
                pinDialog = false
            },
        )
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("清空接收记录？") },
            text = { Text("只会删除记录，不会删除已接收的文件。") },
            confirmButton = {
                Button(
                    onClick = {
                        app.clearHistory()
                        confirmClearHistory = false
                    },
                ) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("取消") } },
        )
    }
}

/** 一条接收记录：文件点整行打开，文本点整行查看（可再次选中/复制），右侧删除只删记录。 */
@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    onOpen: () -> Unit,
    onOpenText: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    MatterCardPadded(
        modifier = modifier.fillMaxWidth(),
        onClick = when {
            entry.isMessage -> onOpenText
            entry.path != null -> onOpen
            else -> null
        },
        contentPadding = PaddingValues(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(
                imageVector = if (entry.isMessage) Icons.AutoMirrored.Rounded.Message else mimeIcon(entry.fileType),
                size = 40.dp,
                iconSize = 20.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (entry.isMessage) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${entry.senderAlias} · ${formatTime(entry.timestamp)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!entry.isMessage) {
                        Spacer(Modifier.width(8.dp))
                        MatterTag(
                            text = formatBytes(entry.fileSize),
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (entry.isMessage) {
                IconButton(onClick = {
                    if (copyToClipboard(context, entry.fileName)) {
                        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = "复制文本",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = "删除记录",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** 接收到的文本消息：不落盘，只在记录里留一份，随时可以再次打开选中/复制。 */
private data class ReceivedText(val title: String, val text: String)

private fun textTitle(senderAlias: String?): String =
    senderAlias?.takeIf { it.isNotBlank() }?.let { "来自 $it 的文本" } ?: "接收到的文本"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceivedTextSheet(title: String, text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember(text) { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "长按可选择文本 · 仅保存在接收记录中，不会写入文件目录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 420.dp)) {
                item {
                    SelectionContainer {
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("关闭") }
                Button(onClick = { copied = copyToClipboard(context, text) }) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) "已复制" else "复制")
                }
            }
        }
    }
}

private fun notifyOpenFailed(context: android.content.Context) =
    Toast.makeText(context, "没有可以打开此文件的应用", Toast.LENGTH_SHORT).show()

/** 自动接收的三档；与引擎的 `quickSave || (quickSaveFromFavorites && 是收藏)` 一一对应。 */
private enum class AutoReceiveMode { OFF, FAVORITES, ALL }

/**
 * M3 Expressive 的「connected button group」：三个 [ToggleButton] 首尾各带半圆角，
 * 选中态会做形状 morph；这里是单选语义，重复点击已选项不会取消。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AutoReceiveSelector(mode: AutoReceiveMode, onChange: (AutoReceiveMode) -> Unit) {
    val options = listOf(
        AutoReceiveMode.OFF to "关闭",
        AutoReceiveMode.FAVORITES to "仅收藏夹",
        AutoReceiveMode.ALL to "全部",
    )
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val requiredWidth = with(LocalDensity.current) {
        options.sumOf { (_, label) ->
            textMeasurer.measure(label, style = labelStyle).size.width.toDouble()
        }.toFloat().toDp() + 48.dp * options.size +
            ButtonGroupDefaults.ConnectedSpaceBetween * (options.size - 1)
    }
    BoxWithConstraints(Modifier.fillMaxWidth().selectableGroup()) {
        // 依据实际文字宽度选择布局，兼容大字体和窄窗口，不缩小文字或截断选项。
        if (maxWidth < requiredWidth) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEachIndexed { index, (value, label) ->
                    AutoReceiveOption(mode, value, label, index, options.lastIndex,
                        onChange, Modifier.fillMaxWidth(), connected = false)
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                options.forEachIndexed { index, (value, label) ->
                    AutoReceiveOption(mode, value, label, index, options.lastIndex,
                        onChange, Modifier.weight(1f))
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AutoReceiveOption(
    mode: AutoReceiveMode,
    value: AutoReceiveMode,
    label: String,
    index: Int,
    lastIndex: Int,
    onChange: (AutoReceiveMode) -> Unit,
    modifier: Modifier = Modifier,
    connected: Boolean = true,
) {
    ToggleButton(
        checked = mode == value,
        onCheckedChange = { checked -> if (checked) onChange(value) },
        shapes = when {
            !connected -> ToggleButtonDefaults.shapesFor(ToggleButtonDefaults.size)
            index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
            index == lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
        },
        modifier = modifier.semantics { role = Role.RadioButton },
    ) {
        Text(label, textAlign = TextAlign.Center)
    }
}



@Composable
private fun ReceivePinDialog(
    current: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var value by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current.isNullOrBlank()) "设置接收 PIN" else "修改接收 PIN") },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { input -> value = input.filter { it.isDigit() }.take(8) },
                    label = { Text("PIN（4-8 位数字）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "开启后，发送方需要输入同样的 PIN 才能发送文件。留空表示不需要 PIN。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(value.trim().ifBlank { null }) },
                enabled = value.isBlank() || value.length >= 4,
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!current.isNullOrBlank()) {
                    TextButton(onClick = { onConfirm(null) }) { Text("关闭 PIN") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
