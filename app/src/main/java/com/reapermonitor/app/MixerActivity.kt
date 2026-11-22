package com.reapermonitor.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MixerActivity : Activity(), ControlListener, AudioListener {

    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var errorText: TextView
    private lateinit var latencyText: TextView
    private lateinit var masterLevel: SeekBar
    private lateinit var masterValue: TextView
    private lateinit var masterMute: Button
    private lateinit var sourceList: LinearLayout

    private var baseUrl = ""
    private var monitorId = ""
    private var monitorName = "监听轨道"
    private var sources: List<String> = emptyList()

    private var controlClient: ControlClient? = null
    private var audioClient: AudioClient? = null
    private val audioPlayer by lazy { AudioPlayer(applicationContext) }

    private val sourceSliders = ArrayList<SeekBar>()
    private val sourceMuteButtons = ArrayList<Button>()
    private val sourceValueLabels = ArrayList<TextView>()
    private val sourceMuted = ArrayList<Boolean>()
    private val sourceLastTouch = ArrayList<Long>()
    private var masterLastTouch = 0L
    private var masterMuted = false

    private var controlOpen = false
    private var audioOpen = false
    private var audioInfoReceived = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private var errorHideRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        baseUrl = intent.getStringExtra(EXTRA_BASE_URL) ?: run {
            finish()
            return
        }
        monitorId = intent.getStringExtra(EXTRA_MONITOR_ID) ?: run {
            finish()
            return
        }
        monitorName = intent.getStringExtra(EXTRA_MONITOR_NAME) ?: monitorName

        setContentView(R.layout.activity_mixer)
        titleText = findViewById(R.id.monitor_title)
        statusText = findViewById(R.id.status_text)
        errorText = findViewById(R.id.error_text)
        latencyText = findViewById(R.id.latency_text)
        masterLevel = findViewById(R.id.master_level)
        masterValue = findViewById(R.id.master_value)
        masterMute = findViewById(R.id.master_mute)
        sourceList = findViewById(R.id.source_list)

        titleText.text = monitorName
        setupMasterControls()
        updateStatus()
        loadConfig()
    }

    override fun onDestroy() {
        super.onDestroy()
        controlClient?.close()
        audioClient?.close()
        audioPlayer.release()
        errorHideRunnable?.let(uiHandler::removeCallbacks)
    }

    private fun loadConfig() {
        thread(name = "fetch-config") {
            try {
                val cfg = ApiClient.fetchConfig(baseUrl)
                runOnUiThread {
                    sources = cfg.sources
                    buildSourceRows()
                    connectClients()
                }
            } catch (e: Exception) {
                runOnUiThread { showError("无法加载配置：" + e.message) }
            }
        }
    }

    private fun connectClients() {
        controlClient = ControlClient(baseUrl, monitorId, this).also { it.connect() }
        audioClient = AudioClient(baseUrl, monitorId, this).also { it.connect() }
    }

    private fun setupMasterControls() {
        masterLevel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                masterLastTouch = SystemClock.elapsedRealtime()
                masterValue.text = progress.toString() + "%"
                controlClient?.sendMasterLevel(progress / 100f)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                masterLastTouch = SystemClock.elapsedRealtime()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })
        masterMute.setOnClickListener {
            masterMuted = !masterMuted
            updateMuteButton(masterMute, masterMuted)
            masterLastTouch = SystemClock.elapsedRealtime()
            controlClient?.sendMasterMute(masterMuted)
        }
    }

    private fun buildSourceRows() {
        sourceList.removeAllViews()
        sourceSliders.clear()
        sourceMuteButtons.clear()
        sourceValueLabels.clear()
        sourceMuted.clear()
        sourceLastTouch.clear()
        for ((index, name) in sources.withIndex()) {
            val row = layoutInflater.inflate(R.layout.item_source, sourceList, false)
            val nameText = row.findViewById<TextView>(R.id.source_name)
            val slider = row.findViewById<SeekBar>(R.id.source_level)
            val value = row.findViewById<TextView>(R.id.source_value)
            val mute = row.findViewById<Button>(R.id.source_mute)

            nameText.text = name
            slider.max = 100
            slider.progress = 75
            value.text = "75%"
            sourceSliders.add(slider)
            sourceMuteButtons.add(mute)
            sourceValueLabels.add(value)
            sourceMuted.add(false)
            sourceLastTouch.add(0L)

            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val idx = sourceSliders.indexOf(seekBar)
                    if (idx < 0) return
                    sourceLastTouch[idx] = SystemClock.elapsedRealtime()
                    sourceValueLabels[idx].text = progress.toString() + "%"
                    controlClient?.sendSourceLevel(idx, progress / 100f)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    val idx = sourceSliders.indexOf(seekBar)
                    if (idx >= 0) sourceLastTouch[idx] = SystemClock.elapsedRealtime()
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                }
            })

            mute.setOnClickListener {
                val idx = sourceMuteButtons.indexOf(mute)
                if (idx < 0) return@setOnClickListener
                val target = !sourceMuted[idx]
                sourceMuted[idx] = target
                updateMuteButton(mute, target)
                sourceLastTouch[idx] = SystemClock.elapsedRealtime()
                controlClient?.sendSourceMute(idx, target)
            }

            sourceList.addView(row)
        }
    }

    private fun updateMuteButton(btn: Button, muted: Boolean) {
        btn.backgroundTintList = resources.getColorStateList(if (muted) R.color.red else R.color.green, theme)
    }

    // ---- ControlListener ----

    override fun onControlOpen() {
        controlOpen = true
        updateStatus()
    }

    override fun onControlState(state: MixState) {
        controlOpen = true
        applyState(state)
        updateStatus()
    }

    override fun onControlError(message: String) {
        showError(message)
    }

    override fun onControlClosed(reconnecting: Boolean) {
        controlOpen = false
        updateStatus()
    }

    private fun applyState(state: MixState) {
        val now = SystemClock.elapsedRealtime()
        state.sources.forEach { s ->
            val idx = s.index
            if (idx in sources.indices) {
                // 推子拖动回环保护：收到广播在 200ms 内不覆盖正在拖动的控件，其它控件正常更新
                if (now - sourceLastTouch[idx] >= 200) {
                    val pct = (s.level * 100).roundToInt().coerceIn(0, 100)
                    sourceSliders[idx].progress = pct
                    sourceValueLabels[idx].text = pct.toString() + "%"
                    sourceMuted[idx] = s.muted
                    updateMuteButton(sourceMuteButtons[idx], s.muted)
                }
            }
        }
        if (now - masterLastTouch >= 200) {
            val pct = (state.master.level * 100).roundToInt().coerceIn(0, 100)
            masterLevel.progress = pct
            masterValue.text = pct.toString() + "%"
            masterMuted = state.master.muted
            updateMuteButton(masterMute, state.master.muted)
        }
    }

    // ---- AudioListener ----

    override fun onStreamInfo(channels: Int, sampleRate: Int, framesPerBlock: Int) {
        audioInfoReceived = true
        audioPlayer.configure(channels, sampleRate, framesPerBlock)
        latencyText.text = "设备音频：" + audioPlayer.getLatencyInfo()
        updateStatus()
    }

    override fun onAudioBlock(bytes: ByteArray) {
        audioPlayer.enqueue(bytes)
    }

    override fun onAudioOpen() {
        audioOpen = true
        audioInfoReceived = false
        updateStatus()
    }

    override fun onAudioClosed(reconnecting: Boolean) {
        audioOpen = false
        audioInfoReceived = false
        updateStatus()
    }

    override fun onAudioError(message: String) {
        showError(message)
    }

    private fun updateStatus() {
        statusText.text = when {
            !controlOpen -> "控制连接中…"
            !audioOpen -> "已连接 · 音频连接中…"
            !audioInfoReceived -> "已连接 · 等待音频…"
            else -> "已连接 · 音频播放中"
        }
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = TextView.VISIBLE
        errorHideRunnable?.let(uiHandler::removeCallbacks)
        val task = Runnable { errorText.visibility = TextView.GONE }
        errorHideRunnable = task
        uiHandler.postDelayed(task, 8000)
    }
}
