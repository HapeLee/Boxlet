package dev.boxlet.core

import dev.boxlet.core.net.ShareServer
import dev.boxlet.core.net.SharedEntry
import dev.boxlet.core.protocol.DeviceType
import dev.boxlet.core.protocol.PrepareDownloadResponse
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
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Covers the Download API (protocol §5) on the plain-HTTP share service. */
class ShareServerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val payload = "boxlet share payload\n".repeat(64).toByteArray()
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

    private fun info(): RegisterRequest = RegisterRequest(
        alias = "Test Sharer",
        deviceType = DeviceType.DESKTOP,
        fingerprint = "A".repeat(64),
        port = 53318,
        protocol = ProtocolType.HTTPS,
        download = true,
    )

    private fun start(pin: String? = null): Pair<ShareServer, Int> {
        val server = ShareServer(
            scope = scope,
            entries = listOf(entry),
            pin = pin,
            infoProvider = ::info,
        )
        return server to server.start()
    }

    private fun prepare(port: Int, query: String = "") = client.newCall(
        Request.Builder()
            .url("http://127.0.0.1:$port/api/localsend/v2/prepare-download$query")
            .post(ByteArray(0).toRequestBody())
            .build(),
    ).execute()

    @Test
    fun indexListsSharedFiles() {
        val (server, port) = start()
        try {
            val response = client.newCall(Request.Builder().url("http://127.0.0.1:$port/").build()).execute()
            val body = response.body.string()
            assertEquals(200, response.code)
            assertContains(body, "报告 final.txt")
            assertContains(body, "Test Sharer")
            assertContains(body, "/api/localsend/v2/download?sessionId=")
        } finally {
            server.stop()
        }
    }

    @Test
    fun prepareDownloadReturnsSessionAndFiles() {
        val (server, port) = start()
        try {
            val response = prepare(port)
            assertEquals(200, response.code)
            val text = response.body.string()
            val parsed = BoxletJson.decodeFromString(PrepareDownloadResponse.serializer(), text)
            assertEquals(server.sessionId, parsed.sessionId)
            val file = assertNotNull(parsed.files["file-1"])
            assertEquals(payload.size.toLong(), file.size)
            assertEquals("text/plain", file.fileType)
            // Protocol §5.2: no port/protocol in `info`, no `metadata` on files.
            assertTrue(!text.contains("\"port\""), text)
            assertTrue(!text.contains("\"metadata\""), text)
            assertEquals(null, file.metadata)
        } finally {
            server.stop()
        }
    }

    @Test
    fun downloadStreamsExactBytes() {
        val (server, port) = start()
        try {
            val url = "http://127.0.0.1:$port/api/localsend/v2/download" +
                "?sessionId=${server.sessionId}&fileId=file-1"
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            assertEquals(200, response.code)
            val bytes = response.body.bytes()
            assertEquals(payload.size, bytes.size)
            assertTrue(payload.contentEquals(bytes))
            assertContains(response.header("Content-Disposition").orEmpty(), "filename*=UTF-8''")
        } finally {
            server.stop()
        }
    }

    @Test
    fun wrongPinIsRejected() {
        val (server, port) = start(pin = "1234")
        try {
            assertEquals(401, prepare(port).code)

            val download = client.newCall(
                Request.Builder()
                    .url("http://127.0.0.1:$port/api/localsend/v2/download?sessionId=x&fileId=file-1&pin=0000")
                    .build(),
            ).execute()
            assertEquals(401, download.code)
            download.close()

            val ok = client.newCall(
                Request.Builder()
                    .url(
                        "http://127.0.0.1:$port/api/localsend/v2/download" +
                            "?sessionId=${server.sessionId}&fileId=file-1&pin=1234",
                    )
                    .build(),
            ).execute()
            assertEquals(200, ok.code)
            ok.close()
        } finally {
            server.stop()
        }
    }
}
