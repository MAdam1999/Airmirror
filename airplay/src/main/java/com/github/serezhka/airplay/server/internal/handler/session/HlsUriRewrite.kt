package com.github.serezhka.airplay.server.internal.handler.session

import io.lindstrom.m3u8.parser.MultivariantPlaylistParser
import io.lindstrom.m3u8.parser.PlaylistParserException

object HlsUriRewrite {

    private val MLHLS_URI = Regex("mlhls://localhost[^\\s\"']+")
    private val MEDIA_URI = Regex("mlhls://localhost[^\\s\"']+/mediadata\\.m3u8")
    private val LINE_BREAK = Regex("\\r\\n|\\r|\\n")

    @JvmStatic
    fun toLocalUri(remoteUri: String, baseUrl: String, sessionId: String): String {
        if (remoteUri.startsWith("http://") || remoteUri.startsWith("https://")) return remoteUri
        var path = remoteUri.replace("mlhls://localhost", "")
        if (!path.startsWith("/")) path = "/$path"
        path = path.substringBefore('?')
        return "$baseUrl$path?session=$sessionId"
    }

    @JvmStatic
    fun rewritePlaylist(playlist: String, baseUrl: String, sessionId: String): String =
        MLHLS_URI.replace(playlist) { toLocalUri(it.value, baseUrl, sessionId) }

    /**
     * Keep H.264 (`avc1`) variants only. Some masters mix AVC + VP9/AV1; a single
     * HLS consumer cannot switch video codecs mid-playlist.
     *
     * Drops subtitles and every audio rendition except one default per `GROUP-ID`
     * referenced by the remaining variant (`DEFAULT=YES`, else the first). Keeps a
     * single AVC video variant (highest bandwidth). Extra renditions make some
     * demuxers probe every playlist before the first frame on a full master.
     */
    @JvmStatic
    fun preferAvcVariants(masterPlaylist: String): String {
        val lines = masterPlaylist.split(LINE_BREAK)
        val headers = ArrayList<String>()
        val audioMedia = ArrayList<String>()
        val variants = ArrayList<String>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-STREAM-INF:") -> {
                    val uri = if (i + 1 < lines.size) lines[++i] else ""
                    if (isAvcStreamInfo(line)) {
                        variants.add(line)
                        variants.add(uri)
                    }
                }
                line.startsWith("#EXT-X-MEDIA:") -> {
                    if (extractAttr(line, "TYPE").orEmpty().equals("AUDIO", ignoreCase = true)) {
                        audioMedia.add(line)
                    }
                    // drop SUBTITLES / CLOSED-CAPTIONS / other
                }
                line.isNotEmpty() || headers.isEmpty() -> headers.add(line)
            }
            i++
        }

        if (variants.isEmpty()) return masterPlaylist

        var best = 0
        var bestBandwidth = -1L
        for (v in variants.indices step 2) {
            val bandwidth = extractAttr(variants[v], "BANDWIDTH")?.toLongOrNull() ?: 0L
            if (bandwidth >= bestBandwidth) {
                bestBandwidth = bandwidth
                best = v
            }
        }
        val chosenInfo = variants[best]
        val chosenUri = variants[best + 1]
        val audioGroups = LinkedHashSet<String>()
        extractAttr(chosenInfo, "AUDIO")?.let { audioGroups.add(it) }

        val out = StringBuilder()
        for (header in headers) {
            if (header.isEmpty() && out.isEmpty()) continue
            out.append(header).append('\n')
        }
        for (media in defaultAudioOnly(audioMedia, audioGroups)) {
            out.append(media).append('\n')
        }
        out.append(chosenInfo).append('\n')
        out.append(chosenUri).append('\n')
        return out.toString()
    }

    /** One audio rendition per referenced group: `DEFAULT=YES`, otherwise the first. */
    private fun defaultAudioOnly(audioMedia: List<String>, audioGroups: Set<String>): List<String> {
        val chosen = LinkedHashMap<String, String>()
        val chosenIsDefault = LinkedHashMap<String, Boolean>()
        for (media in audioMedia) {
            val group = extractAttr(media, "GROUP-ID").orEmpty()
            if (audioGroups.isNotEmpty() && group !in audioGroups) continue
            val isDefault = extractAttr(media, "DEFAULT").orEmpty().equals("YES", ignoreCase = true)
            if (!chosen.containsKey(group) || (isDefault && chosenIsDefault[group] != true)) {
                chosen[group] = media
                chosenIsDefault[group] = isDefault
            }
        }
        return ArrayList(chosen.values)
    }

    private fun isAvcStreamInfo(streamInf: String): Boolean {
        val codecs = extractAttr(streamInf, "CODECS").orEmpty().lowercase()
        if (codecs.isBlank()) return true
        val avc = codecs.contains("avc1") || codecs.contains("avc3")
        val vp9 = codecs.contains("vp09") || codecs.contains("vp9")
        val av1 = codecs.contains("av01") || codecs.contains("av1.")
        return avc && !vp9 && !av1
    }

    private fun extractAttr(line: String, name: String): String? {
        val m = Regex("$name=(?:\"([^\"]*)\"|([^,]*))", RegexOption.IGNORE_CASE).find(line) ?: return null
        val v = m.groups[1]?.value ?: m.groups[2]?.value
        return v?.trim()?.takeIf { it.isNotEmpty() }
    }

    @JvmStatic
    fun extractMediaUris(masterPlaylist: String): List<String> = try {
        extractMediaUrisParsed(masterPlaylist)
    } catch (parserError: PlaylistParserException) {
        extractMediaUrisLenient(masterPlaylist).ifEmpty { throw parserError }
    }

    private fun extractMediaUrisParsed(masterPlaylist: String): List<String> {
        val playlist = MultivariantPlaylistParser().readPlaylist(masterPlaylist)
        val uris = LinkedHashSet<String>()
        for (variant in playlist.variants()) addUri(uris, variant.uri())
        for (rendition in playlist.alternativeRenditions()) {
            rendition.uri().ifPresent { addUri(uris, it) }
        }
        return ArrayList(uris)
    }

    private fun extractMediaUrisLenient(masterPlaylist: String): List<String> {
        val uris = LinkedHashSet<String>()
        MEDIA_URI.findAll(masterPlaylist).forEach { addUri(uris, it.value) }
        return ArrayList(uris)
    }

    private fun addUri(uris: MutableSet<String>, uri: String?) {
        if (uri.isNullOrBlank()) return
        uris.add(uri.substringBefore('?'))
    }
}
