package dev.boxlet.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.boxlet.android.ui.BoxletShapeTokens

/** 设备身份与服务状态共用的卡片标题，长设备名可自然换行。 */
@Composable
fun IdentityHeader(
    icon: ImageVector,
    eyebrow: String,
    title: String,
    description: String,
    active: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconBubble(
            imageVector = icon,
            size = 56.dp,
            iconSize = 28.dp,
            containerColor = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                else MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
            contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 内容卡片：直接使用 Matter 的 [MatterCard]（柔和描边、实色底、按下抬高 2dp）。
 *
 * Matter 的卡片不带内边距（由调用方自己加），Sendee 的页面沿用「卡片自带 20dp 内边距」的
 * 习惯，因此这里只加一层薄包装，卡片本体、描边、交互都来自 Matter。
 */
@Composable
fun MatterCardPadded(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    shape: Shape = MaterialTheme.shapes.medium,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MatterCard(
        modifier = modifier,
        containerColor = containerColor,
        shape = shape,
        onClick = onClick,
    ) {
        Column(Modifier.animateContentSize().padding(contentPadding), content = content)
    }
}

/** 圆形图标容器：12% 品牌色底 + 品牌色图标，卡片里的视觉锚点。 */
@Composable
fun IconBubble(
    imageVector: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    containerColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(BoxletShapeTokens.iconBubble)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = contentColor,
        )
    }
}

/** 列表分组标题行：左侧标题（可带计数），右侧操作。 */
@Composable
fun SectionTitle(
    title: String,
    modifier: Modifier = Modifier,
    count: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (count != null) {
            MatterTag(
                text = count.toString(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 卡片内的分区标题（可带说明）。 */
@Composable
fun SectionHeader(title: String, description: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (description != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 轻量分隔线，区分卡片内容而不抢占视觉焦点。 */
@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
            .height(0.5.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
    )
}
