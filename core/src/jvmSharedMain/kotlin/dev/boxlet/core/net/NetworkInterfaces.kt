package dev.boxlet.core.net

import java.net.Inet4Address
import java.net.NetworkInterface

data class InterfaceAddress(val networkInterface: NetworkInterface, val address: Inet4Address)

object NetworkInterfaces {

    fun activeInterfaces(whitelist: List<String>, blacklist: List<String>): List<NetworkInterface> {
        val result = mutableListOf<NetworkInterface>()
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        for (iface in all) {
            if (!runCatching { iface.isUp }.getOrDefault(false)) continue
            if (runCatching { iface.isLoopback }.getOrDefault(false)) continue
            val addresses = runCatching { iface.inetAddresses?.toList().orEmpty() }.getOrDefault(emptyList())
            val ipv4 = addresses.filterIsInstance<Inet4Address>()
            if (ipv4.isEmpty()) continue
            if (ipv4.none { matchesFilters(it.hostAddress.orEmpty(), whitelist, blacklist) }) continue
            result += iface
        }
        return result
    }

    fun ipv4Addresses(whitelist: List<String>, blacklist: List<String>): List<InterfaceAddress> {
        val result = mutableListOf<InterfaceAddress>()
        for (iface in activeInterfaces(whitelist, blacklist)) {
            val addresses = runCatching { iface.inetAddresses?.toList().orEmpty() }.getOrDefault(emptyList())
            addresses.filterIsInstance<Inet4Address>().forEach { address ->
                if (matchesFilters(address.hostAddress.orEmpty(), whitelist, blacklist)) {
                    result += InterfaceAddress(iface, address)
                }
            }
        }
        return result
    }

    /**
     * A pattern matches one IP segment; `*` never crosses a dot.
     * The blacklist always wins over the whitelist.
     */
    fun matchesFilters(ip: String, whitelist: List<String>, blacklist: List<String>): Boolean {
        if (blacklist.any { matches(ip, it) }) return false
        if (whitelist.isEmpty()) return true
        return whitelist.any { matches(ip, it) }
    }

    fun matches(ip: String, pattern: String): Boolean {
        if (pattern.isBlank()) return false
        if (pattern == "*" || pattern == "*.*.*.*") return true
        val ipParts = ip.split('.')
        val patternParts = pattern.split('.')
        if (ipParts.size != patternParts.size) return false
        return patternParts.indices.all { index ->
            val part = patternParts[index]
            part == "*" || part == ipParts[index]
        }
    }
}
