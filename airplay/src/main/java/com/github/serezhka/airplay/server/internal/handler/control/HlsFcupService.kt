package com.github.serezhka.airplay.server.internal.handler.control

import com.github.serezhka.airplay.server.Playback
import com.github.serezhka.airplay.server.internal.handler.session.Session
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager
import com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpVersion
import org.slf4j.LoggerFactory
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class HlsFcupService(
    private val sessionManager: SessionManager,
    private val airPlayConsumer: Playback,
) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "airplay-hls-master-poll").apply { isDaemon = true }
    }
    private val masterPollTasks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val presentationFallbacks = ConcurrentHashMap<String, ScheduledFuture<*>>()
    /** One in-flight reverse `POST /event` per session (no HTTP pipelining). */
    private val reverseQueues = ConcurrentHashMap<String, ReverseQueue>()
    private val stateHeartbeat: ScheduledFuture<*> = scheduler.scheduleAtFixedRate(
        { pushPlaybackStates() }, STATE_HEARTBEAT_MS, STATE_HEARTBEAT_MS, TimeUnit.MILLISECONDS,
    )

    /** Stop the state re-assert. Master polls use the same executor and keep running. */
    fun stopStateHeartbeat() {
        stateHeartbeat.cancel(false)
    }

    fun refreshActivePlaylists() {
        for (session in sessionManager.allSessions()) {
            val hls = session.hlsPlaylistState
            if (hls == null || !hls.isPlaybackStarted) continue
            cancelMasterPoll(session.id)
            hls.abortMediaPrefetch()
            hls.isWaitingForMasterChange = false
            hls.playbackRate = 0.0

            // Live sliding windows: loading + master/media FCUP until body swap.
            // VOD ENDLIST: push the known-good reverse /event EOS burst (itemPlayedToEnd →
            // stopped/reason=ended → itemRemoved → currentItemChanged), keep rate=1 pin.
            // Lone paused/stopped without that burst hung historically; do not FCUP-storm VOD.
            hls.isWaitingForMasterChange = true
            hls.resetPostEosMediaSweep()
            if (hls.isLivePlaylist) {
                log.info("HLS ended (live playlist), refreshing master (keep session) {}", session.id)
                sendPlaybackStateEvent(session, "loading")
                requestMasterRefresh(session)
                scheduleMasterPoll(session)
            } else {
                log.info(
                    "HLS ended (VOD actionAtItemEnd={}), emit EOS reverse-event burst session={}",
                    hls.actionAtItemEnd, session.id,
                )
                hls.playbackRate = 1.0
                sendVodEosEventBurst(session)
            }
        }
    }

    fun onMasterRefreshDuringPlayback(
        session: Session,
        rewrittenBody: String,
        rawBody: String?,
        remoteMediaUris: List<String>?,
    ) {
        val hls = session.hlsPlaylistState
        if (hls == null || !hls.isPlaybackStarted) return
        val rewrittenChanged = hls.recordMasterIfChanged(rewrittenBody)
        val rawChanged = hls.recordRawMasterIfChanged(rawBody)
        if (rewrittenChanged || rawChanged) {
            log.info(
                "HLS master changed (rewritten={}, raw={}), restarting playback session {}",
                rewrittenChanged, rawChanged, session.id,
            )
            cancelMasterPoll(session.id)
            hls.isWaitingForMasterChange = false
            hls.playbackRate = 1.0
            if (!remoteMediaUris.isNullOrEmpty()) {
                try {
                    hls.storeMasterPlaylist(rewrittenBody, remoteMediaUris)
                } catch (e: Exception) {
                    log.warn("Failed to refresh media URI list after master change", e)
                    hls.updateMasterPlaylist(rewrittenBody)
                }
            } else {
                hls.updateMasterPlaylist(rewrittenBody)
            }
            beginDisplayedPlayback(session)
            val seek = hls.takePendingSeekSeconds()
            if (seek != null && seek > 0) {
                airPlayConsumer.onSeek(seek)
            }
        } else if (hls.isWaitingForMasterChange) {
            if (hls.isPostEosMediaRefreshing) {
                log.debug("HLS master unchanged, media refresh still in flight session={}", session.id)
                return
            }
            hls.updateMasterPlaylist(rewrittenBody)
            // Prefer a mediadata sweep after EOS: master URI list often stays identical while
            // VOD bodies swap (preroll ad → content). First sweep immediately; then every
            // MEDIA_RESWEEP_EVERY_MASTER_POLLS master polls so we do not starve /event
            // (playlistRemove / next /play still need the reverse channel).
            if (!hls.isPostEosMediaSweepDone ||
                hls.noteMasterPollAndShouldResweepMedia(MEDIA_RESWEEP_EVERY_MASTER_POLLS)
            ) {
                log.debug("HLS master unchanged after EOS, refreshing media playlists session={}", session.id)
                hls.beginPostEosMediaRefresh(remoteMediaUris)
                val first = hls.nextMediaUri()
                if (first != null) {
                    sendFcupRequest(session, first)
                } else {
                    hls.markPostEosMediaSweepDone()
                    scheduleMasterPoll(session)
                }
            } else {
                log.debug("HLS master still unchanged, master-only poll session={}", session.id)
                scheduleMasterPoll(session)
            }
        } else {
            log.debug("HLS master refreshed during playback, bytes={}", rewrittenBody.length)
        }
    }

    /** Called when a post-EOS media FCUP round finishes (all queued mediadata fetched). */
    fun onPostEosMediaRefreshComplete(session: Session) {
        val hls = session.hlsPlaylistState
        if (hls == null || !hls.isPostEosMediaRefreshing) return
        val mediaChanged = hls.finishPostEosMediaRefresh()
        hls.markPostEosMediaSweepDone()
        if (mediaChanged) {
            log.info("HLS media playlists changed after EOS, restarting playback session {}", session.id)
            cancelMasterPoll(session.id)
            hls.isWaitingForMasterChange = false
            hls.playbackRate = 1.0
            beginDisplayedPlayback(session)
        } else {
            log.debug("HLS media unchanged after EOS, polling master session {}", session.id)
            scheduleMasterPoll(session)
        }
    }

    fun cancelAllMasterPolls() {
        for (session in sessionManager.allSessions()) {
            cancelMasterPoll(session.id)
            cancelPresentationFallback(session.id)
            session.hlsPlaylistState?.isWaitingForMasterChange = false
        }
    }

    fun clearReverseQueue(session: Session) {
        cancelPresentationFallback(session.id)
        val queue = reverseQueues.remove(session.id) ?: return
        synchronized(queue) {
            queue.pending.clear()
            queue.inFlight = false
        }
    }

    /** Called when the reverse event channel receives HTTP 200 for a POST /event. */
    fun onReverseEventResponse(ctx: ChannelHandlerContext) {
        for (session in sessionManager.allSessions()) {
            if (session.reverseContexts["event"] === ctx) {
                val queue = reverseQueues[session.id] ?: return
                synchronized(queue) {
                    queue.inFlight = false
                    log.debug("Reverse /event accepted, {} still queued session={}", queue.pending.size, session.id)
                    flushReverseQueue(session, queue)
                }
                return
            }
        }
    }

    private fun requestMasterRefresh(session: Session) {
        val hls = session.hlsPlaylistState ?: return
        sendFcupRequest(session, hls.remoteMasterUri)
    }

    private fun scheduleMasterPoll(session: Session) {
        cancelMasterPoll(session.id)
        val future = scheduler.schedule(Runnable {
            val hls = session.hlsPlaylistState
            if (hls != null && hls.isWaitingForMasterChange) {
                requestMasterRefresh(session)
                scheduleMasterPoll(session)
            }
        }, MASTER_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
        masterPollTasks[session.id] = future
    }

    private fun cancelMasterPoll(sessionId: String) {
        masterPollTasks.remove(sessionId)?.cancel(false)
    }

    /**
     * Start the player but keep the sender on `loading` until a frame is visible.
     * Announcing `playing` at player start makes the sender timeline run ahead of the display.
     */
    fun beginDisplayedPlayback(session: Session) {
        val hls = session.hlsPlaylistState ?: return
        hls.awaitPresentation()
        airPlayConsumer.onPlaylist(hls.playlistUriLocal)
        schedulePresentationFallback(session)
    }

    /** Replacement picture after a seek — refresh `playing` with the real clock. */
    fun onSeekDisplayed() {
        for (session in sessionManager.allSessions()) {
            val hls = session.hlsPlaylistState ?: continue
            hls.endScrubGesture()
            log.debug("Announcing playing (seek picture) session={}", session.id)
            sendPlaybackStateEvent(session, "playing")
        }
    }

    /** First picture is on the display. Announce `playing` once. */
    fun onPlaybackPresented() {
        for (session in sessionManager.allSessions()) {
            announcePlayingIfPending(session, "picture")
        }
    }

    private fun schedulePresentationFallback(session: Session) {
        cancelPresentationFallback(session.id)
        val sessionId = session.id
        val future = scheduler.schedule(Runnable {
            presentationFallbacks.remove(sessionId)
            announcePlayingIfPending(sessionManager.getSession(sessionId), "fallback")
        }, PRESENTATION_FALLBACK_MS, TimeUnit.MILLISECONDS)
        presentationFallbacks[sessionId] = future
    }

    private fun announcePlayingIfPending(session: Session, reason: String) {
        val hls = session.hlsPlaylistState
        if (hls == null || !hls.claimPresentation()) return
        cancelPresentationFallback(session.id)
        log.debug("Announcing playing ({}) session={}", reason, session.id)
        sendPlaybackStateEvent(session, "playing")
    }

    private fun cancelPresentationFallback(sessionId: String) {
        presentationFallbacks.remove(sessionId)?.cancel(false)
    }

    /**
     * State to re-assert, or `null` when the sender must not hear one
     * (still loading the first frame, waiting for the next item, or at VOD end).
     */
    internal fun playbackStateToAnnounce(session: Session): String? {
        val hls = session.hlsPlaylistState
        if (hls == null || !hls.isPlaybackStarted || hls.isAwaitingPresentation || hls.isWaitingForMasterChange) {
            return null
        }
        if (hls.playbackRate <= 0) return "paused"
        if (!hls.isLivePlaylist) {
            val info = airPlayConsumer.info()
            val duration = if (hls.mediaDurationSeconds > 0) hls.mediaDurationSeconds else info.duration
            if (duration > 0 && info.position + 0.25 >= duration) return null
        }
        return "playing"
    }

    private fun pushPlaybackStates() {
        for (session in sessionManager.allSessions()) {
            try {
                val state = playbackStateToAnnounce(session) ?: continue
                offerPlaybackState(session, state)
            } catch (e: RuntimeException) {
                log.debug("Playback state heartbeat failed session={}: {}", session.id, e.toString())
            }
        }
    }

    /**
     * Enqueue only when the reverse channel is idle. Checked under the queue lock so a
     * pause or end burst that arrived in the same moment is not followed by a stale `playing`.
     */
    private fun offerPlaybackState(session: Session, state: String) {
        val eventContext = session.reverseContexts["event"]
        if (eventContext == null || !eventContext.channel().isActive) return
        val body = playbackStateBody(session, state)
        val queue = reverseQueues.computeIfAbsent(session.id) { ReverseQueue() }
        synchronized(queue) {
            if (queue.inFlight || queue.pending.isNotEmpty()) return
            queue.pending.add(body)
            flushReverseQueue(session, queue)
        }
    }

    fun sendPlaybackStateEvent(session: Session, state: String) {
        enqueueReverseEvent(session, playbackStateBody(session, state))
    }

    private fun playbackStateBody(session: Session, state: String): ByteArray {
        val hls = session.hlsPlaylistState
        var playback: Playback.Info? = null
        if (state == "playing") {
            val info = airPlayConsumer.info()
            var duration = info.duration
            var position = info.position
            if (hls != null && hls.mediaDurationSeconds > 0) {
                duration = hls.mediaDurationSeconds
            }
            if (duration > 0) {
                position = minOf(maxOf(0.0, position), duration)
                // The event says playing, so the clock rate is 1 even if the player
                // was still paused when this sample was taken.
                playback = Playback.Info(duration, position, 1.0)
            }
        }
        val body = if (hls != null) {
            PropertyListUtil.preparePlaybackStateEvent(state, hls.reverseEventSessionId, hls.itemUuid, null, playback)
        } else {
            PropertyListUtil.preparePlaybackStateEvent(state)
        }
        if (log.isDebugEnabled) {
            val xml = String(body, Charsets.UTF_8).replace(WHITESPACE, " ").trim()
            if (playback != null) {
                log.debug(
                    "Playback state event: {} position={} rate={} session={} xml={}",
                    state, playback.position, playback.rate, session.id, xml,
                )
            } else {
                log.debug("Playback state event: {} session={} xml={}", state, session.id, xml)
            }
        }
        return body
    }

    /**
     * Working receiver VOD EOS sequence on reverse `POST /event`
     * (after local player end). Queued FIFO — one in-flight request at a time.
     */
    fun sendVodEosEventBurst(session: Session) {
        val hls = session.hlsPlaylistState ?: return
        val sid = hls.reverseEventSessionId
        val itemUuid = hls.itemUuid
        val playId = session.id
        log.debug("VOD EOS reverse burst playId={} reverseSessionId={} itemUuid={}", playId, sid, itemUuid)
        enqueueReverseEvent(session, PropertyListUtil.preparePlaybackStateEvent("loading", sid, itemUuid, null))
        enqueueReverseEvent(session, PropertyListUtil.prepareVideoTypedEvent("itemPlayedToEnd", sid, itemUuid))
        enqueueReverseEvent(session, PropertyListUtil.preparePlaybackStateEvent("stopped", sid, itemUuid, "ended"))
        enqueueReverseEvent(session, PropertyListUtil.prepareItemRemovedEvent(playId, itemUuid))
        enqueueReverseEvent(session, PropertyListUtil.prepareCurrentItemChangedEvent(sid))
        enqueueReverseEvent(session, PropertyListUtil.preparePlaybackStateEvent("stopped", sid, itemUuid, null))
    }

    fun sendFcupRequest(session: Session, listUri: String) {
        val requestId = session.hlsPlaylistState?.nextFcupRequestId() ?: 1
        log.debug("FCUP request: url={}, requestId={}, session={}", listUri, requestId, session.id)
        enqueueReverseEvent(session, PropertyListUtil.prepareEventRequest(session.id, listUri, requestId))
    }

    private fun enqueueReverseEvent(session: Session, body: ByteArray) {
        val queue = reverseQueues.computeIfAbsent(session.id) { ReverseQueue() }
        synchronized(queue) {
            queue.pending.add(body)
            flushReverseQueue(session, queue)
        }
    }

    private fun flushReverseQueue(session: Session, queue: ReverseQueue) {
        if (queue.inFlight || queue.pending.isEmpty()) return
        val eventContext = session.reverseContexts["event"]
        if (eventContext == null || !eventContext.channel().isActive) {
            log.error(
                "No active reverse event channel, dropping {} queued events session={}",
                queue.pending.size, session.id,
            )
            queue.pending.clear()
            return
        }
        val body = queue.pending.poll()
        queue.inFlight = true
        val event = DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/event")
        event.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml")
        event.headers().add(HttpHeaderNames.CONTENT_LENGTH, body.size)
        event.headers().add("X-Apple-Session-ID", session.id)
        event.content().writeBytes(body)
        eventContext.writeAndFlush(event)
    }

    private class ReverseQueue {
        val pending = ArrayDeque<ByteArray>()
        var inFlight = false
    }

    private companion object {
        private val log = LoggerFactory.getLogger(HlsFcupService::class.java)
        private val WHITESPACE = Regex("\\s+")

        private const val MASTER_POLL_INTERVAL_MS = 2000L
        /**
         * After the first post-EOS mediadata sweep, re-FCUP all mediadata every N master polls.
         * Ad→content often keeps the same master URI list and only swaps VOD bodies.
         * N=3 (~6s) balances freshness vs reverse /event channel load (playlistRemove / next /play).
         */
        private const val MEDIA_RESWEEP_EVERY_MASTER_POLLS = 3
        /** If no frame arrives, announce anyway so the sender is not stuck on loading. */
        private const val PRESENTATION_FALLBACK_MS = 8_000L
        /**
         * Re-assert `playing` or `paused` on the reverse channel. The sender's
         * play/pause icon follows these events; `/playback-info` only moves the timeline.
         * Skipped while the channel is busy so FCUP and the VOD end burst are not queued behind it.
         */
        private const val STATE_HEARTBEAT_MS = 2_000L
    }
}
