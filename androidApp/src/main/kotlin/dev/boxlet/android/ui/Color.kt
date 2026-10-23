package dev.boxlet.android.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Boxlet 品牌色板。
 *
 * 液态玻璃的所有光学参数都从 `surface` 的亮度推导（见 LiquidGlassSurfaceSpec），
 * 因此这里的明暗两套 scheme 必须让 surface 足够"实"：
 * 亮色接近白纸、深色接近纯黑，玻璃叠上去才有景深。
 */
val BoxletBlue = Color(0xFF0F6CBD)
val BoxletBlueDark = Color(0xFF0B5AA0)
val BoxletCyan = Color(0xFF00677D)

val BoxletLightColors = lightColorScheme(
    primary = BoxletBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E8FF),
    onPrimaryContainer = Color(0xFF001C38),
    inversePrimary = Color(0xFF9FCBFF),

    secondary = Color(0xFF4C6178),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE8F7),
    onSecondaryContainer = Color(0xFF0A1F33),

    tertiary = BoxletCyan,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8F0FA),
    onTertiaryContainer = Color(0xFF00343F),

    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF17191D),
    surface = Color(0xFFF4F6FB),
    onSurface = Color(0xFF17191D),
    surfaceVariant = Color(0xFFE1E6EF),
    onSurfaceVariant = Color(0xFF5A6068),
    surfaceTint = BoxletBlue,

    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFDCE1EA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEEF1F7),
    surfaceContainerHigh = Color(0xFFE8ECF3),
    surfaceContainerHighest = Color(0xFFE1E6EE),

    outline = Color(0xFF767C86),
    outlineVariant = Color(0xFFC9CFD9),
    inverseSurface = Color(0xFF2E3135),
    inverseOnSurface = Color(0xFFF1F3F7),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

val BoxletDarkColors = darkColorScheme(
    primary = Color(0xFF9FCBFF),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF0A4A7D),
    onPrimaryContainer = Color(0xFFD7E8FF),
    inversePrimary = BoxletBlue,

    secondary = Color(0xFFB3C8E2),
    onSecondary = Color(0xFF1C3246),
    secondaryContainer = Color(0xFF26405B),
    onSecondaryContainer = Color(0xFFD3E4FF),

    tertiary = Color(0xFF7FD8EE),
    onTertiary = Color(0xFF00343F),
    tertiaryContainer = Color(0xFF004E5C),
    onTertiaryContainer = Color(0xFFC8F0FA),

    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFE7E9ED),
    surfaceVariant = Color(0xFF2A2F38),
    onSurfaceVariant = Color(0xFFA8AFB9),
    surfaceTint = Color(0xFF9FCBFF),

    surfaceBright = Color(0xFF353A42),
    surfaceDim = Color(0xFF0E1116),
    surfaceContainerLowest = Color(0xFF080A0D),
    surfaceContainerLow = Color(0xFF14171D),
    surfaceContainer = Color(0xFF191D24),
    surfaceContainerHigh = Color(0xFF20252D),
    surfaceContainerHighest = Color(0xFF2A303A),

    outline = Color(0xFF8A9099),
    outlineVariant = Color(0xFF3A414B),
    inverseSurface = Color(0xFFE7E9ED),
    inverseOnSurface = Color(0xFF2E3135),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)
