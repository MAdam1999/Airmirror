package com.github.serezhka.airplay.protocol.media

/**
 * Sums `#EXTINF` durations from a VOD media playlist that ends with `#EXT-X-ENDLIST`.
 * Sliding-window live playlists (no ENDLIST) return `0` so callers do not inflate ad length.
 */
object EndListDuration {

    /** @return total EXTINF seconds, or `0` when content is null, not ENDLIST, or has no EXTINF */
    @JvmStatic
    fun sumSeconds(content: String?): Double {
        if (content == null || !content.contains("#EXT-X-ENDLIST")) return 0.0
        var sum = 0.0
        for (line in content.split("\n")) {
            if (!line.startsWith("#EXTINF:")) continue
            val value = line.substring("#EXTINF:".length).split(",", limit = 2)[0].trim()
            // skip malformed tags
            value.toDoubleOrNull()?.let { sum += it }
        }
        return sum
    }
}
