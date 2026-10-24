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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ApiResult(val code: Int, val body: String, val error: String? = null) {
    val isSuccessful: Boolean get() = code in 200..299

    fun errorMessage(): String = runCatching {
        BoxletJson.decodeFromString(dev.boxlet.core.protocol.ApiError.serializer(), body).message
    }.getOrElse { error ?: "HTTP $code" }
}

/** LocalSend client: discovery is blocking; preparation and upload follow coroutine cancellation. */
class ApiClient(private val security: SecurityContext) {

    private data class ClientKey(val https: Boolean, val fingerprint: String?)
    private val clients = mutableMapOf<ClientKey, OkHttpClient>()

    private fun client(channel: DeviceChannel, expectedFingerprint: String?): OkHttpClient {
        val key = ClientKey(channel.https, expectedFingerprint?.uppercase())
        return synchronized(clients) { clients.getOrPut(key) { newClient(channel.https, expectedFingerprint) } }
    }

    private fun newClient(https: Boolean, expectedFingerprint: String?) = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            if (https) {
                val trust = Certificates.clientTrustManager(expectedFingerprint)
                sslSocketFactory(Certificates.clientContext(security, expectedFingerprint).socketFactory, trust)
                hostnameVerifier { _, _ -> true }
            }
        }
        .build()

    fun register(channel: DeviceChannel, request: RegisterRequest, expectedFingerprint: String?): ApiResult =
        postJson(channel, expectedFingerprint, "/api/localsend/v2/register", BoxletJson.encodeToString(RegisterRequest.serializer(), request), timeoutMs = 4_000)

    fun info(channel: DeviceChannel, expectedFingerprint: String?): ApiResult =
        get(channel, expectedFingerprint, "/api/localsend/v2/info", timeoutMs = 1_500)

    suspend fun prepareUpload(
        channel: DeviceChannel,
        expectedFingerprint: String?,
        request: PrepareUploadRequest,
        pin: String?,
        // 服务端要等接收方人工确认，最长 PREPARE_DECISION_TIMEOUT_MS；这里必须比它更久，
        // 否则接收方还在等用户点击，发送方已经 timeout 失败了。
        timeoutMs: Long = Protocol.PREPARE_DECISION_TIMEOUT_MS + 15_000,
    ): ApiResult {
        val query = pin?.let { "?pin=${encode(it)}" } ?: ""
        val httpRequest = jsonRequest(channel, "/api/localsend/v2/prepare-upload$query",
            BoxletJson.encodeToString(PrepareUploadRequest.serializer(), request))
        return executeCancellable(client(channel, expectedFingerprint), httpRequest, timeoutMs)
    }

    fun parsePrepareUpload(result: ApiResult): PrepareUploadResponse =
        BoxletJson.decodeFromString(PrepareUploadResponse.serializer(), result.body)

    suspend fun upload(
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
            .post(LocalSendUploadBody(file, isCancelled, onProgress, streamingChecksum))
            .build()
        return executeCancellable(client(channel, expectedFingerprint), request)
    }

    /** Retire owned clients on engine stop; subsequent requests create fresh clients on restart. */
    fun cancelAll() {
        val retired = synchronized(clients) { clients.values.toList().also { clients.clear() } }
        retired.forEach {
            it.dispatcher.cancelAll()
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
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
        val payload = buildJsonObject { putJsonArray("args") { args.forEach { add(it) } } }.toString()
        return postJson(channel, null, "/api/localsend/v2/show?token=${encode(token)}", payload, timeoutMs = 1_000)
    }

    private fun postJson(
        channel: DeviceChannel,
        expectedFingerprint: String?,
        path: String,
        json: String,
        timeoutMs: Long,
    ): ApiResult {
        val request = jsonRequest(channel, path, json)
        return execute(client(channel, expectedFingerprint), request, timeoutMs)
    }

    private fun get(channel: DeviceChannel, expectedFingerprint: String?, path: String, timeoutMs: Long): ApiResult {
        val request = Request.Builder().url(buildUrl(channel, path)).get().build()
        return execute(client(channel, expectedFingerprint), request, timeoutMs)
    }

    private fun execute(client: OkHttpClient, request: Request, timeoutMs: Long = 0): ApiResult {
        val call = newCall(client, request, timeoutMs)
        return try {
            readResponse(call.execute())
        } catch (error: IOException) {
            failure(error)
        }
    }

    private suspend fun executeCancellable(client: OkHttpClient, request: Request, timeoutMs: Long = 0): ApiResult =
        suspendCancellableCoroutine { continuation ->
            val call = newCall(client, request, timeoutMs)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resume(failure(e)) }
                override fun onResponse(call: Call, response: Response) {
                    val result = try { readResponse(response) }
                    catch (error: IOException) { failure(error) }
                    catch (error: Exception) { continuation.resumeWithException(error); return }
                    continuation.resume(result)
                }
            })
        }

    private fun newCall(client: OkHttpClient, request: Request, timeoutMs: Long) = client.newCall(request).also {
        if (timeoutMs > 0) it.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun readResponse(response: Response): ApiResult = response.use { ApiResult(it.code, it.body.string()) }
    private fun failure(error: IOException) = ApiResult(0, "", error.message ?: error.javaClass.simpleName)
    private fun jsonRequest(channel: DeviceChannel, path: String, json: String) = Request.Builder()
        .url(buildUrl(channel, path)).post(json.toRequestBody(JSON_MEDIA_TYPE)).build()

    private fun buildUrl(channel: DeviceChannel, path: String): String = channel.url + path

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
