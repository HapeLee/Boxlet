package dev.boxlet.shared.storage

import java.io.File
import java.nio.charset.Charset

/**
 * 默认保存目录：`<系统下载目录>/Boxlet`。
 *
 * 不能直接拼 `~/Downloads`：Windows 允许把「下载」重定向到其它盘（例如 `D:\Downloads`），
 * Linux 也可能通过 xdg-user-dirs 改到本地化目录。这里按系统记录解析，
 * 解析不出来才退回 `~/Downloads`。
 */
object DefaultDownloadDir {

    /** FOLDERID_Downloads 的 GUID，即 shell 里「下载」这个 known folder。 */
    private const val DownloadsFolderId = "{374DE290-123F-4565-9164-39C4925E467B}"

    private val resolved: File by lazy { File(downloadsRoot(), "Boxlet") }

    fun resolve(): File = resolved

    private fun downloadsRoot(): File {
        val home = File(System.getProperty("user.home") ?: ".")
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> windowsDownloads(home)
            os.contains("mac") -> File(home, "Downloads")
            else -> xdgDownloads(home)
        }
    }

    private fun windowsDownloads(home: File): File {
        // 「Shell Folders」存的是已展开的绝对路径，优先用它；
        // 「User Shell Folders」可能是 `%USERPROFILE%\Downloads` 这类未展开形式。
        val expanded = registryValue("Shell Folders", home = false)
        val fromUserShell = registryValue("User Shell Folders", home = true)
        return listOfNotNull(expanded, fromUserShell).map(::File).firstOrNull { it.isDirectory }
            ?: File(home, "Downloads")
    }

    private fun registryValue(folder: String, home: Boolean): String? {
        val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\$folder"
        val output = runCatching {
            val process = ProcessBuilder("reg", "query", key, "/v", DownloadsFolderId)
                .redirectErrorStream(true)
                .start()
            val bytes = process.inputStream.readBytes()
            process.waitFor()
            // reg.exe 按控制台 OEM 代码页输出；JDK 18+ 默认 UTF-8，直接当字符串读会弄坏中文路径。
            String(bytes, oemCharset)
        }.getOrNull() ?: return null
        // 输出形如：`    {374DE290-...}    REG_EXPAND_SZ    %USERPROFILE%\Downloads`
        val raw = Regex("(?i)REG_(?:EXPAND_SZ|SZ)\\s+(.+)$", RegexOption.MULTILINE)
            .find(output)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        return if (home) expandEnvironment(raw) else raw
    }

    private fun expandEnvironment(value: String): String = Regex("%([^%]+)%").replace(value) { match ->
        System.getenv(match.groupValues[1]) ?: match.value
    }

    /** 控制台 OEM 代码页（中文系统是 936）；读不到就退回 UTF-8。 */
    private val oemCharset: Charset by lazy {
        val codePage = runCatching {
            val process = ProcessBuilder(
                "reg", "query", "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Nls\\CodePage", "/v", "OEMCP",
            ).start()
            // 纯数字，ASCII 解码是安全的。
            val text = process.inputStream.readBytes().toString(Charsets.US_ASCII)
            process.waitFor()
            Regex("OEMCP\\s+REG_SZ\\s+(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
        }.getOrNull()
        codePage?.let { runCatching { Charset.forName("cp$it") }.getOrNull() } ?: Charsets.UTF_8
    }

    private fun xdgDownloads(home: File): File {
        val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".config").path
        val declared = runCatching {
            File(configHome, "user-dirs.dirs").readLines()
                .firstOrNull { it.trimStart().startsWith("XDG_DOWNLOAD_DIR") }
                ?.substringAfter('=', "")
                ?.trim()
                ?.trim('"')
        }.getOrNull()
        val expanded = declared
            ?.replace("\${HOME}", home.path)
            ?.replace("\$HOME", home.path)
            ?.takeIf { it.isNotBlank() }
        // xdg 允许显式禁用（值为 $HOME 表示跟随家目录），这时仍用家目录。
        return expanded?.let(::File) ?: File(home, "Downloads")
    }
}
