package com.github.serezhka.airplay.protocol.fairplay

import java.nio.ByteBuffer
import java.nio.ByteOrder

class SapHash {

    private val handGarble = FairPlayScrambler()

    private fun rol8(input: Byte, count: Int): Byte {
        val v = input.toInt()
        return (((v shl count) and 0xff) or ((v and 0xff) shr (8 - count))).toByte()
    }

    /** Unsigned 32-bit modulo, as the original C code does, so negative indices wrap correctly. */
    private fun umod(value: Int, m: Int): Int = ((value.toLong() and 0xffffffffL) % m).toInt()

    fun sapHash(blockIn: ByteArray, keyOut: ByteArray) {
        val buffer0 = byteArrayOf(-106, 95, -58, 83, -8, 70, -52, 24, -33, -66, -78, -8, 56, -41, -20, 34, 3, -47, 32, -113)
        val buffer1 = ByteArray(210)
        val buffer2 = byteArrayOf(67, 84, 98, 122, 24, -61, -42, -77, -102, 86, -10, 28, 20, 63, 12, 29, 59, 54, -125, -79, 57, 81, 74, -86, 9, 62, -2, 68, -81, -34, -61, 32, -99, 66, 58)
        val buffer3 = ByteArray(132)
        val buffer4 = byteArrayOf(-19, 37, -47, -69, -68, 39, -97, 2, -94, -87, 17, 0, 12, -77, 82, -64, -67, -29, 27, 73, -57)
        val i0Index = intArrayOf(18, 22, 23, 0, 5, 19, 32, 31, 10, 21, 30)

        val blockWords = ByteBuffer.wrap(blockIn).order(ByteOrder.LITTLE_ENDIAN)

        // Load the input into the buffer
        for (i in 0 until 210) {
            // We need to swap the byte order around so it is the right endianness
            val inWord = blockWords.getInt(((i % 64) shr 2) * 4)
            buffer1[i] = ((inWord shr ((3 - (i % 4)) shl 3)) and 0xff).toByte()
        }

        // Next a scrambling
        for (i in 0 until 840) {
            // We have to do unsigned, 32-bit modulo, or we get the wrong indices
            val x = buffer1[umod(i - 155, 210)]
            val y = buffer1[umod(i - 57, 210)]
            val z = buffer1[umod(i - 13, 210)]
            val w = buffer1[umod(i, 210)]
            buffer1[i % 210] =
                ((rol8(y, 5) + (rol8(z, 3).toInt() xor w.toInt()) - rol8(x, 7)) and 0xff).toByte()
        }

        // I have no idea what this is doing (yet), but it gives the right output
        handGarble.garble(buffer0, buffer1, buffer2, buffer3, buffer4)

        // Fill the output with 0xE1
        for (i in 0 until 16) {
            keyOut[i] = 0xE1.toByte()
        }

        // Now we use all the buffers we have calculated to grind out the output. First buffer3
        for (i in 0 until 11) {
            // Note that this is addition (mod 255) and not XOR
            // Also note that we only use certain indices
            // And that index 3 is hard-coded to be 0x3d
            if (i == 3) {
                keyOut[i] = 0x3d
            } else {
                keyOut[i] = ((keyOut[i] + buffer3[i0Index[i] * 4]) and 0xff).toByte()
            }
        }

        // Then buffer0
        for (i in 0 until 20) xorInto(keyOut, i % 16, buffer0[i])
        // Then buffer2
        for (i in 0 until 35) xorInto(keyOut, i % 16, buffer2[i])
        // Do buffer1
        for (i in 0 until 210) xorInto(keyOut, i % 16, buffer1[i])

        // Now we do a kind of reverse-scramble
        for (j in 0 until 16) {
            for (i in 0 until 16) {
                val x = keyOut[umod(i - 7, 16)]
                val y = keyOut[i % 16]
                val z = keyOut[umod(i - 37, 16)]
                val w = keyOut[umod(i - 177, 16)]
                keyOut[i] = (rol8(x, 1).toInt() xor y.toInt() xor rol8(z, 6).toInt() xor rol8(w, 5).toInt()).toByte()
            }
        }
    }

    private fun xorInto(arr: ByteArray, idx: Int, value: Byte) {
        arr[idx] = (arr[idx].toInt() xor value.toInt()).toByte()
    }
}
