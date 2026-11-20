package com.github.serezhka.airplay.protocol.pairing

import com.github.serezhka.airplay.protocol.crypto.AirPlayKeyLabels
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.Utils
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.slf4j.LoggerFactory
import org.whispersystems.curve25519.Curve25519
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PairingHandshake(identity: PairingIdentity) {

    private val keyPair: KeyPair = identity.keyPair

    private var edTheirs: ByteArray? = null
    private var ecdhOurs: ByteArray? = null
    private var ecdhTheirs: ByteArray? = null

    /** Curve25519 shared secret from pair-verify, or null when the client skipped pairing. */
    var sharedSecret: ByteArray? = null
        private set

    var isPairVerified = false
        private set

    fun pairSetup(out: OutputStream) {
        out.write((keyPair.public as EdDSAPublicKey).abyte)
    }

    fun pairVerify(request: InputStream, response: OutputStream) {
        val flag = request.read()
        request.skip(3)
        if (flag > 0) {
            val theirs = ByteArray(32).also { request.read(it) }
            edTheirs = ByteArray(32).also { request.read(it) }
            ecdhTheirs = theirs

            val curve25519 = Curve25519.getInstance(Curve25519.BEST)
            val curveKeyPair = curve25519.generateKeyPair()

            val ours = curveKeyPair.publicKey
            ecdhOurs = ours
            val secret = curve25519.calculateAgreement(theirs, curveKeyPair.privateKey)
            sharedSecret = secret
            if (log.isDebugEnabled) {
                log.debug("Shared secret: {}", Utils.bytesToHex(secret))
            }

            val aesCtr128Encrypt = initCipher(secret)

            val dataToSign = ours + theirs

            val edDSAEngine = EdDSAEngine()
            edDSAEngine.initSign(keyPair.private)
            val signature = edDSAEngine.signOneShot(dataToSign)

            val encryptedSignature = aesCtr128Encrypt.doFinal(signature)

            response.write(ours + encryptedSignature)
        } else {
            val signature = ByteArray(64)
            request.read(signature)

            val aesCtr128Encrypt = initCipher(sharedSecret!!)

            // Skip the first 64 bytes of keystream (used for our own signature).
            aesCtr128Encrypt.update(ByteArray(64))
            val sigBuffer = aesCtr128Encrypt.doFinal(signature)

            val sigMessage = ecdhTheirs!! + ecdhOurs!!

            val edDSAEngine = EdDSAEngine()
            val edDSAPublicKey = EdDSAPublicKey(
                EdDSAPublicKeySpec(edTheirs, EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)),
            )
            edDSAEngine.initVerify(edDSAPublicKey)

            isPairVerified = edDSAEngine.verifyOneShot(sigMessage, sigBuffer)
            log.info("Pair verified: $isPairVerified")
        }
    }

    private fun initCipher(secret: ByteArray): Cipher {
        val sha512Digest = MessageDigest.getInstance("SHA-512")
        sha512Digest.update(AirPlayKeyLabels.PAIR_VERIFY_AES_KEY.toByteArray(Charsets.UTF_8))
        sha512Digest.update(secret)
        val aesKey = sha512Digest.digest().copyOfRange(0, 16)

        sha512Digest.update(AirPlayKeyLabels.PAIR_VERIFY_AES_IV.toByteArray(Charsets.UTF_8))
        sha512Digest.update(secret)
        val aesIv = sha512Digest.digest().copyOfRange(0, 16)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        return cipher
    }

    private companion object {
        private val log = LoggerFactory.getLogger(PairingHandshake::class.java)
    }
}
