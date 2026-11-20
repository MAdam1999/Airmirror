package com.github.serezhka.airplay.server.internal.handler.audio

import com.github.serezhka.airplay.protocol.FairPlaySession
import com.github.serezhka.airplay.server.Playback
import com.github.serezhka.airplay.server.internal.packet.AudioPacket
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import org.slf4j.LoggerFactory

class AudioHandler(
    private val airPlay: FairPlaySession,
    private val dataConsumer: Playback,
) : ChannelInboundHandlerAdapter() {

    private val buffer = arrayOfNulls<AudioPacket>(512)

    private var prevSeqNum = 0
    private var started = false
    private var packetsInBuffer = 0
    /** Diagnostic: how many UDP packets this handler has seen, regardless of order/validity. */
    private var packetsSeen = 0

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        val packet = msg as AudioPacket

        val curSeqNum = packet.sequenceNumber
        packetsSeen++
        if (!started || packetsSeen <= 10 || packetsSeen % 50 == 0) {
            // Diagnostic: confirms whether packets keep arriving at all over time, as opposed to
            // just the first one, which would point at a network-layer cutoff rather than our code.
            log.warn("Audio packet #{} arrived: seq={} size={}", packetsSeen, curSeqNum, packet.encodedAudioSize)
        }
        if (started && curSeqNum <= prevSeqNum) {
            return // old/duplicate packet
        }
        if (!started) {
            // RTP sequence numbers start at an arbitrary value per RFC 3550, not necessarily 1.
            started = true
            prevSeqNum = curSeqNum - 1
        }

        buffer[curSeqNum % buffer.size] = packet
        packetsInBuffer++

        while (dequeue(prevSeqNum + 1)) {
            // keep draining contiguous packets
        }

        if (packetsInBuffer >= MAX_BACKLOG) {
            skipGap()
            while (dequeue(prevSeqNum + 1)) {
                // drain whatever the skip just made contiguous
            }
        }
    }

    private fun dequeue(curSeqNo: Int): Boolean {
        val audioPacket = buffer[curSeqNo % buffer.size]
        if (audioPacket != null && audioPacket.isAvailable) {
            val size = audioPacket.encodedAudioSize
            try {
                airPlay.decryptAudio(audioPacket.encodedAudio, size)
                // Pass offset/length so consumers can avoid an extra full-buffer copy.
                dataConsumer.onAudio(audioPacket.encodedAudio, 0, size)
            } catch (e: Exception) {
                // A Netty UDP handler's exceptions don't propagate anywhere visible by default -
                // without this, a decrypt/decode failure here would look identical to no packets
                // arriving at all.
                log.error("Audio packet seq={} size={} failed to decrypt/decode", curSeqNo, size, e)
            }
            audioPacket.isAvailable = false
            prevSeqNum = curSeqNo
            packetsInBuffer--
            return true
        }
        return false
    }

    /** Gives up on the missing packet at prevSeqNum+1 and fast-forwards to the oldest one we have. */
    private fun skipGap() {
        for (seq in prevSeqNum + 1..prevSeqNum + buffer.size) {
            val p = buffer[seq % buffer.size]
            if (p != null && p.isAvailable) {
                prevSeqNum = seq - 1
                return
            }
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        // Diagnostic: a Netty pipeline exception (e.g. in the RTP/decrypt decoder upstream of
        // this handler) otherwise never shows up under this app's own log tags.
        log.error("Audio channel pipeline exception", cause)
    }

    private companion object {
        private val log = LoggerFactory.getLogger(AudioHandler::class.java)

        /**
         * Packets buffered waiting on one missing sequence number before we give up on it. A real
         * Wi-Fi network can drop a UDP packet outright with no retransmission ever following (this
         * receiver doesn't request one) - without this, a single lost packet stalled every packet
         * behind it forever, since [dequeue] only accepted the exact next sequence number.
         * That silent, permanent stall is what "audio connects, then goes silent" turned out to be.
         */
        private const val MAX_BACKLOG = 32
    }
}
