package com.github.serezhka.airplay.protocol.rtsp

import com.dd.plist.BinaryPropertyListParser
import com.dd.plist.NSDictionary
import com.github.serezhka.airplay.protocol.media.AirPlayStreamType
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo
import com.github.serezhka.airplay.protocol.media.MediaStreamInfo
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo
import net.i2p.crypto.eddsa.Utils
import org.slf4j.LoggerFactory
import java.io.InputStream

class RtspMediaSetup {

    var ekey: ByteArray? = null
        private set
    var eiv: ByteArray? = null
        private set
    var streamConnectionID: String? = null
        private set

    fun setup(rtspSetupPayload: InputStream): MediaStreamInfo? {
        val setup = BinaryPropertyListParser.parse(rtspSetupPayload) as NSDictionary
        return when {
            setup.containsKey("ekey") || setup.containsKey("eiv") -> {
                ekey = setup["ekey"]?.toJavaObject() as ByteArray?
                eiv = setup["eiv"]?.toJavaObject() as ByteArray?
                if (log.isDebugEnabled) {
                    log.debug("Encrypted AES key: {}, iv: {}", ekey?.let(Utils::bytesToHex), eiv?.let(Utils::bytesToHex))
                }
                null
            }
            setup.containsKey("streams") -> {
                if (log.isDebugEnabled) {
                    log.debug("RTSP SETUP streams:\n{}", setup.toXMLPropertyList())
                }
                getMediaStreamInfo(setup)
            }
            else -> {
                if (log.isDebugEnabled) {
                    log.error("Unknown RTSP setup content\n{}", setup.toXMLPropertyList())
                } else {
                    log.error("Unknown RTSP setup content")
                }
                null
            }
        }
    }

    fun teardown(rtspTeardownPayload: InputStream): MediaStreamInfo? {
        val teardown = BinaryPropertyListParser.parse(rtspTeardownPayload) as NSDictionary
        if (log.isDebugEnabled) {
            log.debug("RTSP TEARDOWN streams:\n{}", teardown.toXMLPropertyList())
        }
        return if (teardown.containsKey("streams")) getMediaStreamInfo(teardown) else null
    }

    private fun getMediaStreamInfo(request: NSDictionary): MediaStreamInfo? {
        val streams = request["streams"]!!.toJavaObject() as Array<*>
        if (streams.size > 1) {
            log.warn("Request contains more than one stream info")
        }

        val stream = streams[0] as Map<*, *>
        return when (val type = stream["type"] as Int) {
            // video stream (screen mirroring)
            AirPlayStreamType.SCREEN -> {
                if (stream.containsKey("streamConnectionID")) {
                    val rawId = stream["streamConnectionID"]
                    val id = if (rawId is Number) rawId.toLong() else rawId.toString().toLong()
                    streamConnectionID = id.toULong().toString() // unsigned, works on every API level
                }
                VideoStreamInfo(streamConnectionID)
            }

            // realtime audio
            AirPlayStreamType.AUDIO_REALTIME -> {
                val compressionType = (stream["ct"] as Int?)?.let {
                    AudioStreamInfo.CompressionType.fromCode(it.toLong())
                }
                val audioFormat = (stream["audioFormat"] as Int?)?.let {
                    AudioStreamInfo.AudioFormat.fromCode(it.toLong())
                }
                val samplesPerFrame = (stream["spf"] as Int?) ?: 0
                AudioStreamInfo(compressionType, audioFormat, samplesPerFrame)
            }

            else -> {
                log.error("Unknown stream type: {}", type)
                null
            }
        }
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RtspMediaSetup::class.java)
    }
}
