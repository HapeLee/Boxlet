package dev.boxlet.core.net

import dev.boxlet.core.protocol.FileDto
import dev.boxlet.core.protocol.InfoResponse
import dev.boxlet.core.protocol.PrepareDownloadResponse
import dev.boxlet.core.protocol.RegisterRequest
import dev.boxlet.core.protocol.BoxletJson
import kotlinx.coroutines.CoroutineScope
import okio.Source
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

/** One entry of a browser sharing session. [openSource] must return a fresh stream on every call. */
class SharedEntry(
    val id: String,
    val fileName: String,
    val size: Long,
    val mimeType: String,
    val openSource: () -> Source,
)

/**
 * A browser sharing session: plain HTTP, deliberately not TLS.
 *
 * Browsers refuse self-signed certificates, so the Download API (protocol §5) cannot live on the
 * HTTPS main service. See `docs/adr/0003-download-api.md`.
 */
class ShareServer(
    private val scope: CoroutineScope,
    val sessionId: String = UUID.randomUUID().toString(),
    private val entries: List<SharedEntry>,
    private val pin: String?,
    private val infoProvider: () -> RegisterRequest,
) {

    /** 主端口（`prepare-download` / `download`）也用这一份数据，避免两套语义。 */
    fun entry(fileId: String?): SharedEntry? = entries.firstOrNull { it.id == fileId }

    fun pinMatches(pin: String?): Boolean = this.pin.isNullOrBlank() || pin == this.pin

    /** Protocol §5.2 body: no port/protocol in `info`, no `metadata` on files. */
    fun prepareDownloadBody(): String = BoxletJson.encodeToString(
        PrepareDownloadResponse.serializer(),
        PrepareDownloadResponse(info = info(), sessionId = sessionId, files = fileDtos()),
    )

    private var server: LocalSendHttpServer? = null

    var port: Int = 0
        private set

    val isRunning: Boolean get() = server?.isRunning == true

    /** Binds on an ephemeral port and returns the real one. */
    fun start(): Int {
        check(server == null) { "Share server already started" }
        val instance = LocalSendHttpServer(
            security = null,
            port = 0,
            scope = scope,
            https = false,
            handler = HttpHandler { call -> handle(call) },
        )
        port = instance.start()
        server = instance
        return port
    }

    fun stop() {
        server?.stop()
        server = null
    }

    fun url(host: String): String = "http://$host:$port/"

    private fun handle(call: HttpCall): HttpResponse = when (call.path) {
        "/", "/index.html" -> index(call)
        "/api/localsend/v2/prepare-download" -> prepareDownload(call)
        "/api/localsend/v2/download" -> download(call)
        "/api/localsend/v2/info" -> HttpResponse.json(200, BoxletJson.encodeToString(InfoResponse.serializer(), info()))
        else -> HttpResponse.empty(404)
    }

    private fun pinMatches(call: HttpCall): Boolean = pinMatches(call.queryParam("pin"))

    private fun fileDtos(): Map<String, FileDto> = entries.associate { entry ->
        entry.id to FileDto(
            id = entry.id,
            fileName = entry.fileName,
            size = entry.size,
            fileType = entry.mimeType,
        )
    }

    private fun prepareDownload(call: HttpCall): HttpResponse {
        if (call.method != "POST") return HttpResponse.empty(404)
        if (!pinMatches(call)) return HttpResponse.json(401, error("PIN required"))
        return HttpResponse.json(200, prepareDownloadBody())
    }

    private fun download(call: HttpCall): HttpResponse {
        if (call.method != "GET") return HttpResponse.empty(404)
        if (!pinMatches(call)) return HttpResponse.json(401, error("PIN required"))
        if (call.queryParam("sessionId") != sessionId) return HttpResponse.json(403, error("Invalid session"))
        val fileId = call.queryParam("fileId") ?: return HttpResponse.json(400, error("Missing parameters"))
        val entry = entry(fileId) ?: return HttpResponse.json(403, error("Invalid file"))
        val source = runCatching { entry.openSource() }.getOrElse {
            return HttpResponse.json(500, error("Cannot open file"))
        }
        return HttpResponse(
            status = 200,
            contentType = entry.mimeType,
            headers = mapOf("Content-Disposition" to contentDisposition(entry.fileName)),
            stream = source,
            streamLength = entry.size,
        )
    }

    private fun info(): InfoResponse {
        val local = infoProvider()
        return InfoResponse(
            alias = local.alias,
            version = local.version,
            deviceModel = local.deviceModel,
            deviceType = local.deviceType,
            fingerprint = local.fingerprint,
            download = true,
        )
    }

    private fun index(call: HttpCall): HttpResponse {
        if (pin != null && call.queryParam("pin") != pin) {
            return HttpResponse(200, HTML, pinPage().toByteArray(StandardCharsets.UTF_8))
        }
        val pinQuery = pin?.let { "&pin=${encode(it)}" } ?: ""
        val rows = entries.joinToString("\n") { entry ->
            val href = "/api/localsend/v2/download?sessionId=${encode(sessionId)}&fileId=${encode(entry.id)}$pinQuery"
            """
            <li><a href="$href">${escape(entry.fileName)}</a><span>${formatSize(entry.size)}</span></li>
            """.trimIndent()
        }
        val local = infoProvider()
        val html = """
            <!doctype html>
            <html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${escape(local.alias)} · Boxlet</title>
            <style>
            :root{color-scheme:light dark}
            body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;margin:0;padding:32px;background:#0f1115;color:#e8e8e8}
            main{max-width:640px;margin:0 auto}
            h1{font-size:20px;margin:0 0 4px}
            p.sub{color:#9aa0a6;margin:0 0 24px;font-size:13px}
            ul{list-style:none;padding:0;margin:0;display:grid;gap:8px}
            li{display:flex;align-items:center;justify-content:space-between;gap:12px;background:#1b1e24;border-radius:10px;padding:14px 16px}
            a{color:#8ab4f8;text-decoration:none;word-break:break-all}
            a:hover{text-decoration:underline}
            span{color:#9aa0a6;font-size:12px;white-space:nowrap}
            </style></head>
            <body><main>
            <h1>${escape(local.alias)}</h1>
            <p class="sub">通过 Boxlet 分享 · 共 ${entries.size} 个文件</p>
            <ul>
            $rows
            </ul>
            </main></body></html>
        """.trimIndent()
        return HttpResponse(200, HTML, html.toByteArray(StandardCharsets.UTF_8))
    }

    private fun pinPage(): String = """
        <!doctype html>
        <html lang="zh"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>需要 PIN · Boxlet</title>
        <style>
        body{font-family:system-ui,-apple-system,"Segoe UI",sans-serif;margin:0;display:grid;place-items:center;height:100vh;background:#0f1115;color:#e8e8e8}
        form{background:#1b1e24;padding:28px 32px;border-radius:12px;display:grid;gap:12px;min-width:240px}
        h1{font-size:16px;margin:0}
        input,button{font:inherit;padding:10px 12px;border-radius:8px;border:1px solid #333;background:#11141a;color:#e8e8e8}
        button{background:#2d6cdf;border-color:#2d6cdf;cursor:pointer}
        </style></head>
        <body><form method="get">
        <h1>输入 PIN 以查看文件</h1>
        <input name="pin" inputmode="numeric" autocomplete="off" autofocus>
        <button type="submit">打开</button>
        </form></body></html>
    """.trimIndent()

    private fun error(message: String): String =
        """{"message":"${message.replace("\\", "\\\\").replace("\"", "\\\"")}"}"""

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val HTML = "text/html; charset=utf-8"
    }
}

/** `attachment` 头：ASCII 回退 + RFC 5987 的 UTF-8 文件名，两个端口共用。 */
internal fun contentDisposition(fileName: String): String {
    val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
    val ascii = fileName.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
    return """attachment; filename="$ascii"; filename*=UTF-8''$encoded"""
}
