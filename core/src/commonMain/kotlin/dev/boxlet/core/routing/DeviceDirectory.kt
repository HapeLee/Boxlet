package dev.boxlet.core.routing

/** Protocol-scoped endpoint IDs never become global identities without authenticated evidence. */
enum class TransferCapability { FILES, TEXT, FOLDERS, PAUSE, RESUME }

data class AdapterState(
    val adapterId: String,
    val enabled: Boolean = true,
    val discoveryReady: Boolean = false,
    val sendReady: Boolean = false,
    val receiveReady: Boolean = false,
    val reason: String? = null,
)

data class DiscoveredEndpoint(
    val endpointKey: String,
    val adapterId: String,
    val adapterScopedPeerId: String,
    val locator: String,
    val displayName: String,
    val lastSeen: Long,
    val capabilities: Set<TransferCapability>,
    /** Populate only after actual TLS peer verification; never from UDP or an alias. */
    val verifiedCertificate: String? = null,
    val available: Boolean = true,
) {
    init {
        require(endpointKey.isNotBlank() && adapterId.isNotBlank() && adapterScopedPeerId.isNotBlank() && locator.isNotBlank())
        verifiedCertificate?.let {
            require(it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' })
        }
    }
}

data class PeerRecord(val localPeerId: String, val endpoints: List<DiscoveredEndpoint>) {
    val displayName: String get() = endpoints.maxBy { it.lastSeen }.displayName
}

/** Single-owner directory. Stable local ID is allocated by the caller's persistence service. */
class DeviceDirectory(private val newId: () -> String) {
    private val endpoints = linkedMapOf<String, DiscoveredEndpoint>()
    private val peerIds = mutableMapOf<String, String>()
    private val scopeOwners = mutableMapOf<String, Pair<String, String>>()

    fun upsert(endpoint: DiscoveredEndpoint) {
        require(endpoint.endpointKey in endpoints || endpoints.size < 4096) { "Device directory is full" }
        val owner = endpoint.adapterId to endpoint.adapterScopedPeerId
        require(scopeOwners[endpoint.endpointKey]?.let { it == owner } != false) { "Endpoint key reused by another identity" }
        val previous = endpoints[endpoint.endpointKey]
        scopeOwners[endpoint.endpointKey] = owner
        endpoints[endpoint.endpointKey] = endpoint
        val identity = identity(endpoint)
        if (identity !in peerIds) {
            // Verifying an existing untrusted endpoint need not change its UI ID. A changed
            // authenticated certificate, however, always becomes a new identity.
            peerIds[identity] = previous?.takeIf { it.verifiedCertificate == null }?.let { peerIds[identity(it)] }
                ?: newId().also { require(it.isNotBlank()) }
        }
        cleanup()
    }

    fun remove(endpointKey: String) { endpoints.remove(endpointKey); cleanup() }
    fun removeAdapter(adapterId: String) { endpoints.entries.removeAll { it.value.adapterId == adapterId }; cleanup() }
    fun prune(before: Long) { endpoints.entries.removeAll { it.value.lastSeen < before }; cleanup() }
    fun peers(): List<PeerRecord> = endpoints.values.groupBy(::identity).map { (identity, entries) ->
        PeerRecord(peerIds.getValue(identity), entries.toList())
    }.sortedBy { it.displayName.lowercase() }

    private fun identity(endpoint: DiscoveredEndpoint): String = endpoint.verifiedCertificate?.let {
        "certificate:${it.lowercase()}"
    } ?: "endpoint:${endpoint.endpointKey}"

    private fun cleanup() {
        scopeOwners.keys.retainAll(endpoints.keys)
        val active = endpoints.values.map(::identity).toSet()
        peerIds.keys.retainAll(active)
    }
}

/** Frozen at task creation. Retrying does not select another protocol or transfer its authorization. */
data class TransferRoute(val adapterId: String, val endpoint: DiscoveredEndpoint)

class TransferRouter {
    companion object {
        const val BOXLET = dev.boxlet.core.protocol.bp2.Bp2.ADAPTER_ID
        const val LOCALSEND = "localsend-v2"
    }
    private val states = mutableMapOf<String, AdapterState>()
    fun update(state: AdapterState) { states[state.adapterId] = state }
    fun select(peer: PeerRecord, required: Set<TransferCapability> = setOf(TransferCapability.FILES)): TransferRoute {
        val candidates = peer.endpoints.filter {
            val state = states[it.adapterId]
            state?.enabled == true && state.sendReady && it.available && it.capabilities.containsAll(required) &&
                it.adapterId in listOf(BOXLET, LOCALSEND)
        }
        val endpoint = candidates.sortedWith(compareBy<DiscoveredEndpoint> {
            if (it.adapterId == BOXLET) 0 else 1
        }.thenByDescending { it.lastSeen }).firstOrNull() ?: error("No available protocol supports this transfer")
        return TransferRoute(endpoint.adapterId, endpoint)
    }
}
