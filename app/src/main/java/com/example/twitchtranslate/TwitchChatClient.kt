package com.example.twitchtranslate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Random
import javax.net.ssl.SSLSocketFactory

/**
 * Minimal Twitch IRC client.
 *
 * Uses irc.chat.twitch.tv:6697 (SSL) with anonymous login.
 * No OAuth / no API key is needed to READ public channel chat:
 *   PASS SCHMOOPIIE
 *   NICK justinfan<random>
 *   JOIN #<channel>
 *
 * This is the documented anonymous read-only path used by many chat readers.
 * It is rate-limited by Twitch: anonymous connections get ~20 messages / 30s
 * for JOINs and are subject to global chat rate limits. For a single channel
 * this is enough for normal viewing; during huge hype bursts some messages
 * may be dropped by Twitch itself.
 *
 * Parsing: only PRIVMSG lines are emitted. PING is answered with PONG.
 * Example line:
 *   :someuser!someuser@someuser.tmi.twitch.tv PRIVMSG #shroud :hello world
 */
class TwitchChatClient(
    private val onMessage: (user: String, text: String) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private var job: Job? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    @Volatile
    private var running = false

    fun connect(channel: String, scope: CoroutineScope) {
        disconnect()
        val cleanChannel = channel.trim().lowercase().removePrefix("#")
        require(cleanChannel.isNotEmpty()) { "channel is empty" }
        // Twitch channel names: lowercase letters, digits, underscore, 4-25 chars.
        // We validate loosely here; the server will reject invalid names.
        running = true
        job = scope.launch(Dispatchers.IO) {
            var attempt = 0
            while (isActive && running) {
                attempt++
                try {
                    onStatus("正在连接 #$cleanChannel (第 $attempt 次)…")
                    val nick = "justinfan${10000 + Random().nextInt(80000)}"
                    val sock = SSLSocketFactory.getDefault()
                        .createSocket("irc.chat.twitch.tv", 6697)
                    sock.soTimeout = 60_000
                    socket = sock
                    val reader = BufferedReader(
                        InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8)
                    )
                    val w = BufferedWriter(
                        OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8)
                    )
                    writer = w
                    // Anonymous login. "SCHMOOPIIE" is the conventional dummy password.
                    w.write("PASS SCHMOOPIIE\r\n"); w.flush()
                    w.write("NICK $nick\r\n"); w.flush()
                    // Request tags/membership for richer parsing (optional, harmless).
                    w.write("CAP REQ :twitch.tv/tags twitch.tv/commands\r\n"); w.flush()
                    w.write("JOIN #$cleanChannel\r\n"); w.flush()
                    onStatus("已连接 #$cleanChannel，等待弹幕…")

                    var line: String?
                    while (isActive && running) {
                        line = try {
                            reader.readLine()
                        } catch (e: Exception) {
                            break
                        } ?: break
                        if (line.startsWith("PING")) {
                            // PING :tmi.twitch.tv -> PONG :tmi.twitch.tv
                            val token = line.substringAfter("PING")
                            w.write("PONG$token\r\n"); w.flush()
                            continue
                        }
                        parsePrivmsg(line)?.let { (user, text) ->
                            onMessage(user, text)
                        }
                    }
                } catch (e: Exception) {
                    if (running) {
                        onStatus("连接断开: ${e.message}，3 秒后重连…")
                    }
                } finally {
                    try { writer?.close() } catch (_: Exception) {}
                    try { socket?.close() } catch (_: Exception) {}
                    writer = null
                    socket = null
                }
                if (!running || !isActive) break
                // Backoff before reconnect, avoid tight loop
                kotlinx.coroutines.delay(3000)
            }
        }
    }

    fun disconnect() {
        running = false
        job?.cancel()
        job = null
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        socket = null
    }

    companion object {
        /**
         * Returns (user, text) for PRIVMSG lines, null otherwise.
         * Handles both plain and @tags=... prefixed lines.
         */
        fun parsePrivmsg(line: String): Pair<String, String>? {
            // Strip IRCv3 tags if present: "@a=b;c=d :user!..."
            val withoutTags = if (line.startsWith("@")) {
                val space = line.indexOf(' ')
                if (space < 0) return null
                line.substring(space + 1)
            } else line

            // Expected: :user!user@user.tmi.twitch.tv PRIVMSG #channel :message
            if (!withoutTags.contains(" PRIVMSG ")) return null
            val prefixEnd = withoutTags.indexOf(' ')
            if (prefixEnd < 0) return null
            val prefix = withoutTags.substring(0, prefixEnd) // ":user!user@..."
            val user = prefix.removePrefix(":").substringBefore("!")
            val msgIndex = withoutTags.indexOf(" :", prefixEnd)
            if (msgIndex < 0) return null
            val text = withoutTags.substring(msgIndex + 2)
            if (user.isBlank() || text.isBlank()) return null
            return user to text
        }
    }
}
