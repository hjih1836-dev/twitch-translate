package com.example.twitchtranslate

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * Translator abstraction.
 * Default implementation is ML Kit on-device (offline, free, fast).
 * Swap to [CloudTranslator] when you need higher quality / idiom handling.
 */
interface Translator {
    suspend fun translate(text: String): String
    fun close()
}

/**
 * ML Kit on-device English -> Chinese translator.
 *
 * - No API key, no network for translation itself (only model download once).
 * - First use: downloadModelIfNeeded() fetches the EN->ZH model (~30-40 MB).
 *   Show a "正在下载翻译模型…" status while this runs.
 * - If the model is not yet ready, [translate] will download first.
 * - Quality: good for short chat messages, weaker for slang / memes / typos.
 *   For Twitch slang ("pog", "KEKW", "LUL") consider a small glossary
 *   pre-pass in [translate] (see [expandSlang]).
 */
class MlKitTranslator : Translator {
    private val options = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.ENGLISH)
        .setTargetLanguage(TranslateLanguage.CHINESE)
        .build()
    private val client = Translation.getClient(options)

    @Volatile
    private var modelReady = false

    suspend fun ensureModel() {
        if (!modelReady) {
            // Default DownloadConditions allows download on any network.
            // If you want Wi-Fi only, use DownloadConditions.Builder().requireWifi().build()
            client.downloadModelIfNeeded().await()
            modelReady = true
        }
    }

    override suspend fun translate(text: String): String {
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return ""
        ensureModel()
        // Fast path: pure emoji / numbers / URLs need no translation.
        if (isNonTranslatable(cleaned)) return cleaned
        val expanded = expandSlang(cleaned)
        return try {
            client.translate(expanded).await()
        } catch (e: Exception) {
            // On failure return original so the overlay still shows something.
            cleaned
        }
    }

    override fun close() {
        try { client.close() } catch (_: Exception) {}
    }

    private fun isNonTranslatable(text: String): Boolean {
        // If after stripping emoji/symbols/numbers nothing alphabetic remains, skip.
        return text.none { it.isLetter() }
    }

    /**
     * Minimal Twitch slang glossary. ML Kit often leaves these untranslated
     * or mistranslates them. Expand to a short Chinese gloss before translation.
     * Keep this list small; it is a heuristic, not a full dictionary.
     */
    private fun expandSlang(text: String): String {
        var out = text
        val map = mapOf(
            "KEKW" to "笑死",
            "LUL" to "哈哈",
            "Pog" to "厉害",
            "PogChamp" to "厉害",
            "OMEGALUL" to "笑死",
            "Kappa" to "开玩笑",
            "gg" to "打得好",
            "GG" to "打得好",
            "wp" to "打得好",
            "ez" to "太简单了",
            "EZ" to "太简单了",
            "W" to "赢了",
            "L" to "输了"
        )
        for ((k, v) in map) {
            // Word-boundary replacement, case-sensitive for acronyms, case-insensitive for gg/ez.
            val regex = Regex("(?i)\\b${Regex.escape(k)}\\b")
            // Only replace if the original is mostly that token; otherwise keep original
            // and let the model handle context. Here we replace all occurrences.
            out = regex.replace(out) { v }
        }
        return out
    }
}

/**
 * Cloud translation placeholder.
 *
 * To switch to cloud translation:
 *  1. Create a translation API key in your cloud console (e.g. Google Cloud
 *     Translation, DeepL, Azure Translator). NEVER hardcode the key in source.
 *     Use BuildConfig / local.properties / encrypted storage instead.
 *  2. Implement [translate] with an HTTPS POST to the provider's endpoint.
 *  3. In MainActivity / OverlayService, inject CloudTranslator instead of
 *     MlKitTranslator, e.g. via a simple factory flag.
 *
 * This class intentionally does NOT contain any key or endpoint.
 * It returns the original text so the app still runs if selected by mistake.
 */
class CloudTranslator : Translator {
    override suspend fun translate(text: String): String {
        // TODO: Implement cloud translation here. See README "切换到云端翻译".
        return text
    }
    override fun close() {}
}
