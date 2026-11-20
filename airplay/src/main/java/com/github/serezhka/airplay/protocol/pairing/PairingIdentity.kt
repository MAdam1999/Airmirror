package com.github.serezhka.airplay.protocol.pairing

import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.KeyPairGenerator
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.KeyPair
import java.security.SecureRandom

/**
 * Receiver Ed25519 identity for one AirPlay process (legacy FairPlay pair-setup / pair-verify).
 * The public key is what mDNS advertises as `pk` and what `/pair-setup` returns.
 *
 * Not the HKP controller identity saved by the cast client (`pairing_id` + seed) —
 * that lives in the client credential store.
 */
class PairingIdentity private constructor(internal val keyPair: KeyPair) {

    fun publicKey(): ByteArray = (keyPair.public as EdDSAPublicKey).abyte

    fun publicKeyHex(): String = publicKey().joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    companion object {
        @JvmStatic
        fun generate(): PairingIdentity = PairingIdentity(KeyPairGenerator().generateKeyPair())

        /**
         * Rebuilds the exact same identity (same keypair, same public key) from a 32-byte seed
         * saved earlier with [newSeed] — so the receiver presents a stable identity across
         * restarts instead of a fresh random one every time, which breaks a phone's existing pairing.
         */
        @JvmStatic
        fun fromSeed(seed: ByteArray): PairingIdentity {
            val params = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519)
            val privateSpec = EdDSAPrivateKeySpec(seed, params)
            val privateKey = EdDSAPrivateKey(privateSpec)
            val publicKey = EdDSAPublicKey(EdDSAPublicKeySpec(privateSpec.a, params))
            return PairingIdentity(KeyPair(publicKey, privateKey))
        }

        /** A fresh random 32-byte seed for [fromSeed], to be saved by the caller. */
        @JvmStatic
        fun newSeed(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
    }
}
