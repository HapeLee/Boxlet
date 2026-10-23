package dev.boxlet.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Message
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.boxlet.core.engine.PendingReceive
import dev.boxlet.shared.PinRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveRequestSheet(
    request: PendingReceive,
    onAccept: (Set<String>) -> Unit,
    onAcceptAll: () -> Unit,
    onReject: () -> Unit,
) {
    val selected = remember(request.sessionId) {
        mutableStateMapOf<String, Boolean>().apply {
            request.files.forEach { put(it.id, true) }
        }
    }

    ModalBottomSheet(onDismissRequest = onReject) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReceiveClawIllustration(
                    phase = ReceiveVisualState.Request,
                    sessionId = request.sessionId,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("来自 ${request.peer.alias} 的传输请求", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${request.files.size} 个文件 · ${formatBytes(request.files.sumOf { it.size })}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            if (request.isMessage && request.message != null) {
                val message = request.message.orEmpty()
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.AutoMirrored.Rounded.Message, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(request.files, key = { it.id }) { file ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = selected[file.id] == true,
                                onCheckedChange = { selected[file.id] = it },
                            )
                            Icon(mimeIcon(file.fileType), contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.fileName, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    formatBytes(file.size),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onReject) { Text("拒绝") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onAcceptAll) { Text("全部接收") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val ids = selected.filterValues { it }.keys
                        if (ids.isEmpty()) onReject() else onAccept(ids)
                    },
                ) { Text("接收所选") }
            }
        }
    }
}

@Composable
fun PinDialog(
    request: PinRequest,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("需要 PIN") },
        text = {
            Column {
                Text("${request.deviceAlias.ifBlank { "对方设备" }} 要求输入 PIN 才能发送。")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("PIN") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (request.attempt > 1) {
                    Text(
                        "PIN 不正确，请重试（第 ${request.attempt} 次）",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(value.trim()) }, enabled = value.isNotBlank()) { Text("发送") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
