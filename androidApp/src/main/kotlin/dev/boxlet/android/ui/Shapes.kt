package dev.boxlet.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

object BoxletShapeTokens {
    val tag = RoundedCornerShape(8.dp)
    val badge = RoundedCornerShape(4.dp)
    val pill = RoundedCornerShape(percent = 50)
    val listItem = RoundedCornerShape(16.dp)
    val iconBubble = RoundedCornerShape(percent = 50)
}

/** Matter 的圆角 token，供从 Matter 移植的组件原样使用。 */
object MatterShapeTokens {
    /** 小标签 / 计数徽标 */
    val tag = RoundedCornerShape(6.dp)

    /** 图片、视频上的半透明角标 */
    val mediaBadge = RoundedCornerShape(4.dp)

    /** 全圆角胶囊 */
    val pill = RoundedCornerShape(percent = 50)

    /** 列表项卡片（略小于标准卡片） */
    val listItem = RoundedCornerShape(16.dp)
}
