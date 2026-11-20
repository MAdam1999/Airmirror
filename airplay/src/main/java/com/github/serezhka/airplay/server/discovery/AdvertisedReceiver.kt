package com.github.serezhka.airplay.server.discovery

import java.util.EnumSet

/**
 * Receiver advertisement profiles.
 *
 * [ADVERTISED] backs mDNS TXT and `GET /info` — what this server
 * actually implements (mirror, RAOP, FairPlay v3, HLS when enabled).
 * [SERVER_INFO] backs only `GET /server-info` and must stay
 * distinct; see that field's comment.
 */
object AdvertisedReceiver {

    /**
     * Screen mirroring, FairPlay, legacy pairing, RAOP, and the codecs we decode.
     * Video playback (`/play`, volume, HLS, play queue) is included and
     * dropped as a group when `airplay.hls-enabled` is false.
     */
    private val ADVERTISED: Set<AirPlayFeature> = EnumSet.of(
        AirPlayFeature.VIDEO,
        AirPlayFeature.VIDEO_FAIRPLAY,
        AirPlayFeature.VIDEO_VOLUME_CONTROL,
        AirPlayFeature.VIDEO_HTTP_LIVE_STREAMS,
        AirPlayFeature.SCREEN,
        AirPlayFeature.AUDIO,
        AirPlayFeature.FPSAP_V2_5_AES_GCM,
        AirPlayFeature.AUTHENTICATION_4,
        AirPlayFeature.AUDIO_FORMAT_2,
        AirPlayFeature.AUDIO_FORMAT_3,
        AirPlayFeature.AUDIO_FORMAT_4,
        AirPlayFeature.SUPPORTS_LEGACY_PAIRING,
        AirPlayFeature.RAOP,
        AirPlayFeature.SUPPORTS_AIRPLAY_VIDEO_PLAY_QUEUE,
    )

    /**
     * Compact profile for `GET /server-info` only — not mDNS, not `GET /info`.
     *
     * YouTube (and similar HTTP HLS clients) probe `/server-info` before
     * `POST /play`. Advertising the full [ADVERTISED] mask (or
     * [SOURCE_VERSION]) there pushes those clients onto HTTP FairPlay
     * v1 (`/fp-setup`, `/fp-setup2`), which we do not implement;
     * the phone then aborts with “something went wrong” and may fall back to
     * chrome-only mirroring without HLS. Keep this historic compact set
     * (video + HLS + FairPlay, without screen / FPSAP / RAOP) and the
     * matching [SERVER_INFO_SOURCE_VERSION].
     */
    private val SERVER_INFO: Set<AirPlayFeature> = EnumSet.of(
        AirPlayFeature.VIDEO,
        AirPlayFeature.PHOTO,
        AirPlayFeature.VIDEO_FAIRPLAY,
        AirPlayFeature.VIDEO_HTTP_LIVE_STREAMS,
        AirPlayFeature.SLIDESHOW,
        AirPlayFeature.UNASSIGNED_6,
    )

    const val SOURCE_VERSION = "220.68"
    const val MODEL = "AppleTV3,2"
    const val STATUS_FLAGS = 0x44
    const val MDNS_FLAGS = "0x44"
    const val VV = 2

    @JvmField val FEATURES: Long = features(true)
    @JvmField val FEATURES_LO: Int = FEATURES.toInt()
    @JvmField val FEATURES_HI: Int = (FEATURES ushr 32).toInt()
    @JvmField val FEATURES_TXT: String = featuresTxt(true)

    /** Feature mask for [SERVER_INFO]. */
    @JvmField val SERVER_INFO_FEATURES: Long = AirPlayFeature.maskOf(SERVER_INFO)

    /**
     * `srcvers` on `GET /server-info`. Intentionally not
     * [SOURCE_VERSION]: the HTTP HLS probe path expects this value.
     */
    const val SERVER_INFO_SOURCE_VERSION = "101.28"

    @JvmStatic
    fun selected(hlsEnabled: Boolean): Set<AirPlayFeature> {
        val selected = EnumSet.copyOf(ADVERTISED)
        if (!hlsEnabled) selected.removeAll(AirPlayFeature.VIDEO_PLAYBACK)
        return selected
    }

    @JvmStatic
    fun features(hlsEnabled: Boolean): Long = AirPlayFeature.maskOf(selected(hlsEnabled))

    @JvmStatic
    fun featuresTxt(hlsEnabled: Boolean): String {
        val mask = features(hlsEnabled)
        return String.format("0x%X,0x%X", mask.toInt(), (mask ushr 32).toInt())
    }
}
