# Reaper Monitor Android

安卓原生监听 App（Kotlin），让乐队成员在手机上获得接近 iPhone 的低延迟监听体验。配合 reaper-monitor 服务端使用：乐手在手机里输入监听服务器的电脑 IP，选择自己的监听母线轨道，就能像网页混音页一样调整总音量 / 总静音以及每个源轨的响度 / 静音，并通过 WebSocket 接收音频流、用低延迟 AudioTrack 实时播放。

## 功能

- 服务器 IP 输入页：自动记住上次输入（SharedPreferences），默认 192.168.10.109。
- 监听轨道列表页：读取 `GET /api/config`，显示所有监听母线轨道，点击进入混音页；每 3 秒自动刷新。
- 混音页：
  - 顶部状态：控制连接中 / 已连接·音频连接中 / 已连接·等待音频 / 已连接·音频播放中，错误信息会显示在顶部并自动消失。
  - 总音量 SeekBar + 总静音按钮（对应服务器 `master` 快照）。
  - 每个源轨一行：名字、响度 SeekBar、静音按钮（对应服务器 `sources[]` 快照，index 与 /api/config 的 sources 数组下标一致）。
  - 推子拖动时发送控制命令；收到服务器广播快照后，正在拖动的控件 200ms 内不被覆盖，避免回环抖动。
- 音频播放：AudioTrack 低延迟模式（`PERFORMANCE_MODE_LOW_LATENCY`）+ PCM Float 小端；环形缓冲满时丢最旧块、欠载时输出静音；播放线程与 WebSocket 回调解耦。
- 双 WebSocket 断线自动重连：2 秒起步，指数退避，上限 10 秒；重连后按新的 stream_info 重建 AudioTrack。

## 在 Android Studio 打开

1. 安装 Android Studio（Giraffe 及以上，自带 JDK 17 与 Android SDK）。
2. `File → Open`，选择本目录（`reaper-monitor-android`）。
3. 等待 Gradle Sync 完成（首次会下载 Gradle 8.11.1、AGP、Kotlin、OkHttp，需要联网）。
4. 连接手机（开发者选项打开 USB 调试），点 `Run`。

## 构建 APK

命令行（macOS/Linux）：

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk   # Windows 改成你的 SDK 路径
./gradlew assembleDebug
```

APK 输出路径：

```
app/build/outputs/apk/debug/app-debug.apk
```

Windows 使用 `gradlew.bat assembleDebug`。

## 侧载到手机（允许未知来源）

1. 手机上进入 `设置 → 安全 / 应用 → 允许安装未知来源应用`（各厂商路径略有差异，也可在安装弹窗里直接跳转开启）。
2. 把 `app-debug.apk` 传到手机（微信/QQ/AirDrop/数据线均可）。
3. 在手机上点击 APK 文件安装并打开。

或用 adb 安装：

```bash
$ANDROID_HOME/platform-tools/adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用步骤

1. 电脑上启动 reaper-monitor 服务端（监听端口 61390），手机与电脑连接同一局域网。
2. 打开 App，在 IP 输入页填入电脑 IP（默认 192.168.10.109），点「连接」。
3. 在轨道列表里点自己的监听轨道（例如「MON 鼓手」）。
4. 混音页出现后即可听到监听音频；拖动总音量或源轨响度、点静音按钮，与网页混音页效果一致。

## 延迟说明（重要）

- App 音频走 Android 低延迟路径：AudioTrack `PERFORMANCE_MODE_LOW_LATENCY` +（Android 10+）`FLAG_LOW_LATENCY`；输出缓冲按设备原生 burst 只留 2 个（最少 2 个 ReaStream 块）；环形缓冲仅 6 块（约 16ms），满丢最旧；播放线程 `THREAD_PRIORITY_URGENT_AUDIO`。
- 混音页顶部会显示「设备音频：原生缓冲 X 帧 (~Y ms) · 输出 Z Hz · 欠载 N」——**Y ms 就是这台手机音频硬件的最低输出延迟**，App 总延迟 ≈ Y×2 + 局域网/REAPER 约 5ms。欠载数字持续上涨说明缓冲偏小、WiFi 抖动导致爆音。
- **请用有线耳机或手机扬声器测试**：蓝牙耳机（含 AirPods）走 A2DP，延迟 100–300ms，任何 App 都无法降低。
- 如果 Y 在 10ms 以上（不少中低端安卓机的原生缓冲就是 20–40ms），那已经是硬件下限，软件无法再降；这类手机建议直接用 iPhone 网页版监听。

## 技术说明

- Kotlin，minSdk 26，targetSdk 35，普通 Android Views/XML（不使用 Compose）。
- 依赖仅 OkHttp 4.12.0（WebSocket + HTTP）与系统内置 org.json。
- 音频链路：服务器 planar float32 小端 PCM → 解交错为 interleaved → 环形缓冲（满丢最旧）→ AudioTrack 低延迟模式写入。
- 协议与 reaper-monitor 服务端（app/main.py、app/streams.py、app/state.py、app/static/app.js）逐字段一致。

## GitHub

https://github.com/qiandaji/reaper-monitor-android
