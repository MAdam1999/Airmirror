package com.github.serezhka.airplay.server.internal.handler.session

import io.lindstrom.m3u8.parser.MediaPlaylistParser
import io.lindstrom.m3u8.parser.ParsingMode
import io.lindstrom.m3u8.parser.PlaylistParserException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * @param clientItemUuid item `uuid` from `POST /play`; reverse `playing`
 *                       must carry this value or the sender keeps the pause UI after a scrub
 */
class HlsPlaylistState @JvmOverloads constructor(
    val remoteMasterUri: String,
    val playlistUriLocal: String,
    clientItemUuid: String? = null,
) {

    /** Per-item uuid for reverse video events (`params.uuid` / typed `uuid`). */
    val itemUuid: String =
        if (!clientItemUuid.isNullOrBlank()) clientItemUuid else UUID.randomUUID().toString().uppercase()

    /** Integer `sessionID` field inside reverse `/event` plists for this item. */
    val reverseEventSessionId: Int = NEXT_REVERSE_SESSION_ID.getAndAdd(2)

    val pendingMediaUris: MutableList<String> = ArrayList()
    /** STREAM-INF / video itag mediadata URIs (prefetch these before audio-language alts). */
    val videoMediaUris: MutableList<String> = ArrayList()
    val playlists: MutableMap<String, String> = LinkedHashMap()
    /** Hashes of last-seen mediadata bodies; survive [invalidatePlaylists] for EOS compare. */
    val mediaBodyHashes: MutableMap<String, Int> = HashMap()
    /** Last FCUP refresh nanoTime per remote playlist URI (rate-limit live re-fetch). */
    val lastFcupRefreshNanos: MutableMap<String, Long> = HashMap()

    var nextMediaUriIndex = 0
        private set
    var fcupRequestId = 1
        private set
    var isMasterReceived = false
        private set
    var isPlaybackStarted = false
        private set
    var lastMasterBody: String? = null
        private set
    /** Unfiltered FCUP master — AVC rewrite can hide ad→content changes. */
    var lastRawMasterBody: String? = null
        private set

    @Volatile
    private var waitingForMasterChange = false

    @Volatile
    var isPostEosMediaRefreshing = false
        private set

    @Volatile
    private var postEosMediaChanged = false

    /** After EOS, at most one full mediadata FCUP sweep until the next resweep interval. */
    @Volatile
    var isPostEosMediaSweepDone = false
        private set

    /** Master polls while waiting for post-EOS change; drives periodic mediadata resweep. */
    var postEosMasterPollCount = 0
        private set

    /** True once any mediadata lacked `#EXT-X-ENDLIST` (sliding live / event window). */
    @Volatile
    var isLivePlaylist = false
        private set

    var pendingSeekSeconds: Double? = null

    /** Best-effort VOD duration from media playlist `#EXTINF` sums. */
    @Volatile
    var mediaDurationSeconds = 0.0
        private set

    /** AirPlay playback rate: `0` paused, `1` playing. */
    @Volatile
    var playbackRate = 1.0
        set(value) {
            field = if (value <= 0) 0.0 else 1.0
        }

    /**
     * `AVPlayerActionAtItemEnd`: 0=advance, 1=pause, 2=none.
     * VOD senders typically set pause (1) — on EOS we must pause and wait for the next `/play`.
     */
    @Volatile
    var actionAtItemEnd = 1

    /** `/scrub` until the seek-picture callback or the closing `rate=1`. */
    @Volatile
    var isScrubGesture = false
        private set

    /**
     * `rate=0` not yet classified. Scrub often sends `rate=0` then `/scrub`
     * up to ~2s later; pause must wait that long or we freeze the timeline before the seek.
     */
    private val rateZeroWindow = HlsRateZeroWindow()

    /** True from pipeline start until the first displayed frame is announced. */
    private val presentationPending = AtomicBoolean()

    /** Pipeline is up; do not tell the sender `playing` until a frame is visible. */
    fun awaitPresentation() {
        presentationPending.set(true)
    }

    /** @return true the first time a visible frame (or the fallback) may be announced */
    fun claimPresentation(): Boolean = presentationPending.compareAndSet(true, false)

    /** Sender must stay on `loading` until [claimPresentation] succeeds. */
    val isAwaitingPresentation: Boolean
        get() = presentationPending.get()

    fun beginScrubGesture() {
        isScrubGesture = true
    }

    fun endScrubGesture() {
        isScrubGesture = false
    }

    fun armRateZero() = rateZeroWindow.arm()

    /** True while a bare `rate=0` is held open for a possible following `/scrub`. */
    val isRateZeroPending: Boolean
        get() = rateZeroWindow.isPending

    fun clearRateZero() = rateZeroWindow.clear()

    /**
     * @return true when this poll may commit a pause: at least two playback-info probes
     *         **and** [RATE_ZERO_PAUSE_DELAY_NS] since arm (scrub window).
     */
    fun notePlaybackInfoForPause(): Boolean = rateZeroWindow.notePlaybackInfoForPause()

    /** Test helper: pretend `elapsedNs` already passed since arm. */
    internal fun advanceRateZeroArmForTest(elapsedNs: Long) = rateZeroWindow.advanceForTest(elapsedNs)

    fun takePendingSeekSeconds(): Double? {
        val seek = pendingSeekSeconds
        pendingSeekSeconds = null
        return seek
    }

    fun nextFcupRequestId(): Int = fcupRequestId++

    fun putPlaylist(remoteUri: String, body: String) {
        val key = normalizeUri(remoteUri)
        playlists[key] = body
        if (remoteUri.contains("mediadata.m3u8")) {
            val endList = body.contains("#EXT-X-ENDLIST")
            if (!endList) {
                isLivePlaylist = true
                mediaDurationSeconds = 0.0
            } else if (!isLivePlaylist && !waitingForMasterChange) {
                // Only finite VOD may raise duration. Live windows must not.
                // Freeze duration while waiting for playlistRemove — post-EOS FCUP can
                // deliver a longer next-item body and poison /playback-info (7s ad → 15.6).
                val duration = sumMediaDurationSeconds(body)
                if (duration > 0 && duration > mediaDurationSeconds) {
                    mediaDurationSeconds = duration
                }
            }
            val hash = body.hashCode()
            val prev = mediaBodyHashes.put(key, hash)
            if (isPostEosMediaRefreshing && prev != null && prev != hash) {
                postEosMediaChanged = true
            }
        }
    }

    /** @return true if a FCUP refresh for this URI should be sent now (rate-limited). */
    fun shouldRefreshPlaylist(remoteUri: String, minIntervalNanos: Long): Boolean {
        val key = normalizeUri(remoteUri)
        val now = System.nanoTime()
        val prev = lastFcupRefreshNanos[key]
        if (prev != null && now - prev < minIntervalNanos) return false
        lastFcupRefreshNanos[key] = now
        return true
    }

    fun invalidatePlaylists() {
        playlists.clear()
        lastFcupRefreshNanos.clear()
        mediaDurationSeconds = 0.0
    }

    fun updateMasterPlaylist(rewrittenMaster: String) {
        putPlaylist(remoteMasterUri, rewrittenMaster)
    }

    fun recordMasterIfChanged(rewrittenMaster: String): Boolean {
        if (lastMasterBody != null && rewrittenMaster == lastMasterBody) return false
        lastMasterBody = rewrittenMaster
        updateMasterPlaylist(rewrittenMaster)
        return true
    }

    /** Track raw (pre-AVC-filter) master; returns true when bytes differ from last EOS cycle. */
    fun recordRawMasterIfChanged(rawMaster: String?): Boolean {
        if (rawMaster == null) return false
        if (lastRawMasterBody != null && rawMaster == lastRawMasterBody) return false
        lastRawMasterBody = rawMaster
        return true
    }

    /**
     * After ad EOS the sender often keeps the same master URI list and only updates mediadata.
     * Re-queue media FCUP so we can detect that and restart.
     */
    fun beginPostEosMediaRefresh(remoteMediaUris: List<String>?) {
        isPostEosMediaRefreshing = true
        postEosMediaChanged = false
        pendingMediaUris.clear()
        remoteMediaUris?.forEach { pendingMediaUris.add(normalizeUri(it)) }
        nextMediaUriIndex = 0
    }

    /** @return true if any mediadata body changed during the refresh */
    fun finishPostEosMediaRefresh(): Boolean {
        isPostEosMediaRefreshing = false
        val changed = postEosMediaChanged
        postEosMediaChanged = false
        return changed
    }

    fun cancelPostEosMediaRefresh() {
        isPostEosMediaRefreshing = false
        postEosMediaChanged = false
    }

    fun resetPostEosMediaSweep() {
        isPostEosMediaSweepDone = false
        postEosMasterPollCount = 0
    }

    fun markPostEosMediaSweepDone() {
        isPostEosMediaSweepDone = true
    }

    /**
     * Count a master-only poll while waiting after EOS. Every `everyN` polls (inclusive),
     * allow another mediadata FCUP sweep — senders often keep the same master and only update
     * VOD mediadata on ad→content.
     *
     * @param everyN must be >= 1; values <= 0 disable periodic resweep
     */
    fun noteMasterPollAndShouldResweepMedia(everyN: Int): Boolean {
        if (everyN <= 0) return false
        postEosMasterPollCount++
        if (postEosMasterPollCount % everyN != 0) return false
        isPostEosMediaSweepDone = false
        return true
    }

    var isWaitingForMasterChange: Boolean
        get() = waitingForMasterChange
        set(value) {
            waitingForMasterChange = value
            if (!value) {
                cancelPostEosMediaRefresh()
                isPostEosMediaSweepDone = false
                postEosMasterPollCount = 0
            }
        }

    fun getPlaylist(remoteUri: String): String? = playlists[normalizeUri(remoteUri)]

    fun storeMasterPlaylist(rewrittenMaster: String, remoteMediaUris: List<String>?) {
        // rewrittenMaster uses local http URLs for the media consumer; remoteMediaUris stay
        // mlhls://… so FCUP prefetch talks to the AirPlay client, not loopback.
        if (remoteMediaUris.isNullOrEmpty()) {
            throw PlaylistParserException("No media playlists found in master playlist")
        }
        isMasterReceived = true
        lastMasterBody = rewrittenMaster
        putPlaylist(remoteMasterUri, rewrittenMaster)
        pendingMediaUris.clear()
        videoMediaUris.clear()
        val audioOrOther = ArrayList<String>()
        for (uri in remoteMediaUris) {
            val normalized = normalizeUri(uri)
            if (HlsMediaUriClassifier.isLikelyVideo(normalized)) {
                videoMediaUris.add(normalized)
                pendingMediaUris.add(normalized)
            } else {
                audioOrOther.add(normalized)
            }
        }
        // Prefetch video before dozens of audio-language alternatives.
        pendingMediaUris.addAll(audioOrOther)
        nextMediaUriIndex = 0
    }

    /** True when at least one video mediadata body is cached (safe to start the player). */
    fun hasCachedVideoMedia(): Boolean =
        videoMediaUris.any { playlists.containsKey(it) } ||
            playlists.keys.any { HlsMediaUriClassifier.isLikelyVideo(it) }

    fun videoMediaUriCount(): Int = videoMediaUris.size

    fun hasMoreMediaUris(): Boolean = nextMediaUriIndex < pendingMediaUris.size

    /** Stop FCUP media prefetch (e.g. on EOS) so late responses don't starve master refresh. */
    fun abortMediaPrefetch() {
        nextMediaUriIndex = pendingMediaUris.size
    }

    fun nextMediaUri(): String? = if (!hasMoreMediaUris()) null else pendingMediaUris[nextMediaUriIndex++]

    fun allPlaylistsFetched(): Boolean =
        isMasterReceived && pendingMediaUris.isNotEmpty() && nextMediaUriIndex >= pendingMediaUris.size

    fun markPlaybackStarted() {
        isPlaybackStarted = true
    }

    fun pendingMediaUriCount(): Int = pendingMediaUris.size

    fun fetchedMediaPlaylistCount(): Int = maxOf(0, nextMediaUriIndex)

    override fun toString(): String =
        "HlsPlaylistState{master=$isMasterReceived, mediaUris=${pendingMediaUris.size}, " +
            "fetched=${fetchedMediaPlaylistCount()}, playlists=${playlists.size}, localUri=$playlistUriLocal}"

    companion object {
        /** Odd integers 1,3,5,… — reverse-event `sessionID` (distinct from play UUID). */
        private val NEXT_REVERSE_SESSION_ID = AtomicInteger(1)

        /**
         * Minimum hold before a bare `rate=0` becomes a real pause (scrub gesture gap).
         * @see HlsRateZeroWindow.PAUSE_DELAY_NS
         */
        internal const val RATE_ZERO_PAUSE_DELAY_NS = HlsRateZeroWindow.PAUSE_DELAY_NS

        @JvmStatic
        internal fun sumMediaDurationSeconds(mediaPlaylistBody: String?): Double {
            if (mediaPlaylistBody.isNullOrBlank()) return 0.0
            try {
                val playlist = MediaPlaylistParser(ParsingMode.LENIENT).readPlaylist(mediaPlaylistBody)
                val sum = playlist.mediaSegments().sumOf { it.duration() }
                if (sum > 0) return sum
            } catch (ignored: Exception) {
                // use #EXTINF line scan below
            }
            var sum = 0.0
            for (line in mediaPlaylistBody.split("\n")) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXTINF:")) {
                    val value = trimmed.substring("#EXTINF:".length).split(",", limit = 2)[0].trim()
                    value.toDoubleOrNull()?.let { sum += it }
                }
            }
            return sum
        }

        @JvmStatic
        internal fun isLikelyVideoMediaUri(uri: String?): Boolean = HlsMediaUriClassifier.isLikelyVideo(uri)

        @JvmStatic
        internal fun extractMediaUris(masterPlaylist: String): List<String> = HlsUriRewrite.extractMediaUris(masterPlaylist)

        private fun normalizeUri(uri: String): String = uri.substringBefore('?')
    }
}
