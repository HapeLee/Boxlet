package dev.boxlet.core.bp2

import dev.boxlet.core.routing.*
import kotlin.test.*

class TransferRoutingTest {
    private var ids = 0
    private fun directory() = DeviceDirectory { "peer-${++ids}" }
    private fun endpoint(key: String, protocol: String, certificate: String? = null) = DiscoveredEndpoint(
        key, protocol, "protocol-id-$key", "192.0.2.1:53317", "Same name", 1,
        setOf(TransferCapability.FILES), certificate,
    )
    private fun router() = TransferRouter().apply {
        update(AdapterState(TransferRouter.BOXLET, sendReady = true))
        update(AdapterState(TransferRouter.LOCALSEND, sendReady = true))
    }

    @Test fun localSendOnlyTargetIsSelectedWithoutAModeSwitch() {
        val directory = directory()
        directory.upsert(endpoint("ls", TransferRouter.LOCALSEND))
        assertEquals(TransferRouter.LOCALSEND, router().select(directory.peers().single()).adapterId)
    }

    @Test fun onlyVerifiedCertificateEvidenceMergesProtocolsAndPrefersBp2() {
        val directory = directory()
        directory.upsert(endpoint("ls", TransferRouter.LOCALSEND))
        directory.upsert(endpoint("bp2", TransferRouter.BOXLET))
        assertEquals(2, directory.peers().size)
        directory.upsert(endpoint("ls", TransferRouter.LOCALSEND, "a".repeat(64)))
        directory.upsert(endpoint("bp2", TransferRouter.BOXLET, "A".repeat(64)))
        assertEquals(1, directory.peers().size)
        val route = router().select(directory.peers().single())
        assertEquals(TransferRouter.BOXLET, route.adapterId)
        directory.removeAdapter(TransferRouter.BOXLET)
        assertEquals(TransferRouter.LOCALSEND, router().select(directory.peers().single()).adapterId)
        assertEquals(TransferRouter.BOXLET, route.adapterId, "Existing task route stays frozen")
    }

    @Test fun unavailableDisabledAndUnsupportedAdaptersCannotBecomeRoutes() {
        val directory = directory()
        directory.upsert(endpoint("ls", TransferRouter.LOCALSEND))
        val router = router()
        assertFails { router.select(directory.peers().single(), setOf(TransferCapability.RESUME)) }
        router.update(AdapterState(TransferRouter.LOCALSEND, enabled = false, sendReady = true))
        assertFails { router.select(directory.peers().single()) }
        directory.upsert(endpoint("future", "quickshare-experimental"))
        router.update(AdapterState("quickshare-experimental", sendReady = true))
        assertFails { router.select(directory.peers().first { it.endpoints.single().adapterId == "quickshare-experimental" }) }
    }

    @Test fun expiryRemovesOnlyTheExpiredEndpoint() {
        val directory = directory()
        directory.upsert(endpoint("ls", TransferRouter.LOCALSEND, "a".repeat(64)).copy(lastSeen = 1))
        directory.upsert(endpoint("bp2", TransferRouter.BOXLET, "a".repeat(64)).copy(lastSeen = 10))
        directory.prune(5)
        assertEquals(listOf(TransferRouter.BOXLET), directory.peers().single().endpoints.map { it.adapterId })
        assertFails { directory.upsert(endpoint("bp2", TransferRouter.LOCALSEND)) }
    }


}
