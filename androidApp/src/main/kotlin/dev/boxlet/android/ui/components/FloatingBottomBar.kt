package dev.boxlet.android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.boxlet.android.ui.glass.LegadoLiquidFloatingBottomBar
import dev.boxlet.android.ui.glass.LiquidGlassIcon
import dev.boxlet.android.ui.glass.LiquidGlassIconButton
import dev.boxlet.android.ui.glass.LiquidNavItem
import dev.boxlet.android.ui.glass.legadoBarWidth
import kotlinx.coroutines.launch

/** 底栏与导航栏之间的留白。 */
internal val BAR_BOTTOM_PADDING = 12.dp

/** 保留定制悬浮底栏原始高度。 */
internal val BAR_ROW_HEIGHT = 56.dp

/** 悬浮胶囊与两侧悬浮按钮之间的间距。 */
private val BAR_BUTTON_GAP = 10.dp

/** 悬浮按钮直径。 */
private val BAR_BUTTON_SIZE = 56.dp

/** 悬浮按钮与屏幕边缘至少要留出的距离。 */
private val BAR_EDGE_MARGIN = 16.dp

/** 底栏悬浮按钮的数据载体（图标 + 可访问性文案 + 点击）。 */
data class FloatingGlassAction(
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
)

/** 内容避让高度：胶囊 + 底部留白 + 系统导航栏。 */
@Composable
fun bottomBarContentPadding(): Dp =
    BAR_ROW_HEIGHT + BAR_BOTTOM_PADDING +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * 记住最后一次非空的悬浮按钮内容。
 *
 * 切页时调用方可能把 `showActions` 和 action 同时置空；如果直接按当前值渲染，
 * 按钮会在退场动画开始前就被移除，看起来是"啪"地消失。这里保留旧内容，
 * 等 `buttonReveal` 缩回 0 后随 `if` 一起卸载，退场因此是完整的。
 */
@Composable
private fun rememberLastAction(action: FloatingGlassAction?): FloatingGlassAction? {
    var last by remember { mutableStateOf(action) }
    SideEffect { if (action != null) last = action }
    return action ?: last
}

/**
 * 悬浮液态玻璃底栏层（全屏 overlay）：胶囊本体使用
 * [LegadoLiquidFloatingBottomBar]（M3 导航项与玻璃背景），
 * 这一层只负责「居中胶囊 + 两侧让位的悬浮按钮 + 下滑隐藏」这套 Matter 的用法。
 */
@Composable
fun FloatingBottomBarLayer(
    pagerState: PagerState,
    items: List<LiquidNavItem>,
    visible: Boolean,
    backdrop: com.kyant.backdrop.backdrops.LayerBackdrop?,
    showActions: Boolean,
    leftAction: FloatingGlassAction?,
    rightAction: FloatingGlassAction?,
    onReselect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barRowHeight = BAR_ROW_HEIGHT
    val selectedIndex = pagerState.currentPage

    // 下滑隐藏：整层一起位移淡出（距离含导航栏，保证完全移出屏幕）
    val barHeightPx = with(density) {
        (barRowHeight + BAR_BOTTOM_PADDING + navigationBarBottom).toPx()
    }
    val barTranslationY by animateFloatAsState(
        targetValue = if (visible) 0f else barHeightPx + 150f,
        animationSpec = spring(stiffness = 400f, dampingRatio = 0.85f),
        label = "BottomBarTranslation",
    )

    // 底栏恒居中，两侧按钮必须自己找位置：
    // 屏幕半宽 − 胶囊半宽 − 间距 − 按钮直径 ≥ 16dp 边距，不够就把胶囊横向挤压（变窄）让位
    val fullCapsuleWidth = legadoBarWidth(items.size)
    val screenWidthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val maxCapsuleHalf = screenWidthDp / 2 - BAR_BUTTON_GAP - BAR_BUTTON_SIZE - BAR_EDGE_MARGIN
    val capsuleScaleTarget =
        (maxCapsuleHalf.value / (fullCapsuleWidth.value / 2f)).coerceIn(0.7f, 1f)

    // 阻尼 0.66：挤压与还原时伴随果冻般的有机形变
    val capsuleScale by animateFloatAsState(
        targetValue = if (showActions) capsuleScaleTarget else 1f,
        animationSpec = spring(stiffness = 300f, dampingRatio = 0.66f),
        label = "BottomBarCapsuleScale",
    )

    // 按钮停在胶囊侧缘外侧：胶囊半宽 + 间距 + 自身半径
    val buttonOffsetX = fullCapsuleWidth * capsuleScale / 2 + BAR_BUTTON_GAP + BAR_BUTTON_SIZE / 2

    // 1 = 已完全显示；0 = 隐去收起。果冻弹簧保留过冲回弹
    val buttonReveal by animateFloatAsState(
        targetValue = if (showActions) 1f else 0f,
        animationSpec = if (showActions) {
            spring(stiffness = 280f, dampingRatio = 0.62f)
        } else {
            spring(stiffness = 340f, dampingRatio = 0.68f)
        },
        label = "FloatingButtonsReveal",
    )

    val positionFraction = remember(pagerState) {
        { pagerState.currentPage + pagerState.currentPageOffsetFraction }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = barTranslationY
                alpha = (1f - (barTranslationY / barHeightPx)).coerceIn(0f, 1f)
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val shownLeft = rememberLastAction(leftAction)
        val shownRight = rememberLastAction(rightAction)

        if (buttonReveal > 0.01f) {
            shownLeft?.let { action ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(
                            x = screenWidthDp / 2 - buttonOffsetX - BAR_BUTTON_SIZE / 2,
                            y = -(navigationBarBottom + BAR_BOTTOM_PADDING),
                        )
                        .graphicsLayer {
                            val p = buttonReveal
                            alpha = p.coerceIn(0f, 1f)
                            val scale = (0.15f + 0.85f * p).coerceAtLeast(0f)
                            scaleX = scale
                            scaleY = scale
                            val offsetFactor = 1f - p
                            translationX = -offsetFactor * 14.dp.toPx()
                            translationY = offsetFactor * 18.dp.toPx()
                        },
                ) {
                    LiquidGlassIconButton(
                        onClick = { if (showActions) action.onClick() },
                        backdrop = backdrop,
                        size = BAR_BUTTON_SIZE,
                        glassAlpha = 1f,
                        tintAlpha = 1f,
                    ) {
                        LiquidGlassIcon(
                            imageVector = action.icon,
                            contentDescription = action.contentDescription,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        if (buttonReveal > 0.01f) {
            shownRight?.let { action ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(
                            x = -(screenWidthDp / 2 - buttonOffsetX - BAR_BUTTON_SIZE / 2),
                            y = -(navigationBarBottom + BAR_BOTTOM_PADDING),
                        )
                        .graphicsLayer {
                            val p = buttonReveal
                            alpha = p.coerceIn(0f, 1f)
                            val scale = (0.15f + 0.85f * p).coerceAtLeast(0f)
                            scaleX = scale
                            scaleY = scale
                            val offsetFactor = 1f - p
                            translationX = offsetFactor * 14.dp.toPx()
                            translationY = offsetFactor * 18.dp.toPx()
                        },
                ) {
                    LiquidGlassIconButton(
                        onClick = { if (showActions) action.onClick() },
                        backdrop = backdrop,
                        size = BAR_BUTTON_SIZE,
                        glassAlpha = 1f,
                        tintAlpha = 1f,
                    ) {
                        LiquidGlassIcon(
                            imageVector = action.icon,
                            contentDescription = action.contentDescription,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        // 胶囊本体：Matter 的组件，拖拽/点按/选中过渡都由它自己处理
        LegadoLiquidFloatingBottomBar(
            items = items,
            selectedIndex = selectedIndex,
            onItemSelected = { index ->
                scope.launch { pagerState.animateScrollToPage(index) }
            },
            onItemReselected = onReselect,
            backdrop = backdrop,
            positionFraction = positionFraction,
            widthScale = capsuleScale,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = BAR_BOTTOM_PADDING)
                .zIndex(1f),
        )
    }
}
