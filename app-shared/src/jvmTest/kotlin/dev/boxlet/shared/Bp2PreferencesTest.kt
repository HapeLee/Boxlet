package dev.boxlet.shared

import dev.boxlet.core.protocol.BoxletJson
import dev.boxlet.core.protocol.Protocol
import dev.boxlet.shared.repository.SettingsRepository
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

class Bp2PreferencesTest {
    @Test fun productDefaultUsesLocalSendWhileExplicitDeveloperOptInRemainsAvailable() {
        assertFalse(Settings().bp2Enabled)
        assertEquals(Protocol.LOCALSEND_PORT, Settings().port)
        assertFalse(BoxletJson.decodeFromString<Settings>("{}").bp2Enabled)
        assertTrue(BoxletJson.decodeFromString<Settings>("""{"bp2Enabled":true}""").bp2Enabled)
    }

    @Test fun priorDefaultsMigrateOnceWithoutChangingCustomPortsOrReceivePreferences() = runBlocking {
        val directory = Files.createTempDirectory("boxlet-settings-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            for ((priorPort, expectedPort) in listOf(53318 to 53317, 54321 to 54321)) {
                val file = directory.resolve("$priorPort.json")
                file.writeText("""{"alias":"My device","bp2Enabled":true,"port":$priorPort,"quickSaveFromFavorites":true,"createChecksums":false}""")
                val repository = SettingsRepository(file, scope, Settings())
                val migrated = repository.state.value
                assertFalse(migrated.bp2Enabled)
                assertEquals(expectedPort, migrated.port)
                assertEquals("My device", migrated.alias)
                assertTrue(migrated.quickSaveFromFavorites)
                assertFalse(migrated.createChecksums)
                withTimeout(5000) { while (runCatching {
                    BoxletJson.decodeFromString<Settings>(file.readText()).protocolDefaultsVersion
                }.getOrDefault(0) != 1) delay(10) }
                // An explicit experiment after migration is not reset on every subsequent launch.
                file.writeText(BoxletJson.encodeToString(Settings.serializer(), migrated.copy(bp2Enabled = true)))
                assertTrue(SettingsRepository(file, scope, Settings()).state.value.bp2Enabled)
            }
        } finally { scope.cancel(); directory.deleteRecursively() }
    }

    @Test fun removedPairingFieldsDoNotBreakReceivePreferencesOrReappearWhenSaved() {
        val settings = BoxletJson.decodeFromString<Settings>(
            """{"bp2RequirePairing":true,"bp2TrustedPeers":["${"a".repeat(64)}"],"quickSave":false,"quickSaveFromFavorites":true}""")
        assertFalse(settings.quickSave)
        assertTrue(settings.quickSaveFromFavorites)
        val saved = BoxletJson.encodeToString(Settings.serializer(), settings)
        assertFalse("bp2RequirePairing" in saved)
        assertFalse("bp2TrustedPeers" in saved)
    }
}
