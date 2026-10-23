package dev.boxlet.web

import dev.boxlet.core.protocol.BoxletJson
import dev.boxlet.core.sp.Accept
import dev.boxlet.core.sp.DataEnd
import dev.boxlet.core.sp.DataStart
import dev.boxlet.core.sp.DeviceEvent
import dev.boxlet.core.sp.DeviceList
import dev.boxlet.core.sp.Done
import dev.boxlet.core.sp.Hello
import dev.boxlet.core.sp.Offer
import dev.boxlet.core.sp.OfferFile
import dev.boxlet.core.sp.PeerDevice
import dev.boxlet.core.sp.Reject
import dev.boxlet.core.sp.Sp
import dev.boxlet.core.sp.SpControl
import kotlinx.browser.window
import org.w3c.dom.MessageEvent
import org.w3c.dom.WebSocket
import org.w3c.files.File
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.TimeSource

/**
 * 浏览器侧的 SP/1 客户端。
 *
 * 数据面直接 `send(File.slice())`，不把文件读进 Kotlin——wasm 里那次转换纯属浪费。
 *
 * 断线续传：每个文件已发出的字节数记在 [sentByFile] 里，连接意外断开时自动重连并重新
 * `offer`（带上 `offset`）。真正的续传起点以 bridge 回的 `accept.resumeFrom` 为准：
 * bridge 没有半截文件时会回 0（或省略），此时必须从 0 重发，不能相信本地进度。
 */
@OptIn(ExperimentalWasmJsInterop::class)
class SpClient(
    private val url: String,
    private val pairCode: String?,
    private val onReady: () -> Unit,
    private val onDevices: (List<PeerDevice>) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onClosed: () -> Unit = {},
    /** 已交给 socket 的字节数 / 总字节数 / 平均速度。 */
    private val onProgress: (sent: Long, total: Long, bytesPerSecond: Long) -> Unit = { _, _, _ -> },
) {
    private var socket: WebSocket? = null
    private var closing = false
    private var resumeAttempts = 0

    private var devices: List<PeerDevice> = emptyList()

    private var onAccepted: ((Accept) -> Unit)? = null
    private var onDone: ((Done) -> Unit)? = null

    /** 非 null 表示有一次进行中的发送；断线后据此自动重连。 */
    private var pendingFiles: List<File>? = null
    private val sentByFile = mutableMapOf<String, Long>()
    private val completedFiles = mutableSetOf<String>()
    private var currentStreamId = 0
    private var progressClock = TimeSource.Monotonic.markNow()
    private var lastReportMs = 0L

    init {
        connect()
    }

    fun close() {
        closing = true
        pendingFiles = null
        onAccepted = null
        onDone = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun connect() {
        val ws = WebSocket(url)
        socket = ws
        ws.onopen = {
            if (socket === ws) {
                sendControl(
                    Sp.HELLO,
                    BoxletJson.encodeToString(
                        Hello.serializer(),
                        Hello(alias = "Boxlet Web", deviceType = "web", version = 1, pairCode = pairCode),
                    ),
                )
                onReady()
                // 重连后重新 offer，让 bridge 按它手上的半截文件回 resumeFrom。
                if (pendingFiles != null) {
                    onStatus("已重连，继续传输…")
                    offer(sentByFile.toMap())
                }
            }
        }
        ws.onmessage = { event ->
            if (socket === ws) handleMessage(event)
        }
        ws.onerror = {
            if (socket === ws) onStatus("连接失败：无法连到 bridge")
        }
        ws.onclose = {
            if (socket === ws && !closing) {
                onClosed()
                maybeResume()
            }
        }
    }

    /** 连接断了但还有没传完的文件：重连一次，让 bridge 决定从哪继续。 */
    private fun maybeResume() {
        val files = pendingFiles
        val allDone = files != null && files.withIndex().all { (index, _) ->
            completedFiles.contains((index + 1).toString())
        }
        if (files == null || files.isEmpty() || allDone) {
            pendingFiles = null
            return
        }
        if (resumeAttempts >= MAX_RESUME_ATTEMPTS) {
            onStatus("连接已断开，续传重试次数用尽")
            pendingFiles = null
            return
        }
        resumeAttempts++
        onStatus("连接断开，正在从断点续传（${resumeAttempts}/${MAX_RESUME_ATTEMPTS}）…")
        window.setTimeout({
            connect()
            null
        }, RECONNECT_DELAY_MS)
    }

    private fun handleMessage(event: MessageEvent) {
        val data = event.data ?: return
        // binary 时 data 是 ArrayBuffer，toString 出来是 "[object ArrayBuffer]"
        val text = data.toString()
        if (!text.startsWith("{")) return
        when (SpControl.typeOf(text)) {
            Sp.DEVICES -> {
                devices = BoxletJson.decodeFromString(DeviceList.serializer(), text).devices
                onDevices(devices)
            }
            Sp.DEVICE_EVENT -> {
                val event = BoxletJson.decodeFromString(DeviceEvent.serializer(), text)
                devices = applyDeviceEvent(devices, event)
                onDevices(devices)
            }
            Sp.ACCEPT -> onAccepted?.invoke(BoxletJson.decodeFromString(Accept.serializer(), text))
            Sp.DONE -> onDone?.invoke(BoxletJson.decodeFromString(Done.serializer(), text))
            Sp.REJECT -> {
                val reject = BoxletJson.decodeFromString(Reject.serializer(), text)
                onStatus("对方拒绝：${reject.reason}")
            }
        }
    }

    private fun applyDeviceEvent(current: List<PeerDevice>, event: DeviceEvent): List<PeerDevice> {
        val rest = current.filterNot { it.id == event.device.id }
        return if (event.removed) rest else (rest + event.device).sortedBy { it.alias }
    }

    private fun sendControl(type: String, json: String) {
        val payload = SpControl.encode(type, json)
        socket?.send(payload)
    }

    /** 顺序发送：[onFinished] 在所有文件结束后回调。 */
    fun sendFiles(files: List<File>) {
        if (files.isEmpty()) return
        pendingFiles = files
        completedFiles.clear()
        sentByFile.clear()
        resumeAttempts = 0
        onStatus("已发送请求，等待对方接受…")
        offer(offsets = emptyMap())
    }

    private fun offer(offsets: Map<String, Long>) {
        val files = pendingFiles ?: return
        val offer = Offer(
            // 续传重连时已经传完的文件不再 offer，避免 bridge 给它们留空的半截槽位。
            files = files.withIndex()
                .filter { (index, _) -> !completedFiles.contains((index + 1).toString()) }
                .map { (index, file) ->
                    val id = (index + 1).toString()
                    OfferFile(
                        id = id,
                        name = file.name,
                        size = file.size.toDouble().toLong(),
                        mime = file.type.ifBlank { "application/octet-stream" },
                        offset = offsets[id] ?: 0L,
                    )
                },
        )
        onAccepted = { accept -> startTransfer(accept) }
        onDone = null
        sendControl(Sp.OFFER, BoxletJson.encodeToString(Offer.serializer(), offer))
    }

    private fun startTransfer(accept: Accept) {
        onAccepted = null
        val files = pendingFiles ?: return
        progressClock = TimeSource.Monotonic.markNow()
        lastReportMs = 0L
        val accepted = files.withIndex()
            .filter { (index, _) -> accept.accepted.contains((index + 1).toString()) }
        if (accepted.isEmpty()) {
            onStatus("对方没有接受任何文件")
            pendingFiles = null
            return
        }
        val totalBytes = accepted.sumOf { (_, file) -> file.size.toDouble().toLong() }

        var position = 0

        fun report(force: Boolean = false) {
            val elapsedMs = progressClock.elapsedNow().inWholeMilliseconds
            if (!force && elapsedMs - lastReportMs < PROGRESS_INTERVAL_MS) return
            lastReportMs = elapsedMs
            val sent = sentByFile.values.sum()
            val rate = if (elapsedMs > 0) sent * 1000 / elapsedMs else 0L
            onProgress(sent, totalBytes, rate)
        }

        fun next() {
            if (position >= files.size) {
                report(force = true)
                onDone = null
                onStatus("已发送 ${completedFiles.size}/${files.size} 个文件")
                pendingFiles = null
                return
            }
            val id = (position + 1).toString()
            if (!accept.accepted.contains(id) || completedFiles.contains(id)) {
                position++
                next()
                return
            }
            val file = files[position]
            val streamId = position + 1
            currentStreamId = streamId
            // 续传起点只信 bridge：它没有半截文件时会回 0，此时必须从头发。
            val startAt = accept.resumeFrom[id] ?: 0L
            sentByFile[id] = startAt
            report(force = true)

            onDone = { done ->
                completedFiles.add(id)
                sentByFile[id] = done.bytes
                position++
                report(force = true)
                next()
            }
            sendControl(Sp.DATA_START, BoxletJson.encodeToString(DataStart.serializer(), DataStart(streamId, id)))
            streamFile(file, startAt.toInt(), onChunk = { bytes ->
                sentByFile[id] = (sentByFile[id] ?: 0L) + bytes
                report()
            }) {
                sendControl(
                    Sp.DATA_END,
                    BoxletJson.encodeToString(
                        DataEnd.serializer(),
                        DataEnd(currentStreamId, file.size.toDouble().toLong()),
                    ),
                )
            }
        }
        next()
    }

    /**
     * 按 `bufferedAmount` 水位切片发送，一次事件循环里能塞多少塞多少。
     *
     * 之前每块都 `setTimeout(0)`，浏览器嵌套定时器最小 4ms，1 MiB/4ms 就把速度锁死在
     * ~250 MiB/s，后台标签页更是掉到 1s 级。现在只在发送窗口满时才让出主线程，
     * 窗口大小对齐 ADR 0005 的 8 MiB。
     */
    private fun streamFile(
        file: File,
        offset: Int,
        onChunk: (Int) -> Unit,
        onComplete: () -> Unit,
    ) {
        val ws = socket ?: return
        val total = file.size.toInt()
        var sent = offset
        fun pump() {
            if (socket !== ws) return
            while (sent < total && ws.bufferedAmount.toDouble() < WINDOW.toDouble()) {
                val end = minOf(sent + CHUNK, total)
                ws.send(file.slice(sent, end))
                onChunk(end - sent)
                sent = end
            }
            if (sent >= total) {
                onComplete()
                return
            }
            // 窗口满了，等浏览器把已排队的数据刷出去再续
            window.setTimeout({
                pump()
                null
            }, 0)
        }
        pump()
    }

    private companion object {
        /** 浏览器端受 Int 索引限制，单个文件上限 2 GiB。 */
        const val CHUNK = 1024 * 1024

        /** 未确认数据的发送窗口，和 ADR 0005 的默认值一致。 */
        const val WINDOW = 8 * 1024 * 1024

        /** 进度回调节流，别让 UI 每块都重组一次。 */
        const val PROGRESS_INTERVAL_MS = 200L

        const val MAX_RESUME_ATTEMPTS = 3
        const val RECONNECT_DELAY_MS = 800
    }
}
