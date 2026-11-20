package com.github.serezhka.airplay.server.internal.handler.audio

import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.socket.DatagramPacket
import org.slf4j.LoggerFactory

class AudioControlHandler : SimpleChannelInboundHandler<DatagramPacket>() {

    override fun channelRead0(ctx: ChannelHandlerContext, msg: DatagramPacket) {
        val content = msg.content()
        val contentLength = content.readableBytes()
        val contentBytes = ByteArray(contentLength)
        content.readBytes(contentBytes)
        val type = contentBytes[1].toInt() and 0x80.inv()
        log.debug("Got audio control packet, type: {}, length: {}", type, contentLength)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AudioControlHandler::class.java)
    }
}
