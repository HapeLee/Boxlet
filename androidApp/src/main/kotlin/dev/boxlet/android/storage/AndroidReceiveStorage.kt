package dev.boxlet.android.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import dev.boxlet.core.engine.ReceiveDestination
import dev.boxlet.core.engine.ReceiveStorage
import dev.boxlet.core.protocol.FileDto
import dev.boxlet.core.protocol.FileNames
import dev.boxlet.core.protocol.rfc3339ToMillis
import dev.boxlet.shared.storage.FileSystemReceiveStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Sink
import okio.buffer
import okio.sink
import java.io.File

/**
 * 接收文件落盘的地方，优先级：
 *
 * 1. 用户选过的 SAF 目录；
 * 2. Android 10+ 的 MediaStore：图片 →「图片/Boxlet」、视频 →「视频/Boxlet」、其它 →「下载/Boxlet」
 *    （无需存储权限，且能直接出现在系统相册/播放器里）；
 * 3. 应用私有目录（旧系统或 MediaStore 不可用时兜底）。
 *
 * SAF 走 `*.part` 临时文件、提交时才改名；MediaStore 用 `IS_PENDING`，两种方式在取消、
 * 失败或进程被杀时都不会在用户目录里留下半截文件。
 */
class AndroidReceiveStorage(
    private val context: Context,
    private val destinationUri: () -> String?,
) : ReceiveStorage {

    private val appStorage = FileSystemReceiveStorage {
        context.getExternalFilesDir("received") ?: File(context.filesDir, "received")
    }

    override suspend fun createDestination(
        sessionId: String,
        file: FileDto,
        senderAlias: String,
    ): ReceiveDestination = withContext(Dispatchers.IO) {
        // 老发送方会把安装包放在 `<包名>/` 目录里，先摊平成 `<包名>.apk`，再决定落到哪里
        val target = flattenApk(file)
        val tree = destinationUri()?.takeIf { it.startsWith("content://") }
        if (tree != null) {
            safDestination(tree, target)?.let { return@withContext it }
        } else {
            mediaStoreDestination(target)?.let { return@withContext it }
        }
        appStorage.createDestination(sessionId, target, senderAlias)
    }

    /**
     * 安装包不套目录：`<包名>/base.apk` → `<包名>.apk`，分包 → `<包名>-split_xxx.apk`。
     * 只装着一个 base.apk 的文件夹既不能直接安装，也认不出是哪个应用。
     *
     * 口径收得很窄：只有一级目录、目录名是包名、末级确实是 apk 时才改名，
     * 普通文件夹里的 apk（例如用户自己发的 `安装包/微信.apk`）保持原样。
     */
    private fun flattenApk(file: FileDto): FileDto {
        val segments = file.fileName.split('/', '\\').filter { it.isNotBlank() && it != "." && it != ".." }
        if (segments.size != 2) return file
        val directory = segments[0]
        val leaf = segments[1]
        if (!leaf.endsWith(".apk", ignoreCase = true)) return file
        if (!PACKAGE_NAME.matches(directory)) return file
        val name = if (leaf.equals("base.apk", ignoreCase = true)) "$directory.apk" else "$directory-$leaf"
        return file.copy(fileName = name)
    }

    private fun mediaStoreDestination(file: FileDto): ReceiveDestination? {
        val resolver = context.contentResolver
        val segments = FileNames.sanitizeRelativePath(file.fileName)
        val name = segments.last()
        val mime = file.fileType.substringBefore(';').ifBlank { "application/octet-stream" }
        // 图片进「图片/Boxlet」、视频进「视频/Boxlet」，系统相册和播放器直接能看到；
        // 其它仍进「下载/Boxlet」。接收文件夹时保留相对目录：RELATIVE_PATH 支持多级。
        val (collection, root) = when {
            mime.startsWith("image/") -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_MOVIES
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI to Environment.DIRECTORY_DOWNLOADS
        }
        val relativePath = buildList {
            add(root)
            add(FOLDER)
            addAll(segments.dropLast(1))
        }.joinToString("/")
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            rfc3339ToMillis(file.metadata?.modified)?.let {
                put(MediaStore.MediaColumns.DATE_MODIFIED, it / 1000)
            }
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return null
        val output = runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()
        if (output == null) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }
        val sink = output.buffered(WRITE_BUFFER_BYTES).sink().buffer()

        return object : ReceiveDestination {
            override val sink: Sink = sink
            override val displayPath: String = uri.toString()

            override suspend fun commit() {
                sink.close()
                // 清掉 IS_PENDING 之后，媒体库才会真正收录这个文件。
                val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, publish, null, null)
            }

            override suspend fun abort() {
                runCatching { sink.close() }
                runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    private fun safDestination(tree: String, file: FileDto): ReceiveDestination? {
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree)) }.getOrNull()
        if (root == null || !root.canWrite()) return null

        val segments = FileNames.sanitizeRelativePath(file.fileName)
        var directory: DocumentFile = root
        for (segment in segments.dropLast(1)) {
            directory = directory.findFile(segment) ?: directory.createDirectory(segment) ?: directory
        }

        val desired = segments.last()
        val name = FileNames.uniqueFileName(desired) { candidate -> directory.findFile(candidate) != null }
        val mime = file.fileType.substringBefore(';')
        val document = directory.createFile(mime, "$name$PART_SUFFIX")
            ?: directory.createFile("*/*", "$name$PART_SUFFIX")
            ?: return null

        val output = context.contentResolver.openOutputStream(document.uri, "wt") ?: run {
            runCatching { document.delete() }
            return null
        }
        // SAF 的写会穿过 MediaProvider，8 KiB 一次写非常贵；1 MiB 缓冲后调用次数降两个数量级。
        val sink = output.buffered(WRITE_BUFFER_BYTES).sink().buffer()

        return object : ReceiveDestination {
            override val sink: Sink = sink
            override val displayPath: String = document.uri.toString()

            override suspend fun commit() {
                sink.close()
                // 多数 provider 支持 rename；失败就保留 .part 名，至少内容完整
                runCatching { document.renameTo(name) }
            }

            override suspend fun abort() {
                runCatching { sink.close() }
                runCatching { document.delete() }
            }
        }
    }

    private companion object {
        const val FOLDER = "Boxlet"
        const val WRITE_BUFFER_BYTES = 1024 * 1024
        const val PART_SUFFIX = ".part"
        /** 包名：至少两段，每段以字母开头。 */
        val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
    }
}
