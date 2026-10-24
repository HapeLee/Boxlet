package dev.boxlet.shared

import java.net.URI

data class Bp2Endpoint(val host: String, val port: Int)

fun parseBp2Endpoint(value: String): Bp2Endpoint {
    val uri = runCatching { URI("bp2://${value.trim()}") }.getOrNull()
    require(uri != null && !uri.host.isNullOrBlank() && uri.userInfo == null &&
        uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
        !uri.rawAuthority.endsWith(':') && (uri.port == -1 || uri.port in 1..65535)) {
        "请输入 IP 或 主机:端口；IPv6 请使用 [地址]:端口"
    }
    return Bp2Endpoint(uri.host.removeSurrounding("[", "]"), if (uri.port == -1) 53318 else uri.port)
}

/** Validate the complete, independently verified certificate fingerprint, never a discovery alias. */
fun bp2PairingError(value: String, self: String): String? {
    val fingerprint = value.trim()
    return when {
        fingerprint.length != 64 || fingerprint.any { it !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' } ->
            "请输入对端完整的 64 位十六进制指纹"
        fingerprint.equals(self, true) -> "不能添加本机指纹"
        else -> null
    }
}

val UiState.bp2Status: String
    get() {
        if (!settings.bp2Enabled) return "已关闭"
        val adapter = protocols.firstOrNull { it.adapterId == "boxlet-bp2" } ?: return "正在启动"
        return if (adapter.sendReady && adapter.receiveReady) {
            if (adapter.discoveryReady) "已就绪 · 优先连接已配对设备" else "传输已就绪 · 自动发现不可用，可通过 IP 连接"
        } else adapter.reason ?: "尚未就绪"
    }
