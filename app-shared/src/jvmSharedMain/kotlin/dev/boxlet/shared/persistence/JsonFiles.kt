package dev.boxlet.shared.persistence

import dev.boxlet.core.protocol.BoxletJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import java.io.File

internal fun <T> readJson(file: File, serializer: KSerializer<T>): T? {
    if (!file.exists()) return null
    return runCatching {
        BoxletJson.decodeFromString(serializer, file.readText(Charsets.UTF_8))
    }.getOrNull()
}

internal fun writeJson(file: File, text: String) {
    file.parentFile?.mkdirs()
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.writeText(text, Charsets.UTF_8)
    if (file.exists() && !file.delete()) {
        file.writeText(text, Charsets.UTF_8)
        temp.delete()
        return
    }
    if (!temp.renameTo(file)) {
        file.writeText(text, Charsets.UTF_8)
        temp.delete()
    }
}

/** Generic observable JSON-backed store with serialized writes. */
internal class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val scope: CoroutineScope,
    initial: T,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(readJson(file, serializer) ?: initial)
    val state: StateFlow<T> = _state.asStateFlow()

    init {
        // Persist defaults (alias, show token, ...) on first launch so they stay stable.
        if (!file.exists()) {
            runCatching { writeJson(file, BoxletJson.encodeToString(serializer, _state.value)) }
        }
    }

    fun set(value: T) {
        _state.value = value
        val text = BoxletJson.encodeToString(serializer, value)
        scope.launch(Dispatchers.IO) {
            mutex.withLock { runCatching { writeJson(file, text) } }
        }
    }

    fun update(transform: (T) -> T): T {
        val next = transform(_state.value)
        set(next)
        return next
    }
}
