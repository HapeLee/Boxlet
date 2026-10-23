package dev.boxlet.desktop

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import dev.boxlet.shared.brand.ParcelGeometry as Parcel

/**
 * 托盘和窗口图标沿用与应用启动器相同的立体包裹标记，留白比例也取自 `ParcelGeometry.IconScale`。
 */
class BoxletTrayIcon : Painter() {

    override val intrinsicSize: Size = Size(ICON_SIZE, ICON_SIZE)

    override fun DrawScope.onDraw() {
        val side = size.minDimension
        drawRoundRect(
            color = Color(Parcel.BackgroundColor),
            topLeft = Offset.Zero,
            size = Size(side, side),
            cornerRadius = CornerRadius(side * TILE_CORNER_RADIUS),
        )
        drawBoxletParcel(1f, iconScale = Parcel.IconScale)
    }

    private companion object {
        const val ICON_SIZE = 32f

        /** 与导出的 108 单位图标瓦片一致：24/108 圆角。 */
        const val TILE_CORNER_RADIUS = 24f / 108f
    }
}
