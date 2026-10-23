package dev.boxlet.desktop

import dev.boxlet.core.engine.OutgoingFile
import dev.boxlet.core.protocol.DeviceType
import dev.boxlet.shared.Settings
import dev.boxlet.shared.storage.DefaultDownloadDir
import okio.source
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URLConnection
import java.util.UUID
import javax.swing.JFileChooser
import kotlin.time.Instant

object DesktopEnvironment {

    val dataDir: File
        get() {
            val home = File(System.getProperty("user.home"))
            val os = System.getProperty("os.name").lowercase()
            return if (os.contains("win")) {
                File(System.getenv("APPDATA") ?: home.absolutePath, "Boxlet")
            } else {
                File(home, ".config/Boxlet")
            }
        }

    /** 跟随系统的「下载」目录（可能被重定向到其它盘），见 [DefaultDownloadDir]。 */
    val defaultDownloadDir: File
        get() = DefaultDownloadDir.resolve()

    val defaultSettings: Settings
        get() = Settings(
            deviceType = DeviceType.DESKTOP,
            deviceModel = buildString {
                append(System.getProperty("os.name"))
                append(' ')
                append(System.getProperty("os.version"))
            }.trim(),
        )
}

fun pickFiles(): List<File> {
    val dialog = FileDialog(null as Frame?, "选择要发送的文件", FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    return dialog.files?.toList().orEmpty()
}

fun pickDirectory(title: String = "选择目录"): File? {
    val chooser = JFileChooser().apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = title
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

fun File.toOutgoingFile(fileNameOverride: String? = null): OutgoingFile {
    val mime = URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    return OutgoingFile(
        id = UUID.randomUUID().toString(),
        fileName = fileNameOverride ?: name,
        size = length(),
        mimeType = mime,
        modified = Instant.fromEpochMilliseconds(lastModified()),
        openSource = { source() },
    )
}

/** Flattens a directory into LocalSend relative file names (`dir/sub/file.txt`). */
fun collectFolder(root: File): List<OutgoingFile> {
    if (!root.isDirectory) return emptyList()
    return root.walkTopDown()
        .onEnter { it.isDirectory && !it.name.startsWith(".") && !java.nio.file.Files.isSymbolicLink(it.toPath()) }
        .filter { it.isFile && !it.name.startsWith(".") }
        .take(5000)
        .map { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            file.toOutgoingFile(relative)
        }
        .toList()
}
