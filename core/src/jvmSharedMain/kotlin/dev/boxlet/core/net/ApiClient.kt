package dev.boxlet.core.net

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.crypto.SecurityContext
import dev.boxlet.core.engine.OutgoingFile
import dev.boxlet.core.protocol.DeviceChannel
import dev.boxlet.core.protocol.InfoResponse
import dev.boxlet.core.protocol.PrepareUploadRequest
import dev.boxlet.core.protocol.PrepareUploadResponse
import dev.boxlet.core.protocol.Protocol
import dev.boxlet.core.protocol.RegisterRequest
import dev.boxlet.core.protocol.BoxletJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.buffer
import okio.HashingSink
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class ApiResult(val code: Int, val body: String, val error: String? = null) {
    val isSuccessful: Boolean get() = code in 200..299

    fun errorMessage(): String = runCatching {
        BoxletJson.decodeFromString(dev.boxlet.core.protocol.ApiError.serializer(), body).message
    }.getOrElse { error ?: "HTTP $code" }
}

/** Blocking LocalSend HTTP client. Call from Dispatchers.IO. */
class ApiClient(private val security: SecurityContext) {

    private val clients = ConcurrentHashMap<String, OkHttpClient>()

    private fun client(channel: DeviceChannel, expectedFingerprint: String?): OkHttpClient {
        val key = "${channel.https}:${expectedFingerprint?.uppercase()}"
        return clients.getOrPut(key) {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .apply {
                    if (channel.https) {
                        val trust = Certificates.clientTrustManager(expectedFingerprint)
                        sslSocketFactory(
                            Certificates.clientContext(security, expectedFingerprint).socketFactory,
                            trust,
                        )
                        hostnameVerifier { _, _ -> true }
                    }
                }
                .build()
        }
    }

    fun register(channel: DeviceChannel, request: RegisterRequest, expectedFingerprint: String?): ApiResult =
        postJson(channel, expectedFingerprint, "/api/localsend/v2/register", BoxletJson.encodeToString(RegisterRequest.serializer(), request), timeoutMs = 4_000)

    fun info(channel: DeviceChannel, expectedFingerprint: String?): ApiResult =
        get(channel, expectedFingerprint, "/api/localsend/v2/info", timeoutMs = 1_500)

    fun prepareUpload(
        channel: DeviceChannel,
        expectedFingerprint: String?,
        request: PrepareUploadRequest,
        pin: String?,
        // 服务端要等接收方人工确认，最长 PREPARE_DECISION_TIMEOUT_MS；这里必须比它更久，
        // 否则接收方还在等用户点击，发送方已经 timeout 失败了。
        timeoutMs: Long = Protocol.PREPARE_DECISION_TIMEOUT_MS + 15_000,
    ): ApiResult {
        val query = pin?.let { "?pin=${encode(it)}" } ?: ""
        return postJson(
            channel = channel,
            expectedFingerprint = expectedFingerprint,
            path = "/api/localsend/v2/prepare-upload$query",
            json = BoxletJson.encodeToString(PrepareUploadRequest.serializer(), request),
            timeoutMs = timeoutMs,
        )
    }

    fun parsePrepareUpload(result: ApiResult): PrepareUploadResponse =
        BoxletJson.decodeFromString(PrepareUploadResponse.serializer(), result.body)

    fun upload(
        channel: DeviceChannel,
        expectedFingerprint: String?,
        sessionId: String,
        fileId: String,
        token: String,
        file: OutgoingFile,
        isCancelled: () -> Boolean,
        onProgress: (Long) -> Unit,
        streamingChecksum: Boolean = false,
    ): ApiResult {
        val url = buildUrl(
            channel,
            "/api/localsend/v2/upload?sessionId=${encode(sessionId)}&fileId=${encode(fileId)}&token=${encode(token)}",
        )
        val request = Request.Builder()
            .url(url)
            .post(ProgressRequestBody(file, isCancelled, onProgress, streamingChecksum))
            .build()
        return execute(client(channel, expectedFingerprint), request)
    }

    fun cancel(channel: DeviceChannel, expectedFingerprint: String?, sessionId: String?): ApiResult {
        val query = sessionId?.let { "?sessionId=${encode(it)}" } ?: ""
        return postJson(
            channel,
            expectedFingerprint,
            "/api/localsend/v2/cancel$query",
            json = "",
            timeoutMs = 3_000,
        )
    }

    fun show(port: Int, token: String, args: List<String>): ApiResult {
        val channel = DeviceChannel("127.0.0.1", port, https = false)
        val payload = buildString {
            append("""{"args":[""")
            append(args.joinToString(",") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" })
            append("]}")
        }
        return postJson(channel, null, "/api/localsend/v2/show?token=${encode(token)}", payload, timeoutMs = 1_000)
    }

    private fun postJson(
        channel: DeviceChannel,
        expectedFingerprint: String?,
        path: String,
        json: String,
        timeoutMs: Long,
    ): ApiResult {
        val request = Request.Builder()
            .url(buildUrl(channel, path))
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(client(channel, expectedFingerprint), request, timeoutMs)
    }

    private fun get(channel: DeviceChannel, expectedFingerprint: String?, path: String, timeoutMs: Long): ApiResult {
        val request = Request.Builder().url(buildUrl(channel, path)).get().build()
        return execute(client(channel, expectedFingerprint), request, timeoutMs)
    }

    private fun execute(client: OkHttpClient, request: Request, timeoutMs: Long = 0): ApiResult {
        val call = client.newCall(request)
        return try {
            if (timeoutMs > 0) {
                call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
            }
            call.execute().use { response ->
                val text = response.body.string()
                ApiResult(response.code, text)
            }
        } catch (error: IOException) {
            ApiResult(0, "", error.message ?: error.javaClass.simpleName)
        }
    }

    private fun buildUrl(channel: DeviceChannel, path: String): String = channel.url + path

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private class ProgressRequestBody(
        private val file: OutgoingFile,
        private val isCancelled: () -> Boolean,
        private val onProgress: (Long) -> Unit,
        private val streamingChecksum: Boolean,
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()

        override fun contentLength(): Long = if (streamingChecksum) Math.addExact(file.size, 32L) else file.size

        override fun writeTo(sink: BufferedSink) {
            val hashingSink = if (streamingChecksum) HashingSink.sha256(sink) else null
            val dataSink = hashingSink?.buffer() ?: sink
            var written = 0L
            file.openSource().use { source ->
                val buffer = Buffer()
                while (true) {
                    if (isCancelled()) throw IOException("Upload cancelled")
                    val read = source.read(buffer, 64 * 1024)
                    if (read == -1L) break
                    if (read > file.size - written) throw IOException("Source exceeds declared size")
                    dataSink.write(buffer, read)
                    written += read
                    onProgress(written)
                }
            }
            if (written != file.size) {
                throw IOException("Source size mismatch: expected ${file.size}, read $written")
            }
            if (hashingSink != null) {
                dataSink.flush()
                sink.write(hashingSink.hash)
            }
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
