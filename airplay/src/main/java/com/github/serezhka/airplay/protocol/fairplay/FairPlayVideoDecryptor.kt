package com.github.serezhka.airplay.protocol.fairplay

/**
 * Receiver-side FairPlay AES-CTR decrypt for mirror video.
 * Delegates to [FairPlayVideoCipher] so sender and receiver share one schedule.
 */
class FairPlayVideoDecryptor(aesKey: ByteArray, sharedSecret: ByteArray?, streamConnectionID: String) {

    private val cipher = FairPlayVideoCipher(aesKey, sharedSecret, streamConnectionID)

    fun decrypt(video: ByteArray) {
        cipher.crypt(video)
    }
}
