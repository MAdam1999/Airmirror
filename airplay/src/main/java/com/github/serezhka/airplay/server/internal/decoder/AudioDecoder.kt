package com.github.serezhka.airplay.server.internal.decoder

import com.github.serezhka.airplay.server.internal.packet.AudioPacket
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.MessageToMessageDecoder

class AudioDecoder : MessageToMessageDecoder<ByteBuf>() {

    override fun decode(ctx: ChannelHandlerContext, msg: ByteBuf, out: MutableList<Any>) {
        val headerBytes = ByteArray(12)
        msg.readBytes(headerBytes)

        val flag = headerBytes[0].toInt() and 0xFF
        val type = headerBytes[1].toInt() and 0x7F
        val seqNumber = ((headerBytes[2].toInt() and 0xFF) shl 8) or (headerBytes[3].toInt() and 0xFF)
        val timestamp = readUnsignedIntBigEndian(headerBytes, 4)
        val ssrc = readUnsignedIntBigEndian(headerBytes, 8)

        val audioPacket = AudioPacket(
            flag = flag,
            type = type,
            sequenceNumber = seqNumber,
            timestamp = timestamp,
            ssrc = ssrc,
            encodedAudioSize = msg.readableBytes(),
        )
        msg.readBytes(audioPacket.encodedAudio, 0, msg.readableBytes())
        out.add(audioPacket)
    }

    private fun readUnsignedIntBigEndian(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
}
