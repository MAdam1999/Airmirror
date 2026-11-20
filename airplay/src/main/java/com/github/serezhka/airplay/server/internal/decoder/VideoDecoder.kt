package com.github.serezhka.airplay.server.internal.decoder

import com.github.serezhka.airplay.protocol.mirror.MirrorPacketLayout
import com.github.serezhka.airplay.server.internal.packet.VideoPacket
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ReplayingDecoder
import org.slf4j.LoggerFactory

class VideoDecoder : ReplayingDecoder<VideoDecoder.DecoderState>(DecoderState.READ_HEADER) {

    enum class DecoderState {
        READ_HEADER,
        READ_PAYLOAD,
    }

    private var payloadSize = 0
    private var payloadType = 0

    override fun decode(ctx: ChannelHandlerContext, input: ByteBuf, out: MutableList<Any>) {
        if (state() == DecoderState.READ_HEADER) {
            val headerBuf = input.readSlice(128)
            payloadSize = headerBuf.readUnsignedIntLE().toInt()
            payloadType = headerBuf.readUnsignedShortLE() and 0xff
            checkpoint(DecoderState.READ_PAYLOAD)
        }
        // READ_PAYLOAD (also reached straight after reading the header)
        if (payloadType == MirrorPacketLayout.PAYLOAD_VIDEO || payloadType == MirrorPacketLayout.PAYLOAD_CODEC) {
            val payloadBytes = ByteArray(payloadSize)
            input.readSlice(payloadSize).readBytes(payloadBytes)
            checkpoint(DecoderState.READ_HEADER)
            out.add(VideoPacket(payloadType, payloadSize, payloadBytes))
        } else {
            log.debug("Video packet with type: {}, length: {} bytes is skipped", payloadType, payloadSize)
            input.skipBytes(payloadSize)
            checkpoint(DecoderState.READ_HEADER)
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(VideoDecoder::class.java)
    }
}
