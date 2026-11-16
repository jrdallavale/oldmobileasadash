package com.oldmobile.biketracker

/** Read-only snapshot the UI renders at 1 Hz. The service is the only writer. */
data class Snap(
    val mode: Int = MODE_IDLE,
    val acc: Float? = null,
    val fixWall: Long = 0L,
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val bearing: Float = 0f,
    val speed: Double = 0.0,
    val avg: Double = 0.0,
    val max: Double = 0.0,
    val dist: Double = 0.0,
    val movingMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val alt: Double = Double.NaN,
    val climb: Double = 0.0,
    val descent: Double = 0.0,
    val grade: Double? = null,
    val hr: Int = 0,
    val hrZone: Int = 0,
    val hrLink: Boolean = false,
    val lapN: Int = 1,
    val lapDist: Double = 0.0,
    val paused: Boolean = true,
    val battPct: Int = -1,
    val battTemp: Double = Double.NaN,
    val charging: Boolean = false,
    val splits: List<Split> = emptyList(),
    val rideId: Long = -1,
    val alarm: Alarm? = null,
) {
    /** 0 normal, 1 warm (>=45 °C), 2 hot (>=50 °C). */
    val thermal get() = when { battTemp >= 50 -> 2; battTemp >= 45 -> 1; else -> 0 }
    val lowBattery get() = !charging && battPct in 0..19
    val gpsOk get() = acc != null && acc <= RideEngine.MAX_ACC && System.currentTimeMillis() - fixWall < 5000

    companion object {
        const val MODE_IDLE = 0
        const val MODE_LOCKING = 1
        const val MODE_RECORDING = 2
    }
}

data class Alarm(
    val phase: Phase,
    val secondsLeft: Int,
    val text: String,
    val test: Boolean,
) {
    enum class Phase { COUNTDOWN, SENDING, DONE }
}

/** Track of the current ride in local meters, appended by the service. */
class LiveTrack {
    val x = FloatList(4096); val y = FloatList(4096)
    val dist = FloatList(4096); val alt = FloatList(4096)
    fun clear() = synchronized(this) { x.clear(); y.clear(); dist.clear(); alt.clear() }
    fun add(p: TrackPoint) = synchronized(this) {
        x.add(Area.x(p.lon).toFloat()); y.add(Area.y(p.lat).toFloat())
        dist.add(p.dist.toFloat()); alt.add(p.alt.toFloat())
    }
}

object Live {
    @Volatile var snap = Snap()
    val track = LiveTrack()
    /** Set when a ride stops so the UI opens its summary. */
    @Volatile var finishedRideId = -1L
}
