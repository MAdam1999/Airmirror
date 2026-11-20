package com.airmirror.tv

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hardware H.264 decoder for the mirror stream. Input is Annex-B access units; the
 * SPS/PPS unit (codec config) arrives first and again whenever the phone rotates.
 */
internal class VideoDecoder {

    fun interface SizeListener {
        fun onSize(width: Int, height: Int)
    }

    private val lock = Any()
    private val freeInputs = LinkedBlockingQueue<Int>()
    private var thread: HandlerThread? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var config: ByteArray? = null
    private var waitingForKeyFrame = false
    private var firstFrameQueued = false
    var sizeListener: SizeListener? = null

    fun setSurface(surface: Surface?): Unit = synchronized(lock) {
        this.surface = surface
        releaseCodec()
        if (surface != null && config != null) {
            // Resume after the surface came back; frames show again from the next key frame.
            createCodec()
        }
    }

    fun feed(accessUnit: ByteArray) {
        if (accessUnit.size < 5) return
        val nalType = accessUnit[4].toInt() and 0x1F
        synchronized(lock) {
            if (nalType == NAL_SPS) {
                if (codec != null && config.contentEquals(accessUnit)) return
                config = accessUnit.clone()
                releaseCodec()
                if (surface != null) {
                    createCodec()
                }
                return
            }
            if (codec == null) return
            if (waitingForKeyFrame) {
                if (!containsIdr(accessUnit)) return
                waitingForKeyFrame = false
            }
            queue(accessUnit, 0)
        }
    }

    fun stop(): Unit = synchronized(lock) {
        config = null
        releaseCodec()
    }

    /**
     * A fresh decoder lands on the same [Surface] a just-released one (mirroring vs. video
     * casting swap) was using. On slower devices the old one's native teardown can still be
     * finishing when this one tries to attach, which fails with "already connected" even though
     * we already told it to let go — so give it one short, bounded retry before giving up.
     */
    private fun createCodec() {
        for (attempt in 1..2) {
            if (attemptCreateCodec()) return
            if (attempt < 2) {
                Log.w(TAG, "Retrying decoder start, previous surface owner may still be releasing it")
                try {
                    Thread.sleep(150)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /** @return true if the decoder started; false means the caller may retry. */
    private fun attemptCreateCodec(): Boolean {
        try {
            val ht = HandlerThread("video-decoder").also { it.start() }
            thread = ht
            freeInputs.clear()
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            config?.let { setCodecData(format, it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val c = Codecs.createDecoder(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
                        freeInputs.offer(index)
                    }

                    override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        try {
                            mc.releaseOutputBuffer(index, true)
                        } catch (ignored: IllegalStateException) {
                            // codec is being released
                        }
                    }

                    override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                        Log.e(TAG, "Decoder error", e)
                    }

                    override fun onOutputFormatChanged(mc: MediaCodec, f: MediaFormat) {
                        var w = f.getInteger(MediaFormat.KEY_WIDTH)
                        var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (f.containsKey("crop-left") && f.containsKey("crop-right")) {
                            w = f.getInteger("crop-right") + 1 - f.getInteger("crop-left")
                        }
                        if (f.containsKey("crop-top") && f.containsKey("crop-bottom")) {
                            h = f.getInteger("crop-bottom") + 1 - f.getInteger("crop-top")
                        }
                        Log.i(TAG, "Video size ${w}x$h")
                        sizeListener?.onSize(w, h)
                    }
                },
                Handler(ht.looper),
            )
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            waitingForKeyFrame = true
            firstFrameQueued = false
            Log.i(TAG, "H.264 decoder started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start H.264 decoder", e)
            releaseCodec()
            return false
        }
    }

    private fun queue(data: ByteArray, flags: Int) {
        val c = codec ?: return
        try {
            // Some boxes need a few hundred ms before the first input buffer is free.
            val index = freeInputs.poll(if (firstFrameQueued) 50L else 2000L, TimeUnit.MILLISECONDS)
            if (index == null) {
                Log.w(TAG, "Decoder busy, dropping frame")
                return
            }
            val buffer = c.getInputBuffer(index)
            if (buffer == null || buffer.capacity() < data.size) {
                Log.w(TAG, "Frame too large for decoder buffer: ${data.size}")
                c.queueInputBuffer(index, 0, 0, 0, 0)
                return
            }
            buffer.clear()
            buffer.put(data)
            c.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, flags)
            if (!firstFrameQueued) {
                firstFrameQueued = true
                Log.i(TAG, "First frame queued, ${data.size} bytes")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Decoder rejected frame", e)
        }
    }

    private fun releaseCodec() {
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
        freeInputs.clear()
    }

    private companion object {
        const val TAG = "VideoDecoder"
        const val NAL_SPS = 7

        fun isStartCode(b: ByteArray, i: Int) =
            b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1

        /** Splits the Annex-B SPS/PPS unit into csd-0 (SPS) and csd-1 (PPS). */
        fun setCodecData(format: MediaFormat, annexB: ByteArray) {
            var sps = -1
            var pps = -1
            var i = 0
            while (i + 4 < annexB.size) {
                if (isStartCode(annexB, i)) {
                    val type = annexB[i + 4].toInt() and 0x1F
                    if (type == NAL_SPS && sps < 0) {
                        sps = i
                    } else if (type == 8 && pps < 0) {
                        pps = i
                    }
                }
                i++
            }
            if (sps < 0 || pps < 0) return
            val spsEnd = if (sps < pps) pps else annexB.size
            val ppsEnd = if (pps < sps) sps else annexB.size
            format.setByteBuffer("csd-0", ByteBuffer.wrap(annexB.copyOfRange(sps, spsEnd)))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(annexB.copyOfRange(pps, ppsEnd)))
        }

        fun containsIdr(au: ByteArray): Boolean {
            var i = 0
            while (i + 4 < au.size) {
                if (isStartCode(au, i)) {
                    when (au[i + 4].toInt() and 0x1F) {
                        5 -> return true
                        1 -> return false
                    }
                }
                i++
            }
            return false
        }
    }
}
