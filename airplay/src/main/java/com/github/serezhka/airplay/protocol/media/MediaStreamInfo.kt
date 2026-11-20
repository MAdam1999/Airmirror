package com.github.serezhka.airplay.protocol.media

interface MediaStreamInfo {

    val streamType: StreamType

    enum class StreamType {
        AUDIO,
        VIDEO,
    }
}
