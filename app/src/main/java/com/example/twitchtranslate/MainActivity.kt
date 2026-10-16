package com.example.twitchtranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Main UI:
 * - Channel input (default "shroud"), universal: any public Twitch channel works.
 * - Start / Stop translation + overlay
 * - Overlay settings: enable, alpha, font size, position (top/center/bottom)
 * - Live history list of translated danmaku (for debugging + reading)
 * - Permission guidance: overlay + notification
 *
 * Flow:
 *  1. User grants overlay permission (SYSTEM_ALERT_WINDOW) via Settings.
 *  2. User grants POST_NOTIFICATIONS on Android 13+.
 *  3. Start -> OverlayService (foreground) connects to Twitch IRC + translates.
 *  4. Switch to Twitch app; translated danmaku floats on top.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Toast.makeText(this, "通知权限被拒绝，前台服务通知可能不显示，但翻译仍可运行", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        onRequestOverlayPermission = { requestOverlayPermission() },
                        onRequestNotificationPermission = { requestNotificationPermission() },
                        onStart = { channel -> startTranslation(channel) },
                        onStop = { stopTranslation() }
                    )
                }
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "已拥有悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, "已拥有通知权限", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "当前系统无需单独申请通知权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startTranslation(channel: String) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
            requestOverlayPermission()
            return
        }
        val clean = channel.trim().lowercase().removePrefix("#").ifEmpty { OverlayService.DEFAULT_CHANNEL }
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_START
            putExtra(OverlayService.EXTRA_CHANNEL, clean)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "正在连接 #$clean …", Toast.LENGTH_SHORT).show()
    }

    private fun stopTranslation() {
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_STOP
        }
        startService(intent)
    }
}

@Composable
private fun MainScreen(
    onRequestOverlayPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStart: (String) -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    var channel by remember { mutableStateOf(OverlayService.DEFAULT_CHANNEL) }
    val status by DanmakuRepository.status.collectAsState()
    val isRunning by DanmakuRepository.isRunning.collectAsState()
    val settings by DanmakuRepository.settings.collectAsState()
    val items by DanmakuRepository.items.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Twitch 弹幕翻译", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "英语 → 中文 · 悬浮覆盖 · 通用频道",
            style = MaterialTheme.typography.bodyMedium
        )

        // Permissions
        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "1. 先授权（只需一次）", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRequestOverlayPermission) {
                        Text("悬浮窗权限")
                    }
                    OutlinedButton(onClick = onRequestNotificationPermission) {
                        Text("通知权限")
                    }
                }
                Text(
                    text = "悬浮窗权限在系统设置里打开；如果看不到悬浮窗，先检查这里。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Channel + start/stop
        Card {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "2. 输入频道，开始翻译", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' } },
                    label = { Text("Twitch 频道名，例如 shroud") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onStart(channel) },
                        enabled = !isRunning
                    ) { Text("开始翻译") }
                    OutlinedButton(
                        onClick = onStop,
                        enabled = isRunning
                    ) { Text("停止") }
                    OutlinedButton(onClick = { DanmakuRepository.clear() }) {
                        Text("清空")
                    }
                }
                Text(text = "状态：$status", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "提示：切换频道时先点停止，再改频道名重新开始。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Overlay settings
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = "3. 悬浮窗设置", style = MaterialTheme.typography.titleSmall)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("启用悬浮窗", modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = {
                            DanmakuRepository.updateSettings { s -> s.copy(enabled = it) }
                        }
                    )
                }
                Text("透明度：${(settings.alpha * 100).toInt()}%")
                Slider(
                    value = settings.alpha,
                    onValueChange = { v ->
                        DanmakuRepository.updateSettings { s -> s.copy(alpha = v.coerceIn(0.3f, 1f)) }
                    },
                    valueRange = 0.3f..1f
                )
                Text("字体大小：${settings.fontSizeSp.toInt()}sp")
                Slider(
                    value = settings.fontSizeSp,
                    onValueChange = { v ->
                        DanmakuRepository.updateSettings { s -> s.copy(fontSizeSp = v) }
                    },
                    valueRange = 12f..24f
                )
                Text("位置：${when (settings.position) { 1 -> "中间"; 2 -> "底部"; else -> "顶部" }}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        DanmakuRepository.updateSettings { s -> s.copy(position = 0) }
                    }) { Text("顶部") }
                    OutlinedButton(onClick = {
                        DanmakuRepository.updateSettings { s -> s.copy(position = 1) }
                    }) { Text("中间") }
                    OutlinedButton(onClick = {
                        DanmakuRepository.updateSettings { s -> s.copy(position = 2) }
                    }) { Text("底部") }
                }
                Text(
                    text = "注意：改位置后需要重新开始一次才会重新计算悬浮窗位置（当前版本限制）。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // History
        Text(text = "实时翻译记录（${items.size}）", style = MaterialTheme.typography.titleSmall)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(items.reversed()) { item ->
                Card {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "${item.user}: ${item.translated}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = item.original,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        Text(
            text = "使用方法：点开始后切换到 Twitch App 看直播，翻译会以悬浮条覆盖在屏幕上。回这里可查看完整记录。",
            style = MaterialTheme.typography.bodySmall
        )
        // Suppress unused warning
        @Suppress("UNUSED_EXPRESSION")
        context
    }
}
