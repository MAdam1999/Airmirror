package com.github.serezhka.airplay.server

import java.time.Instant
import java.util.Locale

class ControlExchange(
    val timestamp: Instant?,
    val sessionId: String?,
    val protocol: String?,
    val method: String?,
    val uri: String?,
    requestHeaders: Map<String, String>?,
    requestBody: ByteArray?,
    val responseStatus: Int,
    responseHeaders: Map<String, String>?,
    responseBody: ByteArray?,
) {
    val requestHeaders: Map<String, String> = copyHeaders(requestHeaders)
    val responseHeaders: Map<String, String> = copyHeaders(responseHeaders)
    val requestBody: ByteArray = requestBody ?: ByteArray(0)
    val responseBody: ByteArray = responseBody ?: ByteArray(0)

    fun requestContentType(): String? = header(requestHeaders, "Content-Type")

    fun header(headers: Map<String, String>?, name: String?): String? {
        if (headers == null || name == null) return null
        val expected = name.lowercase(Locale.ROOT)
        return headers.entries.firstOrNull { it.key.lowercase(Locale.ROOT) == expected }?.value
    }

    private companion object {
        fun copyHeaders(headers: Map<String, String>?): Map<String, String> =
            if (headers == null) emptyMap() else LinkedHashMap(headers)
    }
}
