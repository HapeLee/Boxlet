package dev.boxlet.android.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 动画值仅在绘制阶段读取，渐变与蜂窝圆点缓慢流动，不逐帧重组页面。 */
@Composable
fun OrbitHero(receiving: Boolean, active: Boolean = true) {
    val colors = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "honeycomb-gradient")
    val phase = transition.animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Restart),
        label = "gradient-phase",
    )
    val heroHeight = with(LocalDensity.current) {
        (LocalWindowInfo.current.containerSize.height.toDp() * 0.39f).coerceIn(272.dp, 400.dp)
    }
    Column(
        modifier = Modifier.fillMaxWidth().height(heroHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
                val t = phase.value
                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                val r = size.minDimension * 0.245f
                val glow = center + Offset(cos(t) * r * 0.65f, sin(t) * r * 0.35f)
                drawRect(Brush.radialGradient(
                    listOf(colors.primary.copy(alpha = 0.16f), Color.Transparent),
                    center = glow, radius = size.width * 0.62f,
                ))
                drawRect(Brush.radialGradient(
                    listOf(colors.tertiary.copy(alpha = 0.12f), Color.Transparent),
                    center = center - Offset(cos(t) * r, sin(t) * r * 0.6f),
                    radius = size.width * 0.48f,
                ))
                // 交错半格的等距圆点形成蜂窝排列；边缘按椭圆范围柔和淡出。
                val spacing = 17.dp.toPx()
                val rowHeight = spacing * sqrt(3f) / 2f
                val columns = (size.width / spacing).toInt() + 1
                val rows = (size.height / rowHeight).toInt() + 1
                for (row in 0..rows) {
                    for (column in 0..columns) {
                        val dot = Offset(
                            column * spacing + if (row % 2 == 0) 0f else spacing / 2f,
                            row * rowHeight,
                        )
                        val dx = (dot.x - center.x) / (size.width * 0.54f)
                        val dy = (dot.y - center.y) / (size.height * 0.56f)
                        val fade = (1f - dx * dx - dy * dy).coerceIn(0f, 1f)
                        if (fade <= 0f) continue
                        val blend = (sin(t + column * 0.14f + row * 0.18f) + 1f) / 2f
                        drawCircle(
                            color = lerp(colors.primary, colors.tertiary, blend)
                                .copy(alpha = fade * (0.16f + 0.10f * blend)),
                            radius = (1.1f + 0.35f * blend).dp.toPx(),
                            center = dot,
                        )
                    }
                }
                drawCircle(Brush.radialGradient(
                    listOf(colors.primaryContainer, colors.primary.copy(alpha = 0.85f), colors.secondaryContainer),
                    center = center - Offset(r * 0.45f, r * 0.6f), radius = r * 2f,
                ), radius = r, center = center)
                drawCircle(colors.primary.copy(alpha = 0.3f), radius = r, center = center, style = Stroke(0.7.dp.toPx()))
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent, 0.18f to Color.Black,
                        0.76f to Color.Black, 1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            Icon(
                if (receiving) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                contentDescription = null, tint = colors.onPrimaryContainer,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            if (receiving) { if (active) "等待接收" else "接收服务未运行" } else "让分享，自然发生",
            style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (receiving) "保持连接，文件即刻抵达" else "发现附近设备，轻松传递文件与文字",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
    }
}
