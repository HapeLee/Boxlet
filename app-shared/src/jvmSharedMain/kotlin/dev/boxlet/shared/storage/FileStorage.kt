package dev.boxlet.shared.storage

import dev.boxlet.core.engine.ReceiveDestination
import dev.boxlet.core.engine.ReceiveStorage
import dev.boxlet.core.protocol.FileDto
import dev.boxlet.core.protocol.FileNames
import dev.boxlet.core.protocol.rfc3339ToMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Sink
import okio.buffer
import okio.sink
import java.io.File
import java.util.UUID

/**
 * Desktop/headless storage. Writes into a user configurable directory using only
 * java.io + Okio APIs so the class is also safe to link on Android (even if unused).
 */
class FileSystemReceiveStorage(
    private val resumeDirectory: File? = null,
    private val directoryProvider: () -> File,
) : ReceiveStorage {

    override fun bp2ResumeStore(): dev.boxlet.core.session.bp2.Bp2ResumeStore? = resumeDirectory?.let { directory ->
        dev.boxlet.core.session.bp2.Bp2ResumeStore(directory, directoryProvider()).takeIf { it.available() }
    }

    init {
        // 上次进程被强杀时可能留下半截临时文件；启动时扫一遍，别让它们堆着。
        runCatching {
            directoryProvider().listFiles { candidate ->
                candidate.isFile && candidate.name.endsWith(PART_SUFFIX)
            }?.forEach { it.delete() }
        }
    }

    override suspend fun createDestination(
        sessionId: String,
        file: FileDto,
        senderAlias: String,
    ): ReceiveDestination = withContext(Dispatchers.IO) {
        val root = directoryProvider().apply { mkdirs() }
        val segments = FileNames.sanitizeRelativePath(file.fileName)
        var directory = root
        for (segment in segments.dropLast(1)) {
            directory = File(directory, segment)
        }
        directory.mkdirs()

        val desired = segments.last()
        val unique = FileNames.uniqueFileName(desired) { candidate -> File(directory, candidate).exists() }
        // 先写 `<名字>.<随机>.boxlet-part`，commit 时才改成正式文件名：
        // 取消、失败、进程被杀都只会留下临时文件，正式目录里永远不会出现半截文件。
        val part = File(directory, "$unique.${UUID.randomUUID().toString().take(8)}$PART_SUFFIX")
        // okio 以 8 KiB 段写出，直接落到 FileOutputStream 就是 8 KiB 一次 syscall。
        // 套一层 1 MiB 缓冲，让系统调用降到每 MiB 一次，顺序写能跑到盘速。
        val sink = part.outputStream().buffered(WRITE_BUFFER_BYTES).sink().buffer()
        var published = File(directory, unique)

        return@withContext object : ReceiveDestination {
            override val sink: Sink = sink
            override val displayPath: String get() = published.absolutePath

            override suspend fun commit() {
                sink.close()
                if (published.exists()) {
                    published = File(
                        directory,
                        FileNames.uniqueFileName(desired) { candidate -> File(directory, candidate).exists() },
                    )
                }
                if (!part.renameTo(published)) {
                    // 极端情况（文件被占用/跨卷）下退回复制，仍然不保留临时名
                    part.copyTo(published, overwrite = true)
                    part.delete()
                }
                // 协议里的 metadata.modified 是发送端的修改时间，落盘时还原，避免接收目录全是“刚刚”。
                rfc3339ToMillis(file.metadata?.modified)?.let { published.setLastModified(it) }
            }

            override suspend fun abort() {
                runCatching { sink.close() }
                runCatching { part.delete() }
            }
        }
    }

    private companion object {
        /** 与 SP/1 的 DATA 块大小对齐，一次落盘就是一次 write。 */
        const val WRITE_BUFFER_BYTES = 1024 * 1024

        const val PART_SUFFIX = ".boxlet-part"
    }
}
