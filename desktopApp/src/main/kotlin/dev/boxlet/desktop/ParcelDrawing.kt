package dev.boxlet.desktop

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
 * Same projection and opaque faces as the launcher mark; no overlapping translucent lids.
 * [iconScale] insets and re-centres the mark on icon surfaces; null fills the whole unit square.
 */
internal fun DrawScope.drawBoxletParcel(
    closed: Float,
    shadow: Boolean = false,
    opacity: Float = 1f,
    iconScale: Float? = null,
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
    fun gradient(light: Long, shade: Long) = Brush.linearGradient(
        listOf(Color(light), Color(shade)),
        start = Offset(x(.2f), y(.22f)),
        end = Offset(x(.8f), y(.85f)),
    )
    if (shadow) drawOval(
        Brush.radialGradient(listOf(Color(Parcel.EdgeColor).copy(alpha = .10f), Color.Transparent),
            center = Offset(size.width * .5f, size.height * .88f), radius = size.width * .44f),
        topLeft = Offset(size.width * .12f, size.height * .80f), size = Size(size.width * .76f, size.height * .15f),
        alpha = opacity,
    )
    val outline = shape(Parcel.outline, .012f)
    clipPath(outline) {
        drawPath(shape(Parcel.left), gradient(Parcel.LeftColor, Parcel.LeftShadeColor), alpha = opacity)
        drawPath(shape(Parcel.right), gradient(Parcel.RightColor, Parcel.RightShadeColor), alpha = opacity)
        drawPath(shape(Parcel.interior), lerp(Color(Parcel.InteriorColor), Color(Parcel.TopColor), p), alpha = opacity)
    }
    val (rear, front) = Parcel.lids(p)
    fun lids() {
        drawPath(shape(rear, .008f * (1f - p)), gradient(Parcel.TopColor, Parcel.TopShadeColor), alpha = opacity)
        drawPath(shape(front, .008f * (1f - p)), gradient(Parcel.TopColor, Parcel.TopShadeColor), alpha = opacity)
    }
    if (p >= .999f) clipPath(outline) { lids() } else lids()
    val seal = ((p - .86f) / .14f).coerceIn(0f, 1f) * opacity
    if (seal > 0f) clipPath(outline) {
        drawPath(shape(Parcel.topTape, .004f), Color(Parcel.TapeColor), alpha = seal)
        drawPath(shape(Parcel.frontTape, .004f), Color(Parcel.TapeColor), alpha = seal)
    }
}
