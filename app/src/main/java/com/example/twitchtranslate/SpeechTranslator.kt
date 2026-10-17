package com.example.twitchtranslate

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 主播语音识别 + 翻译
 *
 * 原理：用系统 SpeechRecognizer 监听麦克风，识别主播说的话（英文），
 * 再用同一个 Translator（ML Kit 英→中）翻译成中文，推到悬浮窗。
 *
 * 两种使用场景都能用：
 *  1. 同一台手机看 Twitch：手机外放的声音会被麦克风收进去，识别成文字。
 *  2. 电脑看直播、手机放旁边：手机麦克风收电脑音箱的声音。
 *
 * 限制（一定要先跟用户说清）：
 *  - 这是麦克风收音，不是直接抓 Twitch 内部音频流。环境噪音、回声、主播语速、
 *    口音、游戏音效都会影响识别率。安静环境 + 外放音量适中效果最好。
 *  - SpeechRecognizer 依赖系统语音识别服务（Google 语音服务），需要联网，
 *    第一次使用可能要下载语音模型。无 Google 服务的设备可能不可用。
 *  - Android 的 AudioPlaybackCapture（直接抓 App 内放音频）理论上更干净，
 *    但需要 MediaProjection 录屏授权，且 Twitch / 系统可能禁止捕获，
 *    实现复杂、兼容性差，所以本版先用麦克风方案，通用性最强。
 *  - 识别是流式的：用 partial results 做低延迟上屏，最终结果再翻译一次，
 *    避免同一句话重复上屏。去重由 DanmakuRepository + 这里的 lastText 做。
 */
class SpeechTranslator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val translator: Translator,
    private val channel: String,
    private val onStatus: (String) -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var lastFinalText = ""
    private var lastFinalAt = 0L
    private var lastPartialText = ""

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun start() {
        if (listening) return
        if (!isAvailable()) {
            onStatus("主播语音识别不可用：此设备无系统语音识别服务")
            return
        }
        listening = true
        startOneSession()
    }

    fun stop() {
        listening = false
        try {
            recognizer?.stopListening()
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun startOneSession() {
        if (!listening) return
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                onStatus("主播语音：正在听…")
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // 常见：ERROR_NO_MATCH（没听清）、ERROR_SPEECH_TIMEOUT（没人说话）
                // 都属于正常，重启一次继续听。ERROR_INSUFFICIENT_PERMISSIONS 需要用户给录音权限。
                if (!listening) return
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    onStatus("主播语音：缺少录音权限，请在设置里允许录音")
                    listening = false
                    return
                }
                // 短暂延迟后重启，避免 tight loop
                scope.launch {
                    kotlinx.coroutines.delay(400)
                    if (listening) startOneSession()
                }
            }
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = texts?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    handleFinal(text)
                }
                // 本轮结束，自动开下一轮，实现“一直听”
                if (listening) {
                    scope.launch {
                        kotlinx.coroutines.delay(150)
                        if (listening) startOneSession()
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val texts = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = texts?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty() && text != lastPartialText) {
                    lastPartialText = text
                    // 实时上屏：先显示原文识别中，翻译在最终结果里做，避免频繁调翻译模型
                    scope.launch {
                        DanmakuRepository.add(
                            DanmakuItem(
                                channel = channel,
                                user = "🎙️ 主播 (识别中)",
                                original = text,
                                translated = "…识别中…"
                            )
                        )
                    }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.US.toLanguageTag())
            // 偏好离线识别失败时自动用在线；不强制离线，避免部分设备无离线模型时直接失败
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 让识别器尽量连续听，不要一停就断
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            onStatus("主播语音启动失败：${e.message}")
        }
    }

    private fun handleFinal(text: String) {
        // 去重：同一句话 3 秒内重复出现就跳过（识别器有时会重复回传）
        val now = System.currentTimeMillis()
        if (text == lastFinalText && now - lastFinalAt < 3000) return
        lastFinalText = text
        lastFinalAt = now
        lastPartialText = ""
        // 太短的直接跳过
        if (text.length < 2) return
        scope.launch {
            val translated = try {
                if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) {
                    translator.translate(text)
                } else text
            } catch (_: Exception) {
                text
            }
            DanmakuRepository.add(
                DanmakuItem(
                    channel = channel,
                    user = "🎙️ 主播",
                    original = text,
                    translated = translated
                )
            )
        }
    }
}
