package com.airmirror.tv

/**
 * Pure-Kotlin ALAC (Apple Lossless) decoder for AirPlay's 16-bit mono/stereo audio frames.
 * Exists because this device's only ALAC [android.media.MediaCodec] component
 * (OMX.google.alac.decoder) fails `native_configure` unconditionally on this
 * firmware, even with a byte-for-byte correct magic cookie — see AudioDecoder's
 * comment history. Decodes the stream ourselves instead of depending on it.
 *
 * Implements adaptive Golomb-Rice residual decoding, sign-sign LMS FIR prediction and
 * mid/side un-mixing, per the published ALAC bitstream algorithm.
 */
internal class AlacDecoder(private val channels: Int, frameLength: Int) {

    private val frameLength = maxOf(1, frameLength)

    /** Decodes one ALAC frame into interleaved 16-bit PCM; empty array if the frame can't be used. */
    fun decode(data: ByteArray): ShortArray {
        val br = BitReader(data)
        val left = IntArray(frameLength)
        val right = IntArray(frameLength)

        return when (br.readBits(3)) {
            0, 3 -> { // SCE, LFE
                val numSamples = decodeChannelElement(br, left)
                if (numSamples <= 0) return ShortArray(0)
                val mono = ShortArray(numSamples * channels)
                for (i in 0 until numSamples) {
                    mono[i * channels] = left[i].toShort()
                    if (channels > 1) {
                        mono[i * channels + 1] = left[i].toShort()
                    }
                }
                mono
            }
            1 -> { // CPE
                val numSamples = decodeChannelPair(br, left, right)
                if (numSamples <= 0) return ShortArray(0)
                val stereo = ShortArray(numSamples * 2)
                for (i in 0 until numSamples) {
                    stereo[i * 2] = left[i].toShort()
                    stereo[i * 2 + 1] = right[i].toShort()
                }
                stereo
            }
            else -> ShortArray(0) // CCE/PCE/DSE/FIL/END as the first tag: nothing to play
        }
    }

    private fun decodeChannelPair(br: BitReader, outLeft: IntArray, outRight: IntArray): Int {
        br.skipBits(4) // element instance tag
        br.skipBits(12) // unused
        val partial = br.readBits(1) != 0
        val bytesShifted = br.readBits(2)
        val escape = br.readBits(1) != 0
        var numSamples = frameLength
        if (partial) {
            numSamples = (br.readBits(16) shl 16) or br.readBits(16)
        }
        if (bytesShifted == 3 || numSamples <= 0 || numSamples > frameLength) return 0

        if (escape) {
            for (i in 0 until numSamples) {
                outLeft[i] = signExtend(br.readBits(16), 16)
                outRight[i] = signExtend(br.readBits(16), 16)
            }
            return numSamples
        }
        if (bytesShifted != 0) {
            return 0 // shifted low bits: not used by AirPlay's 16-bit audio, bail safely
        }

        val mixBits = br.readBits(8)
        val mixRes = br.readBits(8).toByte().toInt()
        val pu = readChannelParams(br)
        val pv = readChannelParams(br)

        val chanBits = 17 // 16-bit stream + 1 bit headroom for the mid channel
        val resU = IntArray(numSamples)
        val resV = IntArray(numSamples)
        decodeResiduals(br, resU, numSamples, chanBits, PB0 * pu.pbFactor / 4)
        decodeResiduals(br, resV, numSamples, chanBits, PB0 * pv.pbFactor / 4)

        val decU = IntArray(numSamples)
        val decV = IntArray(numSamples)
        predictInto(resU, decU, numSamples, pu, chanBits)
        predictInto(resV, decV, numSamples, pv, chanBits)

        if (mixRes == 0) {
            System.arraycopy(decU, 0, outLeft, 0, numSamples)
            System.arraycopy(decV, 0, outRight, 0, numSamples)
        } else {
            for (i in 0 until numSamples) {
                val r = decU[i] - ((mixRes * decV[i]) shr mixBits)
                outRight[i] = r
                outLeft[i] = decV[i] + r
            }
        }
        return numSamples
    }

    private fun decodeChannelElement(br: BitReader, out: IntArray): Int {
        br.skipBits(4)
        br.skipBits(12)
        val partial = br.readBits(1) != 0
        val bytesShifted = br.readBits(2)
        val escape = br.readBits(1) != 0
        var numSamples = frameLength
        if (partial) {
            numSamples = (br.readBits(16) shl 16) or br.readBits(16)
        }
        if (bytesShifted == 3 || numSamples <= 0 || numSamples > frameLength) return 0
        if (escape) {
            for (i in 0 until numSamples) {
                out[i] = signExtend(br.readBits(16), 16)
            }
            return numSamples
        }
        if (bytesShifted != 0) return 0
        br.skipBits(8) // mixBits: meaningless for mono, still present in the bitstream
        br.skipBits(8) // mixRes
        val p = readChannelParams(br)
        val chanBits = 16
        val res = IntArray(numSamples)
        decodeResiduals(br, res, numSamples, chanBits, PB0 * p.pbFactor / 4)
        predictInto(res, out, numSamples, p, chanBits)
        return numSamples
    }

    private class ChannelParams {
        var mode = 0
        var denShift = 0
        var pbFactor = 0
        var numActive = 0
        val coefs = IntArray(32)
    }

    /** MSB-first bit reader over a byte array; reads past the end return 0 rather than throwing. */
    private class BitReader(private val data: ByteArray) {
        private var bitPos = 0

        fun readBits(n: Int): Int {
            var result = 0
            for (i in 0 until n) {
                result = (result shl 1) or readBit()
            }
            return result
        }

        fun peekBits(n: Int): Int {
            val save = bitPos
            val v = readBits(n)
            bitPos = save
            return v
        }

        fun skipBits(n: Int) {
            bitPos += n
        }

        private fun readBit(): Int {
            val byteIndex = bitPos ushr 3
            val bitIndex = 7 - (bitPos and 7)
            bitPos++
            if (byteIndex < 0 || byteIndex >= data.size) return 0
            return (data[byteIndex].toInt() ushr bitIndex) and 1
        }
    }

    private companion object {
        const val QBSHIFT = 9
        const val QB = 1 shl QBSHIFT
        const val PB0 = 40
        const val MB0 = 10
        const val KB0 = 14
        const val MAX_PREFIX = 9

        fun predictInto(res: IntArray, out: IntArray, numSamples: Int, p: ChannelParams, chanBits: Int) {
            if (p.mode == 0) {
                predict(res, out, numSamples, p.coefs, p.numActive, chanBits, p.denShift)
            } else {
                val tmp = IntArray(numSamples)
                predict(res, tmp, numSamples, null, 31, chanBits, 0)
                predict(tmp, out, numSamples, p.coefs, p.numActive, chanBits, p.denShift)
            }
        }

        fun readChannelParams(br: BitReader): ChannelParams {
            val p = ChannelParams()
            val b1 = br.readBits(8)
            p.mode = (b1 shr 4) and 0xF
            p.denShift = b1 and 0xF
            val b2 = br.readBits(8)
            p.pbFactor = (b2 shr 5) and 0x7
            p.numActive = b2 and 0x1F
            for (i in 0 until p.numActive) {
                p.coefs[i] = br.readBits(16).toShort().toInt()
            }
            return p
        }

        /** Adaptive Golomb-Rice residual decode, including the "zero run" fast path for silence. */
        fun decodeResiduals(br: BitReader, out: IntArray, numSamples: Int, chanBits: Int, mult: Int) {
            var history = MB0
            var zeroModifier = 0
            var i = 0
            while (i < numSamples) {
                val k = minOf(log2((history shr QBSHIFT) + 3), KB0)
                val x = readEscapedValue(br, k, chanBits)
                val v = x + zeroModifier
                zeroModifier = 0
                val magnitude = (v + 1) shr 1
                out[i++] = if ((v and 1) != 0) -magnitude else magnitude

                history = if (x > 0xFFFF) 0xFFFF else history + x * mult - ((history * mult) shr QBSHIFT)

                if (history < (QB shr 2) && i < numSamples) {
                    val kk = minOf(7 - log2(history) + ((history + 16) shr 6), KB0)
                    val blockSize = readEscapedValue(br, maxOf(kk, 1), 16)
                    if (blockSize > 0) {
                        val n = minOf(blockSize, numSamples - i)
                        for (j in 0 until n) {
                            out[i++] = 0
                        }
                    }
                    if (blockSize <= 0xFFFF) {
                        zeroModifier = 1
                    }
                    history = 0
                }
            }
        }

        /** Capped-unary prefix + truncated-binary remainder, with a raw escape past the cap. */
        fun readEscapedValue(br: BitReader, k: Int, escapeBits: Int): Int {
            var unary = 0
            while (unary < MAX_PREFIX && br.readBits(1) == 1) {
                unary++
            }
            if (unary >= MAX_PREFIX) return br.readBits(escapeBits)
            if (k <= 1) return unary
            val m = (1 shl k) - 1
            val extra = br.peekBits(k)
            if (extra > 1) {
                br.skipBits(k)
                return unary * m + extra - 1
            }
            br.skipBits(k - 1)
            return unary * m
        }

        /** Sign-sign LMS adaptive FIR predictor decode (or, for numActive==31, a fixed running sum). */
        fun predict(
            res: IntArray,
            out: IntArray,
            numSamples: Int,
            coefs: IntArray?,
            numActive: Int,
            chanBits: Int,
            denShift: Int,
        ) {
            if (numSamples <= 0) return
            out[0] = signExtend(res[0], chanBits)
            if (numActive == 0) {
                for (i in 1 until numSamples) {
                    out[i] = signExtend(res[i], chanBits)
                }
                return
            }
            if (numActive == 31) {
                for (i in 1 until numSamples) {
                    out[i] = signExtend(out[i - 1] + res[i], chanBits)
                }
                return
            }
            val c = coefs!!
            val denHalf = if (denShift > 0) 1 shl (denShift - 1) else 0
            val warm = minOf(numActive, numSamples - 1)
            for (i in 1..warm) {
                out[i] = signExtend(out[i - 1] + res[i], chanBits)
            }
            for (i in numActive + 1 until numSamples) {
                val d = out[i - numActive - 1]
                var sum = 0L
                for (j in 0 until numActive) {
                    sum += (out[i - numActive + j] - d).toLong() * c[j]
                }
                val predicted = ((sum + denHalf) shr denShift).toInt()
                val e = res[i]
                out[i] = signExtend(predicted + d + e, chanBits)

                if (e != 0) {
                    val s = if (e > 0) 1 else -1
                    var r = e
                    var j = 0
                    while (j < numActive && s * r > 0) {
                        val t = d - out[i - numActive + j]
                        val g = Integer.signum(t) * s
                        c[j] -= g
                        r -= ((t * g) shr denShift) * (j + 1)
                        j++
                    }
                }
            }
        }

        fun log2(x: Int): Int = if (x <= 0) 0 else 31 - Integer.numberOfLeadingZeros(x)

        fun signExtend(value: Int, bits: Int): Int {
            val shift = 32 - bits
            return (value shl shift) shr shift
        }
    }
}
