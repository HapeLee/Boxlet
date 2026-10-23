package dev.boxlet.desktop

import dev.boxlet.core.protocol.BoxletJson
import dev.boxlet.shared.Settings
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 单实例转发：`/show` 与 `showToken` 在 core 里早就实现了，桌面端只需要在启动时问一句
 * 「本机是不是已经有一个实例」。命中就把它叫到前台，自己退出，避免两个进程抢端口。
 *
 * 这里直接读 `settings.json`：此时引擎还没起来，拿不到 app-shared 的 StateFlow。
 */
object SingleInstance {

    fun forwardToRunningApp(): Boolean {
        val settings = readSettings() ?: return false
        if (settings.showToken.isBlank()) return false
        // 设置里只说明加密开关；对端实例可能刚改过配置，两种都试一次。
        return postShow(settings, https = true) || postShow(settings, https = false)
    }

    private fun readSettings(): Settings? = runCatching {
        val file = File(DesktopEnvironment.dataDir, "settings.json")
        if (!file.isFile) return null
        BoxletJson.decodeFromString(Settings.serializer(), file.readText())
    }.getOrNull()

    private fun postShow(settings: Settings, https: Boolean): Boolean = runCatching {
        val scheme = if (https) "https" else "http"
        val token = URLEncoder.encode(settings.showToken, "UTF-8")
        val url = java.net.URI("$scheme://127.0.0.1:${settings.port}/api/localsend/v2/show?token=$token").toURL()
        val connection = url.openConnection() as HttpURLConnection
        try {
            if (connection is HttpsURLConnection) {
                // 本机自签名证书：这里只连 127.0.0.1，指纹固定没有意义。
                connection.sslSocketFactory = permissiveContext().socketFactory
                connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = CONNECT_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write("""{"args":[]}""".toByteArray()) }
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun permissiveContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            },
        )
        return SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
    }

    private const val CONNECT_TIMEOUT_MS = 800
}
