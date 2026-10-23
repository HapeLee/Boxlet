package dev.boxlet.engine

import dev.boxlet.core.protocol.DeviceType
import dev.boxlet.core.protocol.Protocol
import dev.boxlet.core.protocol.ProtocolType
import dev.boxlet.core.protocol.BoxletJson
import dev.boxlet.shared.BoxletApp
import dev.boxlet.shared.Settings
import dev.boxlet.shared.UiState
import dev.boxlet.shared.repository.FavoriteDevice
import dev.boxlet.shared.storage.DefaultDownloadDir
import dev.boxlet.shared.storage.FileSystemReceiveStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicBoolean
import okio.source
import kotlin.time.Instant

/**
 * Headless engine process.
 *
 * Native desktop shells (WinUI, Avalonia, Tauri, ...) can own the entire UI and use this
 * process as the network engine. The contract is newline-delimited JSON on stdin/stdout,
 * documented in docs/ENGINE-IPC.md.
 */
fun main(args: Array<String>) {
    val desktop = "--desktop" in args
    val dataDir = parseDataDir(args)
    dataDir.mkdirs()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val running = AtomicBoolean(true)

    lateinit var app: BoxletApp
    val storage = FileSystemReceiveStorage {
        val configured = app.currentSettings.destination
        if (configured != null) File(configured)
        else if (desktop) DefaultDownloadDir.resolve()
        else File(dataDir, "received")
    }
    app = BoxletApp(
        scope = scope,
        dataDir = dataDir,
        storage = storage,
        defaults = Settings(
            deviceType = if (desktop) DeviceType.DESKTOP else DeviceType.HEADLESS,
            deviceModel = "${System.getProperty("os.name")} ${System.getProperty("os.version")}".trim(),
        ),
    )

    // 管道协议固定 UTF-8；Windows System.out 的本机代码页可能不同于 file.encoding。
    val writer = PrintWriter(OutputStreamWriter(System.out, Charsets.UTF_8), true)
    val writeLock = Any()

    fun emit(payload: String) {
        synchronized(writeLock) {
            writer.println(payload)
            writer.flush()
        }
    }

    scope.launch(Dispatchers.IO) {
        app.state.collect { state ->
            emit(buildJsonObject {
                put("event", "state")
                put("data", stateToJson(state))
            }.toString())
            // StateFlow conflates new values while suspended, giving us a natural throttle.
            delay(100)
        }
    }

    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    while (running.get()) {
        val line = reader.readLine()?.removePrefix("\uFEFF") ?: break
        if (line.isBlank()) continue
        val request = runCatching { BoxletJson.parseToJsonElement(line).jsonObject }.getOrNull()
        if (request == null) {
            emit(errorResponse(null, "Invalid JSON"))
            continue
        }
        val id = request["id"]?.jsonPrimitive?.contentOrNull
        val command = request["command"]?.jsonPrimitive?.contentOrNull
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        if (command == null) {
            emit(errorResponse(id, "Missing command"))
            continue
        }

        runCatching {
            when (command) {
                "status" -> emit(okResponse(id, buildJsonObject {
                    put("alias", app.currentSettings.alias)
                    put("port", app.currentSettings.port)
                    put("https", app.currentSettings.https)
                    put("serverRunning", app.state.value.serverRunning)
                    put("fingerprint", app.state.value.fingerprint)
                    put("protocol", Protocol.VERSION)
                    put("deviceCount", app.state.value.devices.size)
                }))

                "devices.scan" -> {
                    app.scan()
                    emit(okResponse(id, buildJsonObject { put("started", true) }))
                }

                "send.files" -> {
                    val fingerprint = params["fingerprint"]?.jsonPrimitive?.contentOrNull
                        ?: error("fingerprint is required")
                    val files = (params["files"] as? JsonArray)?.mapNotNull { element ->
                        val obj = element as? JsonObject ?: return@mapNotNull null
                        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        File(path).takeIf { it.isFile }?.let { file ->
                            file.toOutgoingFile(obj["name"]?.jsonPrimitive?.contentOrNull)
                        }
                    }.orEmpty()
                    if (files.isEmpty()) error("no readable files")
                    app.sendFiles(fingerprint, files)
                    emit(okResponse(id, buildJsonObject { put("accepted", files.size) }))
                }

                "send.text" -> {
                    val fingerprint = params["fingerprint"]?.jsonPrimitive?.contentOrNull
                        ?: error("fingerprint is required")
                    val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
                    app.sendText(fingerprint, text)
                    emit(okResponse(id, buildJsonObject { put("accepted", true) }))
                }

                "share.start" -> {
                    val files = (params["files"] as? JsonArray)?.mapNotNull { element ->
                        val obj = element as? JsonObject ?: return@mapNotNull null
                        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        File(path).takeIf { it.isFile }?.let { file ->
                            file.toOutgoingFile(obj["name"]?.jsonPrimitive?.contentOrNull)
                        }
                    }.orEmpty()
                    if (files.isEmpty()) error("no readable files")
                    val share = app.shareFiles(files)
                        ?: error(app.state.value.error ?: "cannot start sharing")
                    emit(okResponse(id, buildJsonObject {
                        put("url", share.url)
                        put("sessionId", share.sessionId)
                        put("fileCount", share.fileCount)
                        put("pinRequired", share.pinRequired)
                    }))
                }

                "bridge.start" -> {
                    val port = params["port"]?.jsonPrimitive?.intOrNull ?: 0
                    val code = params["pairCode"]?.jsonPrimitive?.contentOrNull
                    val bridge = app.startBridge(port, code)
                        ?: error(app.state.value.error ?: "cannot start bridge")
                    emit(okResponse(id, buildJsonObject {
                        put("url", bridge.url)
                        put("port", bridge.port)
                        put("pairCodeRequired", bridge.pairCodeRequired)
                    }))
                }

                "bridge.stop" -> {
                    app.stopBridge()
                    emit(okResponse(id, buildJsonObject { put("stopped", true) }))
                }

                "share.stop" -> {
                    app.stopShare()
                    emit(okResponse(id, buildJsonObject { put("stopped", true) }))
                }

                "receive.accept" -> {
                    val ids = (params["fileIds"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
                    if (ids.isNullOrEmpty()) app.acceptAllReceive() else app.acceptReceive(ids)
                    emit(okResponse(id, buildJsonObject { put("accepted", true) }))
                }

                "receive.reject" -> {
                    app.rejectReceive()
                    emit(okResponse(id, buildJsonObject { put("rejected", true) }))
                }

                "transfer.cancel" -> {
                    when (params["direction"]?.jsonPrimitive?.contentOrNull) {
                        "receive" -> app.cancelReceive()
                        else -> app.cancelSend()
                    }
                    emit(okResponse(id, buildJsonObject { put("cancelled", true) }))
                }

                "transfer.pin" -> {
                    val pin = params["pin"]?.jsonPrimitive?.contentOrNull ?: error("pin is required")
                    app.submitPin(pin)
                    emit(okResponse(id, buildJsonObject { put("submitted", true) }))
                }

                "settings.get" -> emit(okResponse(id, BoxletJson.encodeToJsonElement(Settings.serializer(), app.currentSettings)))

                "settings.set" -> {
                    val current = BoxletJson.encodeToJsonElement(Settings.serializer(), app.currentSettings).jsonObject
                    val merged = JsonObject(current + params)
                    val next = BoxletJson.decodeFromJsonElement(Settings.serializer(), merged)
                    app.updateSettings { next.copy(showToken = current["showToken"]?.jsonPrimitive?.contentOrNull ?: next.showToken) }
                    emit(okResponse(id, buildJsonObject { put("updated", true) }))
                }

                "favorites.list" -> emit(okResponse(id, buildJsonArray {
                    app.currentFavorites.forEach { favorite ->
                        add(buildJsonObject {
                            put("fingerprint", favorite.fingerprint)
                            put("alias", favorite.alias)
                            put("host", favorite.host)
                            put("port", favorite.port)
                            put("https", favorite.https)
                        })
                    }
                }))

                "favorites.add" -> {
                    val favorite = FavoriteDevice(
                        fingerprint = params["fingerprint"]?.jsonPrimitive?.contentOrNull ?: error("fingerprint is required"),
                        alias = params["alias"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        host = params["host"]?.jsonPrimitive?.contentOrNull ?: error("host is required"),
                        port = params["port"]?.jsonPrimitive?.longOrNull?.toInt() ?: Protocol.DEFAULT_PORT,
                        https = params["https"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
                    )
                    val device = app.state.value.devices.firstOrNull { it.fingerprint == favorite.fingerprint }
                    if (device == null) {
                        error("device is not currently known; scan first")
                    }
                    app.toggleFavorite(device)
                    emit(okResponse(id, buildJsonObject { put("added", true) }))
                }

                "favorites.remove" -> {
                    val fingerprint = params["fingerprint"]?.jsonPrimitive?.contentOrNull
                        ?: error("fingerprint is required")
                    app.removeFavorite(fingerprint)
                    emit(okResponse(id, buildJsonObject { put("removed", true) }))
                }

                "history.list" -> emit(okResponse(id, buildJsonArray {
                    app.currentHistory.forEach { entry ->
                        add(buildJsonObject {
                            put("id", entry.id)
                            put("fileName", entry.fileName)
                            put("fileType", entry.fileType)
                            put("path", entry.path)
                            put("isMessage", entry.isMessage)
                            put("fileSize", entry.fileSize)
                            put("senderAlias", entry.senderAlias)
                            put("timestamp", entry.timestamp)
                        })
                    }
                }))

                "history.clear" -> {
                    app.clearHistory()
                    emit(okResponse(id, buildJsonObject { put("cleared", true) }))
                }

                "shutdown" -> {
                    emit(okResponse(id, buildJsonObject { put("bye", true) }))
                    running.set(false)
                }

                else -> emit(errorResponse(id, "Unknown command: $command"))
            }
        }.onFailure { error ->
            emit(errorResponse(id, error.message ?: error.javaClass.simpleName))
        }

        if (!running.get()) break
    }

    app.engine.stop()
    scope.cancel()
    writer.flush()
    kotlin.system.exitProcess(0)
}

private fun File.toOutgoingFile(nameOverride: String?): dev.boxlet.core.engine.OutgoingFile {
    val mime = java.net.URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    return dev.boxlet.core.engine.OutgoingFile(
        id = java.util.UUID.randomUUID().toString(),
        fileName = nameOverride ?: name,
        size = length(),
        mimeType = mime,
        modified = Instant.fromEpochMilliseconds(lastModified()),
        openSource = { source() },
    )
}

private fun parseDataDir(args: Array<String>): File {
    val index = args.indexOf("--data-dir")
    if (index >= 0 && index + 1 < args.size) return File(args[index + 1]).absoluteFile
    return File("boxlet-data").absoluteFile
}

private fun okResponse(id: String?, result: kotlinx.serialization.json.JsonElement): String =
    buildJsonObject {
        if (id != null) put("id", id)
        put("ok", true)
        put("result", result)
    }.toString()

private fun errorResponse(id: String?, message: String): String =
    buildJsonObject {
        if (id != null) put("id", id)
        put("ok", false)
        putJsonObject("error") { put("message", message) }
    }.toString()

private fun stateToJson(state: UiState): JsonObject = buildJsonObject {
    put("ready", state.ready)
    put("serverRunning", state.serverRunning)
    put("alias", state.settings.alias)
    put("port", state.settings.port)
    put("https", state.settings.https)
    put("fingerprint", state.fingerprint)
    put("error", state.error)
    state.bridge?.let { bridge ->
        putJsonObject("bridge") {
            put("url", bridge.url)
            put("port", bridge.port)
            put("pairCodeRequired", bridge.pairCodeRequired)
        }
    }
    state.share?.let { share ->
        putJsonObject("share") {
            put("url", share.url)
            put("sessionId", share.sessionId)
            put("fileCount", share.fileCount)
            put("totalBytes", share.totalBytes)
            put("pinRequired", share.pinRequired)
        }
    }
    putJsonArray("devices") {
        state.devices.forEach { device ->
            add(buildJsonObject {
                put("fingerprint", device.fingerprint)
                put("alias", device.alias)
                put("deviceType", device.deviceType.name.lowercase())
                put("deviceModel", device.deviceModel)
                put("lastSeen", device.lastSeen)
                putJsonArray("channels") {
                    device.channels.forEach { channel ->
                        add(buildJsonObject {
                            put("host", channel.host)
                            put("port", channel.port)
                            put("https", channel.https)
                        })
                    }
                }
            })
        }
    }
    state.pendingReceive?.let { pending ->
        putJsonObject("pendingReceive") {
            put("sessionId", pending.sessionId)
            put("peer", pending.peer.alias)
            put("isMessage", pending.isMessage)
            put("message", pending.message)
            putJsonArray("files") {
                pending.files.forEach { file ->
                    add(buildJsonObject {
                        put("id", file.id)
                        put("fileName", file.fileName)
                        put("size", file.size)
                        put("fileType", file.fileType)
                    })
                }
            }
        }
    }
    state.outgoing?.let { outgoing ->
        putJsonObject("outgoing") {
            put("sessionId", outgoing.sessionId)
            put("peer", outgoing.peer.alias)
            put("status", outgoing.status.name.lowercase())
            put("progress", outgoing.progress)
            put("transferredBytes", outgoing.transferredBytes)
            put("totalBytes", outgoing.totalBytes)
            put("error", outgoing.error)
        }
    }
    state.incoming?.let { incoming ->
        putJsonObject("incoming") {
            put("sessionId", incoming.sessionId)
            put("peer", incoming.peer.alias)
            put("status", incoming.status.name.lowercase())
            put("progress", incoming.progress)
            put("transferredBytes", incoming.transferredBytes)
            put("totalBytes", incoming.totalBytes)
            put("error", incoming.error)
        }
    }
}
