package dev.boxlet.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dev.boxlet.android.service.TransferForegroundService
import dev.boxlet.android.storage.AndroidReceiveStorage
import dev.boxlet.core.protocol.DeviceType
import dev.boxlet.shared.BoxletApp
import dev.boxlet.shared.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

class BoxletApplication : Application() {

    lateinit var app: BoxletApp
        private set

    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * 系统「分享」进来的文件：Activity 只负责登记，目标设备由 UI 里选。
     * 用 StateFlow 而不是 Intent extra，避免 Activity 重建后重复弹窗。
     */
    private val _sharedUris = MutableStateFlow<List<Uri>>(emptyList())
    val sharedUris: StateFlow<List<Uri>> = _sharedUris

    fun enqueueSharedUris(uris: List<Uri>) {
        if (uris.isNotEmpty()) _sharedUris.value = uris
    }

    fun clearSharedUris() {
        _sharedUris.value = emptyList()
    }

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val defaults = Settings(
            deviceType = DeviceType.MOBILE,
            deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
        )
        val storage = AndroidReceiveStorage(this) { app.state.value.settings.destination }
        app = BoxletApp(
            scope = scope,
            dataDir = File(filesDir, "boxlet"),
            storage = storage,
            defaults = defaults,
        )

        runCatching {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("boxlet-discovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        // 前台服务让接收端口在后台多活一会儿；通知权限没给也能跑，只是通知不可见。
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, TransferForegroundService::class.java),
            )
        }
    }

    override fun onTerminate() {
        multicastLock?.release()
        multicastLock = null
        super.onTerminate()
    }
}
