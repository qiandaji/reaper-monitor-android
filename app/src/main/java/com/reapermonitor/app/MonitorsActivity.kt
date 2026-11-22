package com.reapermonitor.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

class MonitorsActivity : Activity() {

    private lateinit var monitorList: LinearLayout
    private lateinit var countText: TextView
    private lateinit var errorText: TextView

    private var baseUrl = ""
    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            refresh()
            pollHandler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: run {
            finish()
            return
        }
        setContentView(R.layout.activity_monitors)
        monitorList = findViewById(R.id.monitor_list)
        countText = findViewById(R.id.monitor_count)
        errorText = findViewById(R.id.error_text)
        findViewById<Button>(R.id.refresh_btn).setOnClickListener { refresh() }
        refresh()
        pollHandler.postDelayed(pollRunnable, 3000)
    }

    override fun onDestroy() {
        super.onDestroy()
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun refresh() {
        thread(name = "fetch-config-poll") {
            try {
                val cfg = ApiClient.fetchConfig(baseUrl)
                runOnUiThread { render(cfg) }
            } catch (e: Exception) {
                runOnUiThread {
                    errorText.text = "无法连接服务器，请确认监听服务已启动，且手机与电脑在同一局域网"
                    errorText.visibility = TextView.VISIBLE
                }
            }
        }
    }

    private fun render(cfg: MonitorConfig) {
        errorText.visibility = TextView.GONE
        countText.text = cfg.monitors.size.toString() + " 条监听轨道"
        monitorList.removeAllViews()
        if (cfg.monitors.isEmpty()) {
            val hint = TextView(this)
            hint.text = "未发现监听轨道。\n请确认 REAPER 已打开工程、已加载 OSC 控制面，\n且监听母线轨道名以 \"" + cfg.monitorPrefix + "\" 开头。\n页面每 3 秒自动刷新。"
            hint.setTextColor(resources.getColor(R.color.text_secondary, theme))
            hint.textSize = 14f
            hint.gravity = Gravity.CENTER
            hint.setPadding(0, 80, 0, 0)
            monitorList.addView(hint)
            return
        }
        for (m in cfg.monitors) {
            val row = TextView(this)
            row.text = m.name
            row.textSize = 18f
            row.setTextColor(resources.getColor(R.color.text_primary, theme))
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(44, 44, 44, 44)
            row.setBackgroundResource(R.drawable.bg_rounded)
            row.backgroundTintList = resources.getColorStateList(R.color.panel, theme)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = 20
            row.layoutParams = lp
            row.setOnClickListener {
                startActivity(
                    Intent(this, MixerActivity::class.java)
                        .putExtra(EXTRA_BASE_URL, baseUrl)
                        .putExtra(EXTRA_MONITOR_ID, m.id)
                        .putExtra(EXTRA_MONITOR_NAME, m.name)
                )
            }
            monitorList.addView(row)
        }
    }
}
