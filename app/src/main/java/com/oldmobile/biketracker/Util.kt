package com.oldmobile.biketracker

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

/** Ride-area constants: Campo Largo, Paraná, 20 km circle. */
object Area {
    const val CENTER_LAT = -25.46
    const val CENTER_LON = -49.53
    const val RADIUS_M = 10_000.0
    private val COS = cos(Math.toRadians(CENTER_LAT))

    /** Local equirectangular projection in meters around the area centre (y = north). */
    fun x(lon: Double) = ((lon - CENTER_LON) * 111_320.0 * COS)
    fun y(lat: Double) = ((lat - CENTER_LAT) * 110_574.0)
}

object Geo {
    private const val R = 6_371_000.0
    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(min(1.0, sqrt(a)))
    }
}

/** SI-only formatting, as the spec demands. Locale.US keeps the decimal point stable. */
object Fmt {
    private val L = Locale.US
    fun speed(ms: Double) = String.format(L, "%.1f", ms * 3.6)
    fun distValue(m: Double) = if (m < 1000) String.format(L, "%d", m.roundToInt()) else String.format(L, "%.2f", m / 1000)
    fun distUnit(m: Double) = if (m < 1000) "m" else "km"
    fun dist(m: Double) = distValue(m) + " " + distUnit(m)
    fun dur(ms: Long): String {
        val s = max(0L, ms / 1000)
        return String.format(L, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }
    fun mmss(ms: Long): String {
        val s = max(0L, ms / 1000)
        return if (s >= 3600) dur(ms) else String.format(L, "%d:%02d", s / 60, s % 60)
    }
    fun int(v: Double) = String.format(L, "%d", v.roundToInt())
    fun one(v: Double) = String.format(L, "%.1f", v)
    private fun fmt(p: String) = SimpleDateFormat(p, L).apply { timeZone = TimeZone.getDefault() }
    fun date(t: Long): String = fmt("yyyy-MM-dd").format(Date(t))
    fun clock(t: Long): String = fmt("HH:mm:ss").format(Date(t))
    fun hm(t: Long): String = fmt("HH:mm").format(Date(t))
    fun isoUtc(t: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", L).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    fun fileStamp(t: Long): String = fmt("yyyy-MM-dd_HHmm").format(Date(t))
}

/** Growable primitive arrays, cheap to keep a whole ride in memory. */
class FloatList(cap: Int = 1024) {
    var data = FloatArray(cap); private set
    var size = 0; private set
    fun add(v: Float) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    operator fun get(i: Int) = data[i]
    fun last() = data[size - 1]
    fun clear() { size = 0 }
}

class DoubleList(cap: Int = 1024) {
    var data = DoubleArray(cap); private set
    var size = 0; private set
    fun add(v: Double) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v }
    operator fun get(i: Int) = data[i]
    fun clear() { size = 0 }
}

fun hrZone(hr: Int, maxHr: Int): Int {
    if (maxHr <= 0 || hr <= 0) return 0
    val p = hr.toDouble() / maxHr
    return when {
        p < 0.6 -> 1
        p < 0.7 -> 2
        p < 0.8 -> 3
        p < 0.9 -> 4
        else -> 5
    }
}
