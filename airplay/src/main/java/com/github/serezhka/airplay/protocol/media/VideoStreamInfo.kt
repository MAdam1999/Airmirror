package com.github.serezhka.airplay.protocol.media

data class VideoStreamInfo(val streamConnectionId: String?) : MediaStreamInfo {
    override val streamType: MediaStreamInfo.StreamType
        get() = MediaStreamInfo.StreamType.VIDEO
}
