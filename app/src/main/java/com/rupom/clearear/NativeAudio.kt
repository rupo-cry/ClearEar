package com.rupom.clearear

object NativeAudio {
    init { System.loadLibrary("clearear") }
    external fun start(bluetooth: Boolean, inputId: Int, sco: Boolean): Boolean
    external fun stop()
    external fun setParams(strength: Float, voiceFocus: Boolean, gain: Float)
    external fun getLatencyMs(): Double
}
