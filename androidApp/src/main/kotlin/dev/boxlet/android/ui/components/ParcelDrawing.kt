package dev.boxlet.android.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import dev.boxlet.shared.brand.ParcelGeometry as Parcel
import kotlin.math.hypot

/**
 * 包裹三面的取色板。
 *
 * 默认是品牌蓝（[Brand]，与导出的启动图标一致）；界面内的插画用 [of] 从当前主色推导，
 * 只保留同一套「左面最亮、右面最暗、顶面更亮」的明暗关系，因此 Monet 换色后立体感不变。
 */
@Immutable
data class ParcelPalette(
    val left: Color,
    val leftShade: Color,
    val right: Color,
    val rightShade: Color,
    val top: Color,
    val topShade: Color,
    val interior: Color,
    val tape: Color,
    val edge: Color,
) {
    companion object {
        /** 品牌固定配色：图标、安装包等不随主题变化的地方使用。 */
        val Brand = ParcelPalette(
            left = Color(Parcel.LeftColor),
            leftShade = Color(Parcel.LeftShadeColor),
            right = Color(Parcel.RightColor),
            rightShade = Color(Parcel.RightShadeColor),
            top = Color(Parcel.TopColor),
            topShade = Color(Parcel.TopShadeColor),
            interior = Color(Parcel.InteriorColor),
            tape = Color(Parcel.TapeColor),
            edge = Color(Parcel.EdgeColor),
        )

        /** 从主色推导一整套包裹配色。 */
        fun of(base: Color): ParcelPalette = ParcelPalette(
            left = base,
            leftShade = lerp(base, Color.Black, .32f),
            right = lerp(base, Color.Black, .16f),
            rightShade = lerp(base, Color.Black, .40f),
            top = lerp(base, Color.White, .42f),
            topShade = lerp(base, Color.White, .12f),
            interior = lerp(base, Color.Black, .55f),
            tape = lerp(base, Color.White, .58f),
            edge = lerp(base, Color.Black, .62f),
        )
    }
}

/** 当前主题下的包裹配色；切换浅色/深色或 Monet 取色后自动跟随。 */
@Composable
fun rememberParcelPalette(): ParcelPalette {
    val primary = MaterialTheme.colorScheme.primary
    return remember(primary) { ParcelPalette.of(primary) }
}

/**
 * Same projection and opaque faces as the launcher mark; no overlapping translucent lids.
 * [iconScale] insets and re-centres the mark on icon surfaces; null fills the whole unit square.
 */
internal fun DrawScope.drawBoxletParcel(
    closed: Float,
    shadow: Boolean = false,
    opacity: Float = 1f,
    iconScale: Float? = null,
    palette: ParcelPalette = ParcelPalette.Brand,
) {
    val p = closed.coerceIn(0f, 1f)
    val inset = iconScale
    fun x(value: Float) = size.width * (if (inset == null) value else .5f + inset * (value - Parcel.IconCenter.x))
    fun y(value: Float) = size.height * (if (inset == null) value else .5f + inset * (value - Parcel.IconCenter.y))
    fun shape(points: List<Parcel.Point>, cut: Float = 0f): Path = Path().apply {
        if (cut == 0f) {
            moveTo(x(points.first().x), y(points.first().y))
            points.drop(1).forEach { lineTo(x(it.x), y(it.y)) }
        } else {
            points.forEachIndexed { i, vertex ->
                fun toward(other: Parcel.Point): Offset {
                    val distance = hypot(other.x - vertex.x, other.y - vertex.y)
                    val t = if (distance == 0f) 0f else (cut / distance).coerceAtMost(.45f)
                    return Offset(x(vertex.x + (other.x - vertex.x) * t),
                        y(vertex.y + (other.y - vertex.y) * t))
                }
                val entry = toward(points[(i + points.size - 1) % points.size])
                val exit = toward(points[(i + 1) % points.size])
                if (i == 0) moveTo(entry.x, entry.y) else lineTo(entry.x, entry.y)
                quadraticTo(x(vertex.x), y(vertex.y), exit.x, exit.y)
            }
        }
        close()
    }
    fun gradient(light: Color, shade: Color) = Brush.linearGradient(
        listOf(light, shade),
        start = Offset(x(.2f), y(.22f)),
        end = Offset(x(.8f), y(.85f)),
    )
    if (shadow) drawOval(
        Brush.radialGradient(listOf(palette.edge.copy(alpha = .10f), Color.Transparent),
            center = Offset(size.width * .5f, size.height * .88f), radius = size.width * .44f),
        topLeft = Offset(size.width * .12f, size.height * .80f), size = Size(size.width * .76f, size.height * .15f),
        alpha = opacity,
    )
    val outline = shape(Parcel.outline, .012f)
    clipPath(outline) {
        drawPath(shape(Parcel.left), gradient(palette.left, palette.leftShade), alpha = opacity)
        drawPath(shape(Parcel.right), gradient(palette.right, palette.rightShade), alpha = opacity)
        drawPath(shape(Parcel.interior), lerp(palette.interior, palette.top, p), alpha = opacity)
    }
    val (rear, front) = Parcel.lids(p)
    fun lids() {
        drawPath(shape(rear, .008f * (1f - p)), gradient(palette.top, palette.topShade), alpha = opacity)
        drawPath(shape(front, .008f * (1f - p)), gradient(palette.top, palette.topShade), alpha = opacity)
    }
    if (p >= .999f) clipPath(outline) { lids() } else lids()
    val seal = ((p - .86f) / .14f).coerceIn(0f, 1f) * opacity
    if (seal > 0f) clipPath(outline) {
        drawPath(shape(Parcel.topTape, .004f), palette.tape, alpha = seal)
        drawPath(shape(Parcel.frontTape, .004f), palette.tape, alpha = seal)
    }
}
