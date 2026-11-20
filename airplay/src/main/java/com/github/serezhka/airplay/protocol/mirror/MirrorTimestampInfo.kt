package com.github.serezhka.airplay.protocol.mirror

/**
 * Names advertised in mirror video SETUP `timestampInfo`.
 * Receivers use these labels when correlating NTP timing with frame stamps.
 */
object MirrorTimestampInfo {
    @JvmField
    val NAMES = arrayOf("SubSu", "BePxT", "AfPxT", "BefEn", "EmEnc")
}
