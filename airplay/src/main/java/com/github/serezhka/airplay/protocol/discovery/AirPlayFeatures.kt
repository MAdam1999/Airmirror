package com.github.serezhka.airplay.protocol.discovery

/**
 * Bits of mDNS TXT `features` / `ft` (64-bit capability mask).
 * Encoding on the wire: `0xLOWER,0xUPPER` → value `(UPPER << 32) | LOWER`.
 */
object AirPlayFeatures {

    const val VIDEO = 0
    const val PHOTO = 1
    const val SCREEN = 7
    const val AUDIO = 9
    const val FPSAP_V2_5_AES_GCM = 12
    const val AUTHENTICATION_4 = 14
    const val HAS_UNIFIED_ADVERTISER_INFO = 26
    const val SUPPORTS_LEGACY_PAIRING = 27
    const val RAOP = 30
    const val SUPPORTS_BUFFERED_AUDIO = 40
    const val SUPPORTS_PTP = 41
    const val SUPPORTS_SYSTEM_PAIRING = 43
    const val SUPPORTS_HK_PAIRING_AND_ACCESS_CONTROL = 46
    const val SUPPORTS_TRANSIENT_PAIRING = 48
    const val SUPPORTS_AIRPLAY_VIDEO_V2 = 49
    const val SUPPORTS_UNIFIED_PAIR_SETUP_AND_MFI = 51

    @JvmStatic
    fun has(features: Long, bit: Int): Boolean = (features and (1L shl bit)) != 0L

    /**
     * Prefer HomeKit-style pairing when the receiver advertises HK / transient pairing
     * and does not advertise legacy pairing.
     */
    @JvmStatic
    fun prefersHomeKitPairing(features: Long): Boolean {
        val hk = has(features, SUPPORTS_HK_PAIRING_AND_ACCESS_CONTROL) ||
            has(features, SUPPORTS_TRANSIENT_PAIRING) ||
            has(features, SUPPORTS_SYSTEM_PAIRING)
        val legacy = has(features, SUPPORTS_LEGACY_PAIRING)
        return hk && !legacy
    }

    /** Parse `0xLOWER,0xUPPER` or a single hex word. Returns 0 on missing/invalid. */
    @JvmStatic
    fun parse(txt: String?): Long {
        if (txt.isNullOrBlank()) return 0L
        val parts = txt.trim().split(",")
        return try {
            val lower = parseWord(parts[0])
            val upper = if (parts.size > 1) parseWord(parts[1]) else 0L
            (upper shl 32) or (lower and 0xFFFFFFFFL)
        } catch (e: NumberFormatException) {
            0L
        }
    }

    private fun parseWord(word: String): Long {
        var h = word.trim()
        if (h.startsWith("0x") || h.startsWith("0X")) h = h.substring(2)
        return java.lang.Long.parseLong(h, 16) and 0xFFFFFFFFL
    }
}
