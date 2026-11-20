package com.github.serezhka.airplay.server.internal.handler.control

import io.lindstrom.m3u8.model.MediaPlaylist
import io.lindstrom.m3u8.model.MediaSegment
import org.slf4j.LoggerFactory
import java.util.regex.Pattern

/** Expand `YT-EXT-CONDENSED-URL` media playlists into full segment URIs. */
internal object HlsCondensedUrls {

    private val log = LoggerFactory.getLogger(HlsCondensedUrls::class.java)

    private val ATTR = Regex("([A-Z0-9\\-]+)=(?:\"([^\"]+)\"|([^,]+))")

    /** Java `String.split("/")` semantics (trailing empty parts dropped). */
    private val SLASH = Pattern.compile("/")

    private fun paramValues(uri: String, prefix: String): Array<String> = SLASH.split(uri.replaceFirst(prefix, ""))

    fun expand(mediaPlaylist: MediaPlaylist): MediaPlaylist {
        val condensedUrl = LinkedHashMap<String, String>()
        mediaPlaylist.comments()
            .filter { it.startsWith("YT-EXT-CONDENSED-URL:") }
            .map { it.replace("YT-EXT-CONDENSED-URL:", "") }
            .forEach { attributes ->
                ATTR.findAll(attributes).forEach { m ->
                    condensedUrl[m.groupValues[1]] = m.groups[2]?.value ?: m.groups[3]!!.value
                }
            }
        if (condensedUrl.isEmpty()) return mediaPlaylist

        val prefix = condensedUrl["PREFIX"]
        val params = condensedUrl["PARAMS"]
        val baseUri = condensedUrl["BASE-URI"]
        if (prefix == null || params == null || baseUri == null) return mediaPlaylist

        val paramNames = params.split(",").dropLastWhile { it.isEmpty() }
        val matching = mediaPlaylist.mediaSegments().all { paramValues(it.uri(), prefix).size == paramNames.size }
        if (!matching) {
            log.warn("Condensed URL param count mismatch, leaving playlist unchanged")
            return mediaPlaylist
        }
        return MediaPlaylist.builder()
            .from(mediaPlaylist)
            .mediaSegments(
                mediaPlaylist.mediaSegments().map { segment ->
                    val values = paramValues(segment.uri(), prefix)
                    val paramResult = StringBuilder()
                    for (i in paramNames.indices) {
                        paramResult.append("/").append(paramNames[i]).append("/").append(values[i])
                    }
                    MediaSegment.builder().from(segment).uri(baseUri + paramResult).build()
                },
            )
            .build()
    }
}
