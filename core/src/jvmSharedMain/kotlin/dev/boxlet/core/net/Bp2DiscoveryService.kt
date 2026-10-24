package dev.boxlet.core.net

import dev.boxlet.core.protocol.bp2.*
import kotlinx.coroutines.*
import java.net.*
import kotlin.random.Random

/** BP/2 announcements are hints only; the adapter still authenticates using its stored certificate pin. */
class Bp2DiscoveryService(
    private val scope: CoroutineScope,
    private val local: () -> Bp2Announcement,
    private val whitelist: List<String>,
    private val blacklist: List<String>,
    private val onAnnouncement: (String, Bp2Announcement) -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private var socket: MulticastSocket? = null
    private var worker: Job? = null
    private var announcer: Job? = null
    fun start() {
        check(socket == null)
        val udp = MulticastSocket(null as SocketAddress?)
        try {
            udp.reuseAddress = true
            udp.bind(InetSocketAddress(Bp2.PORT))
            udp.timeToLive = 1
            val group = InetAddress.getByName(Bp2.MULTICAST_V4)
            NetworkInterfaces.activeInterfaces(whitelist, blacklist).forEach {
                runCatching { udp.joinGroup(InetSocketAddress(group, Bp2.PORT), it) }
            }
            socket = udp
            worker = scope.launch(Dispatchers.IO) {
                val bytes = ByteArray(1401)
                try {
                    while (isActive) {
                        val packet = DatagramPacket(bytes, bytes.size)
                        udp.receive(packet)
                        if (packet.length > 1400) continue
                        val message = runCatching {
                            Bp2.json.decodeFromString<Bp2Announcement>(packet.data.decodeToString(
                                packet.offset, packet.offset + packet.length, throwOnInvalidSequence = true)).also { it.validate() }
                        }.getOrNull() ?: continue
                        if (message.deviceId == local().deviceId) continue
                        val host = packet.address.hostAddress ?: continue
                        onAnnouncement(host.substringBefore('%'), message)
                    }
                } catch (error: Throwable) { if (isActive && !udp.isClosed) onFailure(error) }
            }
            announcer = scope.launch {
                while (isActive) { announceNow(); delay(5000 + Random.nextLong(500)) }
            }
        } catch (error: Throwable) { udp.close(); socket = null; throw error }
    }
    fun announceNow() {
        val udp = socket ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val info = local().also { it.validate() }
                val bytes = Bp2.json.encodeToString(Bp2Announcement.serializer(), info).encodeToByteArray()
                require(bytes.size <= 1400)
                val group = InetAddress.getByName(Bp2.MULTICAST_V4)
                NetworkInterfaces.activeInterfaces(whitelist, blacklist).forEach {
                    udp.networkInterface = it
                    udp.send(DatagramPacket(bytes, bytes.size, group, Bp2.PORT))
                }
            }.onFailure { if (!udp.isClosed) onFailure(it) }
        }
    }
    override fun close() {
        socket?.close(); socket = null
        worker?.cancel(); worker = null
        announcer?.cancel(); announcer = null
    }
}
