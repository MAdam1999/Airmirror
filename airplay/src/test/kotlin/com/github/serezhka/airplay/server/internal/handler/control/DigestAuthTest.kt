package com.github.serezhka.airplay.server.internal.handler.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DigestAuthTest {

    // RFC 2617 section 3.5 example.
    private val rfcHeader = "Digest username=\"Mufasa\", realm=\"testrealm@host.com\", " +
        "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\", uri=\"/dir/index.html\", qop=auth, nc=00000001, " +
        "cnonce=\"0a4f113b\", response=\"6629fae49393a05397450978507c4ef1\", opaque=\"5ccc069c403ebaf9f0171e9517f40e41\""

    @Test
    fun acceptsRfc2617Example() {
        assertTrue(DigestAuth.verify(rfcHeader, "GET", "Circle Of Life", "dcd98b7102dd2f0e8b11d0f600bfb0c093"))
    }

    @Test
    fun acceptsUppercaseHexResponse() {
        val upper = rfcHeader.replace("6629fae49393a05397450978507c4ef1", "6629FAE49393A05397450978507C4EF1")
        assertTrue(DigestAuth.verify(upper, "GET", "Circle Of Life", "dcd98b7102dd2f0e8b11d0f600bfb0c093"))
    }

    @Test
    fun rejectsWrongPassword() {
        assertFalse(DigestAuth.verify(rfcHeader, "GET", "1234", "dcd98b7102dd2f0e8b11d0f600bfb0c093"))
    }

    @Test
    fun rejectsStaleOrMissingNonce() {
        assertFalse(DigestAuth.verify(rfcHeader, "GET", "Circle Of Life", "00000000000000000000000000000000"))
        assertFalse(DigestAuth.verify(rfcHeader, "GET", "Circle Of Life", null))
    }

    @Test
    fun rejectsGarbage() {
        assertFalse(DigestAuth.verify(null, "SETUP", "1234", "abc"))
        assertFalse(DigestAuth.verify("Basic QWxhZGRpbjpvcGVu", "SETUP", "1234", "abc"))
        assertFalse(DigestAuth.verify("Digest username=\"x\"", "SETUP", "1234", "abc"))
    }

    /** The no-qop form iOS sends for AirPlay: response = MD5(HA1:nonce:HA2). */
    @Test
    fun acceptsAirPlayStyleHeader() {
        val nonce = DigestAuth.newNonce()
        val uri = "rtsp://192.168.1.50/1234567890"
        val ha1 = DigestAuth.md5("AirPlay:raop:4821")
        val ha2 = DigestAuth.md5("SETUP:$uri")
        val response = DigestAuth.md5("$ha1:$nonce:$ha2")
        val header = "Digest username=\"AirPlay\", realm=\"raop\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
        assertTrue(DigestAuth.verify(header, "SETUP", "4821", nonce))
        assertFalse(DigestAuth.verify(header, "SETUP", "4822", nonce))
        assertEquals(32, nonce.length)
        assertTrue(DigestAuth.challenge(nonce).startsWith("Digest realm=\"raop\", nonce=\""))
    }
}
