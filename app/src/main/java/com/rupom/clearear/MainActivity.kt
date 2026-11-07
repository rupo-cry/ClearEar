package com.rupom.clearear

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var latView: TextView
    private lateinit var toggle: Button
    private var on = false
    private var mode = 0
    private var btMic = false
    private val h = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            val l = NativeAudio.getLatencyMs()
            latView.text = if (!on || l < 0) "" else {
                val s = "আনুমানিক দেরি: %.0f ms".format(l)
                if (AudioService.currentBt) s + "\n(ব্লুটুথ কোডেকের ১০০-২০০ ms এতে ধরা নেই)" else s
            }
            h.postDelayed(this, 1000)
        }
    }

    private fun seek(label: String, max: Int, start: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 40, 0, 0) }
        box.addView(TextView(this).apply { text = label })
        box.addView(SeekBar(this).apply {
            this.max = max; progress = start
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        return box
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val sv = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }
        sv.addView(root)
        root.addView(TextView(this).apply { text = "ClearEar V1.0"; textSize = 26f })

        statusView = TextView(this).apply { text = AudioService.status; textSize = 16f; setPadding(0, 32, 0, 8) }
        root.addView(statusView)
        latView = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, 24) }
        root.addView(latView)

        toggle = Button(this).apply { text = "চালু করো"; textSize = 20f; setOnClickListener { onToggle() } }
        root.addView(toggle)

        val rg = RadioGroup(this).apply { setPadding(0, 48, 0, 0) }
        listOf("অটো", "ওয়্যার্ড", "ব্লুটুথ").forEachIndexed { i, t ->
            rg.addView(RadioButton(this).apply { text = t; id = 100 + i })
        }
        rg.check(100)
        rg.setOnCheckedChangeListener { _, id -> mode = id - 100; if (on) startSvc() }
        root.addView(rg)

        root.addView(CheckBox(this).apply {
            text = "ব্লুটুথ হেডসেটের মাইক ব্যবহার করো"
            setOnCheckedChangeListener { _, c -> btMic = c; if (on) startSvc() }
        })
        root.addView(CheckBox(this).apply {
            text = "ভয়েস ফোকাস (শুধু কথার ফ্রিকোয়েন্সি)"
            setOnCheckedChangeListener { _, c -> AudioService.voiceFocus = c; AudioService.applyParams() }
        })

        root.addView(seek("নয়েজ কমানো", 10, 6) { AudioService.strength = it / 10f; AudioService.applyParams() })
        root.addView(seek("ভলিউম বুস্ট", 30, 10) { AudioService.gain = it / 10f; AudioService.applyParams() })

        setContentView(sv)
        AudioService.applyParams()
    }

    override fun onResume() {
        super.onResume()
        AudioService.listener = { statusView.text = it }
        statusView.text = AudioService.status
        h.post(poll)
    }

    override fun onPause() { AudioService.listener = null; h.removeCallbacks(poll); super.onPause() }

    private fun onToggle() {
        if (on) {
            startService(Intent(this, AudioService::class.java).setAction(AudioService.ACTION_STOP))
            on = false; toggle.text = "চালু করো"
        } else {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.BLUETOOTH_CONNECT), 1)
                return
            }
            startSvc()
            on = true; toggle.text = "বন্ধ করো"
        }
    }

    private fun startSvc() {
        startForegroundService(Intent(this, AudioService::class.java)
            .setAction(AudioService.ACTION_START)
            .putExtra(AudioService.EXTRA_MODE, mode)
            .putExtra(AudioService.EXTRA_BTMIC, btMic))
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) onToggle()
    }
}
