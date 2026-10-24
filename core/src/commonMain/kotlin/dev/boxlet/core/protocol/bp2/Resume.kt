package dev.boxlet.core.protocol.bp2

import kotlinx.serialization.Serializable

@Serializable
data class Bp2ResumePosition(val fileId: String, val offset: String, val prefixRoot: String? = null,
                             val completedRoot: String? = null)

/** Local-only source capability. Never include this descriptor in BP/2 wire metadata. */
@Serializable
data class Bp2SourceRef(val kind: String, val locator: String, val grant: String? = null) {
    fun validate() {
        require(kind in listOf("file", "content-uri"))
        require(locator.isNotBlank() && locator.encodeToByteArray().size <= 16384 && '\u0000' !in locator)
        if (kind == "content-uri") require(locator.startsWith("content://"))
        grant?.let { require(kind == "content-uri" && it.startsWith("content://") && it.encodeToByteArray().size <= 16384 && '\u0000' !in it) }
    }
}

/** Sender credential; keep out of logs, discovery and UI snapshots. */
data class Bp2ResumeTicket(val transferId: String, val manifestHash: String, val resumeToken: String, val accepted: Set<String>) {
    override fun toString() = "Bp2ResumeTicket(transferId=$transferId, resumeToken=<redacted>)"
}
