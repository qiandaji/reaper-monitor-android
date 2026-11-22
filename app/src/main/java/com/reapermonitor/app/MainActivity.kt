package com.reapermonitor.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var ipInput: EditText
    private lateinit var connectBtn: Button
    private lateinit var errorText: TextView

    private val prefs by lazy { getSharedPreferences("reaper_monitor_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)

        ipInput = findViewById(R.id.server_ip)
        connectBtn = findViewById(R.id.connect_btn)
        errorText = findViewById(R.id.error_text)

        ipInput.setText(prefs.getString("server_ip", DEFAULT_SERVER_INPUT))
        ipInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onConnect()
                true
            } else false
        }
        connectBtn.setOnClickListener { onConnect() }
    }

    private fun onConnect() {
        val raw = ipInput.text.toString().trim()
        if (raw.isEmpty()) {
            showError("请输入服务器 IP")
            return
        }
        val baseUrl = try {
            ApiClient.normalizeBaseUrl(raw)
        } catch (e: IllegalArgumentException) {
            showError(e.message ?: "服务器地址无效")
            return
        }
        hideError()
        connectBtn.isEnabled = false
        connectBtn.text = "连接中…"
        thread(name = "fetch-config") {
            try {
                ApiClient.fetchConfig(baseUrl)
                runOnUiThread {
                    prefs.edit().putString("server_ip", raw).apply()
                    startActivity(
                        Intent(this, MonitorsActivity::class.java)
                            .putExtra(EXTRA_BASE_URL, baseUrl)
                    )
                    connectBtn.isEnabled = true
                    connectBtn.text = "连接"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showError("无法连接服务器，请确认监听服务已启动，且手机与电脑在同一局域网")
                    connectBtn.isEnabled = true
                    connectBtn.text = "连接"
                }
            }
        }
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = TextView.VISIBLE
    }

    private fun hideError() {
        errorText.visibility = TextView.GONE
    }
}
