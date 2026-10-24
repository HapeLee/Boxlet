package dev.boxlet.shared.repository

import dev.boxlet.core.crypto.Certificates
import dev.boxlet.core.crypto.SecurityContext
import dev.boxlet.core.protocol.BoxletJson
import dev.boxlet.shared.Settings
import dev.boxlet.shared.persistence.JsonStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

class SettingsRepository(
    file: File,
    scope: CoroutineScope,
    defaults: Settings,
) {
    private val store = JsonStore(file, Settings.serializer(), scope, defaults)
    val state: StateFlow<Settings> = store.state

    fun update(transform: (Settings) -> Settings): Settings = store.update(transform)
}

class SecurityRepository(private val file: File) {
    fun loadOrCreate(): SecurityContext {
        val existing = readSecurity()
        if (existing != null) return existing
        val generated = Certificates.generate()
        writeSecurity(generated)
        return generated
    }

    private fun readSecurity(): SecurityContext? {
        if (!file.exists()) return null
        return runCatching {
            BoxletJson.decodeFromString(SecurityContext.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun writeSecurity(context: SecurityContext) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(BoxletJson.encodeToString(SecurityContext.serializer(), context), Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
            temp.delete()
        }
    }
}

@Serializable
data class HistoryEntry(
    val id: String,
    val fileName: String,
    val fileType: String,
    val path: String? = null,
    val isMessage: Boolean = false,
    val fileSize: Long = 0,
    val senderAlias: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val adapterId: String = "localsend-v2",
)

class HistoryRepository(
    file: File,
    scope: CoroutineScope,
) {
    private val store = JsonStore(file, ListSerializer(HistoryEntry.serializer()), scope, emptyList())
    val entries: StateFlow<List<HistoryEntry>> = store.state

    fun add(entry: HistoryEntry) {
        store.update { current ->
            (listOf(entry) + current.filterNot { it.id == entry.id }).take(500)
        }
    }

    fun remove(id: String) {
        store.update { current -> current.filterNot { it.id == id } }
    }

    fun clear() {
        store.set(emptyList())
    }

    fun newEntry(
        fileName: String,
        fileType: String,
        path: String?,
        message: String?,
        fileSize: Long,
        senderAlias: String,
        timestamp: Long,
        adapterId: String = "localsend-v2",
        completionId: String? = null,
    ): HistoryEntry = HistoryEntry(
        id = completionId ?: UUID.randomUUID().toString(),
        fileName = message ?: fileName,
        fileType = fileType,
        path = path,
        isMessage = message != null,
        fileSize = fileSize,
        senderAlias = senderAlias,
        timestamp = timestamp,
        adapterId = adapterId,
    )
}

@Serializable
data class FavoriteDevice(
    val fingerprint: String,
    val alias: String,
    val host: String,
    val port: Int,
    val https: Boolean,
    val addedAt: Long = System.currentTimeMillis(),
)

class FavoritesRepository(
    file: File,
    scope: CoroutineScope,
) {
    private val store = JsonStore(file, ListSerializer(FavoriteDevice.serializer()), scope, emptyList())
    val entries: StateFlow<List<FavoriteDevice>> = store.state

    fun toggle(device: FavoriteDevice) {
        store.update { current ->
            if (current.any { it.fingerprint == device.fingerprint }) {
                current.filterNot { it.fingerprint == device.fingerprint }
            } else {
                current + device
            }
        }
    }

    fun remove(fingerprint: String) {
        store.update { current -> current.filterNot { it.fingerprint == fingerprint } }
    }

    fun isFavorite(fingerprint: String): Boolean = entries.value.any { it.fingerprint == fingerprint }
}

