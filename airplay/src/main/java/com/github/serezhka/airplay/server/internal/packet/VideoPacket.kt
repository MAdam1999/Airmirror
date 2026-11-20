package com.github.serezhka.airplay.server.internal.packet

class VideoPacket(
    val payloadType: Int,
    val payloadSize: Int,
    val payload: ByteArray,
)
