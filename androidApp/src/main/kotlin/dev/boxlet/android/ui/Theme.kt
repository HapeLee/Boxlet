package dev.boxlet.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.boxlet.android.ui.glass.LocalBlurEffectsEnabled
import dev.boxlet.shared.ThemeMode

/** 当前是否深色主题：玻璃前景阴影、状态栏图标配色都靠它判断。 */
val LocalBoxletDarkTheme = staticCompositionLocalOf { false }

/**
 * Boxlet 主题。
 *
 * 默认使用品牌蓝；[dynamicColor] 打开后在 Android 12+ 上改用系统 Monet 取色（与 Matter 一致）。
 * 液态玻璃的表面色/高光/阴影都从 [androidx.compose.material3.ColorScheme.surface] 的亮度推导，
 * 所以动态配色下玻璃质感依旧成立，只是色相跟随壁纸。
 */
@Composable
fun BoxletTheme(
    mode: ThemeMode,
    dynamicColor: Boolean = false,
    glassEffects: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    SyncSystemBars(dark)

    val context = LocalContext.current
    // 动态取色要解析系统资源，按关键输入记忆化，避免每次重组都重算（minSdk 31，Monet 一定可用）
    val colorScheme = remember(dark, dynamicColor) {
        when {
            dynamicColor -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            dark -> BoxletDarkColors
            else -> BoxletLightColors
        }
    }

    // MaterialExpressiveTheme：components 走 M3 Expressive 变体（形状 morph、按钮尺寸档位、
    // LoadingIndicator 等），动效默认换成 expressive 的弹簧曲线。
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
    ) {
        CompositionLocalProvider(
            LocalBoxletDarkTheme provides dark,
            LocalBlurEffectsEnabled provides glassEffects,
            content = content,
        )
    }
}

/**
 * `enableEdgeToEdge()` 只能按系统深浅色决定状态栏图标颜色；
 * 用户在设置里强制浅色/深色时这里再纠正一次，避免白底白字。
 */
@Composable
private fun SyncSystemBars(dark: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
