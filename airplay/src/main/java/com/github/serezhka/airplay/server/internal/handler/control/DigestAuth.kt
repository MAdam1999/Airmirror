package com.github.serezhka.airplay.server.internal.handler.control

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * AirPlay receiver password (PIN) check: RFC 2617 HTTP Digest over RTSP, as iOS uses it when a
 * receiver requires a password. The receiver answers the stream SETUP with
 * `401` + `WWW-Authenticate: Digest realm="raop", nonce="…"`; the iPhone asks the user for the
 * code and retries with an `Authorization: Digest …` header whose `response` is
 * `MD5(MD5(user:realm:password):nonce:MD5(method:uri))` (or the qop variant).
 */
internal object DigestAuth {

    const val REALM = "raop"

    private val random = SecureRandom()

    /** 32 lowercase hex chars, fresh for every challenge. */
    fun newNonce(): String = hex(ByteArray(16).also { random.nextBytes(it) })

    fun challenge(nonce: String): String = "Digest realm=\"$REALM\", nonce=\"$nonce\""

    /** Parses `Digest a="b", c=d, …` into a map (keys lowercased). Null if not a Digest header. */
    fun parse(header: String?): Map<String, String>? {
        if (header == null) return null
        val trimmed = header.trim()
        if (!trimmed.startsWith("Digest", ignoreCase = true)) return null
        val params = HashMap<String, String>()
        PARAM.findAll(trimmed.substring("Digest".length)).forEach { m ->
            params[m.groupValues[1].lowercase()] = m.groups[2]?.value ?: m.groups[3]?.value.orEmpty()
        }
        return params
    }

    /**
     * True when the Authorization header proves knowledge of [password] for the
     * [expectedNonce] this connection issued. [method] is the request's method (e.g. SETUP).
     */
    fun verify(authorization: String?, method: String, password: String, expectedNonce: String?): Boolean {
        val p = parse(authorization) ?: return false
        val username = p["username"] ?: return false
        val realm = p["realm"] ?: return false
        val nonce = p["nonce"] ?: return false
        val uri = p["uri"] ?: return false
        val response = p["response"] ?: return false
        if (expectedNonce == null || !nonce.equals(expectedNonce, ignoreCase = true)) return false

        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")
        val qop = p["qop"]
        val expected = if (qop != null) {
            md5("$ha1:$nonce:${p["nc"].orEmpty()}:${p["cnonce"].orEmpty()}:$qop:$ha2")
        } else {
            md5("$ha1:$nonce:$ha2")
        }
        // Constant-time compare; senders differ in hex case.
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.US_ASCII),
            response.lowercase().toByteArray(Charsets.US_ASCII),
        )
    }

    fun md5(s: String): String = hex(MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)))

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    private val PARAM = Regex("([A-Za-z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|([^,\\s]*))")
}
