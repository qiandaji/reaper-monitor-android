package com.reapermonitor.app

import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import kotlin.math.min

interface AudioListener {
    fun onStreamInfo(channels: Int, sampleRate: Int, framesPerBlock: Int)
    fun onAudioBlock(bytes: ByteArray)
    fun onAudioOpen()
    fun onAudioClosed(reconnecting: Boolean)
    fun onAudioError(message: String)
}

class AudioClient(
    private val baseUrl: String,
    private val monitorId: String,
    private val listener: AudioListener
) {
    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var reconnectAttempt = 0
    private var closedByUser = false
    private var reconnectRunnable: Runnable? = null

    fun connect() {
        if (closedByUser) return
        val url = (if (baseUrl.startsWith("https://")) baseUrl.replaceFirst("https://", "wss://") else baseUrl.replaceFirst("http://", "ws://")) + "/ws/audio/" + monitorId
        val request = Request.Builder().url(url).build()
        ws = ApiClient.http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                handler.post { listener.onAudioOpen() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    if (obj.optString("type") == "stream_info") {
                        val channels = obj.getInt("channels")
                        val sampleRate = obj.getInt("sampleRate")
                        val framesPerBlock = obj.getInt("framesPerBlock")
                        handler.post { listener.onStreamInfo(channels, sampleRate, framesPerBlock) }
                    }
                } catch (ignored: Exception) {
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handler.post { listener.onAudioBlock(bytes.toByteArray()) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post { scheduleReconnect() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { scheduleReconnect() }
            }
        })
    }

    private fun scheduleReconnect() {
        if (closedByUser || reconnectRunnable != null) return
        listener.onAudioClosed(true)
        val delay = reconnectDelayMs()
        reconnectAttempt++
        val task = Runnable {
            reconnectRunnable = null
            connect()
        }
        reconnectRunnable = task
        handler.postDelayed(task, delay)
    }

    private fun reconnectDelayMs(): Long {
        val exp = 1L shl reconnectAttempt.coerceIn(0, 12)
        return min(2000L * exp, 10000L)
    }

    fun close() {
        closedByUser = true
        reconnectRunnable?.let(handler::removeCallbacks)
        try {
            ws?.close(1000, "bye")
        } catch (ignored: Exception) {
        }
    }
}
