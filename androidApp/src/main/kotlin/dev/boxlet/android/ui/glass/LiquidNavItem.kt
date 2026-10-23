package dev.boxlet.android.ui.glass

import androidx.compose.ui.graphics.vector.ImageVector

data class LiquidNavItem(
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
)
