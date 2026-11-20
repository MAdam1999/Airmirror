package com.github.serezhka.airplay.server.internal.handler.control

import com.github.serezhka.airplay.server.Playback
import com.github.serezhka.airplay.server.internal.handler.session.HlsPlaylistState

/**
 * Builds [Playback.Info] for `GET /playback-info` from player clock + HLS session.
 * Pure: no Netty / side effects.
 */
internal object HlsPlaybackInfo {

    fun resolve(hls: HlsPlaylistState?, fromPlayer: Playback.Info): Playback.Info {
        var duration = fromPlayer.duration
        var position = fromPlayer.position
        var rate = fromPlayer.rate
        if (hls != null && hls.isWaitingForMasterChange && hls.isLivePlaylist) {
            // Live post-EOS gap: duration=0 → buffer-empty / not ready (loading).
            return Playback.Info(0.0, 0.0, 0.0)
        }
        if (hls != null) {
            if (hls.isLivePlaylist) {
                duration = 0.0
            } else {
                val playlistDur = hls.mediaDurationSeconds
                if (playlistDur > 0) {
                    duration = playlistDur
                } else if (duration > 600) {
                    // Demux sometimes reports multi-hour clocks for short items.
                    duration = 0.0
                }
            }
            if (hls.isWaitingForMasterChange && duration > 0) {
                // VOD end pin: stay at D/D/rate=1. Prefer the shorter player length if
                // a later FCUP body inflated mediaDurationSeconds.
                val playerDur = fromPlayer.duration
                if (playerDur > 0.5 && playerDur + 0.25 < duration) {
                    duration = playerDur
                }
                position = duration
                rate = 1.0
            } else {
                if (duration > 0) {
                    position = minOf(position, duration)
                }
                rate = if (hls.playbackRate <= 0) 0.0 else 1.0
            }
            return Playback.Info(duration, position, rate)
        }
        if (duration > 600) {
            duration = 0.0
        }
        return Playback.Info(duration, position, rate)
    }
}
