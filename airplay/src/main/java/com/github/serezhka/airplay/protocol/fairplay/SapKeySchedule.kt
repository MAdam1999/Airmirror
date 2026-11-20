package com.github.serezhka.airplay.protocol.fairplay

import com.github.serezhka.airplay.protocol.fairplay.SapTables.defaultSap
import com.github.serezhka.airplay.protocol.fairplay.SapTables.indexMangle
import com.github.serezhka.airplay.protocol.fairplay.SapTables.initialSessionKey
import com.github.serezhka.airplay.protocol.fairplay.SapTables.messageIv
import com.github.serezhka.airplay.protocol.fairplay.SapTables.messageKey
import com.github.serezhka.airplay.protocol.fairplay.SapTables.staticSource1
import com.github.serezhka.airplay.protocol.fairplay.SapTables.staticSource2
import com.github.serezhka.airplay.protocol.fairplay.SapTables.tKey
import com.github.serezhka.airplay.protocol.fairplay.SapTables.tableS1
import com.github.serezhka.airplay.protocol.fairplay.SapTables.tableS10
import com.github.serezhka.airplay.protocol.fairplay.SapTables.tableS2
import com.github.serezhka.airplay.protocol.fairplay.SapTables.tableS3
import com.github.serezhka.airplay.protocol.fairplay.SapTables.tableS4
import com.github.serezhka.airplay.protocol.fairplay.SapTables.tableS5
import com.github.serezhka.airplay.protocol.fairplay.SapTables.tableS6
import com.github.serezhka.airplay.protocol.fairplay.SapTables.tableS7
import com.github.serezhka.airplay.protocol.fairplay.SapTables.tableS8
import com.github.serezhka.airplay.protocol.fairplay.SapTables.tableS9
import com.github.serezhka.airplay.protocol.fairplay.SapTables.xKey
import com.github.serezhka.airplay.protocol.fairplay.SapTables.zKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SapKeySchedule {

    private val modifiedMD5 = FairPlayDigest()
    private val sapHash = SapHash()

    fun decryptAesKey(message3: ByteArray, cipherText: ByteArray, keyOut: ByteArray) {
        val chunk1 = cipherText.copyOfRange(16, cipherText.size)
        val chunk2 = cipherText.copyOfRange(56, cipherText.size)
        val blockIn = ByteArray(16)
        val sapKey = ByteArray(16)
        val keySchedule = Array(11) { IntArray(4) }
        generateSessionKey(defaultSap, message3, sapKey)
        generateKeySchedule(sapKey, keySchedule)
        zXor(chunk2, blockIn, 1)
        cycle(blockIn, keySchedule)
        for (i in 0 until 16) {
            keyOut[i] = (blockIn[i].toInt() xor chunk1[i].toInt()).toByte()
        }
        xXor(keyOut, keyOut, 1)
        zXor(keyOut, keyOut, 1)
    }

    fun decryptMessage(messageIn: ByteArray, decryptedMessage: ByteArray) {
        val buffer = ByteArray(16)
        val mode = messageIn[12].toInt() // 0,1,2,3
        val key = messageKey[mode]

        // Look up `buffer[src]` in the message table for position `base + dst`, then xor the key byte.
        fun step(base: Int, dst: Int, srcByte: Byte): Byte =
            (messageTable(base + dst, srcByte.toInt() and 0xFF).toInt() xor key[base + dst].toInt()).toByte()

        // For M0-M6 we follow the same pattern
        for (i in 0 until 8) {
            // First, copy in the nth block (we must start with the last one)
            for (j in 0 until 16) {
                if (mode == 3) {
                    buffer[j] = messageIn[(0x80 - 0x10 * i) + j]
                } else if (mode == 2 || mode == 1 || mode == 0) {
                    buffer[j] = messageIn[(0x10 * (i + 1)) + j]
                }
            }
            // do this permutation and update 9 times. Could this be cycle(), or the reverse of cycle()?
            for (j in 0 until 9) {
                val base = 0x80 - 0x10 * j

                buffer[0x0] = step(base, 0x0, buffer[0x0])
                buffer[0x4] = step(base, 0x4, buffer[0x4])
                buffer[0x8] = step(base, 0x8, buffer[0x8])
                buffer[0xc] = step(base, 0xc, buffer[0xc])

                var tmp = buffer[0x0d]
                buffer[0xd] = step(base, 0xd, buffer[0x9])
                buffer[0x9] = step(base, 0x9, buffer[0x5])
                buffer[0x5] = step(base, 0x5, buffer[0x1])
                buffer[0x1] = step(base, 0x1, tmp)

                tmp = buffer[0x02]
                buffer[0x2] = step(base, 0x2, buffer[0xa])
                buffer[0xa] = step(base, 0xa, tmp)
                tmp = buffer[0x06]
                buffer[0x6] = step(base, 0x6, buffer[0xe])
                buffer[0xe] = step(base, 0xe, tmp)

                tmp = buffer[0x3]
                buffer[0x3] = step(base, 0x3, buffer[0x7])
                buffer[0x7] = step(base, 0x7, buffer[0xb])
                buffer[0xb] = step(base, 0xb, buffer[0xf])
                buffer[0xf] = step(base, 0xf, tmp)

                // Now we must replace the entire buffer with 4 words that we read and xor together
                val block = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
                fun s9(b0: Int, b1: Int, b2: Int, b3: Int): Int =
                    tableS9[0x000 + (buffer[b0].toInt() and 0xFF)] xor
                        tableS9[0x100 + (buffer[b1].toInt() and 0xFF)] xor
                        tableS9[0x200 + (buffer[b2].toInt() and 0xFF)] xor
                        tableS9[0x300 + (buffer[b3].toInt() and 0xFF)]
                // Each word is computed before it is written, and later words read later bytes,
                // matching the original sequential putInt() calls.
                block.putInt(s9(0x0, 0x1, 0x2, 0x3))
                block.putInt(s9(0x4, 0x5, 0x6, 0x7))
                block.putInt(s9(0x8, 0x9, 0xa, 0xb))
                block.putInt(s9(0xc, 0xd, 0xe, 0xf))
            }
            // Next, another permute with a different table
            fun s10(pos: Int, srcByte: Byte): Byte = tableS10[(pos shl 8) + (srcByte.toInt() and 0xFF)]
            buffer[0x0] = s10(0x0, buffer[0x0])
            buffer[0x4] = s10(0x4, buffer[0x4])
            buffer[0x8] = s10(0x8, buffer[0x8])
            buffer[0xc] = s10(0xc, buffer[0xc])

            var tmp = buffer[0x0d]
            buffer[0xd] = s10(0xd, buffer[0x9])
            buffer[0x9] = s10(0x9, buffer[0x5])
            buffer[0x5] = s10(0x5, buffer[0x1])
            buffer[0x1] = s10(0x1, tmp)

            tmp = buffer[0x02]
            buffer[0x2] = s10(0x2, buffer[0xa])
            buffer[0xa] = s10(0xa, tmp)
            tmp = buffer[0x06]
            buffer[0x6] = s10(0x6, buffer[0xe])
            buffer[0xe] = s10(0xe, tmp)

            tmp = buffer[0x3]
            buffer[0x3] = s10(0x3, buffer[0x7])
            buffer[0x7] = s10(0x7, buffer[0xb])
            buffer[0xb] = s10(0xb, buffer[0xf])
            buffer[0xf] = s10(0xf, tmp)

            // And finally xor with the previous block of the message, except in mode-2 where we do this in reverse
            val xorResult = ByteArray(16)
            if (mode == 2 || mode == 1 || mode == 0) {
                if (i > 0) {
                    // remember that the first 0x10 bytes are the header
                    xorBlocks(buffer, messageIn.copyOfRange(0x10 * i, 0x10 * i + 16), xorResult)
                } else {
                    xorBlocks(buffer, messageIv[mode], xorResult)
                }
                System.arraycopy(xorResult, 0, decryptedMessage, 0x10 * i, 16)
            } else {
                if (i < 7) {
                    xorBlocks(buffer, messageIn.copyOfRange(0x70 - 0x10 * i, (0x70 - 0x10 * i) + 16), xorResult)
                } else {
                    xorBlocks(buffer, messageIv[mode], xorResult)
                }
                System.arraycopy(xorResult, 0, decryptedMessage, 0x70 - 0x10 * i, 16)
            }
        }
    }

    fun generateKeySchedule(keyMaterial: ByteArray, keySchedule: Array<IntArray>) {
        val keyData = IntArray(4)
        for (i in 0 until 11) {
            keySchedule[i].fill(0xdeadbeef.toInt())
        }
        val buffer = ByteArray(16)
        var ti = 0
        // G
        tXor(keyMaterial, buffer)

        val wrap = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 4) {
            keyData[i] = wrap.getInt()
        }

        for (round in 0 until 11) {
            // H
            keySchedule[round][0] = keyData[0]
            // I
            val t1 = tableOffset(ti)
            val t2 = tableOffset(ti + 1)
            val t3 = tableOffset(ti + 2)
            val t4 = tableOffset(ti + 3)
            ti += 4

            buffer[0] = (buffer[0].toInt() xor
                (tableS1[t1 + (buffer[0x0d].toInt() and 0xFF)].toInt() xor indexMangle[round].toInt())).toByte()
            buffer[1] = (buffer[1].toInt() xor tableS1[t2 + (buffer[0x0e].toInt() and 0xFF)].toInt()).toByte()
            buffer[2] = (buffer[2].toInt() xor tableS1[t3 + (buffer[0x0f].toInt() and 0xFF)].toInt()).toByte()
            buffer[3] = (buffer[3].toInt() xor tableS1[t4 + (buffer[0x0c].toInt() and 0xFF)].toInt()).toByte()

            keyData[0] = wrap.getInt(0)

            // H
            keySchedule[round][1] = keyData[1]
            // J
            keyData[1] = keyData[1] xor keyData[0]
            wrap.putInt(4, keyData[1])
            // H
            keySchedule[round][2] = keyData[2]
            // J
            keyData[2] = keyData[2] xor keyData[1]
            wrap.putInt(8, keyData[2])
            // K and L
            keySchedule[round][3] = keyData[3]
            // J again
            keyData[3] = keyData[3] xor keyData[2]
            wrap.putInt(12, keyData[3])
        }
    }

    fun generateSessionKey(oldSap: ByteArray, messageIn: ByteArray, sessionKey: ByteArray) {
        val decryptedMessage = ByteArray(128)
        val newSap = ByteArray(320)
        val md5 = ByteArray(16)

        decryptMessage(messageIn, decryptedMessage)

        System.arraycopy(staticSource1, 0, newSap, 0, 0x11)
        System.arraycopy(decryptedMessage, 0, newSap, 0x11, 0x80)
        System.arraycopy(oldSap, 0x80, newSap, 0x091, 0x80)
        System.arraycopy(staticSource2, 0, newSap, 0x111, 0x2f)
        System.arraycopy(initialSessionKey, 0, sessionKey, 0, 16)

        for (round in 0 until 5) {
            val base = newSap.copyOfRange(round * 64, newSap.size)
            modifiedMD5.modifiedMd5(base, sessionKey, md5)
            sapHash.sapHash(base, sessionKey)
            val md5Wrap = ByteBuffer.wrap(md5).order(ByteOrder.LITTLE_ENDIAN)
            val sessionKeyWrap = ByteBuffer.wrap(sessionKey).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 4) {
                sessionKeyWrap.putInt(i * 4, sessionKeyWrap.getInt(i * 4) + md5Wrap.getInt(i * 4))
            }
        }

        for (i in 0 until 16 step 4) {
            var tmp = sessionKey[i]
            sessionKey[i] = sessionKey[i + 3]
            sessionKey[i + 3] = tmp
            tmp = sessionKey[i + 1]
            sessionKey[i + 1] = sessionKey[i + 2]
            sessionKey[i + 2] = tmp
        }

        // Finally the whole thing is XORd with 121:
        for (i in 0 until 16) {
            sessionKey[i] = (sessionKey[i].toInt() xor 121).toByte()
        }
    }

    fun cycle(block: ByteArray, keySchedule: Array<IntArray>) {
        val bWords = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        for (w in 0 until 4) {
            bWords.putInt(w * 4, bWords.getInt(w * 4) xor keySchedule[10][w])
        }
        // First, these are permuted
        permuteBlock1(block)

        for (round in 0 until 9) {
            // E
            // Note that table_s5 is a table of 4-byte words. Therefore we do not need to <<2 these indices
            val key = ByteArray(16)
            val wrap = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 4) {
                wrap.putInt(keySchedule[9 - round][i])
            }

            fun k(i: Int): Int = (block[i].toInt() and 0xff) xor (key[i].toInt() and 0xff)

            // A B, then C
            bWords.putInt(0, tableS5[k(3)] xor tableS6[k(2)] xor tableS8[k(0)] xor tableS7[k(1)])
            // A B again
            bWords.putInt(4, tableS6[k(6)] xor tableS5[k(7)] xor tableS8[k(4)] xor tableS7[k(5)])
            // D
            bWords.putInt(8, tableS5[k(11)] xor tableS6[k(10)] xor tableS7[k(9)] xor tableS8[k(8)])
            bWords.putInt(12, tableS5[k(15)] xor tableS6[k(14)] xor tableS7[k(13)] xor tableS8[k(12)])

            // In the last round, instead of the permute, we do F
            permuteBlock2(block, 8 - round)
        }

        for (w in 0 until 4) {
            bWords.putInt(w * 4, bWords.getInt(w * 4) xor keySchedule[0][w])
        }
    }

    private fun xorBlocks(a: ByteArray, b: ByteArray, out: ByteArray) {
        for (i in 0 until 16) {
            out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        }
    }

    private fun zXor(input: ByteArray, out: ByteArray, blocks: Int) {
        for (j in 0 until blocks) {
            for (i in 0 until 16) {
                out[j * 16 + i] = (input[j * 16 + i].toInt() xor zKey[i].toInt()).toByte()
            }
        }
    }

    private fun xXor(input: ByteArray, out: ByteArray, blocks: Int) {
        for (j in 0 until blocks) {
            for (i in 0 until 16) {
                out[j * 16 + i] = (input[j * 16 + i].toInt() xor xKey[i].toInt()).toByte()
            }
        }
    }

    private fun tXor(input: ByteArray, out: ByteArray) {
        for (i in 0 until 16) {
            out[i] = (input[i].toInt() xor tKey[i].toInt()).toByte()
        }
    }

    /** Start offset of sub-table `i` in table_s1. */
    private fun tableOffset(i: Int): Int = ((31 * i) % 0x28) shl 8

    /** Entry `idx` of message sub-table `i` in table_s2. */
    private fun messageTable(i: Int, idx: Int): Byte = tableS2[((97 * i % 144) shl 8) + idx]

    private fun permuteBlock1(block: ByteArray) {
        fun s3(offset: Int, b: Byte): Byte = tableS3[offset + (b.toInt() and 0xff)]
        block[0] = s3(0x000, block[0])
        block[4] = s3(0x400, block[4])
        block[8] = s3(0x800, block[8])
        block[12] = s3(0xc00, block[12])

        var tmp = block[13]
        block[13] = s3(0x100, block[9])
        block[9] = s3(0xd00, block[5])
        block[5] = s3(0x900, block[1])
        block[1] = s3(0x500, tmp)

        tmp = block[2]
        block[2] = s3(0xa00, block[10])
        block[10] = s3(0x200, tmp)
        tmp = block[6]
        block[6] = s3(0xe00, block[14])
        block[14] = s3(0x600, tmp)

        tmp = block[3]
        block[3] = s3(0xf00, block[7])
        block[7] = s3(0x300, block[11])
        block[11] = s3(0x700, block[15])
        block[15] = s3(0xb00, tmp)
    }

    private fun permuteBlock2(block: ByteArray, round: Int) {
        fun p(pos: Int, b: Byte): Byte =
            tableS4[(((71 * (round * 16 + pos)) % 144) shl 8) + (b.toInt() and 0xff)]
        block[0] = p(0, block[0])
        block[4] = p(4, block[4])
        block[8] = p(8, block[8])
        block[12] = p(12, block[12])

        var tmp = block[13]
        block[13] = p(13, block[9])
        block[9] = p(9, block[5])
        block[5] = p(5, block[1])
        block[1] = p(1, tmp)

        tmp = block[2]
        block[2] = p(2, block[10])
        block[10] = p(10, tmp)
        tmp = block[6]
        block[6] = p(6, block[14])
        block[14] = p(14, tmp)

        tmp = block[3]
        block[3] = p(3, block[7])
        block[7] = p(7, block[11])
        block[11] = p(11, block[15])
        block[15] = p(15, tmp)
    }
}
