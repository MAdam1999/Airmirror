package com.github.serezhka.airplay.protocol.mirror

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builders for the 128-byte AirPlay mirror TCP header.
 * Layout constants live in [MirrorPacketLayout].
 */
object MirrorPacketHeaders {

    /** Type-1 option field observed on sender codec packets. */
    const val CODEC_OPTION: Short = 286

    /**
     * HKP / DataStream codec (type-1) header: marks a running stream and carries
     * float32 width/height at several offsets some receivers require.
     */
    @JvmStatic
    fun codec(nbytes: Int, ts: Long, width: Int, height: Int): ByteArray {
        val header = ByteArray(MirrorPacketLayout.HEADER_BYTES)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0, nbytes)
        header[4] = MirrorPacketLayout.PAYLOAD_CODEC.toByte()
        header[6] = 0x16
        header[7] = 0x01
        buf.putLong(8, ts)
        for (offset in intArrayOf(16, 40, 56)) {
            buf.putFloat(offset, width.toFloat())
            buf.putFloat(offset + 4, height.toFloat())
        }
        return header
    }

    /**
     * HKP / DataStream video (type-0) header. `nbytes` includes the Poly1305 tag
     * when the payload is sealed.
     */
    @JvmStatic
    fun video(nbytes: Int, ts: Long, keyframe: Boolean): ByteArray {
        val header = ByteArray(MirrorPacketLayout.HEADER_BYTES)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0, nbytes)
        header[4] = MirrorPacketLayout.PAYLOAD_VIDEO.toByte()
        header[5] = if (keyframe) 0x10 else 0x00
        buf.putLong(8, ts)
        return header
    }

    /**
     * Legacy FairPlay-style header (type-1 sets option [CODEC_OPTION]
     * and float32 width/height at offset 16; type-0 keeps those zero).
     */
    @JvmStatic
    @JvmOverloads
    fun legacy(payloadLen: Int, payloadType: Int, pts: Long, width: Int = 0, height: Int = 0): ByteArray {
        val h = ByteBuffer.allocate(MirrorPacketLayout.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(payloadLen)
        h.putShort(payloadType.toShort())
        h.putShort(if (payloadType == MirrorPacketLayout.PAYLOAD_CODEC) CODEC_OPTION else 0)
        h.putLong(pts)
        if (payloadType == MirrorPacketLayout.PAYLOAD_CODEC && width > 0 && height > 0) {
            h.putFloat(width.toFloat())
            h.putFloat(height.toFloat())
        }
        return h.array()
    }
}
