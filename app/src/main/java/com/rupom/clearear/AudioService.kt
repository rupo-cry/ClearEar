package com.rupom.clearear

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class AudioService : Service() {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val EXTRA_MODE = "mode"      // 0 auto, 1 wired, 2 bluetooth
        const val EXTRA_BTMIC = "btmic"
        @Volatile var status: String = "বন্ধ"
        @Volatile var currentBt = false
        var listener: ((String) -> Unit)? = null
        var gain = 1.0f
        var strength = 0.6f
        var voiceFocus = false
        fun applyParams() = NativeAudio.setParams(strength, voiceFocus, gain)
    }

    private lateinit var am: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var manualMode = 0
    private var useBtMic = false
    private var lastSig = ""
    private var scoActive = false

    private val cb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(d: Array<out AudioDeviceInfo>) = schedule()
        override fun onAudioDevicesRemoved(d: Array<out AudioDeviceInfo>) = schedule()
    }
    private val restartRunnable = Runnable { restart() }
    private fun schedule() { handler.removeCallbacks(restartRunnable); handler.postDelayed(restartRunnable, 400) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (intent?.action == ACTION_STOP) {
            stopEngine(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        manualMode = intent?.getIntExtra(EXTRA_MODE, 0) ?: 0
        useBtMic = intent?.getBooleanExtra(EXTRA_BTMIC, false) ?: false
        startForegroundNotif()
        if (!running) { am.registerAudioDeviceCallback(cb, handler); running = true }
        lastSig = ""
        schedule()
        return START_NOT_STICKY
    }

    private fun startForegroundNotif() {
        val ch = "clearear"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ch, "ClearEar", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle("ClearEar চলছে")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(1, n)
    }

    private fun isBluetooth(t: Int) =
        t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_BROADCAST))

    private fun isWired(t: Int) =
        t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        t == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun enableSco() {
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices.firstOrNull { isBluetooth(it.type) }
                ?.let { am.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION") am.startBluetoothSco()
            @Suppress("DEPRECATION") am.isBluetoothScoOn = true
        }
        scoActive = true
    }

    private fun disableSco() {
        if (!scoActive) return
        if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice()
        else { @Suppress("DEPRECATION") am.stopBluetoothSco(); @Suppress("DEPRECATION") am.isBluetoothScoOn = false }
        am.mode = AudioManager.MODE_NORMAL
        scoActive = false
    }

    private fun restart() {
        if (!running) return
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBt = outs.any { isBluetooth(it.type) }
        val hasWired = outs.any { isWired(it.type) }

        val bt = when (manualMode) { 1 -> false; 2 -> true; else -> if (hasWired) false else hasBt }
        if (!hasBt && !hasWired) {
            NativeAudio.stop(); disableSco(); lastSig = ""
            setStatus("ইয়ারফোন লাগাও (স্পিকারে ফিডব্যাক হবে)")
            return
        }
        val sco = bt && useBtMic && hasBt
        var inputId = 0
        if (sco) {
            enableSco()
            inputId = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isBluetooth(it.type) }?.id ?: 0
        } else {
            disableSco()
            inputId = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.id ?: 0
        }
        val sig = "$bt-$sco-$inputId"
        if (sig == lastSig) return
        lastSig = sig

        val ok = NativeAudio.start(bt, inputId, sco)
        applyParams()
        currentBt = bt
        setStatus(
            if (!ok) "শুরু করা যায়নি"
            else if (bt) (if (sco) "ব্লুটুথ মোড (ব্লুটুথ মাইক) চলছে" else "ব্লুটুথ মোড চলছে")
            else "ওয়্যার্ড মোড চলছে"
        )
    }

    private fun stopEngine() {
        running = false
        handler.removeCallbacks(restartRunnable)
        try { am.unregisterAudioDeviceCallback(cb) } catch (_: Exception) {}
        NativeAudio.stop()
        disableSco()
        lastSig = ""
        setStatus("বন্ধ")
    }

    private fun setStatus(s: String) { status = s; handler.post { listener?.invoke(s) } }

    override fun onDestroy() { stopEngine(); super.onDestroy() }
}
