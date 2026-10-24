package dev.boxlet.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.boxlet.android.ui.components.IconBubble
import dev.boxlet.android.ui.components.MatterCardPadded
import dev.boxlet.core.engine.FileTransferStatus
import dev.boxlet.core.engine.TransferFileState
import dev.boxlet.core.engine.TransferStatus
import dev.boxlet.core.protocol.DeviceType
import kotlinx.coroutines.delay

fun deviceTypeIcon(type: DeviceType): ImageVector = when (type) {
    DeviceType.MOBILE -> Icons.Rounded.Android
    DeviceType.DESKTOP -> Icons.Rounded.Computer
    DeviceType.WEB -> Icons.Rounded.Language
    DeviceType.HEADLESS, DeviceType.SERVER -> Icons.Rounded.Computer
}

@Composable
fun DeviceTypeIcon(type: DeviceType, modifier: Modifier = Modifier) {
    Icon(deviceTypeIcon(type), contentDescription = null, modifier = modifier)
}

fun mimeIcon(mime: String): ImageVector = when {
    mime.startsWith("image/") -> Icons.Rounded.Image
    mime.startsWith("video/") -> Icons.Rounded.Movie
    mime.startsWith("text/") -> Icons.AutoMirrored.Rounded.TextSnippet
    mime == "application/pdf" -> Icons.Rounded.Description
    mime.contains("android.package") -> Icons.Rounded.Android
    else -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 空态：72dp 的柔和圆形图标底 + 紧凑文案。
 * 比 M3 默认的"大图标 + 长句"更克制，不会在玻璃栏下面显得突兀。
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBubble(
            imageVector = icon,
            size = 80.dp,
            iconSize = 32.dp,
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
        )
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        action?.let {
            Spacer(Modifier.height(16.dp))
            it()
        }
    }
}

/**
 * 从累计字节数推速度：每 400ms 采样一次，和上一次做指数平滑，数字不会乱跳。
 *
 * 引擎那边 100ms 才推一次进度，所以这里再快也没有意义。
 */
@Composable
private fun rememberTransferRate(transferred: Long, active: Boolean): Long {
    var rate by remember { mutableStateOf(0L) }
    val latest = rememberUpdatedState(transferred)
    LaunchedEffect(active) {
        if (!active) {
            rate = 0L
            return@LaunchedEffect
        }
        var lastBytes = latest.value
        var lastAt = System.nanoTime()
        while (true) {
            delay(400)
            val now = System.nanoTime()
            val bytes = latest.value
            val seconds = (now - lastAt) / 1_000_000_000.0
            if (seconds > 0.05) {
                val instant = ((bytes - lastBytes).coerceAtLeast(0) / seconds).toLong()
                rate = if (rate <= 0L) instant else (rate * 0.55 + instant * 0.45).toLong()
            }
            lastBytes = bytes
            lastAt = now
        }
    }
    return rate
}

@Composable
fun TransferCard(
    title: String,
    subtitle: String,
    progress: Float,
    status: TransferStatus,
    files: List<TransferFileState>,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
    error: String? = null,
    footer: String? = null,
    onTogglePause: (() -> Unit)? = null,
    onResetFile: ((String) -> Unit)? = null,
) {
    val transferring = status == TransferStatus.TRANSFERRING
    val busy = transferring || status == TransferStatus.PAUSED ||
        status == TransferStatus.PREPARING ||
        status == TransferStatus.WAITING_FOR_ACCEPT
    val transferred = files.sumOf { it.bytesTransferred }
    val totalBytes = files.sumOf { it.size }
    val rate = rememberTransferRate(transferred, transferring)
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 250),
        label = "transfer-progress",
    )
    val speedText = if (transferring && rate > 0) {
        val remaining = (totalBytes - transferred).coerceAtLeast(0)
        val eta = formatEta(remaining / rate.coerceAtLeast(1))
        if (eta.isNotEmpty()) "${formatSpeed(rate)} · 剩 $eta" else formatSpeed(rate)
    } else {
        ""
    }

    MatterCardPadded(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.takeIf { it.isNotBlank() }?.let { reason ->
                    Text(
                        reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (transferring || status == TransferStatus.PREPARING) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (transferring || status == TransferStatus.PAUSED || status == TransferStatus.COMPLETED) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(percent = 50)),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${formatBytes(transferred)} / ${formatBytes(totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    speedText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        files.take(6).forEach { file ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    mimeIcon(file.mimeType),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        file.fileName.substringAfterLast('/'),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    file.error?.let { fileError ->
                        Text(
                            fileError,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Text(
                    statusLabel(file),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (files.size > 6) {
            Text(
                "另有 ${files.size - 6} 个文件",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (status == TransferStatus.PAUSED && onResetFile != null) {
            files.filter { it.restartRequired }.forEach { file ->
                TextButton(onClick = { onResetFile(file.id) }) {
                    Text("使用当前内容从头重传：${file.fileName.substringAfterLast('/')}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        footer?.takeIf { it.isNotBlank() }?.let { hint ->
            Spacer(Modifier.height(8.dp))
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onCancel != null || onTogglePause != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onTogglePause != null) TextButton(onClick = onTogglePause) {
                    Text(if (status == TransferStatus.PAUSED) "继续传输" else "暂停传输")
                }
                if (onCancel != null) TextButton(onClick = onCancel) {
                    Text(if (busy) "取消传输" else "关闭")
                }
            }
        }
    }
}

private fun statusLabel(file: TransferFileState): String = when (file.status) {
    FileTransferStatus.QUEUED -> formatBytes(file.size)
    FileTransferStatus.TRANSFERRING ->
        "${file.bytesTransferred * 100 / file.size.coerceAtLeast(1)}% · ${formatBytes(file.bytesTransferred)}"

    FileTransferStatus.COMPLETED -> "完成 · ${formatBytes(file.size)}"
    FileTransferStatus.FAILED -> "失败"
    FileTransferStatus.SKIPPED -> "跳过"
}
