package dev.boxlet.android.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.boxlet.core.protocol.Device
import dev.boxlet.core.protocol.Protocol

/**
 * 从系统「分享」进来后选一个目标设备。文件先不读，选中设备时再转成 [dev.boxlet.core.engine.OutgoingFile]，
 * 避免在弹窗阶段就去开一堆 ContentResolver 流。
 */
@Composable
fun SharedFilesDialog(
    files: List<Uri>,
    devices: List<Device>,
    onSend: (Device) -> Unit,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
) {
    val context = LocalContext.current
    // content:// 的 lastPathSegment 是数字 ID，弹窗里要显示真实文件名。
    val names = remember(files) { files.map { displayName(context, it) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发送分享的 ${files.size} 个文件") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                names.take(4).forEach { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (files.size > 4) {
                    Text("…另有 ${files.size - 4} 个", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                if (devices.isEmpty()) {
                Text("还没有发现设备。确认对方已打开 Boxlet，或先扫描一次。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    devices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSend(device) }
                                .padding(vertical = 12.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(device.alias, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    buildString {
                                        append(device.deviceModel ?: device.deviceType.name.lowercase())
                                        if (System.currentTimeMillis() - device.lastSeen < Protocol.DEVICE_ONLINE_WINDOW_MS) {
                                            append(" · 在线")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (devices.isEmpty()) {
                TextButton(onClick = onScan) { Text("扫描设备") }
            } else {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        dismissButton = if (devices.isEmpty()) {
            { TextButton(onClick = onDismiss) { Text("取消") } }
        } else {
            null
        },
    )
}

private fun displayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
