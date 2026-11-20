package com.github.serezhka.airplay.server.internal.handler.session

/**
 * Holds a bare `rate=0` open long enough that a following `/scrub` is not
 * committed as a real pause. Observed scrub gaps were ~0.2–1.8s before `/scrub`.
 */
internal class HlsRateZeroWindow {

    var isPending = false
        private set
    private var playbackInfosSinceArm = 0
    private var armedAtNanos = 0L

    fun arm() {
        isPending = true
        playbackInfosSinceArm = 0
        armedAtNanos = System.nanoTime()
    }

    fun clear() {
        isPending = false
        playbackInfosSinceArm = 0
        armedAtNanos = 0
    }

    /**
     * @return true when this poll may commit a pause: at least two playback-info probes
     *         **and** [PAUSE_DELAY_NS] since arm.
     */
    fun notePlaybackInfoForPause(): Boolean {
        if (!isPending) return false
        playbackInfosSinceArm++
        if (playbackInfosSinceArm < 2) return false
        val armedAt = armedAtNanos
        return armedAt > 0 && System.nanoTime() - armedAt >= PAUSE_DELAY_NS
    }

    /** Test helper: pretend `elapsedNs` already passed since arm. */
    fun advanceForTest(elapsedNs: Long) {
        if (armedAtNanos > 0) {
            armedAtNanos = System.nanoTime() - elapsedNs
        }
    }

    companion object {
        /** Minimum hold before a bare `rate=0` becomes a real pause. */
        const val PAUSE_DELAY_NS = 2_500_000_000L
    }
}
