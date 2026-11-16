package com.oldmobile.biketracker

import kotlin.math.max

/** Everything the summary and detail screens show, computed once from the stored points. */
class Report(val ride: RideRow, val points: List<TrackPoint>, val laps: List<Lap>, massKg: Double) {
    val splits = ArrayList<Split>()
    val chartDist: FloatArray
    val chartSpeed: FloatArray
    val chartAlt: FloatArray
    val chartHr: FloatArray
    val hasHr = points.any { it.hr > 0 }
    val energyKj = Energy.kJ(points, massKg)
    val minAlt: Double
    val maxAlt: Double

    init {
        // km splits from the stored points
        var km = 1; var lastMove = 0L; var movingAcc = 0L; var lastT = points.firstOrNull()?.t ?: 0L
        var lastEl = lastT; var climbAcc = 0.0; var altRef = points.firstOrNull()?.alt ?: 0.0
        for (i in points.indices) {
            val p = points[i]
            if (i > 0) {
                val dt = (p.t - points[i - 1].t).coerceIn(0, 5000)
                if (p.moving) movingAcc += dt
                if (p.alt - altRef >= 3) { climbAcc += p.alt - altRef; altRef = p.alt } else if (altRef - p.alt >= 3) altRef = p.alt
            }
            while (p.dist >= km * 1000.0) {
                splits.add(Split(km, movingAcc - lastMove, p.t - lastEl, climbAcc))
                lastMove = movingAcc; lastEl = p.t; climbAcc = 0.0; km++
            }
            lastT = p.t
        }
        val n = points.size
        val m = minOf(n, 600)
        chartDist = FloatArray(m); chartSpeed = FloatArray(m); chartAlt = FloatArray(m); chartHr = FloatArray(m)
        for (i in 0 until m) {
            val p = points[if (m <= 1) 0 else (i.toLong() * (n - 1) / (m - 1)).toInt()]
            chartDist[i] = p.dist.toFloat(); chartSpeed[i] = (p.speed * 3.6).toFloat()
            chartAlt[i] = p.alt.toFloat(); chartHr[i] = p.hr.toFloat()
        }
        minAlt = points.minOfOrNull { it.alt } ?: 0.0
        maxAlt = points.maxOfOrNull { it.alt } ?: 0.0
    }

    val stoppedMs get() = max(0L, ride.elapsedMs - ride.movingMs)
}
