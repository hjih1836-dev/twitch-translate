package com.example.twitchtranslate

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Single source of truth for translated danmaku items.
 * Both MainActivity (history list) and OverlayService (floating window)
 * observe the same flow.
 */
data class DanmakuItem(
    val id: String = UUID.randomUUID().toString(),
    val channel: String,
    val user: String,
    val original: String,
    val translated: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class OverlaySettings(
    val enabled: Boolean = true,
    /** 0.3f .. 1.0f, background alpha of the overlay cards */
    val alpha: Float = 0.85f,
    /** 12 .. 24 */
    val fontSizeSp: Float = 16f,
    /** 0 = top, 1 = center, 2 = bottom */
    val position: Int = 0,
    /** max items kept in overlay */
    val maxItems: Int = 6,
    /** how long an item stays visible (ms) */
    val ttlMillis: Long = 8000L,
    /** 是否开启主播语音识别 + 翻译（麦克风收音） */
    val speechEnabled: Boolean = false
)

object DanmakuRepository {
    private val _items = MutableStateFlow<List<DanmakuItem>>(emptyList())
    val items: StateFlow<List<DanmakuItem>> = _items.asStateFlow()

    private val _settings = MutableStateFlow(OverlaySettings())
    val settings: StateFlow<OverlaySettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow("未连接")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun setStatus(text: String) {
        _status.value = text
    }

    fun setRunning(running: Boolean) {
        _isRunning.value = running
    }

    fun updateSettings(transform: (OverlaySettings) -> OverlaySettings) {
        _settings.value = transform(_settings.value)
    }

    fun add(item: DanmakuItem) {
        val now = System.currentTimeMillis()
        val ttl = _settings.value.ttlMillis
        val kept = _items.value
            .filter { now - it.timestampMillis < ttl * 2 }
            .takeLast(50)
        _items.value = (kept + item).takeLast(60)
    }

    fun clear() {
        _items.value = emptyList()
    }
}

/**
 * Deduplication + rate limiting for chat messages.
 * Twitch chat can burst (e.g. hype moments). Without this the translator
 * and overlay would be flooded.
 *
 * - Same text from same user within [dedupWindowMillis] is dropped.
 * - Same text from anyone within [globalDedupWindowMillis] is dropped
 *   (copy-paste spam).
 * - Queue is capped; oldest items are dropped when full.
 */
class DedupFilter(
    private val dedupWindowMillis: Long = 15_000L,
    private val globalDedupWindowMillis: Long = 5_000L,
    private val maxSize: Int = 200
) {
    private data class Seen(val key: String, val at: Long)
    private val seen = ConcurrentLinkedQueue<Seen>()

    fun shouldProcess(user: String, text: String): Boolean {
        val now = System.currentTimeMillis()
        // Prune old entries
        while (seen.isNotEmpty()) {
            val head = seen.peek() ?: break
            if (now - head.at > dedupWindowMillis) seen.poll() else break
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        // Very short messages like "lol", "hi" are not worth translating.
        // Keep them out to save translation budget.
        if (trimmed.length < 2) return false

        val userKey = "$user::$trimmed"
        val globalKey = "global::$trimmed"
        for (entry in seen) {
            if (entry.key == userKey && now - entry.at < dedupWindowMillis) return false
            if (entry.key == globalKey && now - entry.at < globalDedupWindowMillis) return false
        }
        seen.add(Seen(userKey, now))
        seen.add(Seen(globalKey, now))
        while (seen.size > maxSize) seen.poll()
        return true
    }

    fun clear() = seen.clear()
}
