package com.reapermonitor.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

const val EXTRA_BASE_URL = "extra_base_url"
const val EXTRA_MONITOR_ID = "extra_monitor_id"
const val EXTRA_MONITOR_NAME = "extra_monitor_name"
const val DEFAULT_SERVER_INPUT = "192.168.10.109"
const val DEFAULT_PORT = 61390

data class Monitor(val id: String, val name: String)

data class MonitorConfig(
    val monitorPrefix: String,
    val monitors: List<Monitor>,
    val sources: List<String>
)

object ApiClient {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    /** 规范化用户输入：补 http:// 前缀；缺端口时补默认端口 61390。 */
    fun normalizeBaseUrl(input: String): String {
        var s = input.trim()
        if (s.isEmpty()) throw IllegalArgumentException("请输入服务器 IP")
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://" + s
        val schemeEnd = s.indexOf("://") + 3
        val pathStart = s.indexOf('/', schemeEnd)
        val authority = if (pathStart >= 0) s.substring(schemeEnd, pathStart) else s.substring(schemeEnd)
        if (!authority.contains(':')) {
            s = if (pathStart >= 0) {
                s.substring(0, schemeEnd) + authority + ":" + DEFAULT_PORT + s.substring(pathStart)
            } else {
                s.substring(0, schemeEnd) + authority + ":" + DEFAULT_PORT
            }
        }
        return s.trimEnd('/')
    }

    fun fetchConfig(baseUrl: String): MonitorConfig {
        val request = Request.Builder().url(baseUrl + "/api/config").get().build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("服务器返回 HTTP " + resp.code)
            val body = resp.body?.string() ?: throw IOException("服务器返回空响应")
            val obj = JSONObject(body)
            val prefix = obj.optString("monitor_prefix", "MON ")
            val monitors = ArrayList<Monitor>()
            val monArr: JSONArray = obj.optJSONArray("monitors") ?: JSONArray()
            for (i in 0 until monArr.length()) {
                val m = monArr.getJSONObject(i)
                monitors.add(Monitor(m.getString("id"), m.getString("name")))
            }
            val sources = ArrayList<String>()
            val srcArr: JSONArray = obj.optJSONArray("sources") ?: JSONArray()
            for (i in 0 until srcArr.length()) sources.add(srcArr.getString(i))
            return MonitorConfig(prefix, monitors, sources)
        }
    }
}
