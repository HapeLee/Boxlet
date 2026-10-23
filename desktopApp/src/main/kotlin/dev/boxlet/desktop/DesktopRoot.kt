package dev.boxlet.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.boxlet.core.engine.TransferStatus
import dev.boxlet.core.protocol.Device
import dev.boxlet.core.protocol.Protocol
import dev.boxlet.shared.BoxletApp
import dev.boxlet.shared.ThemeMode
import dev.boxlet.shared.UiState
import kotlinx.coroutines.delay
import io.github.composefluent.ExperimentalFluentApi
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.AccentButton
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.InfoBar
import io.github.composefluent.component.NavigationView
import io.github.composefluent.component.ProgressBar
import io.github.composefluent.component.Switcher
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import io.github.composefluent.component.menuItem
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.filled.ArrowDownload
import io.github.composefluent.icons.filled.ArrowSync
import io.github.composefluent.icons.filled.Delete
import io.github.composefluent.icons.filled.Folder
import io.github.composefluent.icons.filled.History
import io.github.composefluent.icons.filled.Info
import io.github.composefluent.icons.filled.Send
import io.github.composefluent.icons.filled.Settings
import io.github.composefluent.icons.regular.Globe
import io.github.composefluent.surface.Card
import java.awt.Desktop
import java.io.File

private enum class Page { Send, Receive, History, Settings }

@OptIn(ExperimentalFluentApi::class)
@Composable
fun DesktopRoot(
    app: BoxletApp,
    droppedFiles: List<File> = emptyList(),
    onDropHandled: () -> Unit = {},
    mica: Boolean = false,
) {
    val state by app.state.collectAsState()
    // 透明根容器让原生 Mica 透出（Win11）；其他平台用 Fluent Mica 不透明底色保证可见。
    val bg = if (mica) Color.Transparent else FluentTheme.colors.background.mica.base
    Box(Modifier.fillMaxSize().background(bg)) {
        var page by remember { mutableStateOf(Page.Send) }
            LaunchedEffect(droppedFiles) {
                if (droppedFiles.isNotEmpty()) page = Page.Send
            }
            NavigationView(
                modifier = Modifier.fillMaxSize(),
                menuItems = {
                    menuItem(
                        selected = page == Page.Send,
                        onClick = { page = Page.Send },
                        text = { Text("发送") },
                        icon = { Icon(Icons.Filled.Send, null) },
                    )
                    menuItem(
                        selected = page == Page.Receive,
                        onClick = { page = Page.Receive },
                        text = { Text("接收") },
                        icon = { Icon(Icons.Filled.ArrowDownload, null) },
                    )
                    menuItem(
                        selected = page == Page.History,
                        onClick = { page = Page.History },
                        text = { Text("历史") },
                        icon = { Icon(Icons.Filled.History, null) },
                    )
                },
                title = {
                    Text("Boxlet", style = FluentTheme.typography.bodyStrong,
                        color = FluentTheme.colors.text.text.primary)
                },
                footerItems = {
                    menuItem(
                        selected = page == Page.Settings,
                        onClick = { page = Page.Settings },
                        text = { Text("设置") },
                        icon = { Icon(Icons.Filled.Settings, null) },
                    )
                },
                pane = {
                    when (page) {
                        Page.Send -> SendPage(app, state, droppedFiles, onDropHandled)
                        Page.Receive -> ReceivePage(app, state)
                        Page.History -> HistoryPage(app, state)
                        Page.Settings -> SettingsPage(app, state)
                    }
                },
            )

            PendingReceiveDialog(app, state)
            PinDialog(app, state)
        }
}

@Composable
private fun PageLayout(
    title: String,
    subtitle: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = FluentTheme.typography.title)
                    Text(
                        subtitle,
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    actions()
                }
            }
        }
        content()
    }
}

@Composable
private fun SendPage(
    app: BoxletApp,
    state: UiState,
    droppedFiles: List<File> = emptyList(),
    onDropHandled: () -> Unit = {},
) {
    var actionDevice by remember { mutableStateOf<Device?>(null) }
    val selection by app.sendSelection.collectAsState()
    val sending = state.outgoing?.status in setOf(
        TransferStatus.PREPARING, TransferStatus.WAITING_FOR_ACCEPT,
        TransferStatus.PIN_REQUIRED, TransferStatus.TRANSFERRING,
    )
    var importError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(droppedFiles) {
        if (droppedFiles.isEmpty()) return@LaunchedEffect
        try {
            val outgoing = withContext(Dispatchers.IO) {
                droppedFiles.flatMap { file -> if (file.isDirectory) collectFolder(file) else listOf(file.toOutgoingFile()) }
            }
            app.selectFiles(outgoing)
            if (outgoing.isEmpty()) importError = "拖入的目录中没有可发送的文件"
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { importError = "无法读取拖入的内容，请重新选择" }
        finally { onDropHandled() }
    }

    // 「在线」依赖当前时间，没有状态变化不会重组，这里每 5 秒推一次。
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }

    PageLayout(
        title = "发送",
        subtitle = "本机 ${state.settings.alias} · ${if (state.serverRunning) "可在局域网内被发现" else "接收服务未运行"}",
        actions = {
            Button(onClick = {
                if (selection.isEmpty()) importError = "请先选择要分享的内容"
                else app.shareFiles(selection.toList())
            }) {
                Icon(Icons.Regular.Globe, null, Modifier.size(16.dp))
                Text("浏览器分享")
            }
            AccentButton(onClick = { app.scan() }) {
                Icon(Icons.Filled.ArrowSync, null, Modifier.size(16.dp))
                Text("扫描设备")
            }
        },
    ) {
        item { DesktopSendSelection(app) }
        importError?.let { message ->
            item { Text(message, color = FluentTheme.colors.text.text.secondary) }
        }
        state.share?.let { share ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().padding(18.dp)) {
                        Text("浏览器分享已开启", style = FluentTheme.typography.bodyStrong)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            share.url,
                            style = FluentTheme.typography.body,
                            color = FluentTheme.colors.text.text.secondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            buildString {
                                append("${share.fileCount} 个文件 · ${formatBytes(share.totalBytes)}")
                                if (share.pinRequired) append(" · 需要 PIN")
                            },
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.text.text.tertiary,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { copyToClipboard(share.url) }) { Text("复制链接") }
                            Button(onClick = { app.stopShare() }) { Text("停止分享") }
                        }
                    }
                }
            }
        }

        state.outgoing?.let { outgoing ->
            item {
                TransferCard(
                    title = "发送到 ${outgoing.peer.alias}",
                    subtitle = statusText(outgoing.status),
                    progress = outgoing.progress,
                    status = outgoing.status,
                    error = outgoing.error,
                    transferredBytes = outgoing.files.sumOf { it.bytesTransferred },
                    totalBytes = outgoing.files.sumOf { it.size },
                    files = outgoing.files.map { it.fileName to it.status },
                    onCancel = if (outgoing.status == TransferStatus.TRANSFERRING ||
                        outgoing.status == TransferStatus.PREPARING
                    ) {
                        { app.cancelSend() }
                    } else {
                        { app.clearOutgoing() }
                    },
                )
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("附近设备", style = FluentTheme.typography.bodyStrong)
                if (state.devices.isNotEmpty()) Text(
                    state.devices.size.toString(),
                    style = FluentTheme.typography.body,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        }

        if (state.devices.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Filled.Info, null, Modifier.padding(top = 2.dp).size(20.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("还没有发现设备", style = FluentTheme.typography.bodyStrong)
                            Text(
                                "请确认对方已打开 Boxlet 或 LocalSend，并且两台设备连接到同一 Wi-Fi。",
                                style = FluentTheme.typography.body,
                                color = FluentTheme.colors.text.text.secondary,
                            )
                            Text(
                                "如果设备没有自动出现，可点击右上角「扫描设备」。",
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.secondary,
                            )
                        }
                    }
                }
            }
        } else {
            items(state.devices, key = { it.fingerprint }) { device ->
                DeviceCard(
                    device = device,
                    favorite = state.isFavorite(device.fingerprint),
                    now = now,
                    onClick = { actionDevice = device },
                    onToggleFavorite = { app.toggleFavorite(device) },
                )
            }
        }
    }

    actionDevice?.let { device ->
        ContentDialog(
            title = "发送到 ${device.alias}",
            visible = true,
            content = {
                Text(if (selection.isEmpty()) "请先在上方选择要发送的内容。"
                    else "发送 ${selection.size} 项内容 · ${formatBytes(selection.sumOf { it.size })}")
            },
            primaryButtonText = if (selection.isEmpty()) "返回选择" else if (sending) "正在发送中" else "发送",
            closeButtonText = "取消",
            onButtonClick = { button ->
                if (button == ContentDialogButton.Primary && selection.isNotEmpty() && !sending) {
                    app.sendFiles(device.fingerprint, selection.toList())
                }
                actionDevice = null
            },
        )
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    favorite: Boolean,
    now: Long,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(FluentTheme.colors.fillAccent.secondary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Send, null, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.alias, style = FluentTheme.typography.bodyStrong)
                Text(
                    buildString {
                        append(device.deviceModel ?: device.deviceType.name.lowercase())
                        append(" · ")
                        append(
                            if (now - device.lastSeen < Protocol.DEVICE_ONLINE_WINDOW_MS) "在线" else "最近可见",
                        )
                    },
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
            Button(onClick = onToggleFavorite) {
                Text(if (favorite) "★ 已收藏" else "☆ 收藏")
            }
        }
    }
}

@Composable
private fun ReceivePage(app: BoxletApp, state: UiState) {
    PageLayout(
        title = "接收",
        subtitle = if (state.serverRunning) "服务运行中 · 端口 ${state.settings.port}" else "服务未运行",
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("本机信息", style = FluentTheme.typography.bodyStrong)
                    Spacer(Modifier.height(8.dp))
                    InfoLine("设备名称", state.settings.alias)
                    InfoLine("监听端口", state.settings.port.toString())
                    InfoLine("传输加密", if (state.settings.https) "HTTPS · 指纹固定" else "HTTP · 不安全")
                    InfoLine("接收 PIN", state.settings.receivePin?.takeIf { it.isNotBlank() } ?: "未设置")
                    InfoLine("保存目录", state.settings.destination ?: DesktopEnvironment.defaultDownloadDir.absolutePath)
                }
            }
        }

        state.incoming?.let { incoming ->
            item {
                TransferCard(
                    title = "来自 ${incoming.peer.alias}",
                    subtitle = statusText(incoming.status),
                    progress = incoming.progress,
                    status = incoming.status,
                    transferredBytes = incoming.files.sumOf { it.bytesTransferred },
                    totalBytes = incoming.files.sumOf { it.size },
                    files = incoming.files.map { it.fileName to it.status },
                    onCancel = when (incoming.status) {
                        TransferStatus.TRANSFERRING -> ({ app.cancelReceive() })
                        TransferStatus.COMPLETED, TransferStatus.FAILED, TransferStatus.CANCELLED -> ({ app.clearIncoming() })
                        else -> null
                    },
                )
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("自动接收", style = FluentTheme.typography.bodyStrong)
                    SwitchRow(
                        title = "快速保存",
                        description = "自动接受所有传输请求",
                        checked = state.settings.quickSave,
                    ) { value -> app.updateSettings { it.copy(quickSave = value) } }
                    SwitchRow(
                        title = "仅收藏设备",
                        description = "只自动接受已收藏设备",
                        checked = state.settings.quickSaveFromFavorites,
                    ) { value -> app.updateSettings { it.copy(quickSaveFromFavorites = value) } }
                }
            }
        }
    }
}

@Composable
private fun HistoryPage(app: BoxletApp, state: UiState) {
    PageLayout(
        title = "历史",
        subtitle = "最近接收的文件与消息",
        actions = {
            if (state.history.isNotEmpty()) {
                Button(onClick = { app.clearHistory() }) { Text("清空") }
            }
        },
    ) {
        if (state.history.isEmpty()) {
            item {
                InfoBar(
                    title = { Text("暂无记录") },
                    message = { Text("成功接收的文件和文本消息会显示在这里。") },
                    icon = { Icon(Icons.Filled.History, null) },
                )
            }
        } else {
            items(state.history, key = { it.id }) { entry ->
                Card(
                    onClick = {
                        if (!entry.isMessage && entry.path != null) {
                            runCatching { Desktop.getDesktop().open(File(entry.path)) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (entry.isMessage) Icons.Filled.Info else Icons.Filled.Folder, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.fileName,
                                style = FluentTheme.typography.body,
                                maxLines = if (entry.isMessage) 3 else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${entry.senderAlias} · ${formatTime(entry.timestamp)}" +
                                    if (entry.isMessage) "" else " · ${formatBytes(entry.fileSize)}",
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.secondary,
                            )
                        }
                        Button(onClick = { app.removeHistory(entry.id) }, iconOnly = true) {
                            Icon(Icons.Filled.Delete, "删除", Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(app: BoxletApp, state: UiState) {
    val settings = state.settings
    PageLayout(title = "设置", subtitle = "修改会立即生效并写入本地配置") {
        item {
            SectionCard("设备") {
                CommitRow("设备名称", settings.alias) { value ->
                    if (value.isNotBlank()) app.updateSettings { it.copy(alias = value.trim()) }
                }
                CommitRow("端口", settings.port.toString()) { value ->
                    value.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { port ->
                        app.updateSettings { it.copy(port = port) }
                    }
                }
                CommitRow("设备型号", settings.deviceModel.orEmpty()) { value ->
                    app.updateSettings { it.copy(deviceModel = value.trim().ifBlank { null }) }
                }
            }
        }

        item {
            SectionCard("传输") {
                SwitchRow("快速保存", "自动接受所有传输请求", settings.quickSave) { value ->
                    app.updateSettings { it.copy(quickSave = value) }
                }
                SwitchRow("仅收藏设备自动接收", "只对已收藏设备启用快速保存", settings.quickSaveFromFavorites) { value ->
                    app.updateSettings { it.copy(quickSaveFromFavorites = value) }
                }
                SwitchRow("发送时计算 SHA-256", "新版 Sendee 边传边校验；兼容设备会在发送前计算校验值", settings.createChecksums) { value ->
                    app.updateSettings { it.copy(createChecksums = value) }
                }
                SwitchRow("接收时校验 SHA-256", "失败自动重传，最多 3 次", settings.verifyChecksums) { value ->
                    app.updateSettings { it.copy(verifyChecksums = value) }
                }
                SwitchRow("记录接收历史", "保存文件与消息记录", settings.saveToHistory) { value ->
                    app.updateSettings { it.copy(saveToHistory = value) }
                }
                CommitRow("接收 PIN（留空关闭）", settings.receivePin.orEmpty()) { value ->
                    app.updateSettings { it.copy(receivePin = value.trim().ifBlank { null }) }
                }
            }
        }

        item {
            SectionCard("保存目录") {
                Text(
                    settings.destination ?: DesktopEnvironment.defaultDownloadDir.absolutePath,
                    style = FluentTheme.typography.body,
                    color = FluentTheme.colors.text.text.secondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        pickDirectory("选择接收目录")?.let { directory ->
                            app.updateSettings { it.copy(destination = directory.absolutePath) }
                        }
                    }) { Text("选择目录") }
                    Button(onClick = { app.updateSettings { it.copy(destination = null) } }) { Text("恢复默认") }
                }
            }
        }

        item {
            SectionCard("网络") {
                SwitchRow("HTTPS 加密", "自签名证书 + 指纹固定；关闭后仅限可信网络", settings.https) { value ->
                    app.updateSettings { it.copy(https = value) }
                }
                CommitRow("多播组", settings.multicastGroup) { value ->
                    if (value.isNotBlank()) app.updateSettings { it.copy(multicastGroup = value.trim()) }
                }
                CommitRow("接口白名单（逗号分隔，支持 *）", settings.networkWhitelist.joinToString(",")) { value ->
                    app.updateSettings { it.copy(networkWhitelist = parsePatterns(value)) }
                }
                CommitRow("接口黑名单（优先于白名单）", settings.networkBlacklist.joinToString(",")) { value ->
                    app.updateSettings { it.copy(networkBlacklist = parsePatterns(value)) }
                }
                Button(onClick = { app.ensureRestarted() }) { Text("重启接收服务") }
            }
        }

        item {
            SectionCard("浏览器接入") {
                val bridge = state.bridge
                if (bridge == null) {
                    Text(
                        "开启后，在浏览器打开 Boxlet Web 页面，填入下面的地址即可把文件发到本机。",
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.tertiary,
                    )
                    Spacer(Modifier.height(10.dp))
                    AccentButton(onClick = { app.startBridge() }) { Text("开启接入") }
                } else {
                    InfoLine("地址", bridge.url)
                    InfoLine(
                        "配对码",
                        if (bridge.pairCodeRequired) "需要（与接收 PIN 相同）" else "不需要",
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { copyToClipboard(bridge.url) }) { Text("复制地址") }
                        Button(onClick = { app.stopBridge() }) { Text("停止接入") }
                    }
                }
            }
        }

        item {
            SectionCard("外观") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeButton("跟随系统", settings.theme == ThemeMode.SYSTEM) {
                        app.updateSettings { it.copy(theme = ThemeMode.SYSTEM) }
                    }
                    ThemeButton("浅色", settings.theme == ThemeMode.LIGHT) {
                        app.updateSettings { it.copy(theme = ThemeMode.LIGHT) }
                    }
                    ThemeButton("深色", settings.theme == ThemeMode.DARK) {
                        app.updateSettings { it.copy(theme = ThemeMode.DARK) }
                    }
                }
            }
        }

        item {
            SectionCard("关于") {
                InfoLine("版本", "1.0.0")
                InfoLine("协议", "LocalSend v${Protocol.VERSION}")
                InfoLine("设备指纹", "")
                Text(
                    state.fingerprint,
                    style = FluentTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                    color = FluentTheme.colors.text.text.secondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Boxlet 不会上传任何文件，所有传输都在本地网络内直连完成。",
                    style = FluentTheme.typography.caption,
                    color = FluentTheme.colors.text.text.tertiary,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(title, style = FluentTheme.typography.bodyStrong)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ThemeButton(text: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        AccentButton(onClick = onClick) { Text(text) }
    } else {
        Button(onClick = onClick) { Text(text) }
    }
}

@Composable
private fun CommitRow(label: String, value: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(TextFieldValue(value)) }
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        TextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            header = { Text(label) },
            singleLine = true,
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = { onCommit(text.text) }, disabled = text.text == value) { Text("应用") }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = FluentTheme.typography.body)
            Text(
                description,
                style = FluentTheme.typography.caption,
                color = FluentTheme.colors.text.text.secondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switcher(checked = checked, onCheckStateChange = onChange)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            label,
            modifier = Modifier.width(120.dp),
            style = FluentTheme.typography.body,
            color = FluentTheme.colors.text.text.secondary,
        )
        Text(value, modifier = Modifier.weight(1f), style = FluentTheme.typography.body)
    }
}

@Composable
private fun TransferCard(
    title: String,
    subtitle: String,
    progress: Float,
    status: TransferStatus,
    files: List<Pair<String, dev.boxlet.core.engine.FileTransferStatus>>,
    onCancel: (() -> Unit)?,
    error: String? = null,
    transferredBytes: Long = 0L,
    totalBytes: Long = 0L,
) {
    val transferring = status == TransferStatus.TRANSFERRING
    val busy = transferring ||
        status == TransferStatus.PREPARING ||
        status == TransferStatus.WAITING_FOR_ACCEPT

    // 进度每 100ms 才推一次，这里按两次采样之间的增量算瞬时速度，再做指数平滑。
    var rate by remember { mutableStateOf(0L) }
    var lastBytes by remember { mutableStateOf(0L) }
    var lastAt by remember { mutableStateOf(System.nanoTime()) }
    LaunchedEffect(transferredBytes, status) {
        if (!transferring) {
            rate = 0L
            lastBytes = transferredBytes
            lastAt = System.nanoTime()
            return@LaunchedEffect
        }
        val now = System.nanoTime()
        val elapsed = (now - lastAt) / 1_000_000_000.0
        if (elapsed > 0.05) {
            val instant = ((transferredBytes - lastBytes).coerceAtLeast(0) / elapsed).toLong()
            rate = if (rate <= 0L) instant else (rate * 0.55 + instant * 0.45).toLong()
        }
        lastBytes = transferredBytes
        lastAt = now
    }
    val speedText = if (transferring && rate > 0) {
        val eta = formatEta((totalBytes - transferredBytes).coerceAtLeast(0) / rate.coerceAtLeast(1))
        if (eta.isNotEmpty()) "${formatSpeed(rate)} · 剩 $eta" else formatSpeed(rate)
    } else {
        ""
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = FluentTheme.typography.bodyStrong)
                    Text(
                        subtitle,
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                    error?.takeIf { it.isNotBlank() }?.let { reason ->
                        Text(
                            reason,
                            style = FluentTheme.typography.caption,
                            color = FluentTheme.colors.system.critical,
                        )
                    }
                }
                if (onCancel != null) {
                    Button(onClick = onCancel) { Text(if (busy) "取消传输" else "关闭") }
                }
            }
            if (transferring || status == TransferStatus.COMPLETED) {
                Spacer(Modifier.height(12.dp))
                ProgressBar(progress = progress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${formatBytes(transferredBytes)} / ${formatBytes(totalBytes)}",
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                    Text(
                        speedText,
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            files.take(8).forEach { (name, fileStatus) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        name.substringAfterLast('/'),
                        modifier = Modifier.weight(1f),
                        style = FluentTheme.typography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (fileStatus) {
                            dev.boxlet.core.engine.FileTransferStatus.QUEUED -> "等待"
                            dev.boxlet.core.engine.FileTransferStatus.TRANSFERRING -> "传输中"
                            dev.boxlet.core.engine.FileTransferStatus.COMPLETED -> "完成"
                            dev.boxlet.core.engine.FileTransferStatus.FAILED -> "失败"
                            dev.boxlet.core.engine.FileTransferStatus.SKIPPED -> "跳过"
                        },
                        style = FluentTheme.typography.caption,
                        color = FluentTheme.colors.text.text.secondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingReceiveDialog(app: BoxletApp, state: UiState) {
    val request = state.pendingReceive ?: return
    ContentDialog(
        title = "来自 ${request.peer.alias} 的传输请求",
        visible = true,
        content = {
            Column {
                if (request.isMessage && request.message != null) {
                    Text(request.message.orEmpty())
                } else {
                    Text("${request.files.size} 个文件 · ${formatBytes(request.files.sumOf { it.size })}")
                    Spacer(Modifier.height(8.dp))
                    request.files.take(8).forEach { file ->
                        Text(file.fileName, style = FluentTheme.typography.caption)
                    }
                    if (request.files.size > 8) {
                        Text("…另有 ${request.files.size - 8} 个文件", style = FluentTheme.typography.caption)
                    }
                }
            }
        },
        primaryButtonText = "全部接收",
        secondaryButtonText = "拒绝",
        onButtonClick = { button ->
            if (button == ContentDialogButton.Primary) app.acceptAllReceive() else app.rejectReceive()
        },
    )
}

@Composable
private fun PinDialog(app: BoxletApp, state: UiState) {
    val request = state.pinRequest ?: return
    var value by remember(request.attempt) { mutableStateOf("") }
    ContentDialog(
        title = "需要 PIN",
        visible = true,
        content = {
            Column {
                Text("${request.deviceAlias.ifBlank { "对方设备" }} 要求输入 PIN。")
                Spacer(Modifier.height(10.dp))
                TextField(
                    value = TextFieldValue(value),
                    onValueChange = { value = it.text },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("PIN") },
                    singleLine = true,
                )
            }
        },
        primaryButtonText = "发送",
        secondaryButtonText = "取消",
        onButtonClick = { button ->
            if (button == ContentDialogButton.Primary && value.isNotBlank()) {
                app.submitPin(value.trim())
            } else {
                app.cancelPin()
            }
        },
    )
}

private fun statusText(status: TransferStatus): String = when (status) {
    TransferStatus.PREPARING -> "正在准备发送…"
    TransferStatus.WAITING_FOR_ACCEPT -> "等待对方接收…"
    TransferStatus.PIN_REQUIRED -> "需要 PIN"
    TransferStatus.TRANSFERRING -> "正在传输"
    TransferStatus.COMPLETED -> "已完成"
    TransferStatus.FAILED -> "失败"
    TransferStatus.DECLINED -> "对方拒绝"
    TransferStatus.BUSY -> "对方正忙"
    TransferStatus.TOO_MANY_ATTEMPTS -> "PIN 尝试次数过多"
    TransferStatus.CANCELLED -> "已取消"
    TransferStatus.IDLE -> ""
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
}

private fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0 -> ""
    bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
    bytesPerSecond < 1024 * 1024 -> "${bytesPerSecond / 1024} KB/s"
    else -> String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / 1024.0 / 1024.0)
}

private fun formatEta(seconds: Long): String = when {
    seconds <= 0 -> ""
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

private fun formatTime(timestamp: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(timestamp))

private fun copyToClipboard(text: String) {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard
        .setContents(java.awt.datatransfer.StringSelection(text), null)
}

private fun parsePatterns(value: String): List<String> =
    value.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
