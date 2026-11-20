package com.github.serezhka.airplay.server.internal.handler.util

import kotlin.math.log10
import kotlin.math.pow

/**
 * AirPlay RTSP volume is typically dB in roughly `[-30, 0]` (mute often `-144`).
 * HTTP `/play` uses linear `[0, 1]`.
 */
object AirPlayVolume {

    @JvmStatic
    fun clampLinear(linear: Double): Double {
        if (linear.isNaN()) return 1.0
        return linear.coerceIn(0.0, 1.0)
    }

    /** Convert RTSP dB volume to linear gain in `[0, 1]`. */
    @JvmStatic
    fun fromDecibels(db: Double): Double {
        if (db <= -144.0) return 0.0
        val clamped = db.coerceIn(-30.0, 0.0)
        return clampLinear(10.0.pow(clamped / 20.0))
    }

    /** Convert linear gain to RTSP dB for `GET_PARAMETER`. */
    @JvmStatic
    fun toDecibels(linear: Double): Double {
        val v = clampLinear(linear)
        if (v <= 1e-6) return -144.0
        return 20.0 * log10(v)
    }

    @JvmStatic
    fun formatRtspParameter(linear: Double): String = String.format("volume: %.6f\r\n", toDecibels(linear))
}
