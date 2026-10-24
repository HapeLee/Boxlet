package dev.boxlet.android.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.boxlet.android.ui.components.MatterTag
import dev.boxlet.core.engine.FileTransferStatus
import dev.boxlet.core.engine.TransferStatus
import dev.boxlet.shared.BoxletApp
import dev.boxlet.shared.UiState

internal enum class ReceiveVisualState { Ready, Request, Receiving, Completed, Failed, Cancelled, Offline }

@Composable
internal fun ReceiveStatusCard(
    app: BoxletApp,
    state: UiState,
    onViewFiles: () -> Unit,
) {
    val incoming = state.incoming
    val pending = state.pendingReceive
    val phase = when {
        pending != null -> ReceiveVisualState.Request
        incoming?.status == TransferStatus.TRANSFERRING || incoming?.status == TransferStatus.PAUSED || incoming?.status == TransferStatus.PREPARING -> ReceiveVisualState.Receiving
        incoming?.status == TransferStatus.COMPLETED -> ReceiveVisualState.Completed
        incoming?.status == TransferStatus.FAILED -> ReceiveVisualState.Failed
        incoming?.status == TransferStatus.CANCELLED -> ReceiveVisualState.Cancelled
        !state.serverRunning -> ReceiveVisualState.Offline
        else -> ReceiveVisualState.Ready
    }
    val completedFiles = incoming?.files.orEmpty().filter { it.status == FileTransferStatus.COMPLETED }
    val progress = incoming?.progress ?: 0f
    // 先呈现设备身份和当前状态，端口与 PIN 作为次要信息放在最后。
    val title = when (phase) {
        ReceiveVisualState.Ready -> "已准备好接收"
        ReceiveVisualState.Request -> "${pending?.peer?.alias} 发来 ${pending?.files?.size ?: 0} 项内容"
        ReceiveVisualState.Receiving -> "正在接收 · ${(progress * 100).toInt()}%"
        ReceiveVisualState.Completed -> "接收完成"
        ReceiveVisualState.Failed -> if (completedFiles.isEmpty()) "接收失败" else "部分内容未能接收"
        ReceiveVisualState.Cancelled -> "接收已取消"
        ReceiveVisualState.Offline -> "接收服务未运行"
    }
    val statusColor = when (phase) {
        ReceiveVisualState.Failed -> MaterialTheme.colorScheme.error
        ReceiveVisualState.Ready, ReceiveVisualState.Request,
        ReceiveVisualState.Receiving, ReceiveVisualState.Completed -> MaterialTheme.colorScheme.primary
        ReceiveVisualState.Cancelled, ReceiveVisualState.Offline -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val action = when {
        phase == ReceiveVisualState.Request -> ReceiveVisualState.Request
        phase == ReceiveVisualState.Offline -> ReceiveVisualState.Offline
        completedFiles.any { !it.savedPath.isNullOrBlank() } -> ReceiveVisualState.Completed
        else -> null
    }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            modifier = Modifier.fillMaxWidth().animateContentSize().padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReceiveClawIllustration(phase, progress,
                    sessionId = pending?.sessionId ?: incoming?.sessionId,
                    modifier = Modifier.size(88.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = state.settings.alias,
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = statusColor,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "端口 ${state.settings.port}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!state.settings.receivePin.isNullOrBlank()) {
                            MatterTag(
                                text = "PIN",
                                style = MaterialTheme.typography.labelSmallEmphasized,
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (action != null) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    when (action) {
                        ReceiveVisualState.Request -> {
                            TextButton(onClick = { app.rejectReceive() }) { Text("拒绝") }
                            Button(onClick = { app.acceptAllReceive() }) { Text("全部接收") }
                        }
                        ReceiveVisualState.Offline -> TextButton(onClick = { app.ensureRestarted() }) { Text("启动服务") }
                        ReceiveVisualState.Completed -> TextButton(onClick = onViewFiles) { Text("查看文件") }
                        else -> Unit
                    }
                }
            }
        }
    }
}

