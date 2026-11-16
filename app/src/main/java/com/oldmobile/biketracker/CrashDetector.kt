package com.oldmobile.biketracker

/**
 * Three checks in sequence before an alarm:
 *  1. Impact: acceleration peak >= 3.5 g while the bike moved >= 10 km/h in the last 5 s
 *     (a phone dropped at a stop fails here).
 *  2. Stopped: speed falls below 3 km/h within 15 s of the impact (a pothole fails here).
 *  3. Still: acceleration variation below 0.3 g for 30 s.
 * Pure logic; times are monotonic milliseconds.
 */
class CrashDetector {
    enum class Stage { WATCH, IMPACT, STILL }

    var stage = Stage.WATCH; private set
    private var impactAt = 0L
    private var stillStart = 0L
    private var minG = 0.0
    private var maxG = 0.0
    private val speedT = LongArray(64)
    private val speedV = DoubleArray(64)
    private var speedN = 0
    private var lastKmh = 0.0

    fun reset() { stage = Stage.WATCH }

    fun onSpeed(tMs: Long, kmh: Double) {
        speedT[speedN % 64] = tMs; speedV[speedN % 64] = kmh; speedN++
        lastKmh = kmh
        when (stage) {
            Stage.IMPACT -> if (kmh < STOP_KMH) beginStill(tMs) else if (tMs - impactAt > STOP_WINDOW_MS) reset()
            Stage.STILL -> if (kmh > MOVE_KMH) reset()
            else -> {}
        }
    }

    private fun recentMaxKmh(tMs: Long): Double {
        var m = 0.0
        for (i in 0 until minOf(speedN, 64)) if (tMs - speedT[i] <= 5000 && speedV[i] > m) m = speedV[i]
        return m
    }

    private fun beginStill(tMs: Long) { stage = Stage.STILL; stillStart = tMs; minG = Double.MAX_VALUE; maxG = 0.0 }

    /** Feed one accelerometer magnitude in g. Returns true exactly when the alarm should start. */
    fun onAccel(tMs: Long, g: Double): Boolean {
        when (stage) {
            Stage.WATCH -> if (g >= IMPACT_G && recentMaxKmh(tMs) >= MIN_RIDE_KMH) {
                stage = Stage.IMPACT; impactAt = tMs
                if (lastKmh < STOP_KMH) beginStill(tMs)
            }
            Stage.IMPACT -> if (tMs - impactAt > STOP_WINDOW_MS) reset()
            Stage.STILL -> {
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (maxG - minG >= STILL_G) {
                    // still moving around: restart the stillness clock, give up after 2 min
                    if (tMs - impactAt > 120_000) reset() else { stillStart = tMs; minG = g; maxG = g }
                } else if (tMs - stillStart >= STILL_MS) {
                    reset()
                    return true
                }
            }
        }
        return false
    }

    companion object {
        const val IMPACT_G = 3.5
        const val MIN_RIDE_KMH = 10.0
        const val STOP_KMH = 3.0
        const val MOVE_KMH = 5.0
        const val STOP_WINDOW_MS = 15_000L
        const val STILL_G = 0.3
        const val STILL_MS = 30_000L
    }
}
