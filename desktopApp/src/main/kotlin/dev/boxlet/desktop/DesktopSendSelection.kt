package dev.boxlet.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.boxlet.shared.BoxletApp
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.Button
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Text
import io.github.composefluent.component.TextField
import io.github.composefluent.component.Icon
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.ClipboardPaste
import io.github.composefluent.icons.regular.Document
import io.github.composefluent.icons.regular.Image
import io.github.composefluent.icons.regular.TextDescription
import io.github.composefluent.icons.regular.Folder
import io.github.composefluent.icons.regular.Apps
import io.github.composefluent.surface.Card
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

private data class SelectionAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun DesktopSendSelection(app: BoxletApp) {
    val selection by app.sendSelection.collectAsState()
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var textDialog by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(TextFieldValue("")) }
    var manage by remember { mutableStateOf(false) }

    fun prepare(pick: () -> List<File>) {
        if (loading) return
        notice = null
        loading = true
        scope.launch {
            try {
                val chosen = withContext(Dispatchers.Swing) { pick() }
                if (chosen.isNotEmpty()) {
                    val files = withContext(Dispatchers.IO) {
                        chosen.flatMap { file ->
                            check(file.exists() && file.canRead())
                            if (file.isDirectory) collectFolder(file) else listOf(file.toOutgoingFile())
                        }
                    }
                    app.selectFiles(files)
                    if (files.isEmpty()) notice = "所选目录中没有可发送的文件"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "无法读取所选内容，请重新选择" }
            finally { loading = false }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DesktopParcel(itemCount = selection.size)
                Column(Modifier.weight(1f)) {
                    Text(if (selection.isEmpty()) "选择要发送的内容" else "已打包 ${selection.size} 项内容", style = FluentTheme.typography.bodyStrong)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (selection.isEmpty()) "先选择内容或拖入文件，再选择设备发送"
                        else "${formatBytes(selection.sumOf { it.size })} · 选择下方设备即可发送",
                        color = FluentTheme.colors.text.text.secondary,
                        style = FluentTheme.typography.caption,
                    )
                }
                if (selection.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { manage = true }) { Text("查看内容") }
                        Button(onClick = app::clearSendSelection) { Text("清空") }
                    }
                }
            }
            val actions = listOf(
                SelectionAction("剪贴板", Icons.Regular.ClipboardPaste) {
                    notice = null
                    try {
                        val clip = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
                        when {
                            clip == null -> notice = "剪贴板为空"
                            clip.isDataFlavorSupported(DataFlavor.javaFileListFlavor) -> {
                                val files = (clip.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)?.filterIsInstance<File>().orEmpty()
                                prepare { files }
                            }
                            clip.isDataFlavorSupported(DataFlavor.stringFlavor) -> {
                                val value = clip.getTransferData(DataFlavor.stringFlavor) as? String
                                if (value.isNullOrBlank()) notice = "剪贴板为空" else app.selectText(value)
                            }
                            else -> notice = "剪贴板中没有可分享的文件或文本"
                        }
                    } catch (_: Exception) { notice = "无法读取剪贴板，请稍后重试" }
                },
                SelectionAction("文件", Icons.Regular.Document) { prepare { pickFiles() } },
                SelectionAction("媒体", Icons.Regular.Image) { prepare { pickPackages("选择媒体", arrayOf("jpg", "jpeg", "png", "gif", "webp", "heic", "avif", "mp4", "mov", "mkv", "webm", "mp3", "wav", "flac", "m4a", "ogg")) } },
                SelectionAction("文本", Icons.Regular.TextDescription) { notice = null; textDialog = true },
                SelectionAction("文件夹", Icons.Regular.Folder) { prepare { listOfNotNull(pickDirectory("选择要发送的文件夹")) } },
                SelectionAction("应用", Icons.Regular.Apps) { prepare { pickPackages("选择应用安装包", arrayOf("exe", "msi", "msix", "appx", "apk", "dmg", "pkg", "deb", "rpm", "appimage")) } },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.forEach { action ->
                    Button(onClick = action.onClick, disabled = loading, modifier = Modifier.weight(1f).height(72.dp)) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(action.icon, null, Modifier.size(22.dp))
                            Text(action.label, maxLines = 1)
                        }
                    }
                }
            }
            if (loading) Text("正在整理所选内容…", color = FluentTheme.colors.text.text.secondary)
            notice?.let { Text(it, color = FluentTheme.colors.text.text.secondary) }
        }
    }

    if (textDialog) ContentDialog(
        title = "添加文本", visible = true,
        content = {
            TextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 144.dp), maxLines = 8,
                placeholder = { Text("输入要分享的文本") })
        },
        primaryButtonText = "添加到箱子", closeButtonText = "取消",
        onButtonClick = { button ->
            if (button == ContentDialogButton.Primary) {
                if (text.text.isNotBlank()) {
                    app.selectText(text.text)
                    text = TextFieldValue("")
                    textDialog = false
                }
            } else textDialog = false
        },
    )
    if (manage) ContentDialog(
        title = "已选择 ${selection.size} 项", visible = true,
        content = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                items(selection, key = { it.id }) { file ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(file.preview ?: file.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(formatBytes(file.size), style = FluentTheme.typography.caption)
                        }
                        Button(onClick = { app.removeSelectedFile(file.id) }) { Text("移除") }
                    }
                }
            }
        },
        primaryButtonText = "完成", onButtonClick = { manage = false },
    )
}

private fun pickPackages(title: String, extensions: Array<String>): List<File> {
    val picker = JFileChooser().apply {
        dialogTitle = title
        isMultiSelectionEnabled = true
        fileFilter = FileNameExtensionFilter(title, *extensions)
    }
    return if (picker.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) picker.selectedFiles.toList() else emptyList()
}

@Composable
private fun DesktopParcel(itemCount: Int) {
    val closed = animateFloatAsState(if (itemCount > 0) 1f else 0f, tween(480), label = "parcel-lid")
    val bounce = remember { Animatable(0f) }
    var previousCount by remember { mutableStateOf(itemCount) }
    LaunchedEffect(itemCount) {
        if (previousCount != itemCount) {
            previousCount = itemCount
            bounce.snapTo(0f)
            bounce.animateTo(1f, tween(130))
            bounce.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 380f))
        }
    }
    Canvas(Modifier.size(64.dp).graphicsLayer {
        translationY = -3.dp.toPx() * bounce.value
        rotationZ = -3f * bounce.value
        scaleX = 1f + 0.035f * bounce.value
        scaleY = scaleX
    }) {
        drawBoxletParcel(closed.value, shadow = true)
    }
}
