package com.github.serezhka.airplay.protocol.mirror

/**
 * Fixed layout of the 128-byte AirPlay mirror TCP packet header.
 */
object MirrorPacketLayout {
    const val HEADER_BYTES = 128

    /** Payload type: encrypted (or sealed) video AU. */
    const val PAYLOAD_VIDEO = 0
    /** Payload type: codec config (often plaintext). */
    const val PAYLOAD_CODEC = 1
}
