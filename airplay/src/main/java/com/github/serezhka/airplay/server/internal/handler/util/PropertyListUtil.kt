package com.github.serezhka.airplay.server.internal.handler.util

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.github.serezhka.airplay.protocol.media.AirPlayStreamType
import com.github.serezhka.airplay.server.AirPlayConfig
import com.github.serezhka.airplay.server.Playback
import com.github.serezhka.airplay.server.discovery.AdvertisedReceiver
import org.slf4j.LoggerFactory

object PropertyListUtil {

    private val log = LoggerFactory.getLogger(PropertyListUtil::class.java)

    /**
     * Bitmask of audio codecs listed under `GET /info` `audioFormats`
     * (`audioInputFormats` / `audioOutputFormats`).
     *
     * Includes ALAC: senders pick their compression type independently in the RTSP SETUP
     * request regardless of what's advertised here (confirmed live - trimming this mask does
     * not change what a sender chooses), and the app module now decodes ALAC entirely in Kotlin
     * rather than relying on this box's broken native decoder, so there's no reason to hide it.
     */
    private const val INFO_AUDIO_FORMATS_MASK = 0x3FFFFFC

    /** `type` values pairing `audioFormats` / `audioLatencies` entries. */
    private const val INFO_AUDIO_DESCRIPTOR_TYPE_A = 100
    private const val INFO_AUDIO_DESCRIPTOR_TYPE_B = 101

    /** `displays[].features` in `GET /info` (width / height / pixels capability bits). */
    private const val INFO_DISPLAY_FEATURES = 0xE

    private const val INFO_DISPLAY_REFRESH_RATE_HZ = 60

    private const val SERVER_INFO_PROTOVERS = 1.0

    /** FCUP reverse-request client identity fields (wire constants). */
    private const val FCUP_CLIENT_INFO = 1
    private const val FCUP_CLIENT_REF = 40_030_004

    private fun NSDictionary.toXmlBytes(): ByteArray = toXMLPropertyList().toByteArray(Charsets.UTF_8)

    private fun audioDescriptor(type: Int) = NSDictionary().apply {
        put("audioInputFormats", INFO_AUDIO_FORMATS_MASK)
        put("audioOutputFormats", INFO_AUDIO_FORMATS_MASK)
        put("type", type)
    }

    private fun audioLatency(type: Int) = NSDictionary().apply {
        put("audioType", "default")
        put("inputLatencyMicros", false)
        put("type", type)
    }

    @JvmStatic
    fun prepareInfoResponse(airPlayConfig: AirPlayConfig, pairingPublicKey: ByteArray): ByteArray {
        val audioFormats = NSArray(
            audioDescriptor(INFO_AUDIO_DESCRIPTOR_TYPE_A),
            audioDescriptor(INFO_AUDIO_DESCRIPTOR_TYPE_B),
        )
        val audioLatencies = NSArray(
            audioLatency(INFO_AUDIO_DESCRIPTOR_TYPE_A),
            audioLatency(INFO_AUDIO_DESCRIPTOR_TYPE_B),
        )

        val display = NSDictionary().apply {
            put("features", INFO_DISPLAY_FEATURES)
            put("height", airPlayConfig.height)
            put("heightPhysical", false)
            put("heightPixels", airPlayConfig.height)
            put("maxFPS", airPlayConfig.fps)
            put("overscanned", false)
            put("refreshRate", INFO_DISPLAY_REFRESH_RATE_HZ)
            put("rotation", false)
            put("uuid", "e5f7a68d-7b0f-4305-984b-974f677a150b")
            put("width", airPlayConfig.width)
            put("widthPhysical", false)
            put("widthPixels", airPlayConfig.width)
        }

        val response = NSDictionary().apply {
            put("audioFormats", audioFormats)
            put("audioLatencies", audioLatencies)
            put("displays", NSArray(display))
            put("features", AdvertisedReceiver.features(airPlayConfig.isHlsEnabled))
            put("keepAliveSendStatsAsBody", 1)
            put("model", AdvertisedReceiver.MODEL)
            put("name", "Apple TV")
            put("pi", "b08f5a79-db29-4384-b456-a4784d9e6055")
            put("sourceVersion", AdvertisedReceiver.SOURCE_VERSION)
            put("statusFlags", AdvertisedReceiver.STATUS_FLAGS)
            put("vv", AdvertisedReceiver.VV)
            put("pk", NSData(pairingPublicKey))
        }
        return BinaryPropertyListWriter.writeToArray(response)
    }

    @JvmStatic
    fun prepareSetupAudioResponse(dataPort: Int, controlPort: Int): ByteArray {
        val dataStream = NSDictionary().apply {
            put("dataPort", dataPort)
            put("type", AirPlayStreamType.AUDIO_REALTIME)
            put("controlPort", controlPort)
        }
        val response = NSDictionary().apply { put("streams", NSArray(dataStream)) }
        return BinaryPropertyListWriter.writeToArray(response)
    }

    @JvmStatic
    fun prepareSetupVideoResponse(dataPort: Int, eventPort: Int, timingPort: Int): ByteArray {
        val dataStream = NSDictionary().apply {
            put("dataPort", dataPort)
            put("type", AirPlayStreamType.SCREEN)
        }
        val response = NSDictionary().apply {
            put("streams", NSArray(dataStream))
            put("eventPort", eventPort)
            put("timingPort", timingPort)
        }
        return BinaryPropertyListWriter.writeToArray(response)
    }

    /**
     * `GET /server-info` — compact HLS probe profile
     * ([AdvertisedReceiver.SERVER_INFO_FEATURES]), not the mDNS /
     * `GET /info` identity. See [AdvertisedReceiver].
     */
    @JvmStatic
    fun prepareServerInfoResponse(): ByteArray = NSDictionary().apply {
        put("features", AdvertisedReceiver.SERVER_INFO_FEATURES)
        put("protovers", SERVER_INFO_PROTOVERS)
        put("srcvers", AdvertisedReceiver.SERVER_INFO_SOURCE_VERSION.toDouble())
    }.toXmlBytes()

    @JvmStatic
    fun preparePlaybackInfoResponse(playbackInfo: Playback.Info): ByteArray {
        val response = NSDictionary()
        val duration = maxOf(0.0, playbackInfo.duration)
        var position = maxOf(0.0, playbackInfo.position)
        if (duration > 0) {
            position = minOf(position, duration)
        }
        val rate = if (playbackInfo.rate <= 0) 0.0 else 1.0
        response.put("duration", duration)
        response.put("loadedTimeRanges", NSArray(timeRange(duration, 0.0)))
        val hasDuration = duration > 0
        // Moving clock → full buffer. At end keep empty so VOD ad removal can proceed.
        val atEnd = hasDuration && position + 0.25 >= duration
        val bufferEmpty: Boolean
        val bufferFull: Boolean
        val likelyToKeepUp: Boolean
        val readyToPlay: Boolean
        if (hasDuration && rate > 0 && !atEnd) {
            bufferEmpty = false; bufferFull = true; likelyToKeepUp = true; readyToPlay = true
        } else if (hasDuration) {
            bufferEmpty = true; bufferFull = false; likelyToKeepUp = true; readyToPlay = true
        } else {
            bufferEmpty = true; bufferFull = false; likelyToKeepUp = false; readyToPlay = false
        }
        response.put("playbackBufferEmpty", bufferEmpty)
        response.put("playbackBufferFull", bufferFull)
        response.put("playbackLikelyToKeepUp", likelyToKeepUp)
        response.put("readyToPlay", readyToPlay)
        response.put("position", position)
        response.put("rate", rate)
        response.put("seekableTimeRanges", NSArray(timeRange(duration, 0.0)))
        log.debug("Playback info: duration={} position={} rate={}", duration, position, rate)
        return response.toXmlBytes()
    }

    /**
     * Reverse `POST /event` with `category=video` and a `state`
     * (`loading`, `playing`, `paused`, `stopped`).
     * When `playback` is set for `playing`, clock fields go under `params`
     * (top-level copies are ignored by the sender and leave the pause UI stuck).
     *
     * @param reason optional; working VOD EOS uses `"ended"` with `stopped`
     * @param itemUuid optional playlist-item uuid under `params`
     */
    @JvmStatic
    @JvmOverloads
    fun preparePlaybackStateEvent(
        state: String,
        reverseSessionId: Int = 1,
        itemUuid: String? = null,
        reason: String? = null,
        playback: Playback.Info? = null,
    ): ByteArray {
        val event = NSDictionary()
        event.put("category", "video")
        event.put("sessionID", reverseSessionId)
        event.put("state", state)
        if (reason != null) {
            event.put("reason", reason)
        }
        val params = NSDictionary()
        var hasParams = false
        if (itemUuid != null) {
            params.put("uuid", itemUuid)
            hasParams = true
        }
        if (playback != null && playback.duration > 0 && state == "playing") {
            val duration = playback.duration
            val position = maxOf(0.0, minOf(playback.position, duration))
            params.put("duration", duration)
            params.put("position", position)
            params.put("rate", 1.0)
            params.put("readyToPlay", true)
            params.put("stallCount", 0)
            params.put("playbackBufferEmpty", false)
            params.put("playbackBufferFull", true)
            params.put("playbackLikelyToKeepUp", true)
            params.put("loadedTimeRanges", NSArray(timeRange(duration, 0.0)))
            params.put("seekableTimeRanges", NSArray(timeRange(duration, 0.0)))
            hasParams = true
        }
        if (hasParams) {
            event.put("params", params)
        }
        return event.toXmlBytes()
    }

    private fun timeRange(duration: Double, start: Double) = NSDictionary().apply {
        put("duration", duration)
        put("start", start)
    }

    /** Typed video event (`itemPlayedToEnd`, etc.) with top-level `uuid`. */
    @JvmStatic
    fun prepareVideoTypedEvent(type: String, reverseSessionId: Int, itemUuid: String?): ByteArray =
        NSDictionary().apply {
            put("category", "video")
            put("sessionID", reverseSessionId)
            put("type", type)
            if (itemUuid != null) put("uuid", itemUuid)
        }.toXmlBytes()

    /**
     * `itemRemoved`: `sessionID` is the play UUID string (X-Apple-Session-ID), not the
     * integer reverse-event session id.
     */
    @JvmStatic
    fun prepareItemRemovedEvent(playSessionId: String, itemUuid: String?): ByteArray =
        NSDictionary().apply {
            put("category", "video")
            put("sessionID", playSessionId)
            put("type", "itemRemoved")
            if (itemUuid != null) put("uuid", itemUuid)
        }.toXmlBytes()

    @JvmStatic
    fun prepareCurrentItemChangedEvent(reverseSessionId: Int): ByteArray =
        NSDictionary().apply {
            put("category", "video")
            put("sessionID", reverseSessionId)
            put("type", "currentItemChanged")
        }.toXmlBytes()

    @JvmStatic
    fun prepareEmptyPropertyResponse(): ByteArray = NSDictionary().toXmlBytes()

    @JvmStatic
    fun prepareEventRequest(sessionId: String, listUri: String, requestId: Int): ByteArray {
        val headers = NSDictionary().apply {
            put("X-Playback-Session-Id", sessionId)
            put("User-Agent", "AppleCoreMedia/1.0.0.11B554a (Apple TV; U; CPU OS 7_0_4 like Mac OS X; en_us")
        }
        val request = NSDictionary().apply {
            put("FCUP_Response_ClientInfo", FCUP_CLIENT_INFO)
            put("FCUP_Response_ClientRef", FCUP_CLIENT_REF)
            put("FCUP_Response_Headers", headers)
            put("FCUP_Response_RequestID", requestId)
            put("FCUP_Response_URL", listUri)
            put("sessionID", 1)
        }
        val wrapper = NSDictionary().apply {
            put("request", request)
            put("sessionID", 1)
            put("type", "unhandledURLRequest")
        }
        return wrapper.toXmlBytes()
    }
}
