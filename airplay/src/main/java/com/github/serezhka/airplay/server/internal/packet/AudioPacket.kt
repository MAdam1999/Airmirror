package com.github.serezhka.airplay.server.internal.packet

class AudioPacket(
    var flag: Int,
    var type: Int,
    var sequenceNumber: Int,
    var timestamp: Long,
    var ssrc: Long,
    var encodedAudioSize: Int,
) {
    val encodedAudio = ByteArray(480 * 4)

    var isAvailable: Boolean = true
}
