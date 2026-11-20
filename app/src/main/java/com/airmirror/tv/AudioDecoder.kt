package com.airmirror.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Decodes AirPlay audio (AAC-ELD while mirroring, AAC-LC / ALAC for audio-only casting)
 * with MediaCodec and plays the PCM through an AudioTrack.
 */
internal class AudioDecoder {

    private val lock = Any()
    private val freeInputs = LinkedBlockingQueue<Int>()
    private var thread: HandlerThread? = null
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var volume = 1f

    /** Non-null while active: this device's own ALAC MediaCodec is broken, so we decode it ourselves. */
    @Volatile
    private var alacDecoder: AlacDecoder? = null

    fun start(info: AudioStreamInfo, volume: Float): Unit = synchronized(lock) {
        release()
        this.volume = volume
        val (sampleRate, channels) = rateAndChannelsFor(info.audioFormat)

        if (info.compressionType == AudioStreamInfo.CompressionType.ALAC) {
            // OMX.google.alac.decoder is the only ALAC component on this box, and its
            // native_configure() fails unconditionally here even with a byte-correct magic
            // cookie (verified separately) - a broken vendor build, not a bad request from
            // us. Decode ALAC in pure Kotlin instead of depending on it.
            try {
                val t = createTrack(sampleRate, channels)
                track = t
                t.setVolume(volume)
                t.play()
                val frameLength = if (info.samplesPerFrame > 0) info.samplesPerFrame else 352
                alacDecoder = AlacDecoder(channels, frameLength)
                Log.i(TAG, "Audio stream ALAC ${sampleRate}Hz x${channels}ch (software decode, frame=$frameLength)")
            } catch (e: Exception) {
                Log.e(TAG, "Cannot start software ALAC decode", e)
                release()
            }
            return
        }

        val format = formatFor(info.compressionType, sampleRate, channels, info.samplesPerFrame)
        if (format == null) {
            Log.w(TAG, "Unsupported audio: $info")
            return
        }
        Log.i(
            TAG,
            "Audio stream ${info.compressionType} ${sampleRate}Hz x${channels}ch (negotiated ${info.audioFormat})",
        )
        try {
            val out = createTrack(sampleRate, channels)
            track = out
            out.setVolume(volume)
            out.play()
            val ht = HandlerThread("audio-decoder").also { it.start() }
            thread = ht
            val c = Codecs.createDecoder(
                format.getString(MediaFormat.KEY_MIME)!!,
                object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                        freeInputs.offer(index)
                    }

                    override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, bi: MediaCodec.BufferInfo) {
                        try {
                            val pcm = mc.getOutputBuffer(index)
                            if (pcm != null && bi.size > 0) {
                                pcm.position(bi.offset)
                                pcm.limit(bi.offset + bi.size)
                                out.write(pcm, bi.size, AudioTrack.WRITE_BLOCKING)
                            }
                            mc.releaseOutputBuffer(index, false)
                        } catch (ignored: IllegalStateException) {
                            // codec or track is being released
                        }
                    }

                    override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                        Log.e(TAG, "Audio decoder error", e)
                    }

                    override fun onOutputFormatChanged(mc: MediaCodec, f: MediaFormat) {
                        Log.i(TAG, "Audio output format $f")
                    }
                },
                Handler(ht.looper),
            )
            c.configure(format, null, null, 0)
            c.start()
            codec = c
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start audio decoder for ${info.compressionType}", e)
            release()
        }
    }

    fun feed(data: ByteArray, offset: Int, length: Int): Unit = synchronized(lock) {
        if (length <= 0) return
        val dec = alacDecoder
        if (dec != null) {
            val out = track ?: return
            val frame = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
            try {
                val pcm = dec.decode(frame)
                if (pcm.isNotEmpty()) {
                    out.write(pcm, 0, pcm.size)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Software ALAC decode failed for this frame", e)
            }
            return
        }
        val c = codec ?: return
        try {
            val index = freeInputs.poll(20, TimeUnit.MILLISECONDS) ?: return
            val buffer = c.getInputBuffer(index)
            if (buffer == null || buffer.capacity() < length) {
                c.queueInputBuffer(index, 0, 0, 0, 0)
                return
            }
            buffer.clear()
            buffer.put(data, offset, length)
            c.queueInputBuffer(index, 0, length, System.nanoTime() / 1000, 0)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Audio decoder rejected frame", e)
        }
    }

    fun setVolume(volume: Float): Unit = synchronized(lock) {
        this.volume = volume
        track?.setVolume(volume)
    }

    fun stop(): Unit = synchronized(lock) {
        release()
    }

    private fun release() {
        alacDecoder = null
        codec?.let {
            try {
                it.stop()
            } catch (ignored: Exception) {
            }
            it.release()
        }
        codec = null
        thread?.quitSafely()
        thread = null
        track?.let {
            try {
                it.stop()
            } catch (ignored: IllegalStateException) {
            }
            it.release()
        }
        track = null
        freeInputs.clear()
    }

    /** Minimal big-endian, MSB-first bit packer for building MPEG-4 AudioSpecificConfig bytes. */
    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var bitsInCurrent = 0

        fun write(value: Int, numBits: Int) {
            for (i in numBits - 1 downTo 0) {
                current = (current shl 1) or ((value ushr i) and 1)
                bitsInCurrent++
                if (bitsInCurrent == 8) {
                    out.write(current)
                    current = 0
                    bitsInCurrent = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (bitsInCurrent > 0) {
                out.write(current shl (8 - bitsInCurrent))
                current = 0
                bitsInCurrent = 0
            }
            return out.toByteArray()
        }
    }

    private companion object {
        const val TAG = "AudioDecoder"

        // Fallback only: used if the phone's SETUP request didn't say which rate/channels it picked.
        const val DEFAULT_SAMPLE_RATE = 44100
        const val DEFAULT_CHANNELS = 2

        val SAMPLE_RATE_TABLE = intArrayOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350,
        )

        /** Sample rate and channel count the phone actually asked for, not what we assume. */
        fun rateAndChannelsFor(format: AudioStreamInfo.AudioFormat?): Pair<Int, Int> = when (format) {
            null -> DEFAULT_SAMPLE_RATE to DEFAULT_CHANNELS
            AudioStreamInfo.AudioFormat.AAC_ELD_16000_1 -> 16000 to 1
            AudioStreamInfo.AudioFormat.AAC_ELD_24000_1 -> 24000 to 1
            AudioStreamInfo.AudioFormat.AAC_ELD_44100_1 -> 44100 to 1
            AudioStreamInfo.AudioFormat.AAC_ELD_48000_1 -> 48000 to 1
            AudioStreamInfo.AudioFormat.AAC_ELD_48000_2,
            AudioStreamInfo.AudioFormat.AAC_LC_48000_2,
            AudioStreamInfo.AudioFormat.PCM_48000_16_2,
            AudioStreamInfo.AudioFormat.PCM_48000_24_2,
            AudioStreamInfo.AudioFormat.ALAC_48000_16_2,
            AudioStreamInfo.AudioFormat.ALAC_48000_24_2,
            -> 48000 to 2
            AudioStreamInfo.AudioFormat.PCM_48000_16_1,
            AudioStreamInfo.AudioFormat.PCM_48000_24_1,
            -> 48000 to 1
            // Covers AAC_ELD_44100_2, AAC_LC_44100_2 and every other 44.1 kHz stereo variant.
            else -> 44100 to 2
        }

        fun formatFor(
            type: AudioStreamInfo.CompressionType?,
            sampleRate: Int,
            channels: Int,
            samplesPerFrame: Int,
        ): MediaFormat? = when (type) {
            AudioStreamInfo.CompressionType.AAC_ELD ->
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(eldAudioSpecificConfig(sampleRate, channels)))
                }
            AudioStreamInfo.CompressionType.AAC ->
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(lcAudioSpecificConfig(sampleRate, channels)))
                }
            AudioStreamInfo.CompressionType.ALAC ->
                MediaFormat.createAudioFormat("audio/alac", sampleRate, channels).apply {
                    val frameLength = if (samplesPerFrame > 0) samplesPerFrame else 352
                    setByteBuffer("csd-0", ByteBuffer.wrap(alacMagicCookie(sampleRate, channels, frameLength)))
                    // Some devices' software ALAC decoder needs this spelled out explicitly, since
                    // ALAC frames are larger than the compressed AAC ones Android usually sees.
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
                }
            else -> null
        }

        fun samplingFrequencyIndex(sampleRate: Int): Int =
            SAMPLE_RATE_TABLE.indexOf(sampleRate).takeIf { it >= 0 } ?: 4 // 44100, the common AirPlay default

        /**
         * MPEG-4 AudioSpecificConfig for ER AAC ELD (object type 39), the codec AirPlay mirroring
         * normally sends: escape object type (31) + extension (7), sampling rate, channel count,
         * then ELDSpecificConfig with frameLengthFlag=1 (480 samples/frame), no SBR, no extensions.
         * Built bit by bit so any sample rate / channel count AirPlay negotiates is handled, not
         * just 44.1 kHz stereo.
         */
        fun eldAudioSpecificConfig(sampleRate: Int, channels: Int): ByteArray = BitWriter().apply {
            write(31, 5) // audioObjectType: escape
            write(39 - 32, 6) // audioObjectTypeExt -> AOT 39 (ER AAC ELD)
            write(samplingFrequencyIndex(sampleRate), 4)
            write(channels, 4) // channelConfiguration
            write(1, 1) // frameLengthFlag: 480 samples/frame
            write(0, 1) // aacSectionDataResilienceFlag
            write(0, 1) // aacScalefactorDataResilienceFlag
            write(0, 1) // aacSpectralDataResilienceFlag
            write(0, 1) // ldSbrPresentFlag
            write(0, 1) // end of ELD extension list
        }.toByteArray()

        /** MPEG-4 AudioSpecificConfig for plain AAC-LC (object type 2), any sample rate / channel count. */
        fun lcAudioSpecificConfig(sampleRate: Int, channels: Int): ByteArray = BitWriter().apply {
            write(2, 5) // audioObjectType: AAC LC
            write(samplingFrequencyIndex(sampleRate), 4)
            write(channels, 4) // channelConfiguration
            write(0, 1) // frameLengthFlag: 1024 samples/frame
            write(0, 1) // dependsOnCoreCoder
            write(0, 1) // extensionFlag
        }.toByteArray()

        /** ALACSpecificConfig (24 bytes), with the sample rate / channel count AirPlay negotiated. */
        fun alacMagicCookie(sampleRate: Int, channels: Int, frameLength: Int): ByteArray =
            ByteBuffer.allocate(24).apply {
                putInt(frameLength)
                put(0) // compatibleVersion
                put(16) // bitDepth
                put(40) // pb
                put(10) // mb
                put(14) // kb
                put(channels.toByte())
                putShort(255) // maxRun
                putInt(0) // maxFrameBytes: unconstrained
                putInt(0) // avgBitRate: unconstrained
                putInt(sampleRate)
            }.array()

        fun createTrack(sampleRate: Int, channels: Int): AudioTrack {
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            // A trimmed buffer (1.2x minimum) saved tens of ms of lag but broke playback on this
            // hardware, so back to a safe margin. Correct, working sound first; latency later.
            val bufferSize = maxOf(minBuffer, 8192) * 2
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                return AudioTrack(
                    AudioManager.STREAM_MUSIC, sampleRate, channelMask, encoding, bufferSize, AudioTrack.MODE_STREAM,
                )
            }
            return AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .setEncoding(encoding)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    }
}
