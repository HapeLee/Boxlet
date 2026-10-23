package dev.boxlet.web

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import dev.boxlet.core.sp.PeerDevice
import kotlinx.browser.document
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) {
        App()
    }
}

/** 用户可能只填 `192.168.1.5:53321`，这里补成 ws://。 */
private fun normalize(userInput: String): String {
    val trimmed = userInput.trim().trimEnd('/')
    if (trimmed.isEmpty()) return ""
    return when {
        trimmed.startsWith("ws://") || trimmed.startsWith("wss://") -> trimmed
        trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
        trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
        else -> "ws://$trimmed"
    }
}

/** wasm 没有 java.util.Formatter，手工保留一位小数。 */
private fun formatRate(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0) return "—"
    val kib = bytesPerSecond / 1024
    if (kib < 1024) return "$kib KiB/s"
    val tenths = kib * 10 / 1024
    return "${tenths / 10}.${tenths % 10} MiB/s"
}

@Composable
fun App() {
    var address by remember { mutableStateOf("") }
    var pairing by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<PeerDevice>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var client by remember { mutableStateOf<SpClient?>(null) }

    fun connect() {
        val url = normalize(address)
        if (url.isEmpty()) {
            status = "先填 bridge 地址"
            return
        }
        status = "正在连接 $url …"
        connected = false
        client?.close()
        // 旧连接关闭事件可能在新连接建立之后才到达，用身份判断避免误清状态。
        lateinit var newClient: SpClient
        newClient = SpClient(
            url = "$url/",
            pairCode = pairing.ifBlank { null },
            onReady = {
                if (client === newClient) {
                    connected = true
                    status = "已连接到 bridge"
                }
            },
            onDevices = { if (client === newClient) devices = it },
            onStatus = { if (client === newClient) status = it },
            onClosed = {
                if (client === newClient) {
                    connected = false
                    devices = emptyList()
                }
            },
            onProgress = { sent, total, rate ->
                if (client === newClient && total > 0) {
                    val percent = (sent * 100 / total).toInt()
                    status = "传输中 $percent% · ${formatRate(rate)}"
                }
            },
        )
        client = newClient
    }

    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Boxlet Web", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "SP/1 协议：浏览器通过 bridge 接入局域网，设备列表由对端多播发现后下发。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Bridge 地址，例如 192.168.1.5:53321") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { connect() }) { Text(if (connected) "重连" else "连接") }
                }

                OutlinedTextField(
                    value = pairing,
                    onValueChange = { pairing = it },
                    label = { Text("配对码（bridge 设置了才需要）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = connected,
                        onClick = {
                            pickFiles { files ->
                                if (files.isEmpty()) return@pickFiles
                                status = "准备发送 ${files.size} 个文件…"
                                client?.sendFiles(files)
                            }
                        },
                    ) { Text("选择文件并发送") }
                }

                status?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }

                if (devices.isNotEmpty()) {
                    Text("附近的设备", style = MaterialTheme.typography.titleMedium)
                    devices.forEach { device ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(device.alias, style = MaterialTheme.typography.titleSmall)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (device.online) "在线" else "最近可见",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    "${device.deviceType} · ${device.host}:${device.port}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                    Text(
                        "v1 只能发给当前 bridge 所在设备；跨设备转发在下一版。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun pickFiles(onPicked: (List<File>) -> Unit) {
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"
    input.multiple = true
    input.onchange = {
        val list = input.files
        val files = buildList {
            if (list != null) {
                for (index in 0 until list.length) {
                    list.item(index)?.let { add(it) }
                }
            }
        }
        onPicked(files)
    }
    input.click()
}
