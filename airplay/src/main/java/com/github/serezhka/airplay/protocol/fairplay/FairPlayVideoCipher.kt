package com.github.serezhka.airplay.protocol.fairplay

import com.github.serezhka.airplay.protocol.crypto.AirPlayKeyLabels
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * FairPlay AES-CTR over mirror video payloads (sender and receiver share this schedule).
 *
 * Master material: empty `sharedSecret` → `aesKey` is the stream master;
 * otherwise `SHA-512(aesKey ‖ sharedSecret)[:16]`. Then
 * [AirPlayKeyLabels.STREAM_KEY]/[AirPlayKeyLabels.STREAM_IV] + unsigned
 * `streamConnectionID`. Residual keystream spans packets (`og` buffer).
 *
 * CTR is symmetric — [crypt] is used for both encrypt and decrypt.
 * Payloads are expected to be at least as long as any pending residual (senders
 * pack that way).
 */
class FairPlayVideoCipher(aesKey: ByteArray, sharedSecret: ByteArray?, streamConnectionID: String) {

    private val aesCtr: Cipher = Cipher.getInstance("AES/CTR/NoPadding")
    private val og = ByteArray(16)
    private var nextCount = 0

    init {
        val key = ByteArray(16)
        val iv = ByteArray(16)
        deriveStreamKeyIv(aesKey, sharedSecret ?: ByteArray(0), streamConnectionID, key, iv)
        // ENCRYPT_MODE == DECRYPT_MODE for CTR; either works for crypt().
        aesCtr.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    /** In-place AES-CTR with cross-packet residual (FairPlay video). */
    fun crypt(video: ByteArray) {
        for (i in 0 until nextCount) {
            video[i] = (video[i].toInt() xor og[(16 - nextCount) + i].toInt()).toByte()
        }

        val encryptLen = ((video.size - nextCount) / 16) * 16
        aesCtr.update(video, nextCount, encryptLen, video, nextCount)

        val restLen = (video.size - nextCount) % 16
        val restStart = video.size - restLen
        nextCount = 0
        if (restLen > 0) {
            og.fill(0)
            System.arraycopy(video, restStart, og, 0, restLen)
            aesCtr.update(og, 0, 16, og, 0)
            System.arraycopy(og, 0, video, restStart, restLen)
            nextCount = 16 - restLen
        }
    }

    companion object {
        /** Stream key/IV for FairPlay AES-CTR (and for continuous CTR with empty shared secret). */
        @JvmStatic
        fun deriveStreamKeyIv(
            aesKey: ByteArray,
            sharedSecret: ByteArray?,
            streamConnectionID: String,
            keyOut16: ByteArray,
            ivOut16: ByteArray,
        ) {
            val eaesKeyMaterial: ByteArray = if (sharedSecret == null || sharedSecret.isEmpty()) {
                aesKey
            } else {
                val d = MessageDigest.getInstance("SHA-512")
                d.update(aesKey)
                d.update(sharedSecret)
                d.digest()
            }

            val sha512Digest = MessageDigest.getInstance("SHA-512")
            val skey = (AirPlayKeyLabels.STREAM_KEY + streamConnectionID).toByteArray(Charsets.UTF_8)
            sha512Digest.update(skey)
            sha512Digest.update(eaesKeyMaterial, 0, 16)
            System.arraycopy(sha512Digest.digest(), 0, keyOut16, 0, 16)

            val siv = (AirPlayKeyLabels.STREAM_IV + streamConnectionID).toByteArray(Charsets.UTF_8)
            sha512Digest.update(siv)
            sha512Digest.update(eaesKeyMaterial, 0, 16)
            System.arraycopy(sha512Digest.digest(), 0, ivOut16, 0, 16)
        }
    }
}
