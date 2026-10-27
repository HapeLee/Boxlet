package dev.boxlet.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import dev.boxlet.core.engine.OutgoingFile
import okio.source
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** SAF 读写缓冲；MediaProvider 往返一次很贵，按 1 MiB 批处理。 */
private const val SAF_BUFFER_BYTES = 1024 * 1024

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format(Locale.US, "%.1f %s", value, units[index])
}

/** 实时速度，传 0 表示还没开始。 */
fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond <= 0 -> ""
    bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
    bytesPerSecond < 1024 * 1024 -> "${bytesPerSecond / 1024} KB/s"
    else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1024.0 / 1024.0)
}

/** 剩余时间，粗到秒就够了。 */
fun formatEta(seconds: Long): String = when {
    seconds <= 0 -> ""
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

fun formatTime(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

/** 把 SAF 目录 uri 变成人能读的路径，例如 `Download/Boxlet`。 */
fun formatDestination(raw: String): String {
    val decoded = Uri.decode(raw)
    val tail = decoded.substringAfterLast("tree/", decoded)
    return tail.substringAfter(':', tail).ifBlank { decoded }
}

/**
 * 打开已接收的文件：始终走系统的「打开方式」选择器，不让某个默认应用直接接管
 * （apk、mp4 这类常见类型默认应用往往不是用户想要的）。
 * @return 是否成功拉起选择器；失败时调用方负责提示。
 */
fun openFile(context: Context, path: String?, mimeType: String): Boolean {
    if (path.isNullOrBlank()) return false
    val uri = runCatching { fileUri(context, path) }.getOrNull() ?: return false
    // 精确 MIME 找不到接收方时（例如协议里填的是 application/octet-stream）退化为 */*，
    // 让用户自己从「文件管理器 / 安装包安装程序」里选。
    return startViewer(context, uri, resolveMimeType(path, mimeType)) || startViewer(context, uri, "*/*")
}

private fun fileUri(context: Context, path: String): Uri =
    if (path.startsWith("content://")) {
        Uri.parse(path)
    } else {
        FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
    }

private fun startViewer(context: Context, uri: Uri, mime: String): Boolean {
    val target = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(target, "选择打开方式").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching {
        context.startActivity(chooser)
        true
    }.getOrDefault(false)
}

/**
 * 协议里带的 MIME 常常是 `application/octet-stream` 或干脆为空，直接丢给系统会匹配不到应用，
 * 因此按扩展名补一次推断（apk、mp4 等本地常见类型都靠这一步）。
 */
fun resolveMimeType(path: String, declared: String): String {
    val normalized = declared.substringBefore(';').trim()
    val usable = normalized.takeIf { it.isNotBlank() && it != "*/*" && it != "application/octet-stream" }
    if (usable != null) return usable
    val extension = path.substringAfterLast('/').substringAfterLast('.', "")
        .takeIf { it.length in 1..8 && it.all(Char::isLetterOrDigit) } ?: return "*/*"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase(Locale.US)) ?: "*/*"
}

/** 复制文本到系统剪贴板；返回是否成功，供界面切换按钮文案。 */
fun copyToClipboard(context: Context, text: String, label: String = "Boxlet 文本"): Boolean = runCatching {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return false
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
    true
}.getOrDefault(false)

fun contentUriToOutgoingFile(context: Context, uri: Uri): OutgoingFile? {
    val resolver = context.contentResolver
    var name = "file"
    var size = -1L
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
    }
    if (size < 0) {
        size = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            } ?: return null
        }.getOrDefault(-1L)
    }
    if (size < 0) return null

    val mime = resolver.getType(uri) ?: "application/octet-stream"
    return OutgoingFile(
        id = UUID.randomUUID().toString(),
        fileName = name,
        size = size,
        mimeType = mime,
        sourceUri = uri.toString(),
        recoverySource = retainedReadReference(context, uri, uri),
        openSource = { openSafSource(resolver, uri) },
    )
}

/**
 * SAF 读会穿过 MediaProvider，未缓冲时一次只能拿到几 KB。
 * 套 1 MiB 缓冲后，上传和预哈希都能按大块读。
 */
private fun openSafSource(resolver: android.content.ContentResolver, uri: Uri) =
    resolver.openInputStream(uri)?.buffered(SAF_BUFFER_BYTES)?.source() ?: error("无法读取文件")

/** Recursively flattens a SAF tree into LocalSend relative file names. */
fun documentTreeToOutgoingFiles(context: Context, uri: Uri): List<OutgoingFile> {
    val root = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() ?: return emptyList()
    val resolver = context.contentResolver
    val grant = retainedReadReference(context, uri, uri)
    val result = mutableListOf<OutgoingFile>()

    fun walk(directory: DocumentFile, prefix: String) {
        if (result.size >= 5000) return
        val children = runCatching { directory.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            if (result.size >= 5000) return
            val name = child.name ?: continue
            if (child.isDirectory) {
                walk(child, "$prefix$name/")
            } else if (child.isFile) {
                val fileUri = child.uri
                var size = child.length()
                if (size <= 0) {
                    size = runCatching {
                        resolver.openInputStream(fileUri)?.use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                total += read
                            }
                            total
                        } ?: 0L
                    }.getOrDefault(0L)
                }
                val mime = child.type ?: resolver.getType(fileUri) ?: "application/octet-stream"
                result += OutgoingFile(
                    id = UUID.randomUUID().toString(),
                    fileName = "$prefix$name",
                    size = size,
                    mimeType = mime,
                    sourceUri = fileUri.toString(),
                    recoverySource = grant?.let { dev.boxlet.core.protocol.bp2.Bp2SourceRef("content-uri", fileUri.toString(), uri.toString()) },
                    openSource = { openSafSource(resolver, fileUri) },
                )
            }
        }
    }

    walk(root, "")
    return result
}

private fun retainedReadReference(context: Context, uri: Uri, grant: Uri): dev.boxlet.core.protocol.bp2.Bp2SourceRef? {
    val resolver = context.contentResolver
    runCatching { resolver.takePersistableUriPermission(grant, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    return if (resolver.persistedUriPermissions.any { it.isReadPermission && it.uri == grant })
        dev.boxlet.core.protocol.bp2.Bp2SourceRef("content-uri", uri.toString(), grant.toString()) else null
}
