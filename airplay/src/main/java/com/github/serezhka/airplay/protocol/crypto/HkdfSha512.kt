package com.github.serezhka.airplay.protocol.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA512 as used by HKP pair-setup / pair-verify and DataStream keys.
 */
object HkdfSha512 {

    @JvmStatic
    fun derive(ikm: ByteArray, salt: String, info: String, length: Int): ByteArray =
        derive(ikm, salt.toByteArray(Charsets.UTF_8), info.toByteArray(Charsets.UTF_8), length)

    @JvmStatic
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(salt, "HmacSHA512"))
        val prk = mac.doFinal(ikm)

        mac.init(SecretKeySpec(prk, "HmacSHA512"))
        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1
        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter)
            t = mac.doFinal()
            val copy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copy)
            offset += copy
            counter++
        }
        return result
    }
}
