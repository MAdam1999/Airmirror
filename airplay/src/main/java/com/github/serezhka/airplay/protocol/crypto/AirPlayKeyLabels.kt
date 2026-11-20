package com.github.serezhka.airplay.protocol.crypto

/**
 * Well-known HKDF / SHA-512 labels used across AirPlay control and media crypto.
 *
 * Sourced from public reverse-engineering notes of the wire protocol (not Apple docs).
 */
object AirPlayKeyLabels {

    /** FairPlay AES-CTR video: SHA-512 prefix before `streamConnectionID`. */
    const val STREAM_KEY = "AirPlayStreamKey"
    const val STREAM_IV = "AirPlayStreamIV"

    /** Legacy pair-verify AES wrap (pre-HKP). */
    const val PAIR_VERIFY_AES_KEY = "Pair-Verify-AES-Key"
    const val PAIR_VERIFY_AES_IV = "Pair-Verify-AES-IV"

    /** HKP pair-setup ChaCha session (SRP shared → encrypt M5/M6 inner TLVs). */
    const val PAIR_SETUP_ENCRYPT_SALT = "Pair-Setup-Encrypt-Salt"
    const val PAIR_SETUP_ENCRYPT_INFO = "Pair-Setup-Encrypt-Info"
    const val PAIR_SETUP_CONTROLLER_SIGN_SALT = "Pair-Setup-Controller-Sign-Salt"
    const val PAIR_SETUP_CONTROLLER_SIGN_INFO = "Pair-Setup-Controller-Sign-Info"
    const val PAIR_SETUP_ACCESSORY_SIGN_SALT = "Pair-Setup-Accessory-Sign-Salt"
    const val PAIR_SETUP_ACCESSORY_SIGN_INFO = "Pair-Setup-Accessory-Sign-Info"

    /** HKP pair-verify ChaCha session (X25519 shared → encrypt M2/M3 inner TLVs). */
    const val PAIR_VERIFY_ENCRYPT_SALT = "Pair-Verify-Encrypt-Salt"
    const val PAIR_VERIFY_ENCRYPT_INFO = "Pair-Verify-Encrypt-Info"

    /** Control-channel ChaCha after pair-verify (or after transient M4). */
    const val CONTROL_SALT = "Control-Salt"
    const val CONTROL_WRITE_KEY = "Control-Write-Encryption-Key"
    const val CONTROL_READ_KEY = "Control-Read-Encryption-Key"

    /** Mirror video payload ChaCha after pair-verify (header is AAD). */
    const val DATA_STREAM_SALT_PREFIX = "DataStream-Salt"
    const val DATA_STREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"

    /** ChaCha nonces: ASCII at offset 4 (12-byte nonce). */
    const val PS_MSG_05 = "PS-Msg05"
    const val PS_MSG_06 = "PS-Msg06"
    const val PV_MSG_02 = "PV-Msg02"
    const val PV_MSG_03 = "PV-Msg03"

    /** SRP identity string for HKP pair-setup. */
    const val PAIR_SETUP_SRP_USERNAME = "Pair-Setup"

    /** Default PIN when the receiver allows transient access without an on-screen code. */
    const val TRANSIENT_DEFAULT_PIN = "3939"

    /** `DataStream-Salt` + unsigned decimal `streamConnectionID`. */
    @JvmStatic
    fun dataStreamSalt(streamConnectionId: Long): String =
        DATA_STREAM_SALT_PREFIX + java.lang.Long.toUnsignedString(streamConnectionId)
}
