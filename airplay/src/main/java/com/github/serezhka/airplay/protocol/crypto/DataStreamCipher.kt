package com.github.serezhka.airplay.protocol.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ChaCha20-Poly1305 for mirror video payloads after HKP pair-verify.
 * Salt [AirPlayKeyLabels.dataStreamSalt], info [AirPlayKeyLabels.DATA_STREAM_OUTPUT_KEY].
 * The 128-byte packet header is AAD.
 */
class DataStreamCipher(sharedSecret: ByteArray, streamConnectionId: Long) {

    private val key: ByteArray = HkdfSha512.derive(
        sharedSecret,
        AirPlayKeyLabels.dataStreamSalt(streamConnectionId).toByteArray(Charsets.UTF_8),
        AirPlayKeyLabels.DATA_STREAM_OUTPUT_KEY.toByteArray(Charsets.UTF_8),
        32,
    )
    private val nonce = AtomicLong()

    /** Encrypt [plain]; [header] is associated data (not ciphertext). */
    fun seal(plain: ByteArray, header: ByteArray): ByteArray {
        val n = nonce.getAndIncrement()
        return chacha(Cipher.ENCRYPT_MODE, plain, header, counterNonce(n))
    }

    /** Decrypt with an explicit nonce counter (tests / reopen). */
    fun open(sealed: ByteArray, header: ByteArray, counter: Long): ByteArray =
        chacha(Cipher.DECRYPT_MODE, sealed, header, counterNonce(counter))

    private fun chacha(mode: Int, input: ByteArray, aad: ByteArray, nonce12: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce12))
        cipher.updateAAD(aad)
        return cipher.doFinal(input)
    }

    companion object {
        /** 12-byte nonce: 4 zero bytes + little-endian counter. */
        @JvmStatic
        fun counterNonce(counter: Long): ByteArray {
            val n = ByteArray(12)
            ByteBuffer.wrap(n, 4, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(counter)
            return n
        }
    }
}
