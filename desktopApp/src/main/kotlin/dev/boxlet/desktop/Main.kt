package dev.boxlet.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import dev.boxlet.shared.ThemeMode
import dev.boxlet.shared.BoxletApp
import dev.boxlet.shared.storage.FileSystemReceiveStorage
import io.github.composefluent.FluentTheme
import io.github.composefluent.CornerRadius
import io.github.composefluent.component.Text
import io.github.composefluent.darkColors
import io.github.composefluent.lightColors
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Frame
import java.awt.Window
import java.io.File

@OptIn(io.github.composefluent.ExperimentalFluentApi::class)
fun main() {
    // 已经有实例在跑：让它把窗口叫到前台，然后本进程直接退出（端口/证书只有一个主人）。
    if (SingleInstance.forwardToRunningApp()) return
    application {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
        val app = remember {
            lateinit var instance: BoxletApp
            var runningApp: BoxletApp? = null
            val startupDestination = runCatching {
                dev.boxlet.core.protocol.BoxletJson.decodeFromString(dev.boxlet.shared.Settings.serializer(),
                    File(DesktopEnvironment.dataDir, "settings.json").readText(Charsets.UTF_8)).destination
            }.getOrNull()
            val storage = FileSystemReceiveStorage(File(DesktopEnvironment.dataDir, "bp2-receive")) {
                val current = runningApp
                val configured = if (current == null) startupDestination else current.currentSettings.destination
                if (configured != null) File(configured) else DesktopEnvironment.defaultDownloadDir
            }
            instance = BoxletApp(
                scope = scope,
                dataDir = DesktopEnvironment.dataDir,
                storage = storage,
                defaults = DesktopEnvironment.defaultSettings,
            )
            runningApp = instance
            instance
        }
        val windowState = rememberWindowState(width = 1180.dp, height = 780.dp)
        val trayState = rememberTrayState()
        // 没有系统托盘（精简 Linux 桌面/无头环境）时不能把窗口藏起来，否则用户关不掉进程。
        val traySupported = remember { java.awt.SystemTray.isSupported() }
        var windowVisible by remember { mutableStateOf(true) }
        val windowRef = remember { mutableStateOf<Window?>(null) }
        // 拖进来的文件先交给 UI 决定目标设备；发完由 UI 清空。
        var droppedFiles by remember { mutableStateOf<List<File>>(emptyList()) }

        // 系统深色状态：Windows 上直接读注册表，非 Windows 交给 Compose 默认逻辑。
        val uiState by app.state.collectAsState()
        val isWin = isWindows()
        val mica = isWin && isWindows11()
        var systemDark by remember { mutableStateOf(readWindowsDarkMode() ?: false) }
        // Windows 上直接读注册表；其他平台交给 Compose 默认逻辑。
        val dark = if (isWin) {
            effectiveDark(uiState.settings.theme, systemDark)
        } else {
            when (uiState.settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
        }

        fun raiseWindow() {
            windowVisible = true
            windowRef.value?.let { window ->
                if (window is Frame) {
                    window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                }
                window.toFront()
                window.requestFocus()
            }
        }

        fun quit() {
            app.engine.stop()
            scope.cancel()
            exitApplication()
        }

        // `/show` 由服务端线程回调，必须切回 AWT 事件线程再动窗口。
        app.onShow = { EventQueue.invokeLater { raiseWindow() } }

        if (traySupported) {
            Tray(
                icon = remember { BoxletTrayIcon() },
                state = trayState,
                tooltip = "Boxlet · 局域网文件互传",
                onAction = { raiseWindow() },
                menu = {
                    Item("显示主窗口", onClick = { raiseWindow() })
                    Separator()
                    Item("退出", onClick = { quit() })
                },
            )
        }

        val onCloseRequest: () -> Unit = {
            if (traySupported) windowVisible = false else quit()
        }

        Window(
            onCloseRequest = onCloseRequest,
            state = windowState,
            visible = windowVisible,
            title = "Boxlet",
            icon = remember { BoxletTrayIcon() },
            undecorated = isWin,
            transparent = mica,
        ) {
            DisposableEffect(window) {
                windowRef.value = window
                installFileDropTarget(window) { files -> droppedFiles = files }
                // 原生 Mica + 沉浸式深色（DWM 属性）；透明窗口让 Mica 透出、文字更清晰。
                applyWindowEffects(window, dark, mica)
                val focusListener = object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {
                        readWindowsDarkMode()?.let { systemDark = it }
                        applyWindowEffects(
                            window,
                            effectiveDark(app.state.value.settings.theme, systemDark),
                            mica,
                        )
                    }
                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) {}
                }
                window.addWindowFocusListener(focusListener)
                val removeNativeMoveHandler = installNativeMoveHandler(window)
                val removeWindowDragListener = installWindowDragListener(window)
                val removeWindowCornerShape = installWindowCornerShape(window)
                onDispose {
                    window.removeWindowFocusListener(focusListener)
                    removeWindowDragListener()
                    removeWindowCornerShape()
                    removeNativeMoveHandler()
                }
            }
            LaunchedEffect(dark) {
                applyWindowEffects(window, dark, mica)
            }
            window.minimumSize = Dimension(920, 640)
            FluentTheme(
                colors = if (dark) darkColors() else lightColors(),
                cornerRadius = CornerRadius(overlay = 12.dp, control = 8.dp, intersectionEdge = 8.dp),
            ) {
                val maximized = windowState.placement == WindowPlacement.Maximized
                // 透明 AWT 窗口未必能获得 DWM 圆角，裁剪内容作为补充；最大化时保留直角。
                val shape = RoundedCornerShape(if (isWin && !maximized) 8.dp else 0.dp)
                Column(Modifier.fillMaxSize().clip(shape)) {
                    if (isWin) TitleBar(window = window, onClose = onCloseRequest, mica = mica, maximized = maximized)
                    DesktopRoot(
                        app = app,
                        droppedFiles = droppedFiles,
                        onDropHandled = { droppedFiles = emptyList() },
                        mica = mica,
                    )
                }
            }
        }
    }
}

private enum class TitleGlyph { Minimize, Maximize, Restore, Close }

@Composable
private fun TitleGlyphIcon(glyph: TitleGlyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.dp)) {
        val sw = 1.dp.toPx()
        when (glyph) {
            TitleGlyph.Minimize -> drawLine(
                color,
                Offset(size.width * 0.2f, size.height * 0.65f),
                Offset(size.width * 0.8f, size.height * 0.65f),
                strokeWidth = sw,
                cap = StrokeCap.Round,
            )
            TitleGlyph.Maximize -> drawRect(
                color,
                Offset(size.width * 0.2f, size.height * 0.2f),
                Size(size.width * 0.6f, size.height * 0.6f),
                style = Stroke(sw),
            )
            TitleGlyph.Restore -> {
                drawRect(
                    color,
                    Offset(size.width * 0.3f, size.height * 0.2f),
                    Size(size.width * 0.5f, size.height * 0.5f),
                    style = Stroke(sw),
                )
                drawRect(
                    color,
                    Offset(size.width * 0.2f, size.height * 0.35f),
                    Size(size.width * 0.5f, size.height * 0.5f),
                    style = Stroke(sw),
                )
            }
            TitleGlyph.Close -> {
                drawLine(
                    color,
                    Offset(size.width * 0.25f, size.height * 0.25f),
                    Offset(size.width * 0.75f, size.height * 0.75f),
                    strokeWidth = sw * 1.3f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(size.width * 0.75f, size.height * 0.25f),
                    Offset(size.width * 0.25f, size.height * 0.75f),
                    strokeWidth = sw * 1.3f,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun TitleBarButton(
    glyph: TitleGlyph,
    onClick: () -> Unit,
    hoverColor: Color,
    iconColor: Color,
    danger: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(46.dp, 36.dp)
            .hoverable(interaction)
            .background(if (hover) hoverColor else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        TitleGlyphIcon(glyph, if (danger && hover) Color.White else iconColor)
    }
}

@Composable
private fun TitleBar(window: Frame, onClose: () -> Unit, mica: Boolean, maximized: Boolean) {
    // Win11 上透明，让原生 Mica 透出；其他平台用 Fluent Mica 底色，避免透出桌面。
    val bg = if (mica) Color.Transparent else FluentTheme.colors.background.mica.base
    val iconColor = FluentTheme.colors.text.text.primary
    Row(
        Modifier.fillMaxWidth().height(36.dp).background(bg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                "Boxlet",
                Modifier.padding(start = 12.dp),
                style = FluentTheme.typography.caption,
                color = iconColor,
            )
        }
        TitleBarButton(
            TitleGlyph.Minimize,
            { minimizeDesktopWindow(window) },
            Color.Black.copy(alpha = 0.06f),
            iconColor,
        )
        TitleBarButton(
            if (maximized) TitleGlyph.Restore else TitleGlyph.Maximize,
            { toggleDesktopWindowMaximized(window) },
            Color.Black.copy(alpha = 0.06f),
            iconColor,
        )
        TitleBarButton(TitleGlyph.Close, onClose, Color(0xFFE81123), iconColor, danger = true)
    }
}

// Skiko 子组件接收鼠标事件，事件不会冒泡到 Frame 的 MouseListener。
// 观察本窗口子树的 AWT 事件，再将坐标转换到 Frame 的逻辑坐标空间。
private const val TITLE_H = 36 // 标题栏高度（与 36.dp 对齐，单位 DIP）
private const val BUTTONS_W = 138 // 右侧三个按钮总宽（与 3×46.dp 对齐）
private const val RESIZE_MARGIN = 4 // 边缘缩放命中宽度（与 4.dp 对齐）

// Windows 非客户区命中码（WM_NCLBUTTONDOWN 的 wParam）
private const val HT_CAPTION = 2
private const val HT_LEFT = 10
private const val HT_RIGHT = 11
private const val HT_TOP = 12
private const val HT_TOPLEFT = 13
private const val HT_TOPRIGHT = 14
private const val HT_BOTTOM = 15
private const val HT_BOTTOMLEFT = 16
private const val HT_BOTTOMRIGHT = 17

private fun installWindowDragListener(window: Frame): () -> Unit {
    if (!isWindows()) return {}
    val listener = java.awt.event.AWTEventListener { event ->
        val e = event as? java.awt.event.MouseEvent ?: return@AWTEventListener
        if (e.id != java.awt.event.MouseEvent.MOUSE_PRESSED ||
            !javax.swing.SwingUtilities.isLeftMouseButton(e)
        ) return@AWTEventListener
        val source = e.component ?: return@AWTEventListener
        if (source != window && javax.swing.SwingUtilities.getWindowAncestor(source) != window) {
            return@AWTEventListener
        }
        val point = javax.swing.SwingUtilities.convertPoint(source, e.point, window)
        run {
            val w = window.width
            val h = window.height
            val maximized = window.extendedState and Frame.MAXIMIZED_BOTH != 0
            val inTitle = point.y in 0 until TITLE_H && point.x in 0 until (w - BUTTONS_W)
            if (point.y < TITLE_H && point.x >= w - BUTTONS_W) return@AWTEventListener
            if (inTitle && e.clickCount == 2) {
                toggleDesktopWindowMaximized(window)
                return@AWTEventListener
            }
            val left = point.x < RESIZE_MARGIN
            val right = point.x >= w - RESIZE_MARGIN
            val top = point.y < RESIZE_MARGIN
            val bottom = point.y >= h - RESIZE_MARGIN
            val code = when {
                maximized -> if (inTitle) HT_CAPTION else return@AWTEventListener
                left && top -> HT_TOPLEFT
                right && top -> HT_TOPRIGHT
                left && bottom -> HT_BOTTOMLEFT
                right && bottom -> HT_BOTTOMRIGHT
                left -> HT_LEFT
                right -> HT_RIGHT
                top -> HT_TOP
                bottom -> HT_BOTTOM
                inTitle -> HT_CAPTION
                else -> return@AWTEventListener
            }
            // 等本次事件分发结束，避免 Compose 随后重新捕获指针。
            EventQueue.invokeLater {
                if (window.isDisplayable) {
                    if (code == HT_CAPTION) startWindowMove(window) else startWindowResize(window, code)
                }
            }
        }
    }
    val toolkit = java.awt.Toolkit.getDefaultToolkit()
    toolkit.addAWTEventListener(listener, java.awt.AWTEvent.MOUSE_EVENT_MASK)
    return { toolkit.removeAWTEventListener(listener) }
}
