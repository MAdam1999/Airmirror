package com.airmirror.tv

import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo
import com.github.serezhka.airplay.server.ControlExchange
import com.github.serezhka.airplay.server.Playback

/**
 * Bridges the AirPlay server callbacks to Android's hardware decoders.
 * Called from Netty threads; UI changes go through [Listener].
 */
class MirrorPlayback(private val listener: Listener) : Playback {

    interface Listener {
        fun onMirroringStarted()

        fun onMirroringStopped()

        fun onVideoSize(width: Int, height: Int)
    }

    /** Lets the app hold off showing a picture from a device until someone approves it on the TV. */
    fun interface ApprovalGate {
        /** Called on a background thread. Call `decision` (true/false) once, from any thread. */
        fun requestApproval(deviceName: String, decision: (Boolean) -> Unit)
    }

    private val video = VideoDecoder()
    private val audio = AudioDecoder()
    private var observer: Playback.Observer = Playback.Observer.NONE

    @Volatile private var videoActive = false
    @Volatile private var volume = 1.0

    /** Pass `null` to allow every device without asking. */
    @Volatile var approvalGate: ApprovalGate? = null

    @Volatile private var clientName = "An iPhone"
    @Volatile private var approved = true
    @Volatile private var awaitingApproval = false
    @Volatile private var surface: Surface? = null
    @Volatile private var vodPlayer: MediaPlayer? = null
    @Volatile private var vodActive = false

    init {
        video.sizeListener = VideoDecoder.SizeListener { w, h ->
            listener.onVideoSize(w, h)
            observer.onPresented()
        }
    }

    fun setSurface(surface: Surface?) {
        this.surface = surface
        video.setSurface(surface)
        val mp = vodPlayer
        if (mp != null && surface != null) {
            try {
                mp.setSurface(surface)
            } catch (e: Exception) {
                Log.w(TAG, "Could not hand the surface to the video player", e)
            }
        }
    }

    fun release() {
        video.stop()
        audio.stop()
        releaseVodPlayer()
    }

    override fun setObserver(observer: Playback.Observer) {
        this.observer = observer
    }

    /** The library hands us every RTSP exchange; pull the device's own name out of it, if sent. */
    override fun onControlExchange(exchange: ControlExchange) {
        val name = exchange.header(exchange.requestHeaders, "X-Apple-Client-Name")
        if (!name.isNullOrBlank()) {
            clientName = name.trim()
        }
    }

    override fun onVideoFormat(videoStreamInfo: VideoStreamInfo) {
        Log.i(TAG, "Video stream: $videoStreamInfo from $clientName")
        val gate = approvalGate
        if (gate == null) {
            beginMirroring()
            return
        }
        approved = false
        awaitingApproval = true
        val name = clientName
        gate.requestApproval(name) { ok ->
            awaitingApproval = false
            approved = ok
            if (ok) {
                Log.i(TAG, "Approved: $name")
                beginMirroring()
            } else {
                Log.i(TAG, "Not approved, staying blank: $name")
            }
        }
    }

    /**
     * Mirroring doesn't always go through [onVideoFormat]; some senders go straight to
     * [onVideo], which starts a session itself the same way. Both paths call this, so the
     * surface handoff away from video-casting happens no matter which one a given sender uses.
     */
    private fun beginMirroring() {
        // Mirroring and video-casting can't both hold the screen surface at once; let go of
        // whichever one the previous session was using before this one tries to grab it.
        releaseVodPlayer()
        videoActive = true
        listener.onMirroringStarted()
    }

    override fun onVideo(bytes: ByteArray) {
        if (!approved) return // waiting on (or declined) approval; drop frames instead of decoding them
        if (!videoActive) {
            beginMirroring()
        }
        video.feed(bytes)
    }

    override fun onVideoSrcDisconnect() {
        Log.i(TAG, "Video source disconnected")
        videoActive = false
        approved = true
        awaitingApproval = false
        video.stop()
        listener.onMirroringStopped()
    }

    override fun onAudioFormat(audioStreamInfo: AudioStreamInfo) {
        if (videoActive || vodActive || awaitingApproval) {
            // The audio half of a mirroring or video-cast session already gated via its video.
            if (!approved) return
            Log.i(TAG, "Audio stream: $audioStreamInfo")
            audio.start(audioStreamInfo, volume.toFloat())
            return
        }
        // A standalone "share audio to this TV as a speaker" connection (Control Center), with
        // no video of its own to gate it — ask (or auto-pass if already approved) on its own.
        val gate = approvalGate
        if (gate == null) {
            Log.i(TAG, "Audio stream: $audioStreamInfo")
            audio.start(audioStreamInfo, volume.toFloat())
            return
        }
        val name = clientName
        gate.requestApproval(name) { ok ->
            if (ok) {
                Log.i(TAG, "Audio stream (speaker cast): $audioStreamInfo")
                audio.start(audioStreamInfo, volume.toFloat())
            } else {
                Log.i(TAG, "Speaker cast not approved: $name")
            }
        }
    }

    override fun onAudio(bytes: ByteArray) {
        if (!approved) return
        audio.feed(bytes, 0, bytes.size)
    }

    override fun onAudio(bytes: ByteArray?, offset: Int, length: Int) {
        if (!approved || bytes == null) return
        audio.feed(bytes, offset, length)
    }

    override fun onAudioSrcDisconnect() {
        Log.i(TAG, "Audio source disconnected")
        audio.stop()
    }

    override fun onVolume(volumeLinear: Double) {
        volume = volumeLinear
        audio.setVolume(volumeLinear.toFloat())
    }

    override fun volume(): Double = volume

    // --- Single-video casting: the AirPlay button inside Safari/YouTube/etc, not screen mirroring ---

    override fun onPlaylist(playlistUri: String) {
        Log.i(TAG, "Cast video: $playlistUri from $clientName")
        val gate = approvalGate
        if (gate == null) {
            startVodPlayback(playlistUri)
            return
        }
        val name = clientName
        gate.requestApproval(name) { ok ->
            if (ok) {
                startVodPlayback(playlistUri)
            } else {
                Log.i(TAG, "Cast not approved: $name")
            }
        }
    }

    private fun startVodPlayback(playlistUri: String) {
        releaseVodPlayer()
        // Same reasoning in the other direction: let go of the mirroring decoder's hold on the
        // surface before the video player tries to take it.
        video.stop()
        // On this hardware the just-released decoder's hold on the Surface can take a moment to
        // actually let go, the same race VideoDecoder.createCodec retries for. A MediaPlayer that
        // hits that race here throws from setSurface()/prepareAsync() rather than failing softly,
        // so give it the same bounded retry.
        for (attempt in 1..2) {
            if (attemptStartVod(playlistUri)) return
            if (attempt < 2) {
                Log.w(TAG, "Retrying video cast start, previous surface owner may still be releasing it")
                try {
                    Thread.sleep(150)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /** @return true if the player started preparing; false means the caller may retry. */
    private fun attemptStartVod(playlistUri: String): Boolean {
        val mp = MediaPlayer()
        try {
            mp.setDataSource(playlistUri)
            surface?.let { mp.setSurface(it) }
            mp.setOnPreparedListener { p ->
                try {
                    p.start()
                } catch (e: Exception) {
                    Log.w(TAG, "Video player would not start", e)
                }
                vodActive = true
                listener.onMirroringStarted()
                observer.onPresented()
            }
            mp.setOnVideoSizeChangedListener { _, w, h ->
                if (w > 0 && h > 0) {
                    listener.onVideoSize(w, h)
                }
            }
            mp.setOnCompletionListener { observer.onEnded() }
            mp.setOnSeekCompleteListener { observer.onSeekDisplayed() }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Video player error $what/$extra for $playlistUri")
                true // we handled it; suppresses the default "unknown error" dialog-less crash path
            }
            mp.prepareAsync()
            vodPlayer = mp
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start video casting for $playlistUri", e)
            // Unlike the success path, nothing else holds a reference to mp here - leaving it
            // unreleased would leak its hold on the Surface and jam every mirroring/casting
            // attempt after it until the app restarts.
            try {
                mp.reset()
            } catch (ignored: Exception) {
            }
            try {
                mp.release()
            } catch (ignored: Exception) {
            }
            return false
        }
    }

    override fun onPlaylistRemoved() {
        Log.i(TAG, "Cast playback removed")
        releaseVodPlayer()
        listener.onMirroringStopped()
    }

    override fun onPause() {
        val mp = vodPlayer ?: return
        try {
            mp.pause()
        } catch (e: Exception) {
            Log.w(TAG, "Could not pause the video player", e)
        }
    }

    override fun onResume() {
        val mp = vodPlayer ?: return
        try {
            mp.start()
        } catch (e: Exception) {
            Log.w(TAG, "Could not resume the video player", e)
        }
    }

    override fun onSeek(positionSeconds: Double) {
        val mp = vodPlayer ?: return
        try {
            mp.seekTo((positionSeconds * 1000).toInt())
        } catch (e: Exception) {
            Log.w(TAG, "Could not seek the video player", e)
        }
    }

    override fun info(): Playback.Info {
        val mp = vodPlayer
        if (mp == null || !vodActive) return Playback.Info.idle()
        return try {
            val duration = maxOf(0, mp.duration) / 1000.0
            val position = maxOf(0, mp.currentPosition) / 1000.0
            val rate = if (mp.isPlaying) 1.0 else 0.0
            Playback.Info(duration, position, rate)
        } catch (e: Exception) {
            Playback.Info.idle()
        }
    }

    private fun releaseVodPlayer() {
        val mp = vodPlayer
        vodPlayer = null
        vodActive = false
        if (mp != null) {
            try {
                mp.reset()
            } catch (ignored: Exception) {
                // player was in a state that doesn't allow reset; release() below still works
            }
            try {
                mp.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing the video player", e)
            }
        }
    }

    private companion object {
        const val TAG = "MirrorPlayback"
    }
}
