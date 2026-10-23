package dev.boxlet.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import kotlinx.coroutines.launch

/**
 * 统一页面骨架：内容列表 + 覆盖其上的沉浸式玻璃顶栏。
 *
 * 顶栏不放进 Scaffold 的 topBar 槽，而是作为内容之上的 overlay：
 * 滚动时它只在绘制阶段淡入渐变遮罩，不会参与列表测量，因此不会出现"顶栏变化把列表顶动"的抖动。
 * 顶栏本身、标题胶囊、操作按钮全部来自 Matter 的同名组件，用法与 Matter 一致。
 */
@Composable
fun GlassPage(
    title: String,
    listState: LazyListState,
    backdrop: LayerBackdrop?,
    bottomBarPadding: Dp,
    modifier: Modifier = Modifier,
    /** 传入后标题胶囊里会带返回箭头（Matter 的 TopBarTitleText(onBackClick = ...) 用法） */
    onBack: (() -> Unit)? = null,
    topBarActions: @Composable RowScope.(TopBarScrollState) -> Unit = {},
    contentPadding: PaddingValues? = null,
    content: LazyListScope.() -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    val scope = rememberCoroutineScope()
    val topBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + immersiveTopBarHeight()

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Box(modifier = modifier.fillMaxSize().background(surface)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    // 玻璃采样层：先铺不透明底色再录制内容，避免透明黑洞
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                    .background(surface),
                contentPadding = contentPadding ?: PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = topBarInset + 8.dp,
                    bottom = bottomBarPadding + 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )

            ImmersiveTopBar(
                title = { scroll ->
                    TopBarTitleText(
                        text = title,
                        scroll = scroll,
                        backdrop = backdrop,
                        // 点标题回到顶部：滚到很深处先跳跃、再平滑收尾，避免长距离动画卡顿
                        onClick = {
                            scope.launch {
                                if (listState.firstVisibleItemIndex > 10) listState.scrollToItem(8)
                                listState.animateScrollToItem(0)
                            }
                        },
                        onBackClick = onBack,
                    )
                },
                listState = listState,
                modifier = Modifier.align(Alignment.TopCenter),
                actions = topBarActions,
            )
        }
    }
}
