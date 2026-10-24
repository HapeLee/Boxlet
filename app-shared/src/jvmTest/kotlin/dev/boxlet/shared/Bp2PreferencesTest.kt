package dev.boxlet.shared

import dev.boxlet.core.protocol.BoxletJson
import kotlin.test.*

class Bp2PreferencesTest {
    @Test fun nativeDefaultMigratesMissingFieldButPreservesExplicitOptOut() {
        assertTrue(Settings().bp2Enabled)
        assertTrue(BoxletJson.decodeFromString<Settings>("{}").bp2Enabled)
        assertFalse(BoxletJson.decodeFromString<Settings>("""{"bp2Enabled":false}""").bp2Enabled)
    }

    @Test fun manualEndpointAcceptsCustomPortsAndIpv6WithoutAcceptingUrlsOrCredentials() {
        assertEquals(Bp2Endpoint("192.168.1.2", 53318), parseBp2Endpoint(" 192.168.1.2 "))
        assertEquals(Bp2Endpoint("boxlet.local", 54321), parseBp2Endpoint("boxlet.local:54321"))
        assertEquals(Bp2Endpoint("::1", 53318), parseBp2Endpoint("[::1]"))
        assertEquals(Bp2Endpoint("::1", 54321), parseBp2Endpoint("[::1]:54321"))
        listOf("", "x:", "x:0", "x:65536", "::1", "x/path", "x?query", "x#fragment", "user@x", "https://x").forEach {
            assertFailsWith<IllegalArgumentException>(it) { parseBp2Endpoint(it) }
        }
    }

    @Test fun pairingRequiresFullFingerprintAndRejectsSelf() {
        val self = "a".repeat(64)
        assertNotNull(bp2PairingError(self.uppercase(), self))
        assertNotNull(bp2PairingError("b".repeat(63), self))
        assertNotNull(bp2PairingError("g".repeat(64), self))
        assertNull(bp2PairingError(" B" + "b".repeat(63) + " ", self))
    }
}
