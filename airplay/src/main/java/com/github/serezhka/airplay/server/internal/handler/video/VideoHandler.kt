package com.github.serezhka.airplay.server.internal.handler.video

import com.github.serezhka.airplay.protocol.FairPlaySession
import com.github.serezhka.airplay.protocol.mirror.MirrorPacketLayout
import com.github.serezhka.airplay.server.Playback
import com.github.serezhka.airplay.server.internal.packet.VideoPacket
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.slf4j.LoggerFactory

@ChannelHandler.Sharable
class VideoHandler(
    private val airPlay: FairPlaySession,
    private val dataConsumer: Playback,
) : ChannelInboundHandlerAdapter() {

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val packet = msg as VideoPacket
        try {
            if (packet.payloadType == MirrorPacketLayout.PAYLOAD_VIDEO) {
                // Type 0 only: type 1 codec config is not AES-CTR-encrypted on the wire;
                // decrypting it advances the CTR counter and corrupts every later frame.
                // Type 5 is an unencrypted streaming-report plist — same trap.
                airPlay.decryptVideo(packet.payload)
                // Never feed partial/corrupt Annex-B to the consumer.
                if (!toAnnexB(packet.payload)) return
                dataConsumer.onVideo(packet.payload)
            } else if (packet.payloadType == MirrorPacketLayout.PAYLOAD_CODEC) {
                dataConsumer.onVideo(prepareSpsPpsNALUnits(packet.payload))
            }
        } catch (e: Exception) {
            // Mirror NALs still arrive for a moment after HLS tears the video process down.
            // A stack trace per packet stalls the event loop and delays /play.
            if (e is IllegalStateException && e.message?.contains("not running") == true) {
                log.debug("Dropping video packet: {}", e.message)
            } else {
                log.error(e.message, e)
            }
        }
    }

    private fun prepareSpsPpsNALUnits(payload: ByteArray): ByteArray {
        val payloadBuf = Unpooled.wrappedBuffer(payload)
        try {
            payloadBuf.readerIndex(6)

            val spsLen = payloadBuf.readUnsignedShort().toShort().toInt()
            val sequenceParameterSet = ByteArray(spsLen)
            payloadBuf.readBytes(sequenceParameterSet)

            payloadBuf.skipBytes(1) // pps count

            val ppsLen = payloadBuf.readUnsignedShort().toShort().toInt()
            val pictureParameterSet = ByteArray(ppsLen)
            payloadBuf.readBytes(pictureParameterSet)

            val spsPpsLen = spsLen + ppsLen + 8
            log.debug("SPS PPS length: {}", spsPpsLen)
            val spsPps = ByteArray(spsPpsLen)
            spsPps[3] = 1
            System.arraycopy(sequenceParameterSet, 0, spsPps, 4, spsLen)
            spsPps[spsLen + 7] = 1
            System.arraycopy(pictureParameterSet, 0, spsPps, 8 + spsLen, ppsLen)
            return spsPps
        } finally {
            payloadBuf.release()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(VideoHandler::class.java)

        /** @return false if length-prefixed NALs are invalid (likely bad decrypt) */
        @JvmStatic
        fun toAnnexB(payload: ByteArray): Boolean {
            var idx = 0
            while (idx + 4 <= payload.size) {
                val naluSize = (payload[idx + 3].toInt() and 0xFF) or
                    ((payload[idx + 2].toInt() and 0xFF) shl 8) or
                    ((payload[idx + 1].toInt() and 0xFF) shl 16) or
                    ((payload[idx].toInt() and 0xFF) shl 24)
                if (naluSize <= 0 || idx + 4 + naluSize > payload.size) {
                    log.error("Video packet contains corrupted NAL unit. It might be decrypt error")
                    return false
                }
                payload[idx] = 0
                payload[idx + 1] = 0
                payload[idx + 2] = 0
                payload[idx + 3] = 1
                idx += naluSize + 4
            }
            return true
        }
    }
}
