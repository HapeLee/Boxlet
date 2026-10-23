package dev.boxlet.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.CallbackReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.POINT
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser.WindowProc
import com.sun.jna.platform.win32.WinUser.MONITORINFO
import com.sun.jna.win32.StdCallLibrary
import dev.boxlet.shared.ThemeMode
import java.awt.Frame
import java.awt.Window

// DWM 窗口属性（dwmapi.h）
private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20 // Win11 22H2+；Win10 20H1+ 用 19
private const val DWMWA_SYSTEMBACKDROP_TYPE = 38
private const val DWMSBT_MAINWINDOW = 2 // 主窗口使用 Mica 材质
private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
private const val DWMWCP_ROUND = 2 // 圆角窗体（Win11 风格）

fun isWindows(): Boolean =
    System.getProperty("os.name").contains("Windows", ignoreCase = true)

/**
 * 读取 Windows 应用主题是否为深色。非 Windows 返回 null（交由 Compose 默认逻辑处理）。
 * 通过注册表 `AppsUseLightTheme` 判断：0x0 = 深色，0x1 = 浅色。
 */
fun readWindowsDarkMode(): Boolean? {
    if (!isWindows()) return null
    val raw = regQuery(
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "AppsUseLightTheme",
    ) ?: return false
    return raw.equals("0x0", ignoreCase = true)
}

/** DWMWA_SYSTEMBACKDROP_TYPE 从 Windows 11 22H2（Build 22621）开始支持。 */
fun isWindows11(): Boolean {
    if (!isWindows()) return false
    val build = regQuery(
        "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion",
        "CurrentBuild",
    )?.toIntOrNull() ?: return false
    return build >= 22621
}

/** 综合「跟随系统 / 浅色 / 深色」与当前系统深色状态，得到实际是否深色。 */
fun effectiveDark(theme: ThemeMode, systemDark: Boolean): Boolean = when (theme) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

private interface DwmApi : StdCallLibrary {
    fun DwmSetWindowAttribute(
        hwnd: Pointer?,
        dwAttribute: Int,
        pvAttribute: Pointer?,
        cbAttribute: Int,
    ): Int

    companion object {
        val INSTANCE: DwmApi? = runCatching {
            Native.load("dwmapi", DwmApi::class.java) as DwmApi
        }.getOrNull()
    }
}

// 投递到 HWND 所在线程，让 AWT 的原生消息循环执行移动/缩放。
// 不能在 Java EDT 上直接调用 DefWindowProcW 进入另一个线程所属窗口的移动循环。
private const val WM_CANCELMODE = 0x001F
private const val WM_SYSCOMMAND = 0x0112
private const val SC_MOVE = 0xF010
private const val SC_SIZE = 0xF000
private const val SC_MINIMIZE = 0xF020
private const val SC_MAXIMIZE = 0xF030
private const val SC_RESTORE = 0xF120
private const val HTCAPTION = 0x0002

private interface WindowProcApi : StdCallLibrary {
    fun CallWindowProcW(previous: Pointer?, hwnd: HWND, message: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
    fun GetDpiForWindow(hwnd: HWND): Int
    fun IsZoomed(hwnd: HWND): Boolean

    companion object {
        val INSTANCE = Native.load("user32", WindowProcApi::class.java)
    }
}

/** 在原生消息线程完成标题栏命中测试，避免 AWT 事件转发与鼠标捕获的时序竞争。 */
fun installNativeMoveHandler(window: Window): () -> Unit {
    if (!isWindows()) return {}
    val u = User32.INSTANCE
    val hwnd = HWND(Native.getComponentPointer(window))
    val index = -4 // GWLP_WNDPROC
    val fallbackScale = window.graphicsConfiguration.defaultTransform.scaleX
    val restoreHandlers = mutableListOf<() -> Unit>()

    fun monitorInfo(): MONITORINFO? {
        val monitor = u.MonitorFromWindow(hwnd, 2) ?: return null // MONITOR_DEFAULTTONEAREST
        val info = MONITORINFO()
        return if (u.GetMonitorInfo(monitor, info).booleanValue()) info else null
    }

    fun constrainMaximizedBounds(message: Int, lParam: LPARAM) {
        val info = monitorInfo() ?: return
        val work = info.rcWork
        val pointer = Pointer(lParam.toLong())
        if (message == 0x0024) { // WM_GETMINMAXINFO: MINMAXINFO 中的第二、三个 POINT。
            pointer.setInt(8, work.right - work.left) // ptMaxSize.x
            pointer.setInt(12, work.bottom - work.top) // ptMaxSize.y
            pointer.setInt(16, work.left - info.rcMonitor.left) // ptMaxPosition.x
            pointer.setInt(20, work.top - info.rcMonitor.top) // ptMaxPosition.y
        } else {
            // 无边框 WS_POPUP 不一定收到 WM_GETMINMAXINFO，最终位置也按工作区约束。
            // WINDOWPOS 前两个字段为 HWND，后接 x/y/cx/cy/flags（各 32 位）。
            val offset = 2L * Native.POINTER_SIZE
            val flags = pointer.getInt(offset + 16)
            if (flags and 0x0002 == 0) { // !SWP_NOMOVE
                pointer.setInt(offset, work.left)
                pointer.setInt(offset + 4, work.top)
            }
            if (flags and 0x0001 == 0) { // !SWP_NOSIZE
                pointer.setInt(offset + 8, work.right - work.left)
                pointer.setInt(offset + 12, work.bottom - work.top)
            }
        }
    }

    fun inTitle(lParam: LPARAM): Boolean {
        val rect = RECT()
        if (!u.GetWindowRect(hwnd, rect)) return false
        val dpi = runCatching { WindowProcApi.INSTANCE.GetDpiForWindow(hwnd) }.getOrDefault(0)
        val scale = if (dpi > 0) dpi / 96.0 else fallbackScale
        // GET_X/Y_LPARAM 必须按有符号 16 位读取，支持位于主屏左侧的显示器。
        val x = (lParam.toInt() and 0xFFFF).toShort().toInt() - rect.left
        val y = (lParam.toInt() ushr 16).toShort().toInt() - rect.top
        val margin = (4 * scale).toInt()
        return x >= margin && x < rect.right - rect.left - (138 * scale).toInt() &&
            y >= margin && y < (36 * scale).toInt()
    }

    fun hook(target: HWND, root: Boolean) {
        var previous: Pointer? = null
        val callback = WindowProc { handle, message, wParam, lParam ->
            when {
                root && (message == 0x0024 || // WM_GETMINMAXINFO
                    message == 0x0046 && WindowProcApi.INSTANCE.IsZoomed(handle)) -> { // WM_WINDOWPOSCHANGING
                    val result = WindowProcApi.INSTANCE.CallWindowProcW(previous, handle, message, wParam, lParam)
                    constrainMaximizedBounds(message, lParam)
                    result
                }
                message == 0x0084 && inTitle(lParam) -> // WM_NCHITTEST
                    // 子窗口让命中测试继续到父窗口，根窗口返回 HTCAPTION。
                    LRESULT(if (root) HTCAPTION.toLong() else -1L)
                root && message == 0x00A1 && wParam.toInt() == HTCAPTION -> // WM_NCLBUTTONDOWN
                    u.DefWindowProc(handle, message, wParam, lParam)
                root && message == WM_SYSCOMMAND && (wParam.toInt() and 0xFFF0) == SC_MOVE ->
                    u.DefWindowProc(handle, message, wParam, lParam)
                else -> WindowProcApi.INSTANCE.CallWindowProcW(previous, handle, message, wParam, lParam)
            }
        }
        fun setProc(pointer: Pointer?): Pointer? = if (Native.POINTER_SIZE == 8) {
            u.SetWindowLongPtr(target, index, pointer)
        } else {
            Pointer.createConstant(u.SetWindowLong(target, index, Pointer.nativeValue(pointer).toInt()).toLong())
        }
        previous = setProc(CallbackReference.getFunctionPointer(callback))
        check(previous != null) { "Unable to install window move handler: ${Native.getLastError()}" }
        restoreHandlers += {
            if (u.IsWindow(target)) setProc(previous)
            java.lang.ref.Reference.reachabilityFence(callback)
        }
    }
    hook(hwnd, true)
    u.EnumChildWindows(hwnd, { child, _ -> hook(child, false); true }, null)
    return {
        restoreHandlers.asReversed().forEach { it() }
    }
}

/** 逐像素透明 AWT 窗口不能依赖 DWM 圆角；裁剪真实 HWND 外沿，而非仅裁剪 Compose 内容。 */
fun installWindowCornerShape(window: Frame): () -> Unit {
    if (!isWindows() || !window.isUndecorated ||
        !window.graphicsConfiguration.device.isWindowTranslucencySupported(
            java.awt.GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSPARENT,
        )
    ) return {}
    var lastWidth = -1
    var lastHeight = -1
    var lastMaximized = false
    fun updateShape() {
        if (!window.isDisplayable || window.width <= 0 || window.height <= 0) return
        val maximized = window.extendedState and Frame.MAXIMIZED_BOTH != 0
        if (window.width == lastWidth && window.height == lastHeight && maximized == lastMaximized) return
        lastWidth = window.width
        lastHeight = window.height
        lastMaximized = maximized
        window.shape = if (maximized) null else java.awt.geom.RoundRectangle2D.Float(
            0f, 0f, window.width.toFloat(), window.height.toFloat(), 16f, 16f,
        )
    }
    val componentListener = object : java.awt.event.ComponentAdapter() {
        override fun componentResized(event: java.awt.event.ComponentEvent) = updateShape()
        override fun componentShown(event: java.awt.event.ComponentEvent) = updateShape()
    }
    val stateListener = java.awt.event.WindowStateListener { updateShape() }
    window.addComponentListener(componentListener)
    window.addWindowStateListener(stateListener)
    updateShape()
    return {
        window.removeComponentListener(componentListener)
        window.removeWindowStateListener(stateListener)
        if (window.isDisplayable) window.shape = null
    }
}

fun minimizeDesktopWindow(window: Frame) {
    if (!isWindows()) {
        window.extendedState = window.extendedState or Frame.ICONIFIED
        return
    }
    postWindowCommand(window, SC_MINIMIZE)
}

fun toggleDesktopWindowMaximized(window: Frame) {
    if (!isWindows()) {
        window.extendedState = if (window.extendedState and Frame.MAXIMIZED_BOTH != 0) {
            Frame.NORMAL
        } else Frame.MAXIMIZED_BOTH
        return
    }
    val hwnd = HWND(Native.getComponentPointer(window))
    postWindowCommand(window, if (WindowProcApi.INSTANCE.IsZoomed(hwnd)) SC_RESTORE else SC_MAXIMIZE)
}

private fun postWindowCommand(window: Window, command: Int) {
    val hwnd = HWND(Native.getComponentPointer(window))
    User32.INSTANCE.PostMessage(hwnd, WM_SYSCOMMAND, WPARAM(command.toLong()), LPARAM(0))
}

fun startWindowMove(window: Window) = startWindowSysCommand(window, HTCAPTION)

/** hitTest: HTLEFT=10, HTRIGHT=11, HTTOP=12, HTTOPLEFT=13, HTTOPRIGHT=14, HTBOTTOM=15, HTBOTTOMLEFT=16, HTBOTTOMRIGHT=17（与 Windows 常量一致） */
fun startWindowResize(window: Window, hitTest: Int) = startWindowSysCommand(window, hitTest)

private fun startWindowSysCommand(window: Window, hitTest: Int) {
    if (!isWindows()) return
    if (hitTest != HTCAPTION && window is Frame &&
        window.extendedState and Frame.MAXIMIZED_BOTH != 0
    ) return
    val hwnd = HWND(Native.getComponentPointer(window))
    val u = User32.INSTANCE
    val p = POINT()
    // Win32 消息需要物理屏幕坐标；MouseInfo 返回的是 AWT 逻辑坐标。
    if (!u.GetCursorPos(p)) return
    val lp = (p.x and 0xFFFF) or ((p.y and 0xFFFF) shl 16)
    val command = if (hitTest == HTCAPTION) SC_MOVE or HTCAPTION else SC_SIZE or (hitTest - 9)
    // 在窗口自己的线程释放捕获，而非在 EDT 上调用 ReleaseCapture。
    u.SendMessage(hwnd, WM_CANCELMODE, WPARAM(0), LPARAM(0))
    u.PostMessage(hwnd, WM_SYSCOMMAND, WPARAM(command.toLong()), LPARAM(lp.toLong()))
}

/**
 * 对窗口应用 Windows 原生视觉效果：
 * - 沉浸式深色标题栏（跟随系统/应用主题）
 * - 原生 Mica 材质（仅 Windows 11）
 * 非 Windows 平台为空操作。
 */
fun applyWindowEffects(window: Window, dark: Boolean, mica: Boolean) {
    if (!isWindows()) return
    val hwnd = runCatching { Native.getComponentPointer(window) }.getOrNull() ?: return
    val dwm = DwmApi.INSTANCE ?: return
    runCatching {
        dwm.DwmSetWindowAttribute(
            hwnd,
            DWMWA_USE_IMMERSIVE_DARK_MODE,
            IntByReference(if (dark) 1 else 0).pointer,
            4,
        )
    }
    if (mica) {
        runCatching {
            dwm.DwmSetWindowAttribute(
                hwnd,
                DWMWA_SYSTEMBACKDROP_TYPE,
                IntByReference(DWMSBT_MAINWINDOW).pointer,
                4,
            )
            // 圆角窗体（Win11 视觉一致）
            dwm.DwmSetWindowAttribute(
                hwnd,
                DWMWA_WINDOW_CORNER_PREFERENCE,
                IntByReference(DWMWCP_ROUND).pointer,
                4,
            )
        }
    }
}

/** 读取注册表键值的数据部分（最后一列）。 */
private fun regQuery(key: String, value: String): String? = runCatching {
    val proc = Runtime.getRuntime().exec(arrayOf("reg", "query", key, "/v", value))
    val out = proc.inputStream.bufferedReader().readText()
    proc.waitFor()
    out.lines()
        .firstOrNull { it.contains(value, ignoreCase = true) && it.contains("REG_") }
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.lastOrNull()
}.getOrNull()
