package com.reapermonitor.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

class AudioPlayer(context: Context) {

    @Volatile private var track: AudioTrack? = null
    @Volatile private var channels = 0
    @Volatile private var sampleRate = 0
    @Volatile private var framesPerBlock = 0

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nativeBurstFrames: Int
    private val nativeSampleRate: Int

    init {
        nativeBurstFrames = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        nativeSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
    }

    private val ring = ArrayDeque<FloatArray>()
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val running = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    // 环形缓冲只留 6 块（128 帧 ≈ 16ms），满丢最旧，宁要低延迟不要深缓冲。
    private val capacityBlocks = 6

    fun configure(channels: Int, sampleRate: Int, framesPerBlock: Int) {
        lock.withLock {
            ring.clear()
            this.channels = channels
            this.sampleRate = sampleRate
            this.framesPerBlock = framesPerBlock
        }
        rebuildTrack(channels, sampleRate)
        ensureThread()
    }

    private fun rebuildTrack(channels: Int, sampleRate: Int) {
        releaseTrack()
        val channelMask = if (channels >= 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val bytesPerFrame = channels * 4
        // 以设备原生 burst 为基准，缓冲只留 2 个 burst（至少 2 个 ReaStream 块）。
        val burstFrames = if (nativeBurstFrames > 0) nativeBurstFrames else framesPerBlock
        val bufferFrames = maxOf(burstFrames * 2, framesPerBlock * 2)
        val bufSize = bufferFrames * bytesPerFrame
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply {
                if (Build.VERSION.SDK_INT >= 29) setFlags(AudioAttributes.FLAG_LOW_LATENCY)
            }
            .build()
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(bufSize)
            .build()
        track = t
        t.play()
    }

    private fun releaseTrack() {
        val t = track
        track = null
        if (t != null) {
            try {
                t.pause()
            } catch (_: Exception) {
            }
            try {
                t.flush()
            } catch (_: Exception) {
            }
            try {
                t.release()
            } catch (_: Exception) {
            }
        }
    }

    fun enqueue(block: ByteArray) {
        val ch = channels
        if (ch <= 0) return
        val totalSamples = block.size / 4
        if (totalSamples == 0) return
        val frames = totalSamples / ch
        if (frames == 0) return
        val planar = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val interleaved = FloatArray(frames * ch)
        // 服务器实际发送 planar（非交错）float32 小端，网页端 app/static/app.js 同样按 planar 解交织。
        for (f in 0 until frames) {
            for (c in 0 until ch) {
                interleaved[f * ch + c] = planar[c * frames + f]
            }
        }
        lock.withLock {
            while (ring.size >= capacityBlocks) {
                ring.removeFirst()
            }
            ring.addLast(interleaved)
            notEmpty.signal()
        }
    }

    private fun ensureThread() {
        if (running.getAndSet(true)) return
        playbackThread = thread(name = "audio-playback") {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {
            }
            playbackLoop()
        }
    }

    private fun playbackLoop() {
        var silence: FloatArray? = null
        try {
            while (running.get()) {
                val t = track
                if (t == null) {
                    Thread.sleep(5)
                    continue
                }
                val ch = channels
                val fpb = framesPerBlock
                if (ch <= 0 || fpb <= 0) {
                    Thread.sleep(5)
                    continue
                }

                val block: FloatArray? = lock.withLock {
                    while (ring.isEmpty() && running.get()) {
                        notEmpty.await(20, TimeUnit.MILLISECONDS)
                    }
                    if (!running.get()) null else ring.pollFirst()
                }

                val data = block ?: run {
                    var s = silence
                    if (s == null || s.size != fpb * ch) {
                        s = FloatArray(fpb * ch)
                        silence = s
                    }
                    s
                }

                try {
                    t.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
                } catch (e: IllegalStateException) {
                    Thread.sleep(5)
                }
            }
        } finally {
            releaseTrack()
        }
    }

    /** 设备音频参数与欠载计数，显示在混音页帮助判断手机硬件延迟下限。 */
    fun getLatencyInfo(): String {
        val parts = ArrayList<String>()
        if (nativeBurstFrames > 0) {
            val rate = if (sampleRate > 0) sampleRate else 48000
            val ms = nativeBurstFrames * 1000f / rate
            parts.add("原生缓冲 " + nativeBurstFrames + " 帧 (~" + String.format("%.1f", ms) + " ms)")
        } else {
            parts.add("设备未报告低延迟参数")
        }
        if (nativeSampleRate > 0) parts.add("输出 " + nativeSampleRate + " Hz")
        parts.add("欠载 " + (track?.underrunCount ?: 0))
        return parts.joinToString(" · ")
    }

    fun release() {
        running.set(false)
        lock.withLock { notEmpty.signalAll() }
        try {
            playbackThread?.join(500)
        } catch (_: InterruptedException) {
        }
        releaseTrack()
    }
}
