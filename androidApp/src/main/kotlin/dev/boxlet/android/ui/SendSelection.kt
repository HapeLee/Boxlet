package dev.boxlet.android.ui

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import dev.boxlet.android.ui.components.drawBoxletParcel
import dev.boxlet.android.ui.components.rememberParcelPalette
import dev.boxlet.core.engine.OutgoingFile
import dev.boxlet.shared.BoxletApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.source
import java.io.File
import java.util.UUID

private data class ShareApp(val label: String, val packageName: String, val paths: List<String>)

/** 卡片里最多摊开多少项；更多的走「查看全部」，否则一个文件夹就能把页面顶满。 */
private const val INLINE_ITEM_LIMIT = 8

/** 安装包的 MIME，接收端靠它识别「这是一个可以直接安装的 APK」。 */
private const val APK_MIME = "application/vnd.android.package-archive"

/**
 * 分享出去的安装包文件名：主包 `<包名>.apk`，分包 `<包名>-<原名>.apk`。
 * 不再包一层 `<包名>/` 目录——接收端拿到就是能直接点的单个文件，
 * 不会变成只装着一个 base.apk、看不出是哪个应用的文件夹。
 */
private fun apkFileName(packageName: String, sourceName: String, isBase: Boolean): String =
    if (isBase) "$packageName.apk" else "$packageName-$sourceName"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendSelectionPanel(app: BoxletApp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files by app.sendSelection.collectAsState()
    // 缩略图只加载本地 content:// ，不需要网络栈；整屏共用一个 loader 才能共享内存缓存
    val imageLoader = remember(context) { ImageLoader.Builder(context.applicationContext).build() }
    var loading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var editText by remember { mutableStateOf(false) }
    var clipboardOpen by remember { mutableStateOf(false) }
    var clipboardText by remember { mutableStateOf("") }
    var clipboardUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var manage by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<ShareApp>?>(null) }
    var selectedApps by remember { mutableStateOf<Set<String>>(emptySet()) }
    // 应用选择的进度只留在它自己的 sheet 里，不占用发送页
    var appsLoading by remember { mutableStateOf(false) }
    var appsPreparing by remember { mutableStateOf(false) }

    fun prepare(expectedCount: Int? = null, read: suspend () -> List<OutgoingFile>) {
        if (loading) return
        loading = true
        scope.launch {
            try {
                val selected = withContext(Dispatchers.IO) { read() }
                if (selected.isEmpty()) notice = "没有可添加的内容"
                else {
                    app.selectFiles(selected)
                    if (expectedCount != null && selected.size != expectedCount) notice = "部分内容无法读取，已添加可读取的文件"
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                notice = "无法读取所选内容，请重新选择"
            } finally {
                loading = false
            }
        }
    }

    fun readUris(uris: List<Uri>) = prepare(expectedCount = uris.size) {
        uris.mapNotNull { contentUriToOutgoingFile(context, it) }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) readUris(uris)
    }
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) readUris(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) prepare { documentTreeToOutgoingFiles(context, uri) }
    }

    val actions: List<Triple<String, ImageVector, () -> Unit>> = listOf(
        Triple("剪切板", Icons.Rounded.ContentPaste) {
            val clip = runCatching { context.getSystemService(ClipboardManager::class.java)?.primaryClip }.getOrNull()
            clipboardUris = if (clip == null) emptyList() else
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.distinct()
            clipboardText = if (clip == null) "" else
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }
                    .filter { it.isNotBlank() }.joinToString("\n\n")
            clipboardOpen = true
        },
        Triple("文件", Icons.AutoMirrored.Rounded.InsertDriveFile) { filePicker.launch(arrayOf("*/*")) },
        Triple("媒体", Icons.Rounded.PhotoLibrary) { mediaPicker.launch(arrayOf("image/*", "video/*", "audio/*")) },
        Triple("文本", Icons.AutoMirrored.Rounded.Message) { editText = true },
        Triple("文件夹", Icons.Rounded.Folder) { folderPicker.launch(null) },
        Triple("应用", Icons.Rounded.Apps) {
            // 先把 sheet 打开，读取列表的进度显示在 sheet 里
            apps = emptyList()
            selectedApps = emptySet()
            appsLoading = true
            scope.launch {
                try {
                    apps = withContext(Dispatchers.IO) {
                        val pm = context.packageManager
                        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                            .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
                            .map { info -> ShareApp(info.loadLabel(pm).toString(), info.packageName, listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()) }
                            .sortedBy { it.label.lowercase() }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) {
                    notice = "无法读取应用列表"
                    apps = null
                } finally {
                    appsLoading = false
                }
            }
        },
    )

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth().animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
                ParcelIllustration(itemCount = files.size, modifier = Modifier.size(64.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (files.isEmpty()) "准备分享" else "已打包 ${files.size} 项", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (files.isEmpty()) "选择内容，装进箱子" else formatBytes(files.sumOf { it.size }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (files.isNotEmpty()) {
                    TextButton(onClick = { app.clearSendSelection() }) { Text("清空") }
                }
            }
            // 这个容器在空清单时也保留；它报告动画高度，让下方入口随之移动。
            // 整张卡片不再同时做尺寸动画，避免二次追赶和裁剪新内容。
            Column(Modifier.fillMaxWidth().animateContentSize()) {
                if (files.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    // 选中的内容直接摊开在卡片里，不需要再点一次；只截断数量，避免整屏被清单占满
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        files.take(INLINE_ITEM_LIMIT).forEach { file ->
                            key(file.id) {
                                SelectedFileRow(
                                    file = file,
                                    imageLoader = imageLoader,
                                    onRemove = { app.removeSelectedFile(file.id) },
                                )
                            }
                        }
                    }
                    if (files.size > INLINE_ITEM_LIMIT) {
                        TextButton(onClick = { manage = true }, modifier = Modifier.padding(top = 2.dp)) {
                            Text("查看全部 ${files.size} 项")
                        }
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            // 六个入口排成一行、只留图标：文字靠无障碍描述与长按提示补齐
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                actions.forEach { (label, icon, action) ->
                    IconButton(
                        onClick = action,
                        enabled = !loading,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Column(Modifier.fillMaxWidth().animateContentSize()) {
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
    }

    if (clipboardOpen) SendContentSheet(title = "剪切板", onDismiss = { clipboardOpen = false }) {
        if (clipboardText.isBlank() && clipboardUris.isEmpty()) {
                    Text("剪切板中没有可分享的文件或文本", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 20.dp))
        } else {
            if (clipboardText.isNotBlank()) {
                TextField(
                    value = clipboardText, onValueChange = { clipboardText = it },
                    label = { Text("剪切板文本") },
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    minLines = 3, maxLines = 7,
                    shape = MaterialTheme.shapes.medium,
                )
            }
            if (clipboardUris.isNotEmpty()) {
                Text("${clipboardUris.size} 个剪切板文件", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 12.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                    items(clipboardUris, key = { it.toString() }) { uri ->
                        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                        Text(uri.lastPathSegment ?: "剪切板文件", style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(
            enabled = clipboardText.isNotBlank() || clipboardUris.isNotEmpty(),
            onClick = {
                if (clipboardUris.isNotEmpty()) readUris(clipboardUris)
                if (clipboardText.isNotBlank()) app.selectText(clipboardText)
                clipboardOpen = false
            }, modifier = Modifier.fillMaxWidth(),
        ) { Text("添加到箱子") }
    }
    if (editText) SendContentSheet(title = "添加文本", onDismiss = { editText = false }) {
        TextField(
            value = text, onValueChange = { text = it }, placeholder = { Text("输入要分享的文本") },
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
            minLines = 4, maxLines = 8, shape = MaterialTheme.shapes.medium,
        )
        Spacer(Modifier.height(20.dp))
        Button(enabled = text.isNotBlank(), onClick = {
            app.selectText(text); text = ""; editText = false
        }, modifier = Modifier.fillMaxWidth()) { Text("添加到箱子") }
    }
    if (manage) AlertDialog(
        onDismissRequest = { manage = false }, title = { Text("已选择 ${files.size} 项") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(files, key = { it.id }) { file ->
                    SelectedFileRow(
                        file = file,
                        imageLoader = imageLoader,
                        onRemove = { app.removeSelectedFile(file.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { manage = false }) { Text("完成") } },
        dismissButton = { TextButton(enabled = files.isNotEmpty(), onClick = { app.clearSendSelection(); manage = false }) { Text("清空箱子") } },
    )
    apps?.let { available ->
        SendContentSheet(title = "选择应用", onDismiss = { if (!appsLoading && !appsPreparing) apps = null }) {
            Text("分享应用安装包；分包应用会同时添加所需 APK，不包含应用数据。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            when {
                // 读取列表 / 打包安装包时，进度都只在这一张 sheet 里显示
                appsLoading || appsPreparing -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp)) {
                    Text(
                        if (appsLoading) "正在读取应用列表…" else "正在打包 $selectedApps.size 个应用…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 420.dp)) {
                        items(available, key = { it.packageName }) { entry ->
                            val checked = entry.packageName in selectedApps
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { selected ->
                                        selectedApps = if (selected) selectedApps + entry.packageName else selectedApps - entry.packageName
                                    }).padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ShareAppIcon(entry.packageName)
                                Column(Modifier.weight(1f)) {
                                    Text(entry.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyLarge)
                                    Text(entry.packageName, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Checkbox(checked = checked, onCheckedChange = null)
                            }
                        }
                    }
                    if (available.isEmpty()) Text("没有找到可分享的应用", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 24.dp))
                    Spacer(Modifier.height(16.dp))
                    Button(enabled = selectedApps.isNotEmpty(), onClick = {
                        val chosen = available.filter { it.packageName in selectedApps }
                        appsPreparing = true
                        scope.launch {
                            try {
                                val selected = withContext(Dispatchers.IO) {
                                    chosen.flatMap { entry ->
                                        // 第 0 个是主包（sourceDir），其余是分包，都在同一个目录层级里摊平
                                        entry.paths.mapIndexed { index, path ->
                                            val file = File(path)
                                            check(file.isFile && file.canRead())
                                            OutgoingFile(
                                                id = UUID.randomUUID().toString(),
                                                fileName = apkFileName(entry.packageName, file.name, index == 0),
                                                size = file.length(),
                                                mimeType = APK_MIME,
                                                openSource = { file.source() },
                                            )
                                        }
                                    }
                                }
                                app.selectFiles(selected)
                                apps = null
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                notice = "无法读取所选应用，请重新选择"
                            } finally {
                                appsPreparing = false
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (selectedApps.isEmpty()) "选择要分享的应用" else "添加 ${selectedApps.size} 个应用到箱子")
                    }
                }
            }
        }
    }
    notice?.let { message -> AlertDialog(
        onDismissRequest = { notice = null }, text = { Text(message) },
        confirmButton = { TextButton(onClick = { notice = null }) { Text("知道了") } },
    ) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendContentSheet(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "关闭") }
            }
            Spacer(Modifier.height(16.dp))
            content()
        }
    }
}

/** 已选内容的一行：缩略图（图片）/ 类型图标 + 名称或文本预览 + 移除。 */
@Composable
private fun SelectedFileRow(file: OutgoingFile, imageLoader: ImageLoader, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SelectedFileThumb(file, imageLoader)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (file.isMessage) file.preview ?: file.fileName else file.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (file.isMessage) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (file.isMessage) "文本 · ${formatBytes(file.size)}" else formatBytes(file.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "移除 ${file.fileName}",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 图片走 Coil 的缩略图，其余类型退回一个类型图标。 */
@Composable
private fun SelectedFileThumb(file: OutgoingFile, imageLoader: ImageLoader) {
    val modifier = Modifier
        .size(44.dp)
        .clip(MaterialTheme.shapes.small)
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    val uri = remember(file.sourceUri) {
        file.sourceUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }
    if (uri != null && file.mimeType.startsWith("image/")) {
        SubcomposeAsyncImage(
            model = uri,
            contentDescription = null,
            imageLoader = imageLoader,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            loading = { ThumbFallback(file) },
            error = { ThumbFallback(file) },
        )
    } else {
        ThumbFallback(file, modifier)
    }
}

@Composable
private fun ThumbFallback(file: OutgoingFile, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = fileIcon(file),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun fileIcon(file: OutgoingFile): ImageVector = when {
    file.isMessage -> Icons.AutoMirrored.Rounded.Message
    file.mimeType.startsWith("image/") -> Icons.Rounded.Image
    file.mimeType.startsWith("video/") -> Icons.Rounded.Movie
    file.mimeType.startsWith("audio/") -> Icons.Rounded.Audiotrack
    file.mimeType == APK_MIME ||
        file.fileName.endsWith(".apk", ignoreCase = true) -> Icons.Rounded.Android
    file.mimeType.startsWith("text/") -> Icons.Rounded.Description
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

@Composable
private fun ShareAppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(initialValue = null, packageName, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(width = 96, height = 96).asImageBitmap()
            }.getOrNull()
        }
    }
    val modifier = Modifier.size(44.dp).clip(MaterialTheme.shapes.small)
    icon?.let { Image(bitmap = it, contentDescription = null, modifier = modifier) }
        ?: Icon(Icons.Rounded.Apps, contentDescription = null, modifier = modifier, tint = MaterialTheme.colorScheme.primary)
}

/** 箱盖连续合拢后贴上封条；增减内容时轻弹，动画只影响绘制，不挤动设备列表。 */
@Composable
private fun ParcelIllustration(itemCount: Int, modifier: Modifier = Modifier) {
    val palette = rememberParcelPalette()
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
    Canvas(modifier.graphicsLayer {
        translationY = -3.dp.toPx() * bounce.value
        rotationZ = -3f * bounce.value
        scaleX = 1f + 0.035f * bounce.value
        scaleY = scaleX
    }) {
        drawBoxletParcel(closed.value, shadow = true, palette = palette)
    }
}
