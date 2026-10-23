package dev.boxlet.core.sp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/** A complete WebSocket message (fragments reassembled). */
class WsMessage(val text: Boolean, val bytes: ByteArray)

/**
 * 最小可用的 WebSocket 服务端连接（RFC 6455）。
 *
 * 只做我们用到的那部分：文本/二进制消息、continuation 重组、ping/pong、close。
 * 不接受分片发送（我们每帧就是一个完整消息，一次 write 出去）。
 */
class WsConnection(private val socket: Socket) {

    private val input: InputStream = BufferedInputStream(socket.getInputStream(), 256 * 1024)
    private val output: OutputStream = BufferedOutputStream(socket.getOutputStream(), 256 * 1024)
    private val writeLock = Any()

    @Volatile
    private var closed = false

    /** Reads the HTTP upgrade request and answers 101. Returns false if this is not a WS upgrade. */
    fun handshake(): Boolean {
        val requestLine = readHttpLine() ?: return false
        if (!requestLine.startsWith("GET ")) return false
        var key: String? = null
        while (true) {
            val line = readHttpLine() ?: return false
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            if (name == "sec-websocket-key") key = line.substring(colon + 1).trim()
        }
        val clientKey = key ?: return false
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((clientKey + WS_GUID).toByteArray(Charsets.ISO_8859_1)),
        )
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(response.toByteArray(Charsets.ISO_8859_1))
        output.flush()
        return true
    }

    /** Returns the next complete message, or null when the peer closed. */
    fun readMessage(): WsMessage? {
        var firstOpcode = -1
        // 分片消息才需要累积缓冲；单帧（1 MiB 的 DATA 帧都是单帧）直接返回，省两次拷贝
        var message: ByteArrayOutputStream? = null
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return null
            val b1 = input.read()
            if (b1 < 0) throw EOFException("truncated websocket frame")
            val fin = (b0 and 0x80) != 0
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) {
                length = ((input.read() shl 8) or input.read()).toLong()
            } else if (length == 127L) {
                var value = 0L
                repeat(8) { value = (value shl 8) or input.read().toLong() }
                length = value
            }
            require(length in 0..MAX_MESSAGE) { "websocket message too large: $length" }
            val mask = if (masked) ByteArray(4).also { readFully(it) } else null
            val payload = ByteArray(length.toInt())
            readFully(payload)
            if (mask != null) unmask(payload, mask)
            when (opcode) {
                OP_CLOSE -> {
                    close()
                    return null
                }
                OP_PING -> {
                    writeFrame(OP_PONG, payload, 0, payload.size)
                    continue
                }
                OP_PONG -> continue
                OP_BINARY, OP_TEXT, OP_CONTINUATION -> {
                    if (firstOpcode < 0) firstOpcode = opcode
                    if (fin && message == null) {
                        return WsMessage(text = firstOpcode == OP_TEXT, bytes = payload)
                    }
                    val accumulator = message ?: ByteArrayOutputStream(128 * 1024).also { message = it }
                    accumulator.write(payload)
                }
                else -> continue
            }
            if (fin) {
                return WsMessage(
                    text = firstOpcode == OP_TEXT,
                    bytes = message?.toByteArray() ?: ByteArray(0),
                )
            }
        }
    }

    /** RFC 6455 客户端掩码。按 4 字节展开，避免逐字节取模。 */
    private fun unmask(payload: ByteArray, mask: ByteArray) {
        val m0 = mask[0].toInt() and 0xFF
        val m1 = mask[1].toInt() and 0xFF
        val m2 = mask[2].toInt() and 0xFF
        val m3 = mask[3].toInt() and 0xFF
        val size = payload.size
        var index = 0
        while (index + 4 <= size) {
            payload[index] = (payload[index].toInt() xor m0).toByte()
            payload[index + 1] = (payload[index + 1].toInt() xor m1).toByte()
            payload[index + 2] = (payload[index + 2].toInt() xor m2).toByte()
            payload[index + 3] = (payload[index + 3].toInt() xor m3).toByte()
            index += 4
        }
        while (index < size) {
            payload[index] = (payload[index].toInt() xor (mask[index and 3].toInt() and 0xFF)).toByte()
            index++
        }
    }

    /** Sends one binary message. Payload is written in place, no copy. */
    fun writeBinary(payload: ByteArray, offset: Int, length: Int) {
        writeFrame(OP_BINARY, payload, offset, length)
    }

    fun writeBinary(payload: ByteArray) = writeBinary(payload, 0, payload.size)

    fun writeText(text: String) {
        val bytes = text.encodeToByteArray()
        writeFrame(OP_TEXT, bytes, 0, bytes.size)
    }

    private fun writeFrame(opcode: Int, payload: ByteArray, offset: Int, length: Int) {
        synchronized(writeLock) {
            if (closed) return
            val header = ByteArray(
                when {
                    length < 126 -> 2
                    length <= 0xFFFF -> 4
                    else -> 10
                },
            )
            header[0] = (0x80 or opcode).toByte()
            when {
                length < 126 -> header[1] = length.toByte()
                length <= 0xFFFF -> {
                    header[1] = 126
                    header[2] = (length shr 8).toByte()
                    header[3] = length.toByte()
                }
                else -> {
                    header[1] = 127
                    val value = length.toLong()
                    for (index in 0 until 8) {
                        header[2 + index] = (value shr (8 * (7 - index))).toByte()
                    }
                }
            }
            output.write(header)
            if (length > 0) output.write(payload, offset, length)
            output.flush()
        }
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
    }

    private fun readHttpLine(): String? {
        val line = ByteArrayOutputStream(128)
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == '\n'.code) break
            if (byte != '\r'.code) line.write(byte)
            if (line.size() > 8192) return null
        }
        return line.toString(Charsets.ISO_8859_1.name())
    }

    private fun readFully(target: ByteArray) {
        var read = 0
        while (read < target.size) {
            val count = input.read(target, read, target.size - read)
            if (count < 0) throw EOFException("unexpected end of websocket stream")
            read += count
        }
    }

    private companion object {
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val MAX_MESSAGE = 8 * 1024 * 1024
        const val OP_CONTINUATION = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
    }
}
