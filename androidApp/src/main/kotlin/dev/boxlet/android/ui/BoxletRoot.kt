package dev.boxlet.android.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.boxlet.shared.BoxletApp

@Composable
fun BoxletRoot(app: BoxletApp) {
    val state by app.state.collectAsStateWithLifecycle()

    BoxletTheme(
        mode = state.settings.theme,
        dynamicColor = state.settings.dynamicColor,
        glassEffects = state.settings.liquidGlass,
    ) {
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(state.error) {
            state.error?.let {
                snackbar.showSnackbar(it)
                app.clearError()
            }
        }

        // 用户确认接收后要跳到接收页：传输进度和已存文件都在那一页。
        // 用自增计数当信号，避免把 pager 状态抬到这一层。
        var receiveTabRequest by remember { mutableStateOf(0) }

        MainTabsScreen(
            app = app,
            state = state,
            snackbar = snackbar,
            navigateToReceive = receiveTabRequest,
        )

        state.pendingReceive?.let { request ->
            ReceiveRequestSheet(
                request = request,
                onAccept = { ids -> app.acceptReceive(ids); receiveTabRequest++ },
                onAcceptAll = { app.acceptAllReceive(); receiveTabRequest++ },
                onReject = { app.rejectReceive() },
            )
        }

        state.pinRequest?.let { request ->
            PinDialog(
                request = request,
                onSubmit = { app.submitPin(it) },
                onDismiss = { app.cancelPin() },
            )
        }
    }
}
