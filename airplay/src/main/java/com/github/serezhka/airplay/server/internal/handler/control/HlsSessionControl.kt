package com.github.serezhka.airplay.server.internal.handler.control

import com.github.serezhka.airplay.server.Playback
import com.github.serezhka.airplay.server.internal.handler.session.HlsPlaylistState
import com.github.serezhka.airplay.server.internal.handler.session.HlsUriRewrite
import com.github.serezhka.airplay.server.internal.handler.session.Session
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns HLS session start/stop and the mirror→HLS handoff.
 * HTTP handlers stay in [ControlHandler]; FCUP/events stay in [HlsFcupService].
 */
internal class HlsSessionControl(
    private val sessionManager: SessionManager,
    private val hlsFcupService: HlsFcupService,
    private val airPlayConsumer: Playback,
) {

    private val hlsScheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "airplay-hls-scheduler").apply { isDaemon = true }
    }
    private val pendingPlaybackStarts = ConcurrentHashMap<String, ScheduledFuture<*>>()

    fun stopHlsPlayback(session: Session) {
        cancelPlaybackStartFallback(session.id)
        hlsFcupService.cancelAllMasterPolls()
        hlsFcupService.clearReverseQueue(session)
        session.hlsPlaylistState = null
        airPlayConsumer.onPlaylistRemoved()
    }

    /**
     * Start (or restart) media/HLS playback from a Content-Location URI.
     * Used by POST /play and by playlistInsert (e.g. quality switch).
     */
    fun startMediaPlaylist(
        session: Session,
        playlistUri: String,
        playlistBaseUrl: String,
        clientProcName: String?,
        startPositionSeconds: Double?,
        clientItemUuid: String?,
    ) {
        // Do not tear mirror here. Some senders issue a follow-up /play while leaving the app;
        // stopping VideoServer on that /play drops mirror video when FCUP never reaches mediadata.
        // Tear mirror only when HLS actually starts the player.
        val playlistUriLocal = HlsUriRewrite.toLocalUri(playlistUri, playlistBaseUrl, session.id)
        val remotePlaylistUri = playlistUri.substringBefore('?')

        if (remotePlaylistUri.contains("master.m3u8")) {
            hlsFcupService.cancelAllMasterPolls()
            val hls = HlsPlaylistState(remotePlaylistUri, playlistUriLocal, clientItemUuid)
            hls.playbackRate = 1.0
            session.hlsPlaylistState = hls
            log.info("SESSION mode=HLS session={} itemUuid={} client={}", session.id, hls.itemUuid, clientProcName)
            if (startPositionSeconds != null && startPositionSeconds > 0) {
                hls.pendingSeekSeconds = startPositionSeconds
            }
            log.debug("HLS FCUP prefetch localUri={} client={}", playlistUriLocal, clientProcName)
            // Signal loading while FCUP + first video playlist are still in flight.
            hlsFcupService.sendPlaybackStateEvent(session, "loading")
            hlsFcupService.sendFcupRequest(session, remotePlaylistUri)
        } else {
            stopMirrorVideoIfRunning(session)
            airPlayConsumer.onPlaylist(playlistUriLocal)
            if (startPositionSeconds != null && startPositionSeconds > 0) {
                airPlayConsumer.onSeek(startPositionSeconds)
            }
        }
    }

    fun stopMirrorVideoIfRunning(session: Session) {
        var anyVideo = false
        for (other in sessionManager.allSessions()) {
            try {
                if (other.videoServer.isRunning) {
                    anyVideo = true
                    other.videoServer.stop()
                }
            } catch (e: Exception) {
                log.debug("Mirror video stop before HLS ignored: {}", e.toString())
            }
        }
        if (anyVideo) {
            log.debug("Stopping screen-mirror video before HLS session={}", session.id)
        }
        try {
            airPlayConsumer.onVideoSrcDisconnect()
        } catch (e: Exception) {
            log.debug("Mirror video source disconnect ignored: {}", e.toString())
        }
    }

    fun startHlsPlayback(session: Session, hls: HlsPlaylistState) {
        // First video mediadata is ready — drop mirror so HLS is the only picture.
        stopMirrorVideoIfRunning(session)
        hlsFcupService.beginDisplayedPlayback(session)
        val seek = hls.takePendingSeekSeconds()
        if (seek != null && seek > 0) {
            airPlayConsumer.onSeek(seek)
        }
    }

    fun maybeStartHlsPlayback(session: Session, hls: HlsPlaylistState?, reason: String) {
        if (hls == null || hls.isPlaybackStarted || !hls.hasCachedVideoMedia()) return
        hls.markPlaybackStarted()
        cancelPlaybackStartFallback(session.id)
        log.info("HLS starting playback ({}): {}", reason, hls.playlistUriLocal)
        startHlsPlayback(session, hls)
    }

    fun schedulePlaybackStartFallback(session: Session, hls: HlsPlaylistState) {
        cancelPlaybackStartFallback(session.id)
        val sessionId = session.id
        val future = hlsScheduler.schedule(Runnable {
            pendingPlaybackStarts.remove(sessionId)
            if (hls.isPlaybackStarted) return@Runnable
            if (session.hlsPlaylistState !== hls) return@Runnable
            hls.markPlaybackStarted()
            log.debug(
                "HLS starting playback (fallback after {}ms): {}",
                PLAYBACK_START_FALLBACK_MS, hls.playlistUriLocal,
            )
            startHlsPlayback(session, hls)
        }, PLAYBACK_START_FALLBACK_MS, TimeUnit.MILLISECONDS)
        pendingPlaybackStarts[sessionId] = future
    }

    fun cancelPlaybackStartFallback(sessionId: String) {
        pendingPlaybackStarts.remove(sessionId)?.cancel(false)
    }

    /** Follow a scrub's trailing `rate=0` so the sender does not stay on pause. */
    fun reannounceAfterScrubRateZero(session: Session) {
        hlsFcupService.sendPlaybackStateEvent(session, "loading")
        hlsFcupService.sendPlaybackStateEvent(session, "playing")
    }

    /**
     * The playback-info already in flight with `rate=0` also arrives before `/scrub`.
     * The following poll is the pause: a scrub would have cleared the flag by then.
     */
    fun commitPauseOnPlaybackInfo(session: Session) {
        val hls = session.hlsPlaylistState
        if (hls == null || !hls.notePlaybackInfoForPause()) return
        if (hls.isWaitingForMasterChange) {
            hls.clearRateZero()
            return
        }
        hls.clearRateZero()
        hls.playbackRate = 0.0
        // Pipeline was already soft-paused on rate=0; keep onPause idempotent.
        airPlayConsumer.onPause()
        hlsFcupService.sendPlaybackStateEvent(session, "paused")
        log.debug("Pause committed on playback-info (no /scrub within scrub window) session={}", session.id)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(HlsSessionControl::class.java)

        /** If video mediadata never arrives, start anyway so /play does not hang. */
        private const val PLAYBACK_START_FALLBACK_MS = 8_000L
    }
}
