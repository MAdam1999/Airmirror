package com.github.serezhka.airplay.server.internal.handler.control

import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo
import com.github.serezhka.airplay.protocol.media.MediaStreamInfo
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo
import com.github.serezhka.airplay.server.AirPlayConfig
import com.github.serezhka.airplay.server.Playback
import com.github.serezhka.airplay.server.internal.handler.session.Session
import com.github.serezhka.airplay.server.internal.handler.session.SessionManager
import com.github.serezhka.airplay.server.internal.handler.util.AirPlayVolume
import com.github.serezhka.airplay.server.internal.handler.util.PropertyListUtil
import io.netty.buffer.ByteBufInputStream
import io.netty.buffer.ByteBufOutputStream
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.ServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import io.netty.handler.codec.rtsp.RtspDecoder
import io.netty.handler.codec.rtsp.RtspEncoder
import io.netty.handler.codec.rtsp.RtspHeaderNames
import io.netty.handler.codec.rtsp.RtspMethods
import io.netty.handler.codec.rtsp.RtspVersions
import io.netty.util.ReferenceCountUtil
import org.slf4j.LoggerFactory

class ControlHandler(
    private val sessionManager: SessionManager,
    private val hlsFcupService: HlsFcupService,
    private val airPlayConfig: AirPlayConfig,
    private val airPlayConsumer: Playback,
) : ChannelInboundHandlerAdapter() {

    private var pendingRequestBody = ByteArray(0)

    private val hlsSession = HlsSessionControl(sessionManager, hlsFcupService, airPlayConsumer)
    private val responses = ControlResponses(airPlayConsumer) { pendingRequestBody }
    private val hlsPlaylistHttp = HlsPlaylistHttp(sessionManager, hlsFcupService, hlsSession, airPlayConsumer, responses)

    override fun channelInactive(ctx: ChannelHandlerContext) {
        for (session in sessionManager.allSessions()) {
            val reverse = session.reverseContexts
            if (reverse["event"] === ctx) {
                log.info("Reverse event channel closed, stopping HLS session {}", session.id)
                hlsSession.stopHlsPlayback(session)
            }
            reverse.entries.removeIf { it.value === ctx }
        }
        super.channelInactive(ctx)
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        try {
            when (msg) {
                is FullHttpRequest -> {
                    pendingRequestBody = ByteBufUtil.getBytes(msg.content())
                    try {
                        dispatchControlRequest(ctx, msg)
                    } finally {
                        pendingRequestBody = ByteArray(0)
                    }
                }
                is FullHttpResponse -> {
                    log.debug("Reverse channel response: {} {}", msg.status(), msg.content().readableBytes())
                    hlsFcupService.onReverseEventResponse(ctx)
                }
                else -> log.error("Unknown control message type: {}", msg)
            }
        } finally {
            ReferenceCountUtil.release(msg)
        }
    }

    private fun dispatchControlRequest(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val method = request.method()
        val uri = request.uri()
        if (RtspVersions.RTSP_1_0 == request.protocolVersion()) {
            when {
                HttpMethod.GET == method && uri == "/info" -> handleGetInfo(ctx, request)
                HttpMethod.POST == method && uri == "/pair-setup" -> handlePairSetup(ctx, request)
                HttpMethod.POST == method && uri == "/pair-verify" -> handlePairVerify(ctx, request)
                HttpMethod.POST == method && uri == "/fp-setup" -> handleFairPlaySetup(ctx, request)
                RtspMethods.SETUP == method -> handleRtspSetup(ctx, request)
                HttpMethod.POST == method && uri == "/feedback" -> sendEmptyRtspResponse(ctx, request)
                RtspMethods.GET_PARAMETER == method -> handleRtspGetParameter(ctx, request)
                RtspMethods.RECORD == method -> handleRtspRecord(ctx, request)
                RtspMethods.SET_PARAMETER == method -> handleRtspSetParameter(ctx, request)
                method.toString() == "FLUSH" -> sendEmptyRtspResponse(ctx, request)
                RtspMethods.TEARDOWN == method -> handleRtspTeardown(ctx, request)
                HttpMethod.POST == method && uri == "/audioMode" -> sendEmptyRtspResponse(ctx, request)
                RtspMethods.OPTIONS == method -> handleRtspOptions(ctx, request)
                RtspMethods.ANNOUNCE == method -> handleRtspAnnounce(ctx, request)
                else -> {
                    log.error("Unknown control request: {} {} {}", request.protocolVersion(), method, uri)
                    val response = responses.createRtspResponse(request)
                    response.setStatus(HttpResponseStatus.NOT_FOUND)
                    responses.sendResponse(ctx, request, response)
                }
            }
        } else if (HttpVersion.HTTP_1_1 == request.protocolVersion()) {
            val path = QueryStringDecoder(uri).path()
            when {
                HttpMethod.GET == method && path == "/server-info" -> handleGetServerInfo(ctx, request)
                // YouTube posts FairPlay *version 1* here; we only implement v3 on RTSP.
                // Acknowledge so the client is not 404'd / reset — do not run fairPlaySetup.
                HttpMethod.POST == method && (path == "/fp-setup" || path == "/fp-setup2") ->
                    handleHttpFairPlaySetupAck(ctx, request)
                HttpMethod.POST == method && path == "/reverse" -> handleReverse(ctx, request)
                HttpMethod.POST == method && path == "/play" -> handlePlay(ctx, request)
                HttpMethod.PUT == method && path == "/setProperty" -> handleSetProperty(ctx, request)
                HttpMethod.POST == method && path == "/rate" -> handleRate(ctx, request)
                HttpMethod.GET == method && path == "/playback-info" -> handlePlaybackInfo(ctx, request)
                HttpMethod.POST == method && path == "/action" -> handleAction(ctx, request)
                HttpMethod.POST == method && path == "/getProperty" -> handleGetProperty(ctx, request)
                HttpMethod.POST == method && path == "/scrub" -> handleScrub(ctx, request)
                HttpMethod.POST == method && (path == "/stop" || path == "/stop2") -> handleStop(ctx, request)
                HttpMethod.GET == method && path.startsWith("/playlist") -> hlsPlaylistHttp.handleGetPlaylist(ctx, request)
                else -> {
                    log.error("Unknown control request: {} {} {}", request.protocolVersion(), method, uri)
                    sendHttp(ctx, request, HttpResponseStatus.NOT_FOUND)
                }
            }
        }
    }

    /**
     * Resolves session by the request headers:
     * `Active-Remote` for RTSP, `X-Apple-Session-ID` for HTTP.
     */
    private fun resolveSession(request: FullHttpRequest): Session {
        val sessionId = request.headers().get("Active-Remote") ?: request.headers().get("X-Apple-Session-ID")
        return sessionManager.getSession(sessionId.orEmpty())
    }

    private fun sendHttp(ctx: ChannelHandlerContext, request: FullHttpRequest, status: HttpResponseStatus) {
        responses.sendResponse(ctx, request, DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status))
    }

    private fun sendEmptyRtspResponse(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        responses.sendResponse(ctx, request, responses.createRtspResponse(request))
    }

    private fun handleGetInfo(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val info = PropertyListUtil.prepareInfoResponse(airPlayConfig, sessionManager.pairingPublicKey())
        val response = responses.createRtspResponse(request)
        response.content().writeBytes(info)
        responses.sendResponse(ctx, request, response)
    }

    private fun handlePairSetup(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val session = resolveSession(request)
        val response = responses.createRtspResponse(request)
        session.airPlay.pairSetup(ByteBufOutputStream(response.content()))
        responses.sendResponse(ctx, request, response)
    }

    private fun handlePairVerify(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val session = resolveSession(request)
        val response = responses.createRtspResponse(request)
        session.airPlay.pairVerify(ByteBufInputStream(request.content()), ByteBufOutputStream(response.content()))
        responses.sendResponse(ctx, request, response)
    }

    private fun handleFairPlaySetup(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val session = resolveSession(request)
        val response = responses.createRtspResponse(request)
        session.airPlay.fairPlaySetup(ByteBufInputStream(request.content()), ByteBufOutputStream(response.content()))
        responses.sendResponse(ctx, request, response)
    }

    private fun handleRtspSetup(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val session = resolveSession(request)
        val response = responses.createRtspResponse(request)
        val mediaStreamInfo = session.airPlay.rtspSetup(ByteBufInputStream(request.content()))
        when (mediaStreamInfo?.streamType) {
            MediaStreamInfo.StreamType.AUDIO -> {
                airPlayConsumer.onAudioFormat(mediaStreamInfo as AudioStreamInfo)
                session.audioServer.start(airPlayConsumer)
                session.audioControlServer.start()
                log.info(
                    "SESSION mode=AUDIO session={} audioPort={} controlPort={}",
                    session.id, session.audioServer.port, session.audioControlServer.port,
                )
                val setup = PropertyListUtil.prepareSetupAudioResponse(
                    session.audioServer.port, session.audioControlServer.port,
                )
                response.content().writeBytes(setup)
            }
            MediaStreamInfo.StreamType.VIDEO -> {
                airPlayConsumer.onVideoFormat(mediaStreamInfo as VideoStreamInfo)
                session.videoServer.start(airPlayConsumer)
                log.info("SESSION mode=MIRROR session={} videoPort={}", session.id, session.videoServer.port)
                val eventPort = (ctx.channel().parent() as ServerSocketChannel).localAddress().port
                val setup = PropertyListUtil.prepareSetupVideoResponse(session.videoServer.port, eventPort, 0)
                response.content().writeBytes(setup)
            }
            null -> {}
        }
        responses.sendResponse(ctx, request, response)
    }

    /**
     * First probe when the sender treats the receiver as a speaker (`OPTIONS *`).
     * `Public` lists the methods this connection accepts.
     */
    private fun handleRtspOptions(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        log.debug("RTSP OPTIONS {}", request.uri())
        val response = responses.createRtspResponse(request)
        response.headers().add(
            RtspHeaderNames.PUBLIC,
            "ANNOUNCE, SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET",
        )
        response.headers().add("Audio-Jack-Status", "connected; type=analog")
        responses.sendResponse(ctx, request, response)
    }

    /** Speaker setup follows OPTIONS with an SDP body. Accept it and keep the session open. */
    private fun handleRtspAnnounce(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        if (log.isDebugEnabled) {
            val body = request.content().toString(Charsets.US_ASCII).replace(WHITESPACE, " ").trim()
            log.debug("RTSP ANNOUNCE {}", body.take(400))
        }
        sendEmptyRtspResponse(ctx, request)
    }

    private fun handleRtspGetParameter(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val content = AirPlayVolume.formatRtspParameter(airPlayConsumer.volume()).toByteArray(Charsets.US_ASCII)
        val response = responses.createRtspResponse(request)
        response.content().writeBytes(content)
        responses.sendResponse(ctx, request, response)
    }

    private fun handleRtspRecord(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val response = responses.createRtspResponse(request)
        response.headers().add("Audio-Latency", "11025")
        response.headers().add("Audio-Jack-Status", "connected; type=analog")
        responses.sendResponse(ctx, request, response)
    }

    private fun handleRtspSetParameter(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE).orEmpty()
        if (contentType.contains("text/parameters") && request.content().isReadable) {
            val body = request.content().toString(Charsets.US_ASCII)
            for (line in body.split(LINE_BREAK)) {
                if (line.regionMatches(0, "volume:", 0, 7, ignoreCase = true)) {
                    try {
                        val db = line.substring(7).trim().toDouble()
                        val linear = AirPlayVolume.fromDecibels(db)
                        airPlayConsumer.onVolume(linear)
                        log.debug("RTSP volume {} dB -> linear {}", db, linear)
                    } catch (e: NumberFormatException) {
                        log.warn("Invalid RTSP volume line: {}", line)
                    }
                }
            }
        }
        val response = responses.createRtspResponse(request)
        response.headers().add("Audio-Jack-Status", "connected; type=analog")
        responses.sendResponse(ctx, request, response)
    }

    private fun handleRtspTeardown(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val session = resolveSession(request)
        when (session.airPlay.rtspTeardown(ByteBufInputStream(request.content()))?.streamType) {
            MediaStreamInfo.StreamType.AUDIO -> {
                airPlayConsumer.onAudioSrcDisconnect()
                session.audioServer.stop()
                session.audioControlServer.stop()
            }
            MediaStreamInfo.StreamType.VIDEO -> {
                airPlayConsumer.onVideoSrcDisconnect()
                session.videoServer.stop()
            }
            null -> {
                airPlayConsumer.onAudioSrcDisconnect()
                airPlayConsumer.onVideoSrcDisconnect()
                session.audioServer.stop()
                session.audioControlServer.stop()
                session.videoServer.stop()
                // Full session teardown only — stream-specific TEARDOWN must not stop HLS.
                if (session.hlsPlaylistState != null) {
                    log.info("RTSP TEARDOWN (full): stopping HLS session {}", session.id)
                    hlsSession.stopHlsPlayback(session)
                }
            }
        }
        sendEmptyRtspResponse(ctx, request)
    }

    private fun handleGetServerInfo(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val serverInfo = PropertyListUtil.prepareServerInfoResponse()
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml")
        response.content().writeBytes(serverInfo)
        responses.sendResponse(ctx, request, response)
    }

    /**
     * HTTP `/fp-setup` and `/fp-setup2`: return 200 with empty body.
     * Must not 404 (phone aborts YouTube HLS) and must not run RTSP FairPlay v3
     * against the v1 payload YouTube sends (logs "version 1 is not supported" and resets).
     */
    private fun handleHttpFairPlaySetupAck(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        sendHttp(ctx, request, HttpResponseStatus.OK)
    }

    private fun handleReverse(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS)
        response.headers().add(HttpHeaderNames.UPGRADE, request.headers().get(HttpHeaderNames.UPGRADE))
        response.headers().add(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE)
        responses.sendResponse(ctx, request, response)

        val purpose = request.headers().get("X-Apple-Purpose")
        ctx.pipeline().remove(RtspDecoder::class.java)
        ctx.pipeline().remove(RtspEncoder::class.java)
        ctx.pipeline().addFirst(HttpClientCodec())
        resolveSession(request).reverseContexts[purpose] = ctx
    }

    private fun NSDictionary.string(key: String): String? = this[key]?.toJavaObject(String::class.java)

    private fun handlePlay(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val play = BinaryPropertyListParser.parse(ByteBufInputStream(request.content())) as NSDictionary
        if (log.isDebugEnabled) {
            log.debug("POST /play body:\n{}", play.toXMLPropertyList())
        }

        play["volume"]?.let {
            try {
                airPlayConsumer.onVolume(AirPlayVolume.clampLinear(it.toJavaObject(Double::class.javaObjectType)))
            } catch (e: Exception) {
                log.debug("Ignoring /play volume", e)
            }
        }

        var startPositionSeconds: Double? = null
        play["Start-Position-Seconds"]?.let {
            try {
                startPositionSeconds = it.toJavaObject(Double::class.javaObjectType)
            } catch (e: Exception) {
                log.debug("Ignoring Start-Position-Seconds", e)
            }
        }

        val clientProcName = play.string("clientProcName") ?: ""
        val playlistUri = play.string("Content-Location")
        if (!playlistUri.isNullOrBlank()) {
            sendHttp(ctx, request, HttpResponseStatus.OK)
            hlsSession.startMediaPlaylist(
                resolveSession(request),
                playlistUri,
                hlsPlaylistHttp.playlistBaseUrl(ctx),
                clientProcName,
                startPositionSeconds,
                play.string("uuid"),
            )
        } else {
            log.error("Client proc name [{}] has no Content-Location playlist", clientProcName)
            sendHttp(ctx, request, HttpResponseStatus.NOT_IMPLEMENTED)
        }
    }

    private fun handleSetProperty(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val decoder = QueryStringDecoder(request.uri())
        val path = decoder.path()
        val params = decoder.parameters()
        val play = BinaryPropertyListParser.parse(ByteBufInputStream(request.content())) as NSDictionary
        val isActionAtItemEnd = params.containsKey("actionAtItemEnd") || request.uri().contains("actionAtItemEnd")
        val isSelectedMedia = params.containsKey("selectedMediaArray") || request.uri().contains("selectedMediaArray")
        if (isActionAtItemEnd || isSelectedMedia) {
            if (log.isDebugEnabled) {
                log.debug("SET_PROPERTY {}: {}", request.uri(), play.toXMLPropertyList().replace(WHITESPACE, " ").trim())
            }
        } else {
            log.debug("SET_PARAMETER path={}, params={}", path, params)
            if (log.isDebugEnabled) {
                log.debug("SET_PARAMETER body:\n{}", play.toXMLPropertyList())
            }
        }
        val value = play["value"]
        if (isActionAtItemEnd && value != null) {
            val session = resolveSession(request)
            val hls = session.hlsPlaylistState
            if (hls != null) {
                val action = (value.toJavaObject() as Number).toInt()
                hls.actionAtItemEnd = action
                log.debug("actionAtItemEnd={} session={}", action, session.id)
            }
        }
        sendHttp(ctx, request, HttpResponseStatus.OK)
    }

    private fun handleRate(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val decoder = QueryStringDecoder(request.uri())
        val value = decoder.parameters()["value"]!![0].toDouble()
        val session = resolveSession(request)
        val hls = session.hlsPlaylistState
        log.debug("POST /rate value={}", value)

        // After VOD ad EOS we wait for the next /play; the sender still probes rate 0/1.
        // Answering with paused→playing makes it treat the ad as still active.
        if (hls != null && hls.isWaitingForMasterChange) {
            log.debug("Ignoring rate={} while waiting for next HLS item session={}", value, session.id)
            sendHttp(ctx, request, HttpResponseStatus.OK)
            return
        }

        if (value == 0.0) {
            if (hls != null && hls.isScrubGesture) {
                // Tail of the scrub, before the seek-picture callback. The sender applies
                // this rate=0 locally; playing has to follow it.
                log.debug("rate=0 during scrub gesture session={}", session.id)
                hlsSession.reannounceAfterScrubRateZero(session)
            } else if (hls != null) {
                hls.armRateZero()
                // Freeze the picture now; formal paused (playbackRate=0 + reverse event) still
                // waits for the scrub window so a following /scrub is not blocked by a pause UI.
                airPlayConsumer.onPause()
                log.debug("rate=0 soft-pause; formal pause waits for scrub window session={}", session.id)
            }
        } else {
            val softPaused = hls != null && hls.isRateZeroPending
            val wasPaused = hls != null && (hls.playbackRate <= 0 || softPaused)
            if (hls != null) {
                hls.clearRateZero()
                hls.endScrubGesture()
                hls.playbackRate = 1.0
            }
            // Only resume when leaving a real/soft pause — rate=1 while already playing must not re-seek.
            if (wasPaused) {
                airPlayConsumer.onResume()
            }
            // rate=1 can arrive before the first frame; wait for the picture callback.
            if (hls == null || !hls.isAwaitingPresentation) {
                hlsFcupService.sendPlaybackStateEvent(session, "playing")
            } else {
                log.debug("Holding playing until the picture session={}", session.id)
            }
        }
        sendHttp(ctx, request, HttpResponseStatus.OK)
    }

    private fun handleScrub(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val positions = QueryStringDecoder(request.uri()).parameters()["position"]
        if (!positions.isNullOrEmpty()) {
            try {
                val position = positions[0].toDouble()
                log.debug("POST /scrub position={}", position)
                val session = resolveSession(request)
                val hls = session.hlsPlaylistState
                if (hls != null) {
                    hls.clearRateZero()
                    hls.beginScrubGesture()
                    hls.playbackRate = 1.0
                }
                if (hls != null && !hls.isPlaybackStarted) {
                    hls.pendingSeekSeconds = position
                    log.debug("Deferring /scrub to pending seek until HLS starts")
                } else {
                    // onSeek plays; clears a soft-pause from the leading rate=0.
                    airPlayConsumer.onSeek(position)
                    // loading then playing (clock under params) unsticks the pause UI.
                    hlsFcupService.sendPlaybackStateEvent(session, "loading")
                    hlsFcupService.sendPlaybackStateEvent(session, "playing")
                }
            } catch (e: NumberFormatException) {
                log.warn("Invalid /scrub position: {}", positions[0])
            }
        } else {
            log.warn("POST /scrub without position: {}", request.uri())
        }
        sendHttp(ctx, request, HttpResponseStatus.OK)
    }

    private fun handleStop(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val path = QueryStringDecoder(request.uri()).path()
        val session = resolveSession(request)
        log.info("SESSION mode=STOP path={} session={}", path, session.id)
        hlsSession.stopHlsPlayback(session)
        sendHttp(ctx, request, HttpResponseStatus.OK)
    }

    private fun handlePlaybackInfo(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val session = resolveSession(request)
        hlsSession.commitPauseOnPlaybackInfo(session)
        val playbackInfo = HlsPlaybackInfo.resolve(session.hlsPlaylistState, airPlayConsumer.info())
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml")
        response.content().writeBytes(PropertyListUtil.preparePlaybackInfoResponse(playbackInfo))
        responses.sendResponse(ctx, request, response)
    }

    private fun handleAction(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val action = parseActionPlist(request)
        if (log.isDebugEnabled) {
            log.debug("POST /action body:\n{}", action.toXMLPropertyList())
        }

        val type = action.string("type")
        log.debug("Action type: {}", type)
        when (type) {
            "unhandledURLResponse" -> hlsPlaylistHttp.handleUnhandledUrlResponse(ctx, request, action)
            "playlistRemove" -> hlsSession.stopHlsPlayback(resolveSession(request))
            "playlistInsert" -> handlePlaylistInsert(ctx, request, action)
        }
        sendHttp(ctx, request, HttpResponseStatus.OK)
    }

    private fun handlePlaylistInsert(ctx: ChannelHandlerContext, request: FullHttpRequest, action: NSDictionary) {
        val params = action["params"] as? NSDictionary
        val item = params?.get("item") as? NSDictionary
        val playlistUri = item?.string("Content-Location")
        if (item == null || playlistUri == null) {
            log.warn("playlistInsert without Content-Location: {}", action.toXMLPropertyList())
            return
        }
        val clientProcName = item.string("clientProcName") ?: ""
        val itemUuid = item.string("uuid")
        log.debug("playlistInsert Content-Location={} from [{}]", playlistUri, clientProcName)
        hlsSession.startMediaPlaylist(
            resolveSession(request), playlistUri, hlsPlaylistHttp.playlistBaseUrl(ctx), clientProcName, null, itemUuid,
        )
    }

    private fun handleGetProperty(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val decoder = QueryStringDecoder(request.uri())
        // Senders probe playbackAccessLog / playbackErrorLog after VOD EOS (often before
        // playlistRemove). Log the property name; body stays empty until we have a verified
        // AccessLog template.
        log.debug("POST /getProperty {}", decoder.parameters().keys)
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        response.headers().add(HttpHeaderNames.CONTENT_TYPE, "text/x-apple-plist+xml")
        val body = PropertyListUtil.prepareEmptyPropertyResponse()
        response.content().writeBytes(body)
        HttpUtil.setContentLength(response, body.size.toLong())
        responses.sendResponse(ctx, request, response)
    }

    private fun parseActionPlist(request: FullHttpRequest): NSDictionary {
        val content = ByteBufUtil.getBytes(request.content())
        return try {
            BinaryPropertyListParser.parse(content) as NSDictionary
        } catch (binaryError: Exception) {
            log.debug("Action body is not binary plist, trying XML", binaryError)
            PropertyListParser.parse(content) as NSDictionary
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(ControlHandler::class.java)
        private val WHITESPACE = Regex("\\s+")
        private val LINE_BREAK = Regex("\r?\n")
    }
}
