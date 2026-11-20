package com.github.serezhka.airplay.server.internal.handler.session

/**
 * Classifies mediadata playlist URIs as video vs audio-alternative.
 * Audio alternatives often use itag 233/234 and/or `/xtags/`; STREAM-INF video
 * playlists use other itags without xtags.
 */
object HlsMediaUriClassifier {

    @JvmStatic
    fun isLikelyVideo(uri: String?): Boolean {
        if (uri == null || !uri.contains("mediadata")) return false
        if (uri.contains("/xtags/")) return false
        if (uri.contains("/itag/233/") || uri.contains("/itag/234/")) return false
        return true
    }
}
