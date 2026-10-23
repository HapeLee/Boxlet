package dev.boxlet.android.ui.glass

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.isRuntimeShaderSupported

/** 用户的全局液态玻璃开关（可在设置里关掉，低端机或省电场景用）。 */
val LocalBlurEffectsEnabled = compositionLocalOf { true }

/** 额外叠加 RuntimeShader 能力探测：Android 12 以下没有 AGSL，玻璃自动退化为半透明表面。 */
@Composable
fun rememberBlurEffectsEnabled(): Boolean {
    val enabled = LocalBlurEffectsEnabled.current
    return enabled && remember { isRuntimeShaderSupported() }
}

/**
 * 创建用于屏幕内容采样的 [LayerBackdrop]。
 *
 * 必须在离屏录制阶段先铺一层不透明底色（[backgroundColor]）再画内容，
 * 否则 LazyColumn 的透明区域会让玻璃采样到 Alpha=0 的像素，出现"黑洞"。
 */
@Composable
fun rememberScreenBackdrop(
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
): LayerBackdrop? {
    if (!rememberBlurEffectsEnabled()) return null
    return rememberLayerBackdrop(
        onDraw = {
            drawRect(backgroundColor)
            drawContent()
        }
    )
}

/** 顶栏渐变遮罩的峰值不透明度：32% 的 surface，滚到顶时是页面自己的一层淡底而不是压一层黑。 */
const val TopBarGradientSurfaceAlpha = 0.32f

/**
 * 「surface → 透明」的纵向羽化渐变。
 *
 * 用 5 段不透明度做 ease-out 衰减，避免 2 段渐变在深色主题下出现明显色带。
 */
@Composable
fun Modifier.alphaGradientSurface(
    color: Color = MaterialTheme.colorScheme.surface.copy(alpha = TopBarGradientSurfaceAlpha),
    topAlpha: Float = 1f,
): Modifier {
    val peak = (color.alpha * topAlpha).coerceIn(0f, 1f)
    val fractions = remember { listOf(1f, 0.88f, 0.55f, 0.22f, 0f) }
    return background(
        Brush.verticalGradient(colors = fractions.map { color.copy(alpha = peak * it) })
    )
}
