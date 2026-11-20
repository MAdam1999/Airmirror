package com.github.serezhka.airplay.protocol.discovery

/**
 * Bits of mDNS TXT `flags` / `sf` (receiver system state).
 */
object AirPlayStatusFlags {

    const val PROBLEM_DETECTED = 1 shl 0
    const val NOT_CONFIGURED = 1 shl 1
    const val AUDIO_CABLE_ATTACHED = 1 shl 2
    /** On-screen / one-shot PIN expected for pairing. */
    const val PIN_REQUIRED = 1 shl 3
    const val SUPPORTS_AIRPLAY_FROM_CLOUD = 1 shl 6
    /** Password (or equivalent) required to connect. */
    const val PASSWORD_REQUIRED = 1 shl 7
    const val ONE_TIME_PAIRING_REQUIRED = 1 shl 9
    const val HK_ACCESS_CONTROL_CONFIGURED = 1 shl 10
    const val DEVICE_SUPPORTS_RELAY = 1 shl 11
    const val RECEIVER_SESSION_ACTIVE = 1 shl 17

    @JvmStatic
    fun passwordRequired(flags: Long): Boolean = (flags and PASSWORD_REQUIRED.toLong()) != 0L

    @JvmStatic
    fun pinRequired(flags: Long): Boolean =
        (flags and PIN_REQUIRED.toLong()) != 0L || (flags and ONE_TIME_PAIRING_REQUIRED.toLong()) != 0L

    /** Parse TXT hex (`0x…` or bare hex). Returns 0 on missing/invalid. */
    @JvmStatic
    fun parse(hex: String?): Long {
        if (hex.isNullOrBlank()) return 0L
        var h = hex.trim()
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2)
        // Some stacks publish two words; take the low word if comma-separated.
        val comma = h.indexOf(',')
        if (comma > 0) h = h.substring(0, comma)
        return try {
            java.lang.Long.parseLong(h, 16)
        } catch (e: NumberFormatException) {
            0L
        }
    }
}
