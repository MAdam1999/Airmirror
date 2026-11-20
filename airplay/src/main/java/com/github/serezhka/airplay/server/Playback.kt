package com.github.serezhka.airplay.server

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo

interface Playback {

    fun onVideoFormat(videoStreamInfo: VideoStreamInfo)

    fun onVideo(bytes: ByteArray)

    fun onVideoSrcDisconnect()

    fun onAudioFormat(audioStreamInfo: AudioStreamInfo)

    fun onAudio(bytes: ByteArray)

    /**
     * Audio AU with an explicit range. Default copies into a tight array and calls [onAudio].
     * Hot-path players should override to avoid the copy when they can consume a slice.
     */
    fun onAudio(bytes: ByteArray?, offset: Int, length: Int) {
        if (bytes == null || length <= 0) return
        if (offset == 0 && length == bytes.size) {
            onAudio(bytes)
            return
        }
        onAudio(bytes.copyOfRange(offset, offset + length))
    }

    fun onAudioSrcDisconnect()

    fun onPlaylist(playlistUri: String) {}

    fun onPlaylistContent(playlistUri: String, content: String) {}

    fun onPlaylistRemoved() {}

    fun onPause() {}

    fun onResume() {}

    fun onSeek(positionSeconds: Double) {}

    /** Linear volume in `[0, 1]`. RTSP dB is converted by the control layer. */
    fun onVolume(volumeLinear: Double) {}

    fun volume(): Double = 1.0

    fun onControlExchange(exchange: ControlExchange) {}

    fun info(): Info = Info.idle()

    /** Server registers one observer. The player calls [Observer.onEnded] at VOD end. */
    fun setObserver(observer: Observer) {}

    interface Observer {
        fun onEnded()

        /** First picture is on the display. Until then the sender stays on `loading`. */
        fun onPresented() {}

        /** A seek has a picture. Tell the sender `playing` at the real position. */
        fun onSeekDisplayed() {}

        companion object {
            @JvmField
            val NONE: Observer = object : Observer {
                override fun onEnded() {}
            }
        }
    }

    /** `rate`: `0` paused, `1` playing (`/playback-info`, `/rate`). */
    data class Info(val duration: Double, val position: Double, val rate: Double = 1.0) {
        companion object {
            @JvmStatic
            fun idle(): Info = Info(0.0, 0.0, 1.0)
        }
    }
}
