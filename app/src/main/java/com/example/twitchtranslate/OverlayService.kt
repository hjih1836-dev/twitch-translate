package com.example.twitchtranslate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service that:
 *  - Holds the Twitch IRC connection
 *  - Translates messages (ML Kit on-device)
 *  - Shows translated danmaku in a SYSTEM_ALERT_WINDOW overlay
 *
 * The overlay is a ComposeView added to WindowManager with
 * TYPE_APPLICATION_OVERLAY. It is NOT focusable and does NOT intercept
 * touches (except the empty area is pass-through), so it floats on top
 * of Twitch without blocking the game / video.
 *
 * Lifecycle: MainActivity starts the service with ACTION_START + channel extra.
 * Stop with ACTION_STOP. The service owns the IRC client and translator.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.example.twitchtranslate.START"
        const val ACTION_STOP = "com.example.twitchtranslate.STOP"
        const val EXTRA_CHANNEL = "channel"
        const val CHANNEL_ID = "twitch_translate_overlay"
        const val NOTIFICATION_ID = 1001
        const val DEFAULT_CHANNEL = "shroud"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ircJob: Job? = null
    private var translator: Translator = MlKitTranslator()
    private val dedup = DedupFilter()

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var currentChannel: String = DEFAULT_CHANNEL

    // ComposeView needs a LifecycleOwner + SavedStateRegistryOwner when hosted in a Service.
    private inner class ServiceLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun handleCreate() {
            savedStateController.performAttach()
            savedStateController.performRestore(null)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        fun handleDestroy() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
    private var lifecycleOwner: ServiceLifecycleOwner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val channel = (intent.getStringExtra(EXTRA_CHANNEL) ?: DEFAULT_CHANNEL)
                    .trim().lowercase().removePrefix("#")
                    .ifEmpty { DEFAULT_CHANNEL }
                currentChannel = channel
                startForegroundWithNotification(channel)
                startOverlay()
                startTranslationLoop(channel)
            }
        }
        return START_STICKY
    }

    private fun startForegroundWithNotification(channel: String) {
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Twitch 翻译运行中")
            .setContentText("正在翻译 #$channel 的弹幕 (EN → 中文)")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        DanmakuRepository.setRunning(true)
        DanmakuRepository.setStatus("已启动，正在连接 #$channel")
    }

    private fun startOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val owner = ServiceLifecycleOwner()
        owner.handleCreate()
        lifecycleOwner = owner

        val view = ComposeView(this)
        view.setViewTreeLifecycleOwner(owner)
        view.setViewTreeSavedStateRegistryOwner(owner)
        view.setContent {
            OverlayContent()
        }
        val settings = DanmakuRepository.settings.value
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = when (settings.position) {
            1 -> Gravity.CENTER
            2 -> Gravity.BOTTOM
            else -> Gravity.TOP
        }
        params.y = when (settings.position) {
            1 -> 0
            2 -> 200
            else -> 120
        }
        try {
            wm.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            DanmakuRepository.setStatus("悬浮窗添加失败: ${e.message}，请检查悬浮窗权限")
        }
    }

    private fun startTranslationLoop(channel: String) {
        ircJob?.cancel()
        dedup.clear()
        DanmakuRepository.clear()
        // Translation loop runs on a background scope. IRC callback is already on IO.
        // We throttle: max ~4 translations per second to avoid ML Kit queue pile-up.
        val client = TwitchChatClient(
            onMessage = { user, text ->
                serviceScope.launch {
                    handleChatMessage(channel, user, text)
                }
            },
            onStatus = { s -> DanmakuRepository.setStatus(s) }
        )
        // Keep a reference so stopAll() can disconnect. We store in a field via a holder.
        // Simpler: start a dedicated scope that owns the client lifetime.
        ircJob = serviceScope.launch {
            // Rate limiter: simple delay-based throttle in handleChatMessage.
            // The client itself runs its own reconnect loop.
            client.connect(channel, this)
            // Keep job alive until cancelled; client.connect launches its own job.
            // We suspend here until the service is stopped.
            try {
                // Wait forever until cancelled
                delay(Long.MAX_VALUE)
            } finally {
                client.disconnect()
            }
        }
        // Store client for stop
        currentClient = client
    }

    private var currentClient: TwitchChatClient? = null

    // Simple token-bucket-ish throttle: at most 1 translation per 250ms.
    private var lastTranslateAt = 0L

    private suspend fun handleChatMessage(channel: String, user: String, text: String) {
        if (!dedup.shouldProcess(user, text)) return
        // Throttle
        val now = System.currentTimeMillis()
        val wait = 250 - (now - lastTranslateAt)
        if (wait > 0) delay(wait)
        lastTranslateAt = System.currentTimeMillis()

        val translated = try {
            // Skip translation if text is already Chinese / no English letters
            if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) {
                translator.translate(text)
            } else text
        } catch (e: Exception) {
            text
        }
        DanmakuRepository.add(
            DanmakuItem(
                channel = channel,
                user = user,
                original = text,
                translated = translated
            )
        )
    }

    private fun stopAll() {
        ircJob?.cancel()
        ircJob = null
        currentClient?.disconnect()
        currentClient = null
        try { translator.close() } catch (_: Exception) {}
        translator = MlKitTranslator()
        try {
            overlayView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        overlayView = null
        lifecycleOwner?.handleDestroy()
        lifecycleOwner = null
        DanmakuRepository.setRunning(false)
        DanmakuRepository.setStatus("已停止")
        serviceScope.cancel()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Twitch 翻译",
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "显示 Twitch 翻译服务正在运行"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}

@Composable
private fun OverlayContent() {
    val items by DanmakuRepository.items.collectAsState()
    val settings by DanmakuRepository.settings.collectAsState()
    if (!settings.enabled || items.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth()) {}
        return
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .alpha(settings.alpha),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Show newest N items, newest at bottom (like danmaku flow)
        val visible = items.takeLast(settings.maxItems)
        for (item in visible) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = Color(0xFF111111),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Column {
                    Text(
                        text = "${item.user}: ${item.translated}",
                        color = Color.White,
                        fontSize = settings.fontSizeSp.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2
                    )
                    if (item.original != item.translated) {
                        Text(
                            text = item.original,
                            color = Color(0xFFB0B0B0),
                            fontSize = (settings.fontSizeSp - 3f).coerceAtLeast(10f).sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
