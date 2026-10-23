package dev.boxlet.core

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.crypto.SecurityContext
import dev.boxlet.core.engine.OutgoingFile
import dev.boxlet.core.net.ApiClient
import dev.boxlet.core.net.DownloadOutcome
import dev.boxlet.core.net.LocalSendApiServer
import dev.boxlet.core.net.LocalSendHandler
import dev.boxlet.core.net.PrepareDownloadOutcome
import dev.boxlet.core.net.PrepareUploadOutcome
import dev.boxlet.core.net.UploadOutcome
import dev.boxlet.core.protocol.DeviceChannel
import dev.boxlet.core.protocol.DeviceType
import dev.boxlet.core.protocol.FileDto
import dev.boxlet.core.protocol.InfoResponse
import dev.boxlet.core.protocol.PrepareUploadRequest
import dev.boxlet.core.protocol.Protocol
import dev.boxlet.core.protocol.ProtocolType
import dev.boxlet.core.protocol.RegisterRequest
import dev.boxlet.core.protocol.BoxletJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.Buffer
import java.io.InputStream
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProtocolInteropTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val security = Certificates.generate()
    private val uploaded = HashMap<String, ByteArray>()
    private var lastUpload: UploadOutcome = UploadOutcome.Ok
    private var server: dev.boxlet.core.net.LocalSendHttpServer? = null
    private var port: Int = 0
    private var lastClientFingerprint: String? = null

    private val handler = object : LocalSendHandler {
        override fun localInfo(): RegisterRequest = RegisterRequest(
            alias = "Test Receiver",
            deviceModel = "JVM",
            deviceType = DeviceType.DESKTOP,
            fingerprint = security.fingerprint,
            port = port,
            protocol = ProtocolType.HTTPS,
            download = false,
        )

        override fun showToken(): String = "show-token"

        override fun webIndexHtml(): String = "<html></html>"

        override suspend fun onRegister(
            request: RegisterRequest,
            remoteIp: String,
            clientFingerprint: String?,
        ): InfoResponse {
            lastClientFingerprint = clientFingerprint
            return InfoResponse(
                alias = "Test Receiver",
                deviceModel = "JVM",
                deviceType = DeviceType.DESKTOP,
                fingerprint = security.fingerprint,
                download = false,
            )
        }

        override suspend fun onPrepareUpload(
            request: PrepareUploadRequest,
            pin: String?,
            remoteIp: String,
            clientFingerprint: String?,
        ): PrepareUploadOutcome {
            if (pin != null && pin != "1234") return PrepareUploadOutcome.InvalidPin
            val sessionId = UUID.randomUUID().toString()
            return PrepareUploadOutcome.Accepted(
                sessionId = sessionId,
                files = request.files.keys.associateWith { UUID.randomUUID().toString() },
            )
        }

        override suspend fun onUpload(
            sessionId: String,
            fileId: String,
            token: String,
            remoteIp: String,
            body: InputStream,
            contentLength: Long,
            onProgress: (Long) -> Unit,
        ): UploadOutcome {
            if (lastUpload != UploadOutcome.Ok) return lastUpload
            uploaded[fileId] = body.readBytes()
            return UploadOutcome.Ok
        }

        override suspend fun onCancel(sessionId: String?, remoteIp: String) = Unit

        override suspend fun onPrepareDownload(pin: String?, remoteIp: String): PrepareDownloadOutcome =
            PrepareDownloadOutcome.NoSession

        override suspend fun onDownload(
            sessionId: String?,
            fileId: String?,
            pin: String?,
            remoteIp: String,
        ): DownloadOutcome = DownloadOutcome.NoSession

        override fun onShow(args: List<String>) = Unit
    }

    private fun startServer() {
        server = LocalSendApiServer(handler).newServer(security, 0, scope)
        port = server!!.start()
    }

    @AfterTest
    fun tearDown() {
        server?.stop()
        scope.cancel()
    }

    @Test
    fun fingerprintIsUppercaseHex() {
        assertEquals(64, security.fingerprint.length)
        assertTrue(security.fingerprint.all { it in "0123456789ABCDEF" })
        assertEquals(security.fingerprint, security.fingerprint.uppercase())
    }

    @Test
    fun registerInfoAndUpload() {
        startServer()
        val client = ApiClient(security)
        val channel = DeviceChannel("127.0.0.1", port, https = true)

        val info = client.info(channel, security.fingerprint)
        assertTrue(info.isSuccessful, info.body + info.error)
        val infoBody = BoxletJson.decodeFromString(InfoResponse.serializer(), info.body)
        assertEquals(security.fingerprint, infoBody.fingerprint)

        val register = client.register(
            channel,
            RegisterRequest(
                alias = "Test Sender",
                deviceType = DeviceType.MOBILE,
                fingerprint = security.fingerprint,
                port = 53317,
                protocol = ProtocolType.HTTPS,
            ),
            security.fingerprint,
        )
        assertTrue(register.isSuccessful, register.body + register.error)
        assertEquals(security.fingerprint, lastClientFingerprint, "server did not receive the client certificate")

        val bytes = "hello boxlet".toByteArray()
        val file = OutgoingFile(
            id = "file-1",
            fileName = "hello.txt",
            size = bytes.size.toLong(),
            mimeType = "text/plain",
            openSource = { Buffer().write(bytes) },
        )
        val fileDto = FileDto(
            id = file.id,
            fileName = file.fileName,
            size = file.size,
            fileType = file.mimeType,
        )
        val prepare = client.prepareUpload(
            channel,
            security.fingerprint,
            PrepareUploadRequest(handler.localInfo(), mapOf(file.id to fileDto)),
            pin = null,
        )
        assertTrue(prepare.isSuccessful, prepare.body + prepare.error)
        val response = client.parsePrepareUpload(prepare)
        val token = assertNotNull(response.files[file.id])

        val upload = client.upload(
            channel = channel,
            expectedFingerprint = security.fingerprint,
            sessionId = response.sessionId,
            fileId = file.id,
            token = token,
            file = file,
            isCancelled = { false },
            onProgress = {},
        )
        assertTrue(upload.isSuccessful, upload.body + upload.error)
        assertContentEquals(bytes, uploaded[file.id])
    }

    @Test
    fun wrongFingerprintFailsHandshake() {
        startServer()
        val wrong = Certificates.generate()
        val client = ApiClient(security)
        val result = client.info(DeviceChannel("127.0.0.1", port, https = true), wrong.fingerprint)
        assertFalse(result.isSuccessful)
    }

    @Test
    fun emptyPrepareUploadReturns204() {
        startServer()
        val client = ApiClient(security)
        val channel = DeviceChannel("127.0.0.1", port, https = true)
        val result = client.prepareUpload(
            channel,
            security.fingerprint,
            PrepareUploadRequest(handler.localInfo(), emptyMap()),
            pin = null,
        )
        assertEquals(204, result.code)
    }

    @Test
    fun checksumMismatchMapsTo422() {
        startServer()
        lastUpload = UploadOutcome.ChecksumMismatch
        val client = ApiClient(security)
        val channel = DeviceChannel("127.0.0.1", port, https = true)
        val bytes = ByteArray(32) { it.toByte() }
        val file = OutgoingFile(
            id = "file-2",
            fileName = "data.bin",
            size = bytes.size.toLong(),
            mimeType = "application/octet-stream",
            openSource = { Buffer().write(bytes) },
        )
        val upload = client.upload(
            channel = channel,
            expectedFingerprint = security.fingerprint,
            sessionId = "session",
            fileId = file.id,
            token = "token",
            file = file,
            isCancelled = { false },
            onProgress = {},
        )
        assertEquals(422, upload.code)
        assertEquals(Protocol.MAX_UPLOAD_ATTEMPTS, 3)
    }
}
