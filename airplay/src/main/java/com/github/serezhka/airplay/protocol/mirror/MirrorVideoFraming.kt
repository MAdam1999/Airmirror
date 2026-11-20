package com.github.serezhka.airplay.protocol.mirror

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Annex-B ↔ AVCC helpers and type-1 codec-config payload for AirPlay mirror video.
 */
object MirrorVideoFraming {

    class Nal(val data: ByteArray) {
        fun type(): Int = if (data.isEmpty()) -1 else data[0].toInt() and 0x1f
    }

    private fun isStart4(b: ByteArray, i: Int) =
        i + 4 <= b.size && b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1

    private fun isStart3(b: ByteArray, i: Int) =
        b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1

    @JvmStatic
    fun splitAnnexB(annexB: ByteArray): List<Nal> {
        val out = ArrayList<Nal>()
        var i = 0
        while (i + 3 <= annexB.size) {
            val start = when {
                isStart4(annexB, i) -> i + 4
                isStart3(annexB, i) -> i + 3
                else -> {
                    i++
                    continue
                }
            }
            var j = start
            while (j + 3 <= annexB.size) {
                if (isStart4(annexB, j) || isStart3(annexB, j)) break
                j++
            }
            val nal = annexB.copyOfRange(start, j)
            if (nal.isNotEmpty()) out.add(Nal(nal))
            i = j
        }
        return out
    }

    /** Length-prefixed AVCC payload for type-0 video packets. */
    @JvmStatic
    fun toAvcc(nals: List<Nal>): ByteArray {
        val bos = ByteArrayOutputStream()
        for (nal in nals) {
            val len = nal.data.size
            bos.write((len ushr 24) and 0xff)
            bos.write((len ushr 16) and 0xff)
            bos.write((len ushr 8) and 0xff)
            bos.write(len and 0xff)
            bos.write(nal.data)
        }
        return bos.toByteArray()
    }

    /**
     * Type-1 codec-data payload (AVCDecoderConfigurationRecord-ish) consumed by receivers
     * that skip the first 6 bytes then read spsLen/ppsLen.
     */
    @JvmStatic
    fun buildType1CodecData(sps: ByteArray, pps: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(6 + 2 + sps.size + 1 + 2 + pps.size)
        buf.put(1)
        if (sps.size >= 4) {
            buf.put(sps[1])
            buf.put(sps[2])
            buf.put(sps[3])
        } else {
            buf.put(0)
            buf.put(0)
            buf.put(0)
        }
        buf.put(0xff.toByte())
        buf.put(0xe1.toByte())
        buf.putShort(sps.size.toShort())
        buf.put(sps)
        buf.put(1)
        buf.putShort(pps.size.toShort())
        buf.put(pps)
        return buf.array()
    }
}
