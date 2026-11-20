package com.github.serezhka.airplay.protocol.media

/**
 * RTSP SETUP `streams[].type` values used on the wire.
 */
object AirPlayStreamType {
    /** Realtime audio (ALAC / AAC). */
    const val AUDIO_REALTIME = 96
    /** Buffered audio (AirPlay 2). */
    const val AUDIO_BUFFERED = 103
    /** Screen-mirroring H.264 video. */
    const val SCREEN = 110
}
