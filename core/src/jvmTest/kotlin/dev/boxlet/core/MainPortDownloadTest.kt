package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.net.DownloadOutcome
import dev.boxlet.core.net.LocalSendApiServer
import dev.boxlet.core.net.LocalSendHandler
import dev.boxlet.core.net.PrepareDownloadOutcome
import dev.boxlet.core.net.PrepareUploadOutcome
import dev.boxlet.core.net.ShareServer
import dev.boxlet.core.net.SharedEntry
import dev.boxlet.core.net.UploadOutcome
import dev.boxlet.core.protocol.DeviceType
import dev.boxlet.core.protocol.InfoResponse
import dev.boxlet.core.protocol.PrepareDownloadResponse
import dev.boxlet.core.protocol.PrepareUploadRequest
import dev.boxlet.core.protocol.ProtocolType
import dev.boxlet.core.protocol.RegisterRequest
import dev.boxlet.core.protocol.BoxletJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.After
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 协议 §5 的 Download API 必须长在主端口上：公告里的 `download` 为 `true` 时，
 * 对端（官方客户端也一样）会向公告端口 POST `prepare-download`。之前这里恒回 403。
 */
class MainPortDownloadTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val security = Certificates.generate()
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val payload = "main port download\n".repeat(32).toByteArray()
    private val entry = SharedEntry(
        id = "file-1",
        fileName = "报告 final.txt",
        size = payload.size.toLong(),
        mimeType = "text/plain",
        openSource = { Buffer().write(payload) },
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** 与 [dev.boxlet.core.engine.TransferEngine] 相同：把请求转给分享会话。 */
    private inner class Handler(
        private val share: () -> ShareServer?,
    ) : LocalSendHandler {
        override fun localInfo(): RegisterRequest = RegisterRequest(
            alias = "Test",
            deviceType = DeviceType.DESKTOP,
            fingerprint = security.fingerprint,
            port = 53318,
            protocol = ProtocolType.HTTP,
            download = share() != null,
        )

        override fun showToken(): String = "token"
        override fun webIndexHtml(): String = "<html></html>"

        override suspend fun onRegister(
            request: RegisterRequest,
            remoteIp: String,
            clientFingerprint: String?,
        ): InfoResponse = InfoResponse(
            alias = "Test",
            deviceType = DeviceType.DESKTOP,
            fingerprint = security.fingerprint,
        )

        override suspend fun onPrepareUpload(
            request: PrepareUploadRequest,
            pin: String?,
            remoteIp: String,
            clientFingerprint: String?,
        ): PrepareUploadOutcome = PrepareUploadOutcome.Rejected()

        override suspend fun onUpload(
            sessionId: String,
            fileId: String,
            token: String,
            remoteIp: String,
            body: InputStream,
            contentLength: Long,
            onProgress: (Long) -> Unit,
        ): UploadOutcome = UploadOutcome.Forbidden()

        override suspend fun onCancel(sessionId: String?, remoteIp: String) = Unit

        override suspend fun onPrepareDownload(pin: String?, remoteIp: String): PrepareDownloadOutcome {
            val session = share() ?: return PrepareDownloadOutcome.NoSession
            if (!session.pinMatches(pin)) return PrepareDownloadOutcome.PinRequired
            return PrepareDownloadOutcome.Ok(session.prepareDownloadBody())
        }

        override suspend fun onDownload(
            sessionId: String?,
            fileId: String?,
            pin: String?,
            remoteIp: String,
        ): DownloadOutcome {
            val session = share() ?: return DownloadOutcome.NoSession
            if (!session.pinMatches(pin)) return DownloadOutcome.PinRequired
            if (sessionId != session.sessionId) return DownloadOutcome.InvalidSession
            val entry = session.entry(fileId) ?: return DownloadOutcome.InvalidSession
            return DownloadOutcome.File(
                contentType = entry.mimeType,
                fileName = entry.fileName,
                size = entry.size,
                source = entry.openSource(),
            )
        }

        override fun onShow(args: List<String>) = Unit
    }

    private fun serve(pin: String? = null, block: (ShareServer, String) -> Unit) {
        val share = ShareServer(
            scope = scope,
            entries = listOf(entry),
            pin = pin,
            infoProvider = { RegisterRequest(alias = "Test", fingerprint = security.fingerprint) },
        )
        val server = LocalSendApiServer(Handler { share }).newServer(security, 0, scope, https = false)
        val port = server.start()
        try {
            block(share, "http://127.0.0.1:$port")
        } finally {
            server.stop()
        }
    }

    private fun prepare(base: String, query: String = "") = client.newCall(
        Request.Builder()
            .url("$base/api/localsend/v2/prepare-download$query")
            .post(ByteArray(0).toRequestBody())
            .build(),
    ).execute()

    @Test
    fun prepareDownloadIsServedOnMainPort() {
        serve { share, base ->
            prepare(base).use { response ->
                assertEquals(200, response.code)
                val text = response.body.string()
                val parsed = BoxletJson.decodeFromString(PrepareDownloadResponse.serializer(), text)
                assertEquals(share.sessionId, parsed.sessionId)
                assertEquals(payload.size.toLong(), assertNotNull(parsed.files["file-1"]).size)
                // Protocol §5.2: info 不带 port/protocol，文件项不带 metadata。
                assertTrue(!text.contains("\"port\""), text)
                assertTrue(!text.contains("\"metadata\""), text)
            }
        }
    }

    @Test
    fun downloadStreamsFromMainPort() {
        serve { share, base ->
            client.newCall(
                Request.Builder()
                    .url("$base/api/localsend/v2/download?sessionId=${share.sessionId}&fileId=file-1")
                    .build(),
            ).execute().use { response ->
                assertEquals(200, response.code)
                assertTrue(payload.contentEquals(response.body.bytes()))
                assertContains(response.header("Content-Disposition").orEmpty(), "filename*=UTF-8''")
            }
        }
    }

    @Test
    fun wrongPinAndUnknownSessionAreRejected() {
        serve(pin = "1234") { share, base ->
            prepare(base, "?pin=0000").use { assertEquals(401, it.code) }

            client.newCall(
                Request.Builder()
                    .url("$base/api/localsend/v2/download?sessionId=nope&fileId=file-1&pin=1234")
                    .build(),
            ).execute().use { assertEquals(403, it.code) }

            prepare(base, "?pin=1234").use { response ->
                assertEquals(200, response.code)
                assertContains(response.body.string(), share.sessionId)
            }
        }
    }

    @Test
    fun noSessionReturns403() {
        val server = LocalSendApiServer(Handler { null }).newServer(security, 0, scope, https = false)
        val port = server.start()
        try {
            prepare("http://127.0.0.1:$port").use { assertEquals(403, it.code) }
        } finally {
            server.stop()
        }
    }
}
