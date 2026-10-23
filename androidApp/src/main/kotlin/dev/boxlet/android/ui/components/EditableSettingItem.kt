package dev.boxlet.android.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/** 列表只展示已保存的值；草稿仅在确认后提交，取消或关闭对话框不修改设置。 */
@Composable
fun EditableSettingItem(
    title: String,
    value: String,
    onCommit: (String) -> Unit,
    modifier: Modifier = Modifier,
    valueSummary: String = value.ifBlank { "未设置" },
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    sensitive: Boolean = false,
    validate: (String) -> String? = { null },
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    SettingItem(
        title = title,
        description = valueSummary,
        modifier = modifier,
        semanticRole = Role.Button,
        onClick = { editing = true },
        trailingContent = { Icon(Icons.Rounded.Edit, contentDescription = null) },
    )

    if (editing) {
        var draft by rememberSaveable(value) { mutableStateOf(value) }
        val error = validate(draft)
        val focusRequester = remember { FocusRequester() }
        fun save() {
            if (error == null) {
                if (draft != value) onCommit(draft)
                editing = false
            }
        }

        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(title) },
                        singleLine = true,
                        isError = error != null,
                        supportingText = if (error != null || supportingText != null) {
                            { Text(error ?: supportingText.orEmpty()) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        visualTransformation = if (sensitive) PasswordVisualTransformation() else VisualTransformation.None,
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { save() }, enabled = error == null) { Text("保存") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("取消") } },
        )
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}
