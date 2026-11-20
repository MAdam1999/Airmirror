package com.github.serezhka.airplay.server.internal.handler.control

import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.github.serezhka.airplay.server.Playback
import com.github.serezhka.airplay.server.internal.handler.session.HlsPlaylistState
import com.github.serezhka.airplay.server.internal.handler.session.HlsUriRewrite
import com.github.serezhka.airplay.server.internal.handler.session.PlaylistRequest
import com.github.serezhka.airplay.server.internal.handler.session.Session
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager
import io.lindstrom.m3u8.parser.MediaPlaylistParser
import io.lindstrom.m3u8.parser.ParsingMode
import io.lindstrom.m3u8.parser.PlaylistParserException
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.socket.ServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * HTTP playlist / FCUP exchange paths used by YouTube-style HLS (GET /playlist +
 * unhandledURLResponse). Extracted from [ControlHandler] for readability.
 */
internal class HlsPlaylistHttp(
    private val sessionManager: SessionManager,
    private val hlsFcupService: HlsFcupService,
    private val hlsSession: HlsSessionControl,
    private val airPlayConsumer: Playback,
    private val responses: ControlResponses,
) {

    fun handleUnhandledUrlResponse(ctx: ChannelHandlerContext, request: FullHttpRequest, action: NSDictionary) {
        val params = action["params"] as? NSDictionary
        val urlObject = params?.get("FCUP_Response_URL")
        if (params == null || urlObject == null) {
            log.warn("FCUP response is missing URL: {}", action.toXMLPropertyList())
            return
        }
        val fcupResponseURL = normalizeFcupUrl(urlObject.toJavaObject(String::class.java))!!
        val session = resolveSession(request)
        val data = params["FCUP_Response_Data"]
        if (data == null) {
            log.warn("FCUP response without data: url={}, session={}", fcupResponseURL, session.id)
            val hls = session.hlsPlaylistState
            if (hls != null && !hls.isWaitingForMasterChange) {
                continueMediaPrefetch(session, hls)
            }
            return
        }
        // NSData already holds the decoded bytes (java.util.Base64 needs Android 8+).
        val fcupResponse = String((data as NSData).bytes(), Charsets.UTF_8)
        log.debug("FCUP response: url={}, bytes={}, session={}", fcupResponseURL, fcupResponse.length, session.id)

        var body: String
        var remoteMediaUris: List<String>? = null
        try {
            if (fcupResponseURL.contains("master.m3u8")) {
                val rewritten = rewriteMasterPlaylist(fcupResponse, playlistBaseUrl(ctx), session.id)
                body = rewritten.localBody
                remoteMediaUris = rewritten.remoteMediaUris
            } else if (fcupResponseURL.contains("mediadata.m3u8")) {
                body = mediaPlaylistBody(fcupResponse)
            } else {
                body = fcupResponse
            }
        } catch (e: Exception) {
            log.warn("Failed to rewrite playlist {}, forwarding raw FCUP body", fcupResponseURL, e)
            body = fcupResponse
        }

        val hls = session.hlsPlaylistState
        if (hls != null) {
            try {
                if (fcupResponseURL.contains("master.m3u8")) {
                    if (hls.isPlaybackStarted) {
                        hlsFcupService.onMasterRefreshDuringPlayback(session, body, fcupResponse, remoteMediaUris)
                    } else {
                        hls.storeMasterPlaylist(body, remoteMediaUris)
                        hls.recordRawMasterIfChanged(fcupResponse)
                        log.debug(
                            "HLS master received: {} media playlists queued ({} video), state={}",
                            hls.pendingMediaUriCount(), hls.videoMediaUriCount(), hls,
                        )
                        if (!hls.isPlaybackStarted) {
                            hlsSession.schedulePlaybackStartFallback(session, hls)
                        }
                    }
                } else {
                    hls.putPlaylist(fcupResponseURL, body)
                    hlsSession.maybeStartHlsPlayback(session, hls, "mediadata ready")
                }
            } catch (e: Exception) {
                log.warn("Failed to update HLS state for {}", fcupResponseURL, e)
                hls.putPlaylist(fcupResponseURL, body)
                hlsSession.maybeStartHlsPlayback(session, hls, "mediadata ready after error")
            }
            airPlayConsumer.onPlaylistContent(fcupResponseURL, body)

            replyPendingPlaylists(session, fcupResponseURL, body)

            if (hls.isPostEosMediaRefreshing) {
                if (fcupResponseURL.contains("mediadata.m3u8")) {
                    if (hls.hasMoreMediaUris()) {
                        hlsFcupService.sendFcupRequest(session, hls.nextMediaUri()!!)
                    } else {
                        hlsFcupService.onPostEosMediaRefreshComplete(session)
                    }
                }
            } else if (!hls.isWaitingForMasterChange) {
                continueMediaPrefetch(session, hls)
            }
            return
        }

        if (replyPendingPlaylists(session, fcupResponseURL, body)) {
            airPlayConsumer.onPlaylistContent(fcupResponseURL, body)
        } else {
            log.warn("No pending GET /playlist for {}", fcupResponseURL)
        }
    }

    fun handleGetPlaylist(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val playlistUriRemote = playlistPathToRemote(request.uri())
        val decoder = QueryStringDecoder(request.uri())
        val session = sessionManager.getSession(decoder.parameters()["session"]!![0])

        val pending = PlaylistRequest(
            ctx,
            request.protocolVersion().text(),
            request.method().name(),
            request.uri(),
            ControlResponses.copyHeaders(request.headers()),
            ByteBufUtil.getBytes(request.content()),
            HttpUtil.isKeepAlive(request),
        )

        val hls = session.hlsPlaylistState
        val cached = hls?.getPlaylist(playlistUriRemote)
        val isMasterPlaylist = playlistUriRemote.contains("master.m3u8")
        val refreshPlaylist = isMasterPlaylist && hls != null && hls.isPlaybackStarted && cached == null

        if (cached != null) {
            log.debug("Serving cached playlist {}", playlistUriRemote)
            replyPlaylist(pending, cached)
            if (hls.isPlaybackStarted &&
                hls.shouldRefreshPlaylist(playlistUriRemote, TimeUnit.MILLISECONDS.toNanos(1500))
            ) {
                hlsFcupService.sendFcupRequest(session, playlistUriRemote)
            }
            return
        }

        session.enqueuePlaylistRequest(playlistUriRemote, pending)
        recordPendingPlaylistRequest(pending)

        if (refreshPlaylist || hls == null || !hls.hasMoreMediaUris()) {
            log.debug("GET /playlist {} requesting FCUP", playlistUriRemote)
            hlsFcupService.sendFcupRequest(session, playlistUriRemote)
        } else {
            log.debug("GET /playlist {} waiting for HLS prefetch ({})", playlistUriRemote, hls)
        }
    }

    fun playlistBaseUrl(ctx: ChannelHandlerContext): String {
        val server = ctx.channel().parent() as? ServerSocketChannel
        val port = server?.localAddress()?.port ?: 7000
        // IPv4 loopback: on Windows "localhost" often resolves to ::1 while the
        // control server listens on IPv4, so the local HLS consumer cannot fetch.
        return "http://127.0.0.1:$port/playlist"
    }

    private fun continueMediaPrefetch(session: Session, hls: HlsPlaylistState) {
        if (hls.hasMoreMediaUris()) {
            hlsFcupService.sendFcupRequest(session, hls.nextMediaUri()!!)
        }
    }

    private fun replyPendingPlaylists(session: Session, remoteUri: String, body: String): Boolean {
        var replied = false
        while (true) {
            val pending = session.pollPlaylistRequest(remoteUri) ?: break
            replyPlaylist(pending, body)
            replied = true
        }
        return replied
    }

    private fun mediaPlaylistBody(fcupResponse: String): String {
        val parser = MediaPlaylistParser(ParsingMode.LENIENT)
        val mediaPlaylist = HlsCondensedUrls.expand(parser.readPlaylist(fcupResponse))
        return parser.writePlaylistAsString(mediaPlaylist)
    }

    private fun replyPlaylist(pending: PlaylistRequest, body: String?) {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/vnd.apple.mpegurl")
        response.content().writeCharSequence(body ?: "", Charsets.UTF_8)
        HttpUtil.setContentLength(response, response.content().readableBytes().toLong())
        responses.publishControlExchange(pending, response)
        val future = pending.context.writeAndFlush(response)
        if (!pending.keepAlive) {
            future.addListener(ChannelFutureListener.CLOSE)
        }
    }

    private fun recordPendingPlaylistRequest(pending: PlaylistRequest) {
        val processing = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.PROCESSING)
        responses.publishControlExchange(pending, processing)
    }

    private fun playlistPathToRemote(playlistPath: String): String =
        ("mlhls://localhost" + playlistPath.replace("/playlist", "")).substringBefore('?')

    private class MasterRewrite(val localBody: String, val remoteMediaUris: List<String>)

    private fun rewriteMasterPlaylist(masterPlaylist: String, baseUrl: String, sessionId: String): MasterRewrite {
        val filteredRemote = HlsUriRewrite.preferAvcVariants(masterPlaylist)
        if (filteredRemote != masterPlaylist) {
            log.debug("Filtered HLS master to AVC variants and the default audio track")
        }
        val remoteMediaUris = try {
            HlsUriRewrite.extractMediaUris(filteredRemote)
        } catch (e: PlaylistParserException) {
            log.warn("Failed to extract media URIs from filtered master", e)
            emptyList()
        }
        val localBody = HlsUriRewrite.rewritePlaylist(filteredRemote, baseUrl, sessionId)
        return MasterRewrite(localBody, remoteMediaUris)
    }

    private fun resolveSession(request: FullHttpRequest): Session {
        val sessionId = request.headers().get("Active-Remote") ?: request.headers().get("X-Apple-Session-ID")
        return sessionManager.getSession(sessionId.orEmpty())
    }

    private companion object {
        private val log = LoggerFactory.getLogger(HlsPlaylistHttp::class.java)

        private fun normalizeFcupUrl(url: String?): String? {
            if (url == null) return null
            val bare = url.substringBefore('?')
            val playlistIdx = bare.indexOf("/playlist/")
            if (bare.startsWith("http") && playlistIdx >= 0) {
                return "mlhls://localhost" + bare.substring(playlistIdx + "/playlist".length)
            }
            return bare
        }
    }
}
