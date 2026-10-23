package dev.boxlet.core.sp

import dev.boxlet.core.engine.ReceiveDestination
import dev.boxlet.core.engine.ReceiveStorage
import dev.boxlet.core.protocol.FileDto
import dev.boxlet.core.protocol.BoxletJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import okio.Buffer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * SP/1 的桥：让浏览器接入局域网，代它做设备发现与文件落地。
 *
 * 数据面是裸 binary 消息，由 `data-start` 指定归属；一次只跑一条流。
 *
 * 断点续传的粒度是「bridge 进程内」：连接断开后未完成的半截文件留在 [partials] 里，
 * 同一个文件（id + 文件名 + 大小）再次 offer 且 `offset > 0` 时，`accept.resumeFrom`
 * 告诉浏览器从哪个字节继续，bridge 直接把后续字节追加到同一个 sink 上。
 * 超过 [PARTIAL_TTL_MS] 没有动静的半截文件会被清掉（sink 也一起关掉）。
 */
class BridgeServer(
    private val scope: CoroutineScope,
    private val port: Int,
    private val pairCode: String?,
    private val devicesProvider: () -> List<PeerDevice>,
    private val storage: ReceiveStorage,
) {

    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var expiryJob: Job? = null

    /** 跨连接保留的半截文件，key 见 [partialKey]。 */
    private val partials = ConcurrentHashMap<String, OpenFile>()

    var actualPort: Int = 0
        private set

    val isRunning: Boolean get() = server != null

    fun start(): Int {
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port))
        server = socket
        actualPort = socket.localPort
        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break
                }
                client.tcpNoDelay = true
                client.sendBufferSize = 1024 * 1024
                client.receiveBufferSize = 1024 * 1024
                launch(Dispatchers.IO) { serve(client) }
            }
        }
        expiryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(EXPIRY_SWEEP_MS)
                sweepPartials()
            }
        }
        return actualPort
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        expiryJob?.cancel()
        expiryJob = null
        runCatching { server?.close() }
        server = null
        val leftovers = partials.values.toList()
        partials.clear()
        leftovers.forEach { slot ->
            scope.launch { runCatching { slot.abort() } }
        }
    }

    private fun sweepPartials() {
        val cutoff = System.currentTimeMillis() - PARTIAL_TTL_MS
        for ((key, slot) in partials) {
            if (slot.inUse || slot.lastActive >= cutoff) continue
            if (partials.remove(key, slot)) {
                scope.launch { runCatching { slot.abort() } }
            }
        }
    }

    private suspend fun serve(client: Socket) {
        val ws = WsConnection(client)
        val open = HashMap<Int, OpenFile>()
        var current: OpenFile? = null
        try {
            if (!ws.handshake()) return
            val greeting = ws.readMessage() ?: return
            if (!greeting.text) return
            val helloText = greeting.bytes.decodeToString()
            if (SpControl.typeOf(helloText) != Sp.HELLO) return
            val hello = decode(helloText, Hello.serializer())
            if (!pairCode.isNullOrBlank() && hello.pairCode != pairCode) {
                ws.writeText(text(Sp.REJECT, Reject.serializer(), Reject("pair code mismatch")))
                return
            }
            ws.writeText(text(Sp.DEVICES, DeviceList.serializer(), DeviceList(devicesProvider())))

            coroutineScope {
                val push = launch(Dispatchers.IO) { pushDeviceEvents(ws) }
                try {
                    while (true) {
                        val message = ws.readMessage() ?: break
                        if (!message.text) {
                            val entry = current ?: continue
                            entry.write(message.bytes)
                            if (entry.blocks % ACK_EVERY_BLOCKS == 0) {
                                ws.writeText(text(Sp.ACK, Ack.serializer(), Ack(entry.streamId, entry.received)))
                            }
                            continue
                        }
                        val body = message.bytes.decodeToString()
                        when (SpControl.typeOf(body)) {
                            Sp.PING -> ws.writeText(SpControl.encode(Sp.PONG, "{}"))
                            Sp.OFFER -> handleOffer(body, ws, open)
                            Sp.DATA_START -> {
                                val start = decode(body, DataStart.serializer())
                                current = open[start.streamId]
                            }
                            Sp.DATA_END -> {
                                runCatching { decode(body, DataEnd.serializer()) }
                                val entry = current
                                current = null
                                if (entry != null) finishStream(ws, entry, open)
                            }
                            Sp.CANCEL -> {
                                val cancel = runCatching { decode(body, Cancel.serializer()) }.getOrNull()
                                val entry = current ?: cancel?.streamId?.let { open[it] }
                                current = null
                                if (entry != null) {
                                    open.remove(entry.streamId)
                                    forget(entry)
                                    entry.abort()
                                }
                            }
                        }
                    }
                } finally {
                    push.cancel()
                }
            }
        } catch (error: Throwable) {
            System.err.println("[bridge] connection closed: $error")
        } finally {
            // 不 abort：半截文件按 resume 语义留给下一次连接，由 TTL 兜底清理。
            for (entry in open.values) entry.release()
            ws.close()
        }
    }

    /** 首次 `devices` 全量之后只发增量，别每 5 秒重推整个列表。 */
    private suspend fun pushDeviceEvents(ws: WsConnection) {
        var previous = devicesProvider()
        while (true) {
            delay(DEVICE_PUSH_INTERVAL_MS)
            val currentSnapshot = devicesProvider()
            for (event in diff(previous, currentSnapshot)) {
                val payload = text(Sp.DEVICE_EVENT, DeviceEvent.serializer(), event)
                if (runCatching { ws.writeText(payload) }.isFailure) return
            }
            previous = currentSnapshot
        }
    }

    private fun diff(previous: List<PeerDevice>, current: List<PeerDevice>): List<DeviceEvent> {
        val events = mutableListOf<DeviceEvent>()
        val before = previous.associateBy { it.id }
        val after = current.associateBy { it.id }
        for (device in current) {
            val old = before[device.id]
            if (old == null || old != device) events += DeviceEvent(device, removed = false)
        }
        for (device in previous) {
            if (!after.containsKey(device.id)) events += DeviceEvent(device, removed = true)
        }
        return events
    }

    private suspend fun finishStream(ws: WsConnection, entry: OpenFile, open: MutableMap<Int, OpenFile>) {
        open.remove(entry.streamId)
        if (entry.received != entry.file.size) {
            // 少收或多收都不能 commit：否则用户目录里会出现一个“成功”的坏文件。
            forget(entry)
            entry.abort()
            ws.writeText(
                text(
                    Sp.CANCEL,
                    Cancel.serializer(),
                    Cancel(entry.streamId, "size mismatch: expected ${entry.file.size}, got ${entry.received}"),
                ),
            )
            return
        }
        try {
            entry.finish()
        } catch (error: Throwable) {
            forget(entry)
            entry.abort()
            ws.writeText(text(Sp.CANCEL, Cancel.serializer(), Cancel(entry.streamId, error.message ?: "commit failed")))
            return
        }
        forget(entry)
        ws.writeText(
            text(Sp.DONE, Done.serializer(), Done(entry.streamId, entry.received, entry.file.id)),
        )
    }

    private suspend fun handleOffer(body: String, ws: WsConnection, open: MutableMap<Int, OpenFile>) {
        val offer = decode(body, Offer.serializer())
        if (offer.files.isEmpty()) {
            ws.writeText(text(Sp.REJECT, Reject.serializer(), Reject("no files")))
            return
        }
        if (offer.files.any { partials[partialKey(it)]?.inUse == true }) {
            ws.writeText(text(Sp.REJECT, Reject.serializer(), Reject("file is being received")))
            return
        }

        val sessionId = UUID.randomUUID().toString()
        val accepted = mutableListOf<String>()
        val resumeFrom = mutableMapOf<String, Long>()
        offer.files.forEachIndexed { index, file ->
            val slot = acquireSlot(file, sessionId) ?: return@forEachIndexed
            slot.streamId = index + 1
            open[slot.streamId] = slot
            accepted += file.id
            if (slot.received > 0) resumeFrom[file.id] = slot.received
        }
        if (accepted.isEmpty()) {
            ws.writeText(text(Sp.REJECT, Reject.serializer(), Reject("cannot allocate destination")))
            return
        }
        ws.writeText(
            text(Sp.ACCEPT, Accept.serializer(), Accept(accepted = accepted, resumeFrom = resumeFrom, token = sessionId)),
        )
    }

    /**
     * 复用旧的半截文件（`offset > 0` 且 key 命中），否则丢弃旧半截、从 0 开始新建。
     * 返回 null 表示创建失败，调用方跳过这个文件。
     */
    private suspend fun acquireSlot(file: OfferFile, sessionId: String): OpenFile? {
        val key = partialKey(file)
        val existing = partials[key]
        if (existing != null) {
            val canResume = file.offset > 0 && existing.received > 0 && existing.file.size == file.size
            if (canResume) {
                existing.inUse = true
                existing.touch()
                return existing
            }
            partials.remove(key, existing)
            runCatching { existing.abort() }
        }
        val destination = runCatching {
            storage.createDestination(
                sessionId,
                FileDto(id = file.id, fileName = file.name, size = file.size, fileType = file.mime),
                "browser",
            )
        }.getOrNull() ?: return null
        val slot = OpenFile(key, file, destination)
        partials[key] = slot
        return slot
    }

    /** 从共享表里移除并允许下一次 offer 重新申请。 */
    private fun forget(slot: OpenFile) {
        partials.remove(slot.key, slot)
        slot.release()
    }

    private fun partialKey(file: OfferFile): String = "${file.id}|${file.name}|${file.size}"

    private fun <T> decode(body: String, serializer: KSerializer<T>): T =
        BoxletJson.decodeFromString(serializer, body)

    private fun <T> text(type: String, serializer: KSerializer<T>, value: T): String =
        SpControl.encode(type, BoxletJson.encodeToString(serializer, value))

    private class OpenFile(
        val key: String,
        val file: OfferFile,
        private val destination: ReceiveDestination,
    ) {
        private val buffer = Buffer()
        private val sink = destination.sink

        var streamId: Int = 0

        @Volatile
        var inUse: Boolean = true

        @Volatile
        var lastActive: Long = System.currentTimeMillis()

        @Volatile
        private var aborted: Boolean = false

        var received: Long = 0
            private set

        var blocks: Int = 0
            private set

        fun write(payload: ByteArray) {
            buffer.write(payload)
            sink.write(buffer, payload.size.toLong())
            received += payload.size
            blocks++
            lastActive = System.currentTimeMillis()
        }

        fun touch() {
            lastActive = System.currentTimeMillis()
        }

        fun release() {
            inUse = false
            lastActive = System.currentTimeMillis()
        }

        suspend fun finish() {
            sink.flush()
            destination.commit()
        }

        suspend fun abort() {
            if (aborted) return
            aborted = true
            try {
                sink.close()
            } catch (_: Throwable) {
            }
            destination.abort()
        }
    }

    private companion object {
        const val DEVICE_PUSH_INTERVAL_MS = 5_000L
        const val ACK_EVERY_BLOCKS = 32

        /** 半截文件在 bridge 内存里保留多久；期间浏览器可以用 `offset` 续传。 */
        const val PARTIAL_TTL_MS = 10 * 60 * 1000L
        const val EXPIRY_SWEEP_MS = 60_000L
    }
}
