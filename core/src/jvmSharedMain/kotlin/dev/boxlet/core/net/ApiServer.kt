package dev.boxlet.core.net

import dev.boxlet.core.protocol.ApiError
import dev.boxlet.core.protocol.FileDto
import dev.boxlet.core.protocol.InfoResponse
import dev.boxlet.core.protocol.PrepareUploadRequest
import dev.boxlet.core.protocol.PrepareUploadResponse
import dev.boxlet.core.protocol.RegisterRequest
import dev.boxlet.core.protocol.BoxletJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Source
import java.io.InputStream

sealed interface PrepareUploadOutcome {
    data class Accepted(val sessionId: String, val files: Map<String, String>) : PrepareUploadOutcome
    data object NoFiles : PrepareUploadOutcome
    data object PinRequired : PrepareUploadOutcome
    data object InvalidPin : PrepareUploadOutcome
    data object TooManyRequests : PrepareUploadOutcome
    data object Busy : PrepareUploadOutcome
    data class Rejected(val message: String = "Rejected") : PrepareUploadOutcome
    data class Invalid(val message: String) : PrepareUploadOutcome
}

sealed interface UploadOutcome {
    data object Ok : UploadOutcome
    data class Forbidden(val message: String = "Invalid token or IP address") : UploadOutcome
    data object ChecksumMismatch : UploadOutcome
    data class Failed(val message: String = "Internal server error") : UploadOutcome
}

sealed interface PrepareDownloadOutcome {
    /** Protocol §5.2 body, already encoded as JSON. */
    data class Ok(val payload: String) : PrepareDownloadOutcome
    data object PinRequired : PrepareDownloadOutcome

    /** No active share session: protocol says 403 Rejected. */
    data object NoSession : PrepareDownloadOutcome
}

sealed interface DownloadOutcome {
    data class File(
        val contentType: String,
        val fileName: String,
        val size: Long,
        val source: Source,
    ) : DownloadOutcome

    data object PinRequired : DownloadOutcome
    data object InvalidSession : DownloadOutcome
    data object NoSession : DownloadOutcome
    data object Failed : DownloadOutcome
}

interface LocalSendHandler {
    fun localInfo(): RegisterRequest
    fun showToken(): String
    fun webIndexHtml(): String

    suspend fun onRegister(
        request: RegisterRequest,
        remoteIp: String,
        clientFingerprint: String?,
    ): InfoResponse

    suspend fun onPrepareUpload(
        request: PrepareUploadRequest,
        pin: String?,
        remoteIp: String,
        clientFingerprint: String?,
    ): PrepareUploadOutcome

    suspend fun onUpload(
        sessionId: String,
        fileId: String,
        token: String,
        remoteIp: String,
        body: InputStream,
        contentLength: Long,
        onProgress: (Long) -> Unit,
    ): UploadOutcome

    suspend fun onCancel(sessionId: String?, remoteIp: String)

    /** Protocol §5.2 on the main port; the share session is the same one used by [ShareServer]. */
    suspend fun onPrepareDownload(pin: String?, remoteIp: String): PrepareDownloadOutcome

    /** Protocol §5.3 on the main port. */
    suspend fun onDownload(
        sessionId: String?,
        fileId: String?,
        pin: String?,
        remoteIp: String,
    ): DownloadOutcome

    fun onShow(args: List<String>)
}

/** Maps LocalSend v2 routes to [LocalSendHandler] callbacks. */
class LocalSendApiServer(
    private val handler: LocalSendHandler,
) {
    private companion object {
        val CORS_HEADERS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type, Content-Length",
            "Access-Control-Max-Age" to "86400",
        )
    }

    fun newServer(
        security: dev.boxlet.core.crypto.SecurityContext,
        port: Int,
        scope: kotlinx.coroutines.CoroutineScope,
        https: Boolean = true,
    ): LocalSendHttpServer = LocalSendHttpServer(
        security = security,
        port = port,
        scope = scope,
        https = https,
        handler = { call -> withCors(call) },
    )

    /**
     * 浏览器端（Web 客户端）需要跨域访问，且会先发 OPTIONS 预检。
     * 只放行必要的方法与请求头；不带凭据，因此可以用 `*`。
     */
    private suspend fun withCors(call: HttpCall): HttpResponse {
        if (call.method == "OPTIONS") {
            return HttpResponse(status = 204, contentType = null, headers = CORS_HEADERS)
        }
        val response = route(call)
        return HttpResponse(
            status = response.status,
            contentType = response.contentType,
            body = response.body,
            headers = response.headers + CORS_HEADERS,
            stream = response.stream,
            streamLength = response.streamLength,
        )
    }

    private suspend fun route(call: HttpCall): HttpResponse {
        val path = call.path
        val method = call.method

        if (path == "/" && method == "GET") {
            return HttpResponse(200, "text/html; charset=utf-8", handler.webIndexHtml().encodeToByteArray())
        }
        if (path == "/i18n.json" && method == "GET") {
            return HttpResponse.json(
                200,
                """{"waiting":"Waiting for the receiver to accept…","enterPin":"Enter PIN","invalidPin":"Invalid PIN","tooManyAttempts":"Too many attempts","rejected":"Rejected","uploadRejected":"Upload rejected","busy":"Busy","files":"Files","fileName":"Name","size":"Size","dropHint":"Drop files here"}""",
            )
        }
        if (path.startsWith("/api/localsend/v2/")) {
            return routeApi(call)
        }
        return HttpResponse.empty(404)
    }

    private suspend fun routeApi(call: HttpCall): HttpResponse {
        val path = call.path.removePrefix("/api/localsend/v2/")
        return when (path) {
            "register" -> handleRegister(call)
            "info" -> handleInfo(call)
            "prepare-upload" -> handlePrepareUpload(call)
            "upload" -> handleUpload(call)
            "cancel" -> handleCancel(call)
            "show" -> handleShow(call)
            "prepare-download" -> handlePrepareDownload(call)
            "download" -> handleDownload(call)
            else -> HttpResponse.empty(404)
        }
    }

    private suspend fun handleRegister(call: HttpCall): HttpResponse {
        if (call.method != "POST") return HttpResponse.empty(404)
        val request = runCatching {
            BoxletJson.decodeFromString(RegisterRequest.serializer(), call.readBodyText())
        }.getOrElse {
            return HttpResponse.json(400, error("Invalid JSON body"))
        }
        val response = handler.onRegister(request, call.remoteIp, call.clientFingerprint)
        return HttpResponse.json(200, BoxletJson.encodeToString(InfoResponse.serializer(), response))
    }

    private fun handleInfo(call: HttpCall): HttpResponse {
        val info = handler.localInfo()
        val response = InfoResponse(
            alias = info.alias,
            version = info.version,
            deviceModel = info.deviceModel,
            deviceType = info.deviceType,
            fingerprint = info.fingerprint,
            download = info.download,
            sendeeCapabilities = info.sendeeCapabilities,
        )
        return HttpResponse.json(200, BoxletJson.encodeToString(InfoResponse.serializer(), response))
    }

    private suspend fun handlePrepareUpload(call: HttpCall): HttpResponse {
        if (call.method != "POST") return HttpResponse.empty(404)
        val request = runCatching {
            BoxletJson.decodeFromString(PrepareUploadRequest.serializer(), call.readBodyText())
        }.getOrElse {
            return HttpResponse.json(400, error("Invalid JSON body"))
        }
        // 协议：没有文件需要传输时回 204（不是 400，也不是 403）。
        if (request.files.isEmpty()) return HttpResponse.empty(204)

        return when (
            val outcome = handler.onPrepareUpload(
                request = request,
                pin = call.queryParam("pin"),
                remoteIp = call.remoteIp,
                clientFingerprint = call.clientFingerprint,
            )
        ) {
            is PrepareUploadOutcome.Accepted -> HttpResponse.json(
                200,
                BoxletJson.encodeToString(
                    PrepareUploadResponse.serializer(),
                    PrepareUploadResponse(outcome.sessionId, outcome.files),
                ),
            )

            PrepareUploadOutcome.NoFiles -> HttpResponse.empty(204)
            PrepareUploadOutcome.PinRequired -> HttpResponse.json(401, error("PIN required"))
            PrepareUploadOutcome.InvalidPin -> HttpResponse.json(401, error("Invalid PIN"))
            PrepareUploadOutcome.TooManyRequests -> HttpResponse.json(429, error("Too many requests"))
            PrepareUploadOutcome.Busy -> HttpResponse.json(409, error("Blocked by another session"))
            is PrepareUploadOutcome.Rejected -> HttpResponse.json(403, error(outcome.message))
            is PrepareUploadOutcome.Invalid -> HttpResponse.json(400, error(outcome.message))
        }
    }

    private suspend fun handleUpload(call: HttpCall): HttpResponse {
        if (call.method != "POST") return HttpResponse.empty(404)
        val sessionId = call.queryParam("sessionId")
        val fileId = call.queryParam("fileId")
        val token = call.queryParam("token")
        if (sessionId.isNullOrBlank() || fileId.isNullOrBlank() || token.isNullOrBlank()) {
            return HttpResponse.json(400, error("Missing parameters"))
        }

        return when (
            val outcome = handler.onUpload(
                sessionId = sessionId,
                fileId = fileId,
                token = token,
                remoteIp = call.remoteIp,
                body = call.body,
                contentLength = call.contentLength,
                onProgress = {},
            )
        ) {
            UploadOutcome.Ok -> HttpResponse.empty(200)
            is UploadOutcome.Forbidden -> HttpResponse.json(403, error(outcome.message))
            UploadOutcome.ChecksumMismatch -> HttpResponse.json(422, error("Checksum mismatch"))
            is UploadOutcome.Failed -> HttpResponse.json(500, error(outcome.message))
        }
    }

    /**
     * 协议 §5.2 的 Download API 走主端口：`download` 字段在公告里为 `true` 时，
     * 对端（含官方客户端）就是照这个端口发 `prepare-download` 的，不能返回 403。
     * 分享服务仍然保留独立明文端口给浏览器用，两边读同一个会话。
     */
    private suspend fun handlePrepareDownload(call: HttpCall): HttpResponse {
        if (call.method != "POST") return HttpResponse.empty(404)
        return when (val outcome = handler.onPrepareDownload(call.queryParam("pin"), call.remoteIp)) {
            is PrepareDownloadOutcome.Ok -> HttpResponse.json(200, outcome.payload)
            PrepareDownloadOutcome.PinRequired -> HttpResponse.json(401, error("PIN required"))
            PrepareDownloadOutcome.NoSession -> HttpResponse.json(403, error("Rejected"))
        }
    }

    private suspend fun handleDownload(call: HttpCall): HttpResponse {
        if (call.method != "GET") return HttpResponse.empty(404)
        return when (
            val outcome = handler.onDownload(
                sessionId = call.queryParam("sessionId"),
                fileId = call.queryParam("fileId"),
                pin = call.queryParam("pin"),
                remoteIp = call.remoteIp,
            )
        ) {
            is DownloadOutcome.File -> HttpResponse(
                status = 200,
                contentType = outcome.contentType,
                headers = mapOf("Content-Disposition" to contentDisposition(outcome.fileName)),
                stream = outcome.source,
                streamLength = outcome.size,
            )

            DownloadOutcome.PinRequired -> HttpResponse.json(401, error("PIN required"))
            DownloadOutcome.InvalidSession -> HttpResponse.json(403, error("Invalid session or file"))
            DownloadOutcome.NoSession -> HttpResponse.json(403, error("Rejected"))
            DownloadOutcome.Failed -> HttpResponse.json(500, error("Cannot open file"))
        }
    }

    private suspend fun handleCancel(call: HttpCall): HttpResponse {
        if (call.method != "POST") return HttpResponse.empty(404)
        handler.onCancel(call.queryParam("sessionId"), call.remoteIp)
        return HttpResponse.empty(200)
    }

    private fun handleShow(call: HttpCall): HttpResponse {
        if (call.method != "POST") return HttpResponse.empty(404)
        if (call.queryParam("token") != handler.showToken()) {
            return HttpResponse.json(403, error("Invalid token"))
        }
        val text = call.readBodyText(64 * 1024)
        val args = runCatching {
            val root = BoxletJson.parseToJsonElement(text).jsonObject
            val array = root["args"] as? JsonArray ?: JsonArray(emptyList())
            array.map { it.jsonPrimitive.content }
        }.getOrDefault(emptyList())
        handler.onShow(args)
        return HttpResponse.empty(200)
    }

    private fun error(message: String): String =
        BoxletJson.encodeToString(ApiError.serializer(), ApiError(message))
}
