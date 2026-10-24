package dev.boxlet.core

import dev.boxlet.core.net.*
import dev.boxlet.core.protocol.*
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.test.*

class OfficialDiscoveryTest {
    @Test fun discoveryPortIsIndependentFromAdvertisedTransferPort() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val received = CompletableDeferred<MulticastMessage>()
        // Use an isolated UDP port rather than taking the user's real LocalSend port in tests.
        val discoveryPort = DatagramSocket(0).use { it.localPort }
        val config = DiscoveryConfig(port = 42424, multicastGroup = Protocol.DEFAULT_MULTICAST_GROUP,
            alias = "Boxlet", deviceModel = null, deviceType = DeviceType.DESKTOP,
            protocolType = ProtocolType.HTTPS, fingerprint = "own", download = false,
            whitelist = listOf("127.*.*.*"), blacklist = emptyList(), discoveryPort = discoveryPort)
        val service = DiscoveryService(scope, { config }, { _, message -> received.complete(message) },
            { received.completeExceptionally(it) })
        try {
            service.start()
            val official = MulticastMessage(alias = "LocalSend peer", fingerprint = "remote", port = 53317)
            val bytes = BoxletJson.encodeToString(MulticastMessage.serializer(), official).encodeToByteArray()
            DatagramSocket().use { it.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName("127.0.0.1"), discoveryPort)) }
            val message = withTimeout(5000) { received.await() }
            assertEquals("remote", message.fingerprint)
            assertEquals(53317, message.port, "Target transfer port is taken from the remote announcement")
            assertEquals(42424, config.port, "Local announcement keeps the actual HTTP port")
        } finally { service.stop(); scope.cancel() }
    }
}
