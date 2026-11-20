package com.airmirror.tv

import android.media.MediaCodec
import android.os.Build
import android.os.Handler
import java.util.concurrent.FutureTask

/** MediaCodec helpers that work back to Android 5.0. */
internal object Codecs {

    /**
     * Creates the decoder on the handler's thread and attaches the callback there.
     * Before Android 6 `setCallback` has no handler overload: callbacks go to the
     * looper of the thread that created the codec.
     */
    fun createDecoder(mime: String, callback: MediaCodec.Callback, handler: Handler): MediaCodec {
        val task = FutureTask { MediaCodec.createDecoderByType(mime) }
        handler.post(task)
        val codec = task.get()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            codec.setCallback(callback, handler)
        } else {
            codec.setCallback(callback)
        }
        return codec
    }
}
