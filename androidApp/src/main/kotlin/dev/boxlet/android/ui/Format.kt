package dev.boxlet.android.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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

fun openFile(context: Context, path: String?, mimeType: String) {
    if (path.isNullOrBlank()) return
    runCatching {
        val uri = if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            FileProvider.getUriForFile(context, "${context.packageName}.files", File(path))
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

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
                    openSource = { openSafSource(resolver, fileUri) },
                )
            }
        }
    }

    walk(root, "")
    return result
}
