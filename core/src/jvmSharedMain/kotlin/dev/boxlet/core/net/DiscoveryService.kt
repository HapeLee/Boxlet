package dev.boxlet.core.net

import dev.boxlet.core.protocol.MulticastMessage
import dev.boxlet.core.protocol.Protocol
import dev.boxlet.core.protocol.BoxletJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketAddress

data class DiscoveryConfig(
    val port: Int,
    val multicastGroup: String,
    val alias: String,
    val deviceModel: String?,
    val deviceType: dev.boxlet.core.protocol.DeviceType,
    val protocolType: dev.boxlet.core.protocol.ProtocolType,
    val fingerprint: String,
    val download: Boolean,
    val whitelist: List<String>,
    val blacklist: List<String>,
    /** UDP discovery can use the official port while advertising a different HTTP server port. */
    val discoveryPort: Int = port,
)

/**
 * IPv4 UDP multicast discovery. Sends announces only; peers answer with an HTTP register call.
 */
class DiscoveryService(
    private val scope: CoroutineScope,
    private val configProvider: () -> DiscoveryConfig,
    private val onAnnounce: (String, MulticastMessage) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) {
    private var socket: MulticastSocket? = null
    private var receiveJob: Job? = null
    private var announceJob: Job? = null
    private val joined = mutableListOf<Pair<NetworkInterface, InetAddress>>()

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        val config = configProvider()
        val multicastSocket = MulticastSocket(null as SocketAddress?)
        try {
            multicastSocket.reuseAddress = true
            multicastSocket.bind(InetSocketAddress(config.discoveryPort))
            multicastSocket.timeToLive = 1
            @Suppress("DEPRECATION")
            multicastSocket.loopbackMode = true

            val group = InetAddress.getByName(config.multicastGroup)
            val interfaces = NetworkInterfaces.activeInterfaces(config.whitelist, config.blacklist)
            for (iface in interfaces) {
                runCatching {
                    multicastSocket.joinGroup(InetSocketAddress(group, config.discoveryPort), iface)
                    joined += iface to group
                }
            }

            socket = multicastSocket
            running = true

            receiveJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(64 * 1024)
                var errors = 0
                while (isActive && running) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        multicastSocket.receive(packet)
                        errors = 0
                        val host = packet.address?.hostAddress?.substringBefore('%') ?: continue
                        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        val message = runCatching {
                            BoxletJson.decodeFromString(MulticastMessage.serializer(), text)
                        }.getOrNull() ?: continue
                        if (message.fingerprint.equals(configProvider().fingerprint, ignoreCase = true)) continue
                        onAnnounce(host, message)
                    } catch (error: SocketException) {
                        if (!running || multicastSocket.isClosed) break
                        errors++
                        if (errors >= 10) {
                            onFailure(error)
                            break
                        }
                    } catch (_: Exception) {
                        // Malformed datagrams are ignored; keep listening.
                    }
                }
            }

            announceJob = scope.launch(Dispatchers.IO) {
                while (isActive && running) {
                    announceBurst()
                    delay(Protocol.ANNOUNCE_INTERVAL_MS)
                }
            }
        } catch (error: Throwable) {
            multicastSocket.close()
            socket = null
            joined.clear()
            running = false
            throw error
        }
    }

    fun announceNow() {
        scope.launch(Dispatchers.IO) { announceBurst() }
    }

    private suspend fun announceBurst() {
        for (delayMs in Protocol.ANNOUNCE_DELAYS_MS) {
            delay(delayMs)
            broadcast(announce = true)
        }
    }

    /**
     * 回退应答：对端的 HTTP 打不通时，用 `announce = false` 的多播报文告诉它「我在这里」，
     * 让它反过来向我发起 register。见 ADR 0002。
     */
    fun announceFallback() {
        scope.launch(Dispatchers.IO) { broadcast(announce = false) }
    }

    private fun broadcast(announce: Boolean) {
        val config = configProvider()
        val message = MulticastMessage(
            alias = config.alias,
            deviceModel = config.deviceModel,
            deviceType = config.deviceType,
            fingerprint = config.fingerprint,
            port = config.port,
            protocol = config.protocolType,
            download = config.download,
            announce = announce,
        )
        val payload = BoxletJson.encodeToString(MulticastMessage.serializer(), message).encodeToByteArray()
        val group = runCatching { InetAddress.getByName(config.multicastGroup) }.getOrNull() ?: return
        val interfaces = NetworkInterfaces.activeInterfaces(config.whitelist, config.blacklist)
        for (iface in interfaces) {
            send(payload, group, config.discoveryPort, iface)
        }
    }

    private fun send(payload: ByteArray, group: InetAddress, port: Int, iface: NetworkInterface) {
        val multicastSocket = socket ?: return
        runCatching {
            multicastSocket.networkInterface = iface
            multicastSocket.send(DatagramPacket(payload, payload.size, group, port))
        }
    }

    fun stop() {
        running = false
        receiveJob?.cancel()
        announceJob?.cancel()
        val multicastSocket = socket
        socket = null
        joined.forEach { (iface, group) ->
            runCatching { multicastSocket?.leaveGroup(InetSocketAddress(group, configProvider().discoveryPort), iface) }
        }
        joined.clear()
        runCatching { multicastSocket?.close() }
    }
}
