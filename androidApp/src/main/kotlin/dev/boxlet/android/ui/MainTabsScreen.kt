package dev.boxlet.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import dev.boxlet.android.BoxletApplication
import dev.boxlet.android.ui.components.BottomBarScrollTracker
import dev.boxlet.android.ui.components.FloatingBottomBarLayer
import dev.boxlet.android.ui.components.bottomBarContentPadding
import dev.boxlet.android.ui.glass.LiquidNavItem
import dev.boxlet.android.ui.glass.rememberScreenBackdrop
import dev.boxlet.shared.BoxletApp
import dev.boxlet.shared.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val navItems = listOf(
    LiquidNavItem("发送", Icons.AutoMirrored.Outlined.Send, Icons.AutoMirrored.Filled.Send),
    LiquidNavItem("接收", Icons.Outlined.Download, Icons.Filled.Download),
    LiquidNavItem("设置", Icons.Outlined.Settings, Icons.Filled.Settings),
)

/** 接收页在 [navItems] 里的下标。 */
private const val RECEIVE_PAGE = 1

/**
 * 主框架：三个 Tab 的横向分页 + 内容区之上的悬浮底栏层。
 *
 * 结构照搬 Matter：
 * - `bottomBarBackdrop` 挂在 HorizontalPager 上，滑动切页时玻璃采样的是真实滚动的页面内容；
 * - 每个页面再各有一份自己的 backdrop，只服务本页的顶栏；
 * - 底栏不使用 Scaffold 的 bottomBar 槽，而是作为全屏 overlay 叠上来，
 *   内容避让按同一份几何计算（[bottomBarContentPadding]）。
 */
@Composable
fun MainTabsScreen(
    app: BoxletApp,
    state: UiState,
    snackbar: SnackbarHostState,
    /** 每次自增表示「跳到接收页」一次；0 表示还没有这个请求。 */
    navigateToReceive: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val boxletApplication = context.applicationContext as BoxletApplication
    val sharedUris by boxletApplication.sharedUris.collectAsState()
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { navItems.size })
    // 重建时对齐完整页面，避免恢复分页动画中途的偏移。
    LaunchedEffect(pagerState) { pagerState.scrollToPage(pagerState.currentPage) }

    // 确认接收之后切到接收页，用户不用自己再滑一次
    LaunchedEffect(navigateToReceive) {
        if (navigateToReceive > 0) pagerState.animateScrollToPage(RECEIVE_PAGE)
    }

    // 每页一份列表状态：顶栏联动与"滑到顶部立即显示底栏"都要读它
    val sendListState = remember { LazyListState() }
    val receiveListState = remember { LazyListState() }
    val settingsListState = remember { LazyListState() }
    val listStates = remember(sendListState, receiveListState, settingsListState) {
        listOf(sendListState, receiveListState, settingsListState)
    }

    val backdropSend = rememberScreenBackdrop()
    val backdropReceive = rememberScreenBackdrop()
    val backdropSettings = rememberScreenBackdrop()
    val bottomBarBackdrop = rememberScreenBackdrop()

    fun shareSelected() {
        val files = app.sendSelection.value
        if (files.isEmpty()) scope.launch { snackbar.showSnackbar("请先选择要分享的内容") }
        else app.shareFiles(files.toList())
    }
    LaunchedEffect(sharedUris) {
        if (sharedUris.isEmpty()) return@LaunchedEffect
        try {
            val files = withContext(Dispatchers.IO) {
                sharedUris.mapNotNull { contentUriToOutgoingFile(context, it) }
            }
            app.selectFiles(files)
            pagerState.scrollToPage(0)
            if (files.size != sharedUris.size) snackbar.showSnackbar("部分分享内容无法读取，请重新选择")
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { snackbar.showSnackbar("无法读取分享内容，请重新选择") }
        finally { boxletApplication.clearSharedUris() }
    }

    var bottomBarVisible by remember { mutableStateOf(true) }
    val scrollThreshold = with(LocalDensity.current) { 32.dp.toPx() }
    val scrollTracker = remember(scrollThreshold) { BottomBarScrollTracker(scrollThreshold) }
    val scrollConnection = remember(pagerState, listStates, scrollTracker) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    val active = listStates[pagerState.currentPage]
                    if (active.firstVisibleItemIndex == 0 && active.firstVisibleItemScrollOffset == 0) {
                        // 到顶立即显示，避免"必须往上滑一下底栏才回来"
                        bottomBarVisible = true
                        scrollTracker.reset()
                    } else {
                        scrollTracker.onScroll(consumed.y)?.let { bottomBarVisible = it }
                    }
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(pagerState.currentPage) {
        bottomBarVisible = true
        scrollTracker.reset()
    }

    val bottomBarPadding = bottomBarContentPadding()

    Box(modifier = modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 1,
            key = { it },
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollConnection)
                .then(
                    if (bottomBarBackdrop != null) {
                        Modifier.layerBackdrop(bottomBarBackdrop)
                    } else {
                        Modifier
                    }
                ),
        ) { page ->
            when (page) {
                0 -> SendScreen(
                    app = app,
                    state = state,
                    listState = sendListState,
                    backdrop = backdropSend,
                    bottomBarPadding = bottomBarPadding,
                    onBrowserShare = { shareSelected() },
                )

                RECEIVE_PAGE -> ReceiveScreen(
                    app = app,
                    state = state,
                    listState = receiveListState,
                    backdrop = backdropReceive,
                    bottomBarPadding = bottomBarPadding,
                )

                else -> SettingsScreen(
                    app = app,
                    state = state,
                    listState = settingsListState,
                    backdrop = backdropSettings,
                    bottomBarPadding = bottomBarPadding,
                )
            }
        }

        FloatingBottomBarLayer(
            pagerState = pagerState,
            items = navItems,
            visible = bottomBarVisible,
            backdrop = bottomBarBackdrop,
            showActions = false,
            leftAction = null,
            rightAction = null,
            onReselect = { index ->
                val listState = listStates.getOrNull(index) ?: return@FloatingBottomBarLayer
                scope.launch {
                    if (listState.firstVisibleItemIndex > 10) listState.scrollToItem(8)
                    listState.animateScrollToItem(0)
                }
            },
        )

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomBarPadding + 8.dp)
                .padding(horizontal = 24.dp),
        )

    }
}
