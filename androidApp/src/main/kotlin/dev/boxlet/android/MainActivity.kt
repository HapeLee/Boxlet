package dev.boxlet.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.boxlet.android.ui.BoxletRoot

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val boxlet = application as BoxletApplication
        setContent {
            BoxletRoot(boxlet.app)
        }
        boxlet.enqueueSharedUris(sharedUris(intent))
        requestLocalNetworkPermission()
        requestNotificationPermission()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as BoxletApplication).enqueueSharedUris(sharedUris(intent))
    }

    /** 系统「分享」进来的文件（`ACTION_SEND` / `ACTION_SEND_MULTIPLE`）；纯文本分享不处理。 */
    private fun sharedUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(singleStream(intent))
            Intent.ACTION_SEND_MULTIPLE -> multipleStreams(intent)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) return uris
        // 少数应用只填 ClipData，不填 EXTRA_STREAM。
        intent.clipData?.let { clip ->
            val items = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            if (items.isNotEmpty()) return items
        }
        // 更少见的一种：文件 uri 塞在 data 里。
        return listOfNotNull(intent.data)
    }

    private fun singleStream(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun multipleStreams(intent: Intent): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }

    private fun requestLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT < 37) return
        val permission = "android.permission.ACCESS_LOCAL_NETWORK"
        if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 1001)
        }
    }

    /** Android 13+ 前台服务的传输进度通知需要运行时授权。 */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 1002)
        }
    }
}
