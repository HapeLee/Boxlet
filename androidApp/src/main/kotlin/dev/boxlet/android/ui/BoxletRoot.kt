package dev.boxlet.android.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

        MainTabsScreen(app = app, state = state, snackbar = snackbar)

        state.pendingReceive?.let { request ->
            ReceiveRequestSheet(
                request = request,
                onAccept = { ids -> app.acceptReceive(ids) },
                onAcceptAll = { app.acceptAllReceive() },
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
