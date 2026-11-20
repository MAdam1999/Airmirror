package com.github.serezhka.airplay.protocol

import com.github.serezhka.airplay.protocol.fairplay.FairPlayAudioDecryptor
import com.github.serezhka.airplay.protocol.fairplay.FairPlayHandshake
import com.github.serezhka.airplay.protocol.fairplay.FairPlayVideoDecryptor
import com.github.serezhka.airplay.protocol.media.MediaStreamInfo
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo
import com.github.serezhka.airplay.protocol.pairing.PairingHandshake
import com.github.serezhka.airplay.protocol.pairing.PairingIdentity
import com.github.serezhka.airplay.protocol.rtsp.RtspMediaSetup
import java.io.InputStream
import java.io.OutputStream

/**
 * Crypto and RTSP media-setup state for one AirPlay connection.
 */
class FairPlaySession(identity: PairingIdentity = PairingIdentity.generate()) {

    val pairing = PairingHandshake(identity)
    val fairPlay = FairPlayHandshake()
    val rtsp = RtspMediaSetup()

    private var fairPlayVideoDecryptor: FairPlayVideoDecryptor? = null
    private var fairPlayAudioDecryptor: FairPlayAudioDecryptor? = null

    /** `/pair-setup`: writes EdDSA public key bytes to the output stream. */
    fun pairSetup(out: OutputStream) {
        pairing.pairSetup(out)
    }

    /**
     * `/pair-verify`: on the first request writes the curve25519 public key + encrypted
     * signature bytes; on the second request verifies the signature.
     */
    fun pairVerify(input: InputStream, out: OutputStream) {
        pairing.pairVerify(input, out)
    }

    /** Pair was verified successfully. */
    val isPairVerified: Boolean
        get() = pairing.isPairVerified

    /** `/fp-setup`: writes the fp-setup response bytes to the output stream. */
    fun fairPlaySetup(input: InputStream, out: OutputStream) {
        fairPlay.fairPlaySetup(input, out)
    }

    /** `RTSP SETUP`: sets the encrypted EAS key and IV or retrieves media stream info. */
    fun rtspSetup(input: InputStream): MediaStreamInfo? {
        val mediaStreamInfo = rtsp.setup(input)
        // A new type-110 stream comes with a new streamConnectionID (e.g. next HLS item).
        // Reusing the previous AES-CTR decryptor yields garbage NALs for the new stream.
        if (mediaStreamInfo is VideoStreamInfo) {
            fairPlayVideoDecryptor = null
        }
        return mediaStreamInfo
    }

    /** `RTSP TEARDOWN`: retrieves media stream info. */
    fun rtspTeardown(input: InputStream): MediaStreamInfo? {
        val mediaStreamInfo = rtsp.teardown(input)
        if (mediaStreamInfo is VideoStreamInfo) {
            fairPlayVideoDecryptor = null
        }
        return mediaStreamInfo
    }

    val fairPlayAesKey: ByteArray
        get() = fairPlay.decryptAesKey(rtsp.ekey!!)

    /**
     * Video decrypt needs FairPlay `ekey` + streamConnectionID.
     * Shared secret is optional: absent when the client skips pairing (features bit 27 off).
     */
    val isFairPlayVideoDecryptorReady: Boolean
        get() = rtsp.ekey != null && rtsp.streamConnectionID != null

    /** Audio decrypt needs FairPlay `ekey` + `eiv`. Shared secret is optional (same as video). */
    val isFairPlayAudioDecryptorReady: Boolean
        get() = rtsp.ekey != null && rtsp.eiv != null

    fun decryptVideo(video: ByteArray) {
        val decryptor = fairPlayVideoDecryptor ?: run {
            check(isFairPlayVideoDecryptorReady) { "FairPlayVideoDecryptor not ready!" }
            val secret = pairing.sharedSecret ?: ByteArray(0)
            FairPlayVideoDecryptor(fairPlayAesKey, secret, rtsp.streamConnectionID!!)
                .also { fairPlayVideoDecryptor = it }
        }
        decryptor.decrypt(video)
    }

    fun decryptAudio(audio: ByteArray, audioLength: Int) {
        val decryptor = fairPlayAudioDecryptor ?: run {
            check(isFairPlayAudioDecryptorReady) { "FairPlayAudioDecryptor not ready!" }
            val secret = pairing.sharedSecret ?: ByteArray(0)
            FairPlayAudioDecryptor(fairPlayAesKey, rtsp.eiv!!, secret)
                .also { fairPlayAudioDecryptor = it }
        }
        decryptor.decrypt(audio, audioLength)
    }
}
