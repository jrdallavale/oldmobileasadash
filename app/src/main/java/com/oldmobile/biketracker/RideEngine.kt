package com.oldmobile.biketracker

import kotlin.math.max
import kotlin.math.sqrt

/** One raw GPS fix. elapsedMs is monotonic, wallMs is the clock time stored in the database. */
data class Fix(
    val wallMs: Long,
    val elapsedMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val speed: Float?,          // m/s from Doppler, null if missing
    val speedAccuracy: Float?,
    val bearing: Float?,
    val gpsAlt: Double?,
)

/** An accepted, filtered point that goes to the database and the live charts. */
data class TrackPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val alt: Double,
    val speed: Double,
    val dist: Double,
    val hr: Int,
    val moving: Boolean,
)

data class Split(val km: Int, val movingMs: Long, val elapsedMs: Long, val climb: Double)
data class Lap(val n: Int, val t: Long, val dist: Double, val movingMs: Long, val elapsedMs: Long)

/** Scalar Kalman filter for speed. */
class SpeedKalman(private val q: Double = 0.6) {
    private var x = 0.0
    private var p = -1.0
    fun update(z: Double, r: Double, dtS: Double): Double {
        if (p < 0) { x = z; p = r; return x }
        p += q * dtS
        val k = p / (p + r)
        x += k * (z - x)
        p *= (1 - k)
        return max(0.0, x)
    }
}

/** Constant-position Kalman filter on local meters, variance driven by the fix accuracy. */
class PosKalman(private val qMs: Double = 3.0) {
    var x = 0.0; private set
    var y = 0.0; private set
    private var v = -1.0
    fun update(mx: Double, my: Double, acc: Double, dtS: Double) {
        val r = max(1.0, acc * acc)
        if (v < 0) { x = mx; y = my; v = r; return }
        v += dtS * qMs * qMs
        val k = v / (v + r)
        x += k * (mx - x); y += k * (my - y)
        v *= (1 - k)
    }
}

/**
 * Pure ride logic: filtering, distance, auto-pause, climb, grade, splits, laps, HR zones.
 * No Android types here so it runs in unit tests.
 */
class RideEngine(private val terrain: (Double, Double) -> Double?, private val maxHr: () -> Int) {
    // live totals
    var distM = 0.0; private set
    var movingMs = 0L; private set
    var maxSpeed = 0.0; private set
    var speed = 0.0; private set
    var climb = 0.0; private set
    var descent = 0.0; private set
    var alt = Double.NaN; private set
    var grade: Double? = null; private set
    var maxGrade = 0.0; private set
    var paused = true; private set
    var bearing = 0f; private set
    var lat = Double.NaN; private set
    var lon = Double.NaN; private set
    var hr = 0
    var hrSum = 0L; private set
    var hrSamples = 0L; private set
    var hrMax = 0; private set
    val zoneMs = LongArray(6)
    val splits = ArrayList<Split>()
    val laps = ArrayList<Lap>()
    var startWall = 0L; private set
    var startElapsed = -1L; private set
    var lastElapsed = -1L; private set

    private val sk = SpeedKalman()
    private val pk = PosKalman()
    private var lastRaw: Fix? = null
    private var anchorX = Double.NaN
    private var anchorY = Double.NaN
    private var slowSinceMs = -1L
    private var altRef = Double.NaN
    private val gDist = DoubleList(); private val gAlt = DoubleList()
    private var gStart = 0
    private var lapStartDist = 0.0; private var lapStartMoving = 0L; private var lapStartElapsed = 0L
    private var splitMoving = 0L; private var splitElapsed = 0L; private var splitClimb = 0.0

    fun start(wallMs: Long, elapsedMs: Long) {
        startWall = wallMs; startElapsed = elapsedMs; lastElapsed = elapsedMs
    }

    fun elapsedMs(nowElapsed: Long) = if (startElapsed < 0) 0L else nowElapsed - startElapsed
    val avgSpeed get() = if (movingMs > 0) distM / (movingMs / 1000.0) else 0.0
    val lapDist get() = distM - lapStartDist
    val avgHr get() = if (hrSamples > 0) (hrSum / hrSamples).toInt() else 0

    /** Returns the accepted point, or null when the fix is rejected. */
    fun onFix(f: Fix): TrackPoint? {
        if (f.accuracy > MAX_ACC) return null
        val prev = lastRaw
        if (prev != null) {
            val dt = (f.elapsedMs - prev.elapsedMs) / 1000.0
            if (dt <= 0) return null
            val jump = Geo.dist(prev.lat, prev.lon, f.lat, f.lon)
            if (jump / dt > MAX_JUMP) return null
        }
        val dtS = if (prev == null) 1.0 else ((f.elapsedMs - prev.elapsedMs) / 1000.0)
        lastRaw = f

        // speed
        val rawSpeed = f.speed?.toDouble() ?: if (prev != null) Geo.dist(prev.lat, prev.lon, f.lat, f.lon) / dtS else 0.0
        val r = f.speedAccuracy?.toDouble()?.let { max(0.3, it) * max(0.3, it) } ?: 1.0
        speed = sk.update(rawSpeed, r, dtS)
        if (f.bearing != null && speed > 1.5) bearing = f.bearing

        // auto-pause: below 3 km/h for 5 s pauses, above 5 km/h resumes
        if (speed * 3.6 > RESUME_KMH) { paused = false; slowSinceMs = -1 }
        else if (speed * 3.6 < PAUSE_KMH) {
            if (slowSinceMs < 0) slowSinceMs = f.elapsedMs
            if (f.elapsedMs - slowSinceMs >= PAUSE_AFTER_MS) paused = true
        } else slowSinceMs = -1

        val dtMs = if (lastElapsed < 0) 0L else (f.elapsedMs - lastElapsed).coerceIn(0, 5000)
        lastElapsed = f.elapsedMs
        if (!paused) {
            movingMs += dtMs
            if (speed > maxSpeed) maxSpeed = speed
        }

        // position + distance with a 2 m gate on the filtered position
        pk.update(Area.x(f.lon), Area.y(f.lat), f.accuracy.toDouble(), dtS)
        if (anchorX.isNaN()) { anchorX = pk.x; anchorY = pk.y }
        val dx = pk.x - anchorX; val dy = pk.y - anchorY
        val step = sqrt(dx * dx + dy * dy)
        if (step >= MIN_STEP) {
            if (!paused) distM += step
            anchorX = pk.x; anchorY = pk.y
        }
        lat = f.lat; lon = f.lon

        // altitude from terrain, GPS as fallback
        val a = terrain(f.lat, f.lon) ?: f.gpsAlt ?: if (alt.isNaN()) 0.0 else alt
        alt = a
        if (altRef.isNaN()) altRef = a
        val dA = a - altRef
        if (dA >= CLIMB_STEP) { climb += dA; splitClimb += dA; altRef = a }
        else if (-dA >= CLIMB_STEP) { descent -= dA; altRef = a }

        // grade over a 50 m window
        if (gDist.size == 0 || distM - gDist[gDist.size - 1] >= 1.0) { gDist.add(distM); gAlt.add(a) }
        while (gStart + 1 < gDist.size && distM - gDist[gStart + 1] >= GRADE_WINDOW) gStart++
        grade = if (gDist.size > 1 && distM - gDist[gStart] >= GRADE_WINDOW) {
            val g = (a - gAlt[gStart]) / (distM - gDist[gStart]) * 100
            if (g > maxGrade) maxGrade = g
            g
        } else null

        // heart rate
        if (hr > 0) {
            hrSum += hr; hrSamples++
            if (hr > hrMax) hrMax = hr
            zoneMs[hrZone(hr, maxHr())] += dtMs
        }

        // splits per km
        val el = elapsedMs(f.elapsedMs)
        while (distM >= (splits.size + 1) * 1000.0) {
            splits.add(Split(splits.size + 1, movingMs - splitMoving, el - splitElapsed, splitClimb))
            splitMoving = movingMs; splitElapsed = el; splitClimb = 0.0
        }
        return TrackPoint(f.wallMs, f.lat, f.lon, a, speed, distM, hr, !paused)
    }

    /** Marks the end of the current lap. */
    fun lap(wallMs: Long, nowElapsed: Long): Lap {
        val el = elapsedMs(nowElapsed)
        val l = Lap(laps.size + 1, wallMs, distM - lapStartDist, movingMs - lapStartMoving, el - lapStartElapsed)
        laps.add(l)
        lapStartDist = distM; lapStartMoving = movingMs; lapStartElapsed = el
        return l
    }

    /** Speed reported to the UI: zero when the fix stream died. */
    fun liveSpeed(nowElapsed: Long) = if (lastElapsed < 0 || nowElapsed - lastElapsed > 4000) 0.0 else speed

    companion object {
        const val MAX_ACC = 20f
        const val MAX_JUMP = 25.0
        const val MIN_STEP = 2.0
        const val PAUSE_KMH = 3.0
        const val RESUME_KMH = 5.0
        const val PAUSE_AFTER_MS = 5000L
        const val CLIMB_STEP = 3.0
        const val GRADE_WINDOW = 50.0
    }
}

/** Energy estimate from physics: rolling + aero + gravity + kinetic, positive work only, kJ. */
object Energy {
    fun kJ(points: List<TrackPoint>, totalMassKg: Double): Double {
        val g = 9.81; val crr = 0.005; val cda = 0.45; val rho = 1.1 // ~1000 m altitude air
        var e = 0.0
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            if (!b.moving) continue
            val dt = (b.t - a.t) / 1000.0
            if (dt <= 0 || dt > 10) continue
            val v = (a.speed + b.speed) / 2
            val ds = b.dist - a.dist
            val p = crr * totalMassKg * g * v + 0.5 * rho * cda * v * v * v
            val climbW = if (ds > 0.5) totalMassKg * g * (b.alt - a.alt) / dt else 0.0
            val kin = 0.5 * totalMassKg * (b.speed * b.speed - a.speed * a.speed) / dt
            e += max(0.0, p + climbW + kin) * dt
        }
        return e / 1000.0
    }
}
