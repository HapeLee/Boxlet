package dev.boxlet.android.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.boxlet.android.ui.components.EditableSettingItem
import dev.boxlet.android.ui.components.GlassPage
import dev.boxlet.android.ui.components.SettingCard
import dev.boxlet.android.ui.components.SettingGroup
import dev.boxlet.android.ui.components.SettingItem
import dev.boxlet.android.ui.components.SwitchSettingItem
import dev.boxlet.core.protocol.Protocol
import dev.boxlet.shared.BoxletApp
import dev.boxlet.shared.ThemeMode
import dev.boxlet.shared.UiState

@Composable
fun SettingsScreen(
    app: BoxletApp,
    state: UiState,
    listState: LazyListState,
    backdrop: LayerBackdrop?,
    bottomBarPadding: Dp,
) {
    val context = LocalContext.current
    val settings = state.settings

    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            app.updateSettings { it.copy(destination = uri.toString()) }
        }
    }

    GlassPage(
        title = "设置",
        listState = listState,
        backdrop = backdrop,
        bottomBarPadding = bottomBarPadding,
    ) {
        item {
            SettingGroup(title = "设备") {
                EditableSettingItem(
                    title = "设备名称",
                    value = settings.alias,
                    supportingText = "附近设备会通过这个名称识别你。",
                    validate = { if (it.isBlank()) "设备名称不能为空" else null },
                    onCommit = { value -> app.updateSettings { it.copy(alias = value.trim()) } },
                )
                EditableSettingItem(
                    title = "端口",
                    value = settings.port.toString(),
                    supportingText = "可用范围：1024–65535。",
                    keyboardType = KeyboardType.Number,
                    validate = { value ->
                        if (value.trim().toIntOrNull() in 1024..65535) null else "请输入 1024–65535 之间的端口"
                    },
                    onCommit = { value -> app.updateSettings { it.copy(port = value.trim().toInt()) } },
                )
                EditableSettingItem(
                    title = "设备型号",
                    value = settings.deviceModel.orEmpty(),
                    valueSummary = settings.deviceModel?.takeIf { it.isNotBlank() } ?: "使用默认型号",
                    supportingText = "可选，留空使用默认型号。",
                    onCommit = { value -> app.updateSettings { it.copy(deviceModel = value.trim().ifBlank { null }) } },
                )
                SettingItem(title = "设备类型", description = settings.deviceType.name.lowercase())
            }
        }

        item {
            SettingGroup(title = "传输") {
                SwitchSettingItem(
                    title = "快速保存",
                    checked = settings.quickSave,
                    description = "自动接受所有传输请求",
                    onCheckedChange = { value -> app.updateSettings { it.copy(quickSave = value) } },
                )
                SwitchSettingItem(
                    title = "仅收藏设备自动接收",
                    checked = settings.quickSaveFromFavorites,
                    description = "只对已收藏设备启用快速保存",
                    onCheckedChange = { value -> app.updateSettings { it.copy(quickSaveFromFavorites = value) } },
                )
                SwitchSettingItem(
                    title = "发送时计算 SHA-256",
                    checked = settings.createChecksums,
                    description = "新版 Sendee 边传边校验；兼容设备会在发送前计算校验值",
                    onCheckedChange = { value -> app.updateSettings { it.copy(createChecksums = value) } },
                )
                SwitchSettingItem(
                    title = "接收时校验 SHA-256",
                    checked = settings.verifyChecksums,
                    description = "校验失败会自动重传（最多 3 次）",
                    onCheckedChange = { value -> app.updateSettings { it.copy(verifyChecksums = value) } },
                )
                SwitchSettingItem(
                    title = "记录接收历史",
                    checked = settings.saveToHistory,
                    description = "保存文件与文本消息记录",
                    onCheckedChange = { value -> app.updateSettings { it.copy(saveToHistory = value) } },
                )
                EditableSettingItem(
                    title = "接收 PIN",
                    value = settings.receivePin.orEmpty(),
                    valueSummary = if (settings.receivePin.isNullOrBlank()) "未启用" else "已启用",
                    supportingText = "设置 4–8 位数字；留空关闭。发送方需要输入相同 PIN 才能发送文件。",
                    keyboardType = KeyboardType.NumberPassword,
                    sensitive = true,
                    validate = { value ->
                        if (value.isBlank() || (value.length in 4..8 && value.all { it in '0'..'9' })) null
                        else "PIN 需要为 4–8 位数字，或留空关闭"
                    },
                    onCommit = { value -> app.updateSettings { it.copy(receivePin = value.trim().ifBlank { null }) } },
                )
            }
        }

        item {
            SettingGroup(title = "保存位置") {
                SettingItem(
                    title = "接收目录",
                    description = settings.destination?.let(::formatDestination) ?: "下载/Boxlet",
                    onClick = { directoryPicker.launch(null) },
                )
                if (settings.destination != null) {
                    SettingItem(
                        title = "恢复默认目录",
                        description = "使用下载/Boxlet 保存接收的文件",
                        onClick = { app.updateSettings { it.copy(destination = null) } },
                    )
                }
            }
        }

        item {
            SettingGroup(title = "网络") {
                SwitchSettingItem(
                    title = "HTTPS 加密",
                    checked = settings.https,
                    description = "使用自签名证书 + 指纹固定；关闭后仅建议在可信网络使用",
                    onCheckedChange = { value -> app.updateSettings { it.copy(https = value) } },
                )
                EditableSettingItem(
                    title = "多播组",
                    value = settings.multicastGroup,
                    supportingText = "用于发现同一网络中的设备。",
                    validate = { if (it.isBlank()) "多播组不能为空" else null },
                    onCommit = { value -> app.updateSettings { it.copy(multicastGroup = value.trim()) } },
                )
                EditableSettingItem(
                    title = "接口白名单",
                    value = settings.networkWhitelist.joinToString(","),
                    valueSummary = settings.networkWhitelist.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "不限制接口",
                    supportingText = "多个规则用逗号分隔，支持 *；留空不限制接口。",
                    onCommit = { value -> app.updateSettings { it.copy(networkWhitelist = parsePatterns(value)) } },
                )
                EditableSettingItem(
                    title = "接口黑名单",
                    value = settings.networkBlacklist.joinToString(","),
                    valueSummary = settings.networkBlacklist.takeIf { it.isNotEmpty() }?.joinToString("、") ?: "未排除接口",
                    supportingText = "多个规则用逗号分隔，支持 *；优先于白名单。",
                    onCommit = { value -> app.updateSettings { it.copy(networkBlacklist = parsePatterns(value)) } },
                )
                SettingItem(
                    title = "重启接收服务",
                    description = "重新启动服务并发现附近设备",
                    onClick = { app.ensureRestarted() },
                )
            }
        }

        item {
            SettingGroup(title = "外观") {
                SettingItem(
                    title = "主题模式",
                    description = "跟随系统或强制浅色 / 深色",
                    option = when (settings.theme) {
                        ThemeMode.SYSTEM -> "跟随系统"
                        ThemeMode.LIGHT -> "浅色"
                        ThemeMode.DARK -> "深色"
                    },
                    dropdownMenu = { dismiss ->
                        ThemeMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "跟随系统"
                                            ThemeMode.LIGHT -> "浅色"
                                            ThemeMode.DARK -> "深色"
                                        },
                                    )
                                },
                                onClick = {
                                    app.updateSettings { it.copy(theme = mode) }
                                    dismiss()
                                },
                            )
                        }
                    },
                )
                SwitchSettingItem(
                    title = "跟随系统取色（Monet）",
                    checked = settings.dynamicColor,
                    description = "用系统壁纸生成的配色替换品牌色，玻璃质感保持不变",
                    onCheckedChange = { value -> app.updateSettings { it.copy(dynamicColor = value) } },
                )
                SwitchSettingItem(
                    title = "液态玻璃效果",
                    checked = settings.liquidGlass,
                    description = "关闭后玻璃退化为半透明表面",
                    onCheckedChange = { value -> app.updateSettings { it.copy(liquidGlass = value) } },
                )
            }
        }

        item {
            SettingGroup(title = "关于") {
                SettingCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfoRow("版本", "1.0.0")
                        InfoRow("协议", "LocalSend v${Protocol.VERSION}")
                        Text("设备指纹", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            state.fingerprint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Boxlet 不会把文件上传到任何服务器。相邻设备通过本地网络直连。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun parsePatterns(value: String): List<String> =
    value.split(',', ';', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
