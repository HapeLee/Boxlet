package dev.boxlet.core.protocol

import okio.FileSystem
import okio.Path

/** Cross-platform sanitization of peer supplied file names. */
object FileNames {

    private val WINDOWS_RESERVED = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    /** Sanitizes one path segment; never returns an empty string. */
    fun sanitizeSegment(raw: String): String {
        var value = raw.trim().trim('.')
        value = value.replace(Regex("""[<>:"/\\|?*\u0000-\u001F]"""), "_")
        if (value.isEmpty()) value = "file"
        if (value.substringBefore('.').uppercase() in WINDOWS_RESERVED) value = "_$value"
        if (value.length > 120) {
            val extension = value.substringAfterLast('.', "")
            value = if (extension.length in 1..10) {
                value.dropLast(extension.length + 1).take(100) + "." + extension
            } else {
                value.take(120)
            }
        }
        return value
    }

    /**
     * Converts a peer supplied relative file name into safe segments.
     * Directory traversal, absolute paths and empty segments are removed.
     */
    fun sanitizeRelativePath(raw: String): List<String> {
        val normalized = raw.replace('\\', '/')
        val segments = normalized.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map(::sanitizeSegment)
        return segments.ifEmpty { listOf("file") }
    }

    /** Finds a non-conflicting name inside [directory], appending ` (n)` before the extension. */
    fun uniqueFileName(fileSystem: FileSystem, directory: Path, desired: String): String =
        uniqueFileName(desired) { candidate -> fileSystem.exists(directory / candidate) }

    /** Same as above but backed by any existence predicate (java.io on Android). */
    fun uniqueFileName(desired: String, exists: (String) -> Boolean): String {
        if (!exists(desired)) return desired
        val base = desired.substringBeforeLast('.', desired)
        val extension = desired.substringAfterLast('.', "")
        var index = 1
        while (true) {
            val candidate = if (extension.isEmpty()) {
                "$base ($index)"
            } else {
                "$base ($index).$extension"
            }
            if (!exists(candidate)) return candidate
            index++
            if (index > 10_000) return "${base}_${System.currentTimeMillis()}.$extension"
        }
    }
}
