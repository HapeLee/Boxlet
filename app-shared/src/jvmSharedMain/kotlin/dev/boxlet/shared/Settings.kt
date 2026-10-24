package dev.boxlet.shared

import dev.boxlet.core.protocol.DeviceType
import dev.boxlet.core.protocol.Protocol
import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.random.Random

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Serializable
data class Settings(
    val alias: String = randomAlias(),
    val deviceType: DeviceType = DeviceType.DESKTOP,
    val deviceModel: String? = null,
    val port: Int = Protocol.DEFAULT_PORT,
    val https: Boolean = true,
    /** Native peers prefer authenticated BP/2; LocalSend remains enabled for compatibility. */
    val bp2Enabled: Boolean = true,
    val bp2Port: Int = 53318,
    val bp2TrustedPeers: Set<String> = emptySet(),
    val multicastGroup: String = Protocol.DEFAULT_MULTICAST_GROUP,
    val discoveryTimeoutMs: Long = Protocol.DISCOVERY_TIMEOUT_MS,
    val networkWhitelist: List<String> = emptyList(),
    val networkBlacklist: List<String> = emptyList(),
    val quickSave: Boolean = false,
    val quickSaveFromFavorites: Boolean = false,
    val receivePin: String? = null,
    val saveToHistory: Boolean = true,
    /** Optional LocalSend file digest. Quick send is its inverse; BP/2 digests are independent. */
    val createChecksums: Boolean = true,
    val verifyChecksums: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** 跟随系统取色（Android 12+ Monet）；关闭时使用品牌蓝配色。 */
    val dynamicColor: Boolean = false,
    /** 液态玻璃（模糊 / 折射 / 高光）。关闭后所有玻璃退化为半透明表面。 */
    val liquidGlass: Boolean = true,
    val locale: String? = null,
    val destination: String? = null,
    val showToken: String = UUID.randomUUID().toString(),
)

fun randomAlias(): String {
    val adjectives = listOf(
        "Swift", "Quiet", "Bright", "Clever", "Gentle", "Lucky", "Silent", "Bold",
        "Cosmic", "Frozen", "Golden", "Hidden", "Jolly", "Misty", "Noble", "Rapid",
    )
    val animals = listOf(
        "Otter", "Falcon", "Panda", "Lynx", "Dolphin", "Sparrow", "Tiger", "Gecko",
        "Heron", "Badger", "Koala", "Marmot", "Puma", "Raven", "Seal", "Wombat",
    )
    return "${adjectives.random(Random.Default)} ${animals.random(Random.Default)}"
}
