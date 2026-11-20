package com.github.serezhka.airplay.protocol.fairplay

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class FairPlayAudioDecryptor(aesKey: ByteArray, private val aesIV: ByteArray, sharedSecret: ByteArray?) {

    private val eaesKey: ByteArray = if (sharedSecret == null || sharedSecret.isEmpty()) {
        aesKey.copyOf(16)
    } else {
        val sha512Digest = MessageDigest.getInstance("SHA-512")
        sha512Digest.update(aesKey)
        sha512Digest.update(sharedSecret)
        sha512Digest.digest().copyOfRange(0, 16)
    }

    private val aesCbcDecrypt: Cipher = Cipher.getInstance("AES/CBC/NoPadding")

    fun decrypt(audio: ByteArray, audioLength: Int) {
        initAesCbcCipher()
        aesCbcDecrypt.update(audio, 0, audioLength / 16 * 16, audio, 0)
    }

    private fun initAesCbcCipher() {
        aesCbcDecrypt.init(Cipher.DECRYPT_MODE, SecretKeySpec(eaesKey, "AES"), IvParameterSpec(aesIV))
    }
}
