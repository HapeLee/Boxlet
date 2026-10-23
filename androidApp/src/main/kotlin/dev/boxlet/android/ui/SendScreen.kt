package dev.boxlet.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import dev.boxlet.android.ui.components.GlassPage
import dev.boxlet.android.ui.components.HairlineDivider
import dev.boxlet.android.ui.components.IconBubble
import dev.boxlet.android.ui.components.MatterCardPadded
import dev.boxlet.android.ui.components.MatterTag
import dev.boxlet.android.ui.components.SectionTitle
import dev.boxlet.core.engine.TransferStatus
import dev.boxlet.core.protocol.Device
import dev.boxlet.core.protocol.Protocol
import dev.boxlet.shared.BoxletApp
import dev.boxlet.shared.UiState
import kotlinx.coroutines.delay

@Composable
fun SendScreen(
    app: BoxletApp,
    state: UiState,
    listState: LazyListState,
    backdrop: LayerBackdrop?,
    bottomBarPadding: Dp,
    onBrowserShare: () -> Unit,
) {
    val context = LocalContext.current
    var actionDevice by remember { mutableStateOf<Device?>(null) }
    val selection by app.sendSelection.collectAsState()
    val sending = state.outgoing?.status in setOf(
        TransferStatus.PREPARING, TransferStatus.WAITING_FOR_ACCEPT,
        TransferStatus.PIN_REQUIRED, TransferStatus.TRANSFERRING,
    )

    // 「在线」是相对当前时间算的，没有状态变化就不会重组；定时推进一次，设备下线后文案才会及时变。
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            now = System.currentTimeMillis()
        }
    }

    GlassPage(
        title = "发送",
        listState = listState,
        backdrop = backdrop,
        bottomBarPadding = bottomBarPadding,
    ) {
        item(key = "send-selection") {
            SendSelectionPanel(app, modifier = Modifier.animateItem())
        }
        item(key = "nearby-title") {
            SectionTitle(
                title = "附近设备",
                modifier = Modifier.animateItem(),
                count = state.devices.size.takeIf { it > 0 },
                trailing = {
                    IconButton(onClick = { app.scan() }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "重新扫描附近设备",
                            modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        }

        if (state.devices.isEmpty()) {
            item(key = "nearby-empty") {
                Text(
                    "在另一台设备上打开 Boxlet 或 LocalSend，连接同一 Wi-Fi 后即可发现。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.animateItem().padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        } else {
            items(state.devices, key = { it.fingerprint }) { device ->
                DeviceCard(
                    modifier = Modifier.animateItem(),
                    device = device,
                    favorite = state.isFavorite(device.fingerprint),
                    now = now,
                    onClick = { actionDevice = device },
                    onToggleFavorite = { app.toggleFavorite(device) },
                )
            }
        }
        state.outgoing?.let { outgoing ->
            item(key = "outgoing-transfer") {
                TransferCard(
                    modifier = Modifier.animateItem(),
                    title = "发送到 ${outgoing.peer.alias}",
                    subtitle = transferSubtitle(outgoing.status),
                    progress = outgoing.progress,
                    status = outgoing.status,
                    error = outgoing.error,
                    files = outgoing.files,
                    onCancel = if (outgoing.status == TransferStatus.TRANSFERRING ||
                        outgoing.status == TransferStatus.PREPARING ||
                        outgoing.status == TransferStatus.WAITING_FOR_ACCEPT
                    ) {
                        { app.cancelSend() }
                    } else {
                        { app.clearOutgoing() }
                    },
                )
            }
        }

        state.share?.let { share ->
            item(key = "browser-share") {
                MatterCardPadded(
                    modifier = Modifier.animateItem().fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("浏览器分享已开启", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                share.url,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        MatterTag(
                            text = "${share.fileCount} 个文件",
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        buildString {
                            append(formatBytes(share.totalBytes))
                            if (share.pinRequired) append(" · 需要 PIN")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HairlineDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            val manager = context.getSystemService(android.content.ClipboardManager::class.java)
                            manager?.setPrimaryClip(android.content.ClipData.newPlainText("Boxlet", share.url))
                        }) { Text("复制链接") }
                        TextButton(onClick = { app.stopShare() }) { Text("停止分享") }
                    }
                }
            }
        }

        item(key = "local-device") {
            LocalDeviceCard(
                modifier = Modifier.animateItem(),
                state = state,
                onBrowserShare = onBrowserShare,
            )
        }

    }

    actionDevice?.let { device ->
        AlertDialog(
            onDismissRequest = { actionDevice = null },
            title = { Text("发送到 ${device.alias}") },
            text = { Text(
                if (selection.isEmpty()) "请先通过上方六个入口添加要发送的内容。"
                else "发送 ${selection.size} 项内容 · ${formatBytes(selection.sumOf { it.size })}",
            ) },
            confirmButton = {
                TextButton(enabled = selection.isNotEmpty() && !sending, onClick = {
                    app.sendFiles(device.fingerprint, selection.toList())
                    actionDevice = null
                }) { Text(if (sending) "正在发送中" else "发送") }
            },
            dismissButton = { TextButton(onClick = { actionDevice = null }) { Text("取消") } },
        )
    }
}

/**
 * 本机信息卡：发送页只关心「我是谁、对方能不能找到我」，
 * 端口 / 协议 / PIN 这些接收侧细节交给设置页，这里一律不展示。
 */
@Composable
private fun LocalDeviceCard(state: UiState, onBrowserShare: () -> Unit, modifier: Modifier = Modifier) {
    MatterCardPadded(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(
                imageVector = deviceTypeIcon(state.settings.deviceType),
                size = 40.dp,
                iconSize = 20.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    state.settings.alias,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (state.serverRunning) "可被附近设备发现" else "接收服务未运行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.share == null) {
                TextButton(onClick = onBrowserShare) { Text("浏览器分享") }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    favorite: Boolean,
    now: Long,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MatterCardPadded(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(
                imageVector = deviceTypeIcon(device.deviceType),
                size = 44.dp,
                iconSize = 24.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.alias,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (now - device.lastSeen < Protocol.DEVICE_ONLINE_WINDOW_MS) {
                        Spacer(Modifier.width(8.dp))
                        MatterTag(text = "在线")
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        append(device.deviceModel ?: device.deviceType.name.lowercase())
                        append(" · ")
                        append(
                            if (now - device.lastSeen < Protocol.DEVICE_ONLINE_WINDOW_MS) {
                                "点击发送文件"
                            } else {
                                "最近可见 ${formatTime(device.lastSeen)}"
                            }
                        )
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = if (favorite) "取消收藏" else "收藏",
                    tint = if (favorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun transferSubtitle(status: TransferStatus): String = when (status) {
    TransferStatus.PREPARING -> "正在准备发送…"
    TransferStatus.WAITING_FOR_ACCEPT -> "等待对方接收…"
    TransferStatus.PIN_REQUIRED -> "需要 PIN"
    TransferStatus.TRANSFERRING -> "正在发送"
    TransferStatus.COMPLETED -> "发送完成"
    TransferStatus.FAILED -> "发送失败"
    TransferStatus.DECLINED -> "对方拒绝接收"
    TransferStatus.BUSY -> "对方正忙"
    TransferStatus.TOO_MANY_ATTEMPTS -> "PIN 尝试次数过多"
    TransferStatus.CANCELLED -> "已取消"
    TransferStatus.IDLE -> ""
}
