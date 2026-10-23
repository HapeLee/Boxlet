package dev.boxlet.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import dev.boxlet.android.ui.components.ParcelPalette
import dev.boxlet.android.ui.components.drawBoxletParcel
import dev.boxlet.android.ui.components.rememberParcelPalette
import dev.boxlet.shared.brand.ParcelGeometry as Parcel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 矮顶座、双叉臂与传送带组成紧凑的收件空间，所有结构沿用包裹投影。 */
@Composable
internal fun ReceiveClawIllustration(
    phase: ReceiveVisualState,
    progress: Float = 0f,
    sessionId: String? = null,
    modifier: Modifier = Modifier,
) {
    val extension = remember(sessionId) { Animatable(0f) }
    val parcel = remember(sessionId) { Animatable(0f) }
    val visible = phase in setOf(ReceiveVisualState.Request, ReceiveVisualState.Receiving,
        ReceiveVisualState.Completed, ReceiveVisualState.Failed, ReceiveVisualState.Cancelled)
    val target = when (phase) {
        ReceiveVisualState.Completed -> 1f
        ReceiveVisualState.Receiving, ReceiveVisualState.Failed, ReceiveVisualState.Cancelled ->
            .2f + .8f * progress.coerceIn(0f, 1f)
        else -> 0f
    }
    LaunchedEffect(target, sessionId) { extension.animateTo(target, tween(300)) }
    LaunchedEffect(visible, sessionId) { parcel.animateTo(if (visible) 1f else 0f, tween(220)) }
    val complete = animateFloatAsState(if (phase == ReceiveVisualState.Completed) 1f else 0f,
        tween(300), label = "receive-fork-complete")
    // 机械臂、传送带与包裹共用当前主题色；离线态退化为主题的中性色
    val palette = rememberParcelPalette()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val checkColor = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        withTransform({
            translate(size.width * .14f, size.height * .20f)
            scale(.72f, .72f, pivot = Offset.Zero)
        }) {
            drawReceiveFork(extension.value, parcel.value, complete.value,
                palette = palette, muted = muted, checkColor = checkColor,
                offline = phase == ReceiveVisualState.Offline)
        }
    }
}

/** All edges use the parcel's U/V/vertical axes, including the stem and its split. */
private fun DrawScope.drawReceiveFork(
    extension: Float,
    packageAlpha: Float,
    complete: Float,
    palette: ParcelPalette,
    muted: Color,
    checkColor: Color,
    offline: Boolean,
) {
    fun tone(color: Color) = if (offline) lerp(color, muted, .88f) else color
    val topColor = tone(palette.top)
    val leftColor = tone(palette.left)
    val rightColor = tone(palette.rightShade)
    val u0 = .41f
    val u1 = .59f
    val drop = .40f * extension.coerceIn(0f, 1f)
    val beamTop = .76f - drop
    val beamBottom = .61f - drop
    val tip = -.01f - drop

    fun face(color: Color, vararg points: Parcel.Point, edge: Color? = null) {
        val path = Path().apply {
            moveTo(points[0].x * size.width, points[0].y * size.height)
            points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
            close()
        }
        drawPath(path, color)
        if (edge != null) drawPath(path, edge, style = Stroke(width = size.minDimension * .007f))
    }
    fun prism(v0: Float, v1: Float, bottom: Float, top: Float, cap: Boolean = false) {
        face(leftColor, Parcel.project(u0, v1, top), Parcel.project(u1, v1, top),
            Parcel.project(u1, v1, bottom), Parcel.project(u0, v1, bottom))
        face(rightColor, Parcel.project(u1, v1, top), Parcel.project(u1, v0, top),
            Parcel.project(u1, v0, bottom), Parcel.project(u1, v1, bottom))
        if (cap) face(topColor, Parcel.project(u0, v0, top), Parcel.project(u1, v0, top),
            Parcel.project(u1, v1, top), Parcel.project(u0, v1, top))
    }

    fun plate(minU: Float, maxU: Float, minV: Float, maxV: Float, bottom: Float, top: Float, glass: Boolean) {
        face(leftColor.copy(alpha = if (glass) .24f else .60f),
            Parcel.project(minU, maxV, top), Parcel.project(maxU, maxV, top),
            Parcel.project(maxU, maxV, bottom), Parcel.project(minU, maxV, bottom))
        face(rightColor.copy(alpha = if (glass) .30f else .55f),
            Parcel.project(maxU, maxV, top), Parcel.project(maxU, minV, top),
            Parcel.project(maxU, minV, bottom), Parcel.project(maxU, maxV, bottom))
        face(topColor.copy(alpha = if (glass) .26f else .48f),
            Parcel.project(minU, minV, top), Parcel.project(maxU, minV, top),
            Parcel.project(maxU, maxV, top), Parcel.project(minU, maxV, top),
            edge = topColor.copy(alpha = if (glass) .70f else .55f))
    }

    // A wider, low conveyor balances the short canopy above. Slats follow real progress,
    // so the belt stays still while waiting, paused or finished.
    val beltMin = -.40f
    val beltMax = 1.40f
    val beltTop = -1.03f
    // The belt runs along U: upper-left to lower-right in the parcel's projection.
    plate(beltMin, beltMax, -.08f, 1.08f, bottom = -1.20f, top = beltTop, glass = false)
    val spacing = .30f
    val offset = (extension.coerceIn(0f, 1f) * 1.20f) % spacing
    for (index in 0..6) {
        val u = beltMin + index * spacing + offset
        val end = (u + .025f).coerceAtMost(beltMax)
        if (u >= beltMax) continue
        face(rightColor.copy(alpha = .17f),
            Parcel.project(u, -.015f, beltTop), Parcel.project(end, -.015f, beltTop),
            Parcel.project(end, 1.015f, beltTop), Parcel.project(u, 1.015f, beltTop))
    }
    // Two slim edge strips and end rollers are enough to read as a conveyor at 72–88 dp.
    for ((low, high) in listOf(-.08f to -.025f, 1.025f to 1.08f)) {
        face(leftColor.copy(alpha = .35f),
            Parcel.project(beltMin, low, beltTop), Parcel.project(beltMax, low, beltTop),
            Parcel.project(beltMax, high, beltTop), Parcel.project(beltMin, high, beltTop))
    }
    for (u in listOf(beltMin + .10f, beltMax - .10f)) {
        val roller = Path().apply {
            for (segment in 0..20) {
                val angle = segment * (2.0 * PI / 20.0)
                val point = Parcel.project(u + cos(angle).toFloat() * .06f, 1.082f,
                    -1.115f + sin(angle).toFloat() * .055f)
                if (segment == 0) moveTo(point.x * size.width, point.y * size.height)
                else lineTo(point.x * size.width, point.y * size.height)
            }
            close()
        }
        drawPath(roller, topColor.copy(alpha = .85f))
    }
    // Far arm stays behind the parcel, near arm in front: one continuous U-shaped fork.
    prism(-.30f, -.10f, tip, beamBottom)
    if (packageAlpha > 0f) drawBoxletParcel(1f, shadow = true, opacity = packageAlpha, palette = palette)
    prism(1.10f, 1.30f, tip, beamBottom)
    prism(-.30f, 1.30f, beamBottom, beamTop, cap = true)
    prism(.41f, .59f, beamTop, .90f)
    // Draw the translucent cap last so the stem reads through its pale top and sides.
    plate(.12f, .88f, -.10f, 1.10f, bottom = .90f, top = 1.02f, glass = true)

    if (complete > 0f) {
        val check = Path().apply {
            moveTo(size.width * .87f, size.height * .75f)
            lineTo(size.width * .92f, size.height * .80f)
            lineTo(size.width * 1.02f, size.height * .68f)
        }
        drawPath(check, checkColor, alpha = complete,
            style = Stroke(width = size.minDimension * .025f, cap = StrokeCap.Square, join = StrokeJoin.Miter))
    }
}
