package com.reapermonitor.app

import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.math.min

data class SourceState(val index: Int, val level: Float, val muted: Boolean)
data class MasterState(val level: Float, val muted: Boolean)
data class MixState(val sources: List<SourceState>, val master: MasterState)

interface ControlListener {
    fun onControlState(state: MixState)
    fun onControlError(message: String)
    fun onControlOpen()
    fun onControlClosed(reconnecting: Boolean)
}

class ControlClient(
    private val baseUrl: String,
    private val monitorId: String,
    private val listener: ControlListener
) {
    private val handler = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var reconnectAttempt = 0
    private var closedByUser = false
    private var reconnectRunnable: Runnable? = null

    fun connect() {
        if (closedByUser) return
        val url = wsBase() + "/ws/control/" + monitorId
        val request = Request.Builder().url(url).build()
        ws = ApiClient.http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                handler.post { listener.onControlOpen() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "state" -> {
                            val state = parseState(obj)
                            handler.post { listener.onControlState(state) }
                        }
                        "error" -> {
                            val message = obj.optString("message", "未知错误")
                            handler.post { listener.onControlError(message) }
                        }
                    }
                } catch (ignored: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handler.post { scheduleReconnect() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handler.post { scheduleReconnect() }
            }
        })
    }

    private fun wsBase(): String = if (baseUrl.startsWith("https://")) {
        baseUrl.replaceFirst("https://", "wss://")
    } else {
        baseUrl.replaceFirst("http://", "ws://")
    }

    private fun scheduleReconnect() {
        if (closedByUser || reconnectRunnable != null) return
        listener.onControlClosed(true)
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

    private fun parseState(obj: JSONObject): MixState {
        val sources = ArrayList<SourceState>()
        val arr = obj.getJSONArray("sources")
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            sources.add(
                SourceState(
                    s.getInt("index"),
                    s.getDouble("level").toFloat(),
                    s.getBoolean("muted")
                )
            )
        }
        val master = obj.getJSONObject("master")
        return MixState(sources, MasterState(master.getDouble("level").toFloat(), master.getBoolean("muted")))
    }

    private fun send(obj: JSONObject) {
        val socket = ws ?: return
        try {
            socket.send(obj.toString())
        } catch (ignored: Exception) {
        }
    }

    fun sendSourceLevel(index: Int, value: Float) {
        send(JSONObject().put("type", "set_source_level").put("index", index).put("value", value.toDouble()))
    }

    fun sendSourceMute(index: Int, muted: Boolean) {
        send(JSONObject().put("type", "set_source_mute").put("index", index).put("muted", muted))
    }

    fun sendMasterLevel(value: Float) {
        send(JSONObject().put("type", "set_master_level").put("value", value.toDouble()))
    }

    fun sendMasterMute(muted: Boolean) {
        send(JSONObject().put("type", "set_master_mute").put("muted", muted))
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
