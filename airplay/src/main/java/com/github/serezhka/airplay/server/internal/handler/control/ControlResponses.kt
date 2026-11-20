package com.github.serezhka.airplay.server.internal.handler.control

import com.github.serezhka.airplay.server.ControlExchange
import com.github.serezhka.airplay.server.Playback
import com.github.serezhka.airplay.server.internal.handler.session.PlaylistRequest
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.rtsp.RtspHeaderNames
import io.netty.handler.codec.rtsp.RtspResponseStatuses
import io.netty.handler.codec.rtsp.RtspVersions
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Shared RTSP/HTTP response helpers and control-exchange publish for [ControlHandler].
 */
internal class ControlResponses(
    private val airPlayConsumer: Playback,
    private val pendingRequestBody: () -> ByteArray?,
) {

    fun createRtspResponse(request: FullHttpRequest): DefaultFullHttpResponse {
        val response = DefaultFullHttpResponse(RtspVersions.RTSP_1_0, RtspResponseStatuses.OK)
        response.headers().clear()

        val cSeq = request.headers().get(RtspHeaderNames.CSEQ)
        if (cSeq != null) {
            response.headers().add(RtspHeaderNames.CSEQ, cSeq)
            response.headers().add(RtspHeaderNames.SERVER, "AirTunes/220.68")
        }
        return response
    }

    fun sendResponse(ctx: ChannelHandlerContext, request: FullHttpRequest, response: FullHttpResponse) {
        HttpUtil.setContentLength(response, response.content().readableBytes().toLong())
        publishControlExchange(request, response)
        val future = ctx.writeAndFlush(response)
        if (!HttpUtil.isKeepAlive(request)) {
            future.addListener(ChannelFutureListener.CLOSE)
        }
    }

    fun publishControlExchange(request: FullHttpRequest, response: FullHttpResponse) {
        publishControlExchange(
            request.headers().get("Active-Remote") ?: request.headers().get("X-Apple-Session-ID"),
            request.protocolVersion().text(),
            request.method().name(),
            request.uri(),
            copyHeaders(request.headers()),
            pendingRequestBody(),
            response,
        )
    }

    fun publishControlExchange(pending: PlaylistRequest, response: FullHttpResponse) {
        publishControlExchange(
            pending.requestHeaders["X-Apple-Session-ID"],
            pending.protocol,
            pending.method,
            pending.uri,
            pending.requestHeaders,
            pending.requestBody,
            response,
        )
    }

    fun publishControlExchange(
        sessionId: String?,
        protocol: String?,
        method: String?,
        uri: String?,
        requestHeaders: Map<String, String>?,
        requestBody: ByteArray?,
        response: FullHttpResponse,
    ) {
        try {
            airPlayConsumer.onControlExchange(
                ControlExchange(
                    Instant.now(),
                    sessionId,
                    protocol,
                    method,
                    uri,
                    requestHeaders,
                    requestBody,
                    response.status().code(),
                    copyHeaders(response.headers()),
                    ByteBufUtil.getBytes(response.content()),
                ),
            )
        } catch (e: Exception) {
            log.warn("Failed to publish control exchange", e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(ControlResponses::class.java)

        @JvmStatic
        fun copyHeaders(headers: HttpHeaders): Map<String, String> {
            val copy = LinkedHashMap<String, String>()
            for ((key, value) in headers.iteratorAsString()) {
                copy.putIfAbsent(key, value)
            }
            return copy
        }
    }
}
