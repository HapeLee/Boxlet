package dev.boxlet.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.boxlet.android.MainActivity
import dev.boxlet.android.R
import dev.boxlet.android.BoxletApplication
import dev.boxlet.core.engine.FileTransferStatus
import dev.boxlet.core.engine.TransferStatus
import dev.boxlet.shared.UiState
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 常驻前台服务：让接收端口在多任务里被系统回收之前活得更久，并把传输进度放到通知栏。
 *
 * 不是每个传输才启动一次——「随时能被别人发现并接收」本身就要求进程常驻，所以随
 * `BoxletApplication` 一起起来，通知内容跟着 [UiState] 变（空闲时显示端口）。
 */
class TransferForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastUpdateAt = 0L
    private var lastPayload: String? = null

    /** 完成态在胶囊上停留几秒再退回空闲，避免进度条永远停在 100%。 */
    private var completionSession: String? = null
    private var completionAt = 0L
    private var completionTimerScheduled = false

    /** 高优先级「等待确认接收」通知只发一次，确认/拒绝后取消。 */
    private var lastPendingSession: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val engineApp = (application as BoxletApplication).app
        if (!startInForeground(render(engineApp.state.value))) {
            // 拿不到前台服务类型（例如厂商 ROM 限制）就安静退出，别让应用因为通知起不来而崩溃。
            stopSelf()
            return
        }

        scope.launch {
            engineApp.state.collect { state ->
                val now = System.currentTimeMillis()
                // 进度更新本来就节流到 100ms；这里再压到 500ms，别让通知栏比传输更忙。
                if (now - lastUpdateAt < UPDATE_INTERVAL_MS) return@collect
                lastUpdateAt = now
                syncAttention(state)
                val notification = render(state)
                if (notification.key != lastPayload) {
                    lastPayload = notification.key
                    notify(notification)
                    if (notification.completion) scheduleCompletionRevert()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(payload: Payload): Boolean {
        lastPayload = payload.key
        val notification = build(payload)
        val typed = runCatching {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }
        if (typed.isSuccess) return true
        return runCatching { startForeground(NOTIFICATION_ID, notification) }.isSuccess
    }

    private fun notify(payload: Payload) {
        runCatching {
            if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, build(payload))
            }
        }
    }

    private fun render(state: UiState, now: Long = System.currentTimeMillis()): Payload {
        val incoming = state.incoming
        if (incoming != null && incoming.status == TransferStatus.COMPLETED) {
            val sessionKey = "done:${incoming.sessionId}"
            if (completionSession != sessionKey) {
                completionSession = sessionKey
                completionAt = now
            } else if (now - completionAt > COMPLETION_HOLD_MS) {
                completionSession = null
            }
            if (completionSession == sessionKey) {
                return Payload(
                    title = "接收完成",
                    text = "${incoming.files.size} 个文件",
                    progress = 100,
                    files = incoming.files,
                    completion = true,
                    key = sessionKey,
                )
            }
        }
        val outgoing = state.outgoing
        if (outgoing != null && outgoing.status == TransferStatus.COMPLETED) {
            val sessionKey = "done:${outgoing.sessionId}"
            if (completionSession != sessionKey) {
                completionSession = sessionKey
                completionAt = now
            } else if (now - completionAt > COMPLETION_HOLD_MS) {
                completionSession = null
            }
            if (completionSession == sessionKey) {
                return Payload(
                    title = "发送完成",
                    text = "${outgoing.peer.alias} · ${outgoing.files.size} 个文件",
                    progress = 100,
                    files = outgoing.files,
                    completion = true,
                    key = sessionKey,
                )
            }
        }
        if (incoming != null && incoming.status == TransferStatus.TRANSFERRING) {
            val file = incoming.files.firstOrNull { it.status == FileTransferStatus.TRANSFERRING }
            val percent = (incoming.progress * 100).toInt()
            return Payload(
                title = "正在接收 ${percent}%",
                text = file?.fileName?.substringAfterLast('/') ?: incoming.peer.alias,
                progress = percent,
                files = incoming.files,
                key = "in:${incoming.sessionId}:$percent",
            )
        }
        if (outgoing != null && outgoing.status == TransferStatus.TRANSFERRING) {
            val percent = (outgoing.progress * 100).toInt()
            return Payload(
                title = "正在发送 ${percent}%",
                text = outgoing.peer.alias,
                progress = percent,
                files = outgoing.files,
                key = "out:${outgoing.sessionId}:$percent",
            )
        }
        if (incoming?.status == TransferStatus.PAUSED) {
            return Payload("接收已暂停", incoming.peer.alias, key = "paused-in:${incoming.sessionId}")
        }
        if (outgoing?.status == TransferStatus.PAUSED) {
            return Payload("发送已暂停", outgoing.peer.alias, key = "paused-out:${outgoing.sessionId}")
        }
        if (outgoing != null && outgoing.status == TransferStatus.WAITING_FOR_ACCEPT) {
            return Payload(
                title = "等待对方接受",
                text = outgoing.peer.alias,
                indeterminate = true,
                key = "wait:${outgoing.sessionId}",
            )
        }
        if (outgoing != null && outgoing.status == TransferStatus.PIN_REQUIRED) {
            return Payload("需要输入 PIN", outgoing.peer.alias, key = "pin:${outgoing.sessionId}")
        }
        // 「等待确认接收」不再占常驻通知，改走高优先级 attention 通道（见 syncAttention）。
        val text = if (state.serverRunning) {
            "局域网可发现 · 端口 ${state.settings.port}"
        } else {
            "接收服务未运行"
        }
        return Payload("Boxlet 正在运行", text, key = "idle:${state.serverRunning}:${state.settings.port}")
    }

    private fun build(payload: Payload): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return if (Build.VERSION.SDK_INT >= 36) {
            buildLiveUpdate(payload, intent)
        } else {
            buildCompat(payload, intent)
        }
    }

    private fun buildCompat(payload: Payload, intent: PendingIntent): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_boxlet_notification)
            .setContentTitle(payload.title)
            .setContentText(payload.text)
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        when {
            payload.indeterminate -> builder.setProgress(0, 0, true)
            payload.progress != null -> builder.setProgress(100, payload.progress.coerceIn(0, 100), false)
        }
        return builder.build()
    }

    /**
     * Android 16（API 36）的 Live Updates：进度型通知可被系统"提升"到状态栏胶囊 / 锁屏顶部，
     * 也就是各家 ROM 广告里说的「灵动岛」。
     *
     * 规则：只有真的在传的时候才请求提升（`setRequestPromotedOngoing`），空闲时常驻通知
     * 仍然只是普通通知——系统也不会让一个没有进度的常驻通知长期占胶囊位。
     * 低于 36 的机型仍走 [buildCompat] 的普通进度通知；ColorOS/HyperOS 等厂商岛只认自己的
     * 白名单，App 侧没有公开接口可申请。
     */
    @RequiresApi(36)
    private fun buildLiveUpdate(payload: Payload, intent: PendingIntent): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_boxlet_notification)
            .setContentTitle(payload.title)
            .setContentText(payload.text)
            .setContentIntent(intent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
        when {
            payload.indeterminate -> {
                builder.setStyle(Notification.ProgressStyle().setProgressIndeterminate(true).setStyledByProgress(true))
                builder.setShortCriticalText("等待")
                builder.setRequestPromotedOngoing(true)
            }
            payload.progress != null -> {
                val percent = payload.progress.coerceIn(0, 100)
                val style = Notification.ProgressStyle().setProgress(percent).setStyledByProgress(true)
                // 多文件：每段一个文件，宽度按字节占比，长度之和取 100 让 setProgress 两种语义都对齐。
                val files = payload.files
                if (files != null && files.size > 1) {
                    val total = files.sumOf { it.size }.coerceAtLeast(1)
                    style.setProgressSegments(
                        files.map { f ->
                            val len = ((f.size.toDouble() / total) * 100).roundToInt().coerceAtLeast(1)
                            Notification.ProgressStyle.Segment(len).setColor(
                                if (f.status == FileTransferStatus.COMPLETED) DONE_COLOR else PENDING_COLOR,
                            )
                        },
                    )
                }
                builder.setStyle(style)
                // 胶囊位只放得下几个字；百分比比文件名更稳，完成时显示「完成」。
                builder.setShortCriticalText(if (payload.completion) "完成" else "$percent%")
                builder.setRequestPromotedOngoing(true)
            }
        }
        return builder.build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "传输状态", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "接收服务运行状态与传输进度"
                    setShowBadge(false)
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_ID_ATTENTION) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_ATTENTION, "接收请求", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "有设备发来传输请求时弹出，避免错过确认"
                    setShowBadge(false)
                },
            )
        }
    }

    /**
     * 「等待确认接收」走高优先级通道（弹窗 + 锁屏），常驻通知只保留背景状态。
     * 同一个请求只发一次，确认/拒绝后 [UiState.pendingReceive] 变空就取消。
     */
    private fun syncAttention(state: UiState) {
        val pending = state.pendingReceive
        if (pending != null) {
            if (pending.sessionId != lastPendingSession) {
                lastPendingSession = pending.sessionId
                postAttention(pending)
            }
        } else if (lastPendingSession != null) {
            lastPendingSession = null
            runCatching { NotificationManagerCompat.from(this).cancel(ATTENTION_ID) }
        }
    }

    private fun postAttention(pending: dev.boxlet.core.engine.PendingReceive) {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ATTENTION)
            .setSmallIcon(R.drawable.ic_boxlet_notification)
            .setContentTitle("来自 ${pending.peer.alias} 的传输请求")
            .setContentText("${pending.files.size} 个文件 · 点击确认")
            .setContentIntent(intent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(ATTENTION_ID, notification) }
    }

    /** 完成态停留 [COMPLETION_HOLD_MS] 后强制回空闲：清空 lastPayload 让空闲通知重新下发。 */
    private fun scheduleCompletionRevert() {
        if (completionTimerScheduled) return
        completionTimerScheduled = true
        scope.launch {
            delay(COMPLETION_HOLD_MS)
            completionTimerScheduled = false
            lastPayload = null
            val state = (application as BoxletApplication).app.state.value
            val notification = render(state)
            if (notification.key != lastPayload) {
                lastPayload = notification.key
                notify(notification)
            }
        }
    }

    private data class Payload(
        val title: String,
        val text: String,
        val progress: Int? = null,
        val indeterminate: Boolean = false,
        val files: List<dev.boxlet.core.engine.TransferFileState>? = null,
        val completion: Boolean = false,
        val key: String,
    )

    private companion object {
        const val CHANNEL_ID = "boxlet-transfer"
        const val CHANNEL_ID_ATTENTION = "boxlet-attention"
        const val NOTIFICATION_ID = 1001
        const val ATTENTION_ID = 1002
        const val UPDATE_INTERVAL_MS = 500L
        const val COMPLETION_HOLD_MS = 3000L
        const val DONE_COLOR = 0xFF43A047.toInt()
        const val PENDING_COLOR = 0xFF90A4AE.toInt()
    }
}
