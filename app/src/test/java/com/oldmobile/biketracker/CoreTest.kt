package com.oldmobile.biketracker

import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesgListener
import com.garmin.fit.SessionMesgListener
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class CoreTest {
    private val assets = File("src/main/assets")

    /** Simulated ride heading east at a given speed, 1 Hz. */
    private fun ride(e: RideEngine, seconds: Int, kmh: Double, startLat: Double = -25.46, startLon: Double = -49.53, t0: Int = 0, alt: (Int) -> Double? = { null }): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        val mps = kmh / 3.6
        var lon = startLon
        for (i in 0 until seconds) {
            lon += mps / (111_320.0 * Math.cos(Math.toRadians(startLat)))
            val t = (t0 + i) * 1000L
            e.onFix(Fix(t, t, startLat, lon, 5f, mps.toFloat(), 0.5f, 90f, alt(i)))?.let { out.add(it) }
        }
        return out
    }

    @Test fun distanceAndMovingTime() {
        val e = RideEngine({ _, _ -> 900.0 }, { 185 })
        e.start(0, 0)
        ride(e, 600, 20.0)
        assertEquals(20.0 / 3.6 * 600, e.distM, 60.0)  // ~3.3 km within 2 %
        assertTrue(e.movingMs in 590_000..600_000)
        assertEquals(3, e.splits.size)
        assertEquals(20.0, e.avgSpeed * 3.6, 0.5)
    }

    @Test fun stoppedBikeCollectsNoDistanceAndPauses() {
        val e = RideEngine({ _, _ -> 900.0 }, { 185 })
        e.start(0, 0)
        ride(e, 60, 20.0)
        val d = e.distM
        // stopped with 3 m GPS jitter
        val r = java.util.Random(1)
        for (i in 60 until 180) {
            val t = i * 1000L
            e.onFix(Fix(t, t, -25.46 + r.nextGaussian() * 1e-5, e.lon + r.nextGaussian() * 1e-5, 6f, 0.2f, 0.5f, null, null))
        }
        assertTrue(e.paused)
        assertEquals(d, e.distM, 8.0)
        assertTrue("moving ${e.movingMs}", e.movingMs < 70_000)
    }

    @Test fun rejectsBadAccuracyAndJumps() {
        val e = RideEngine({ _, _ -> null }, { 185 })
        e.start(0, 0)
        assertNotNull(e.onFix(Fix(0, 0, -25.46, -49.53, 5f, 0f, null, null, 950.0)))
        assertNull(e.onFix(Fix(1000, 1000, -25.46, -49.53, 35f, 0f, null, null, 950.0)))
        assertNull(e.onFix(Fix(2000, 2000, -25.46, -49.50, 5f, 0f, null, null, 950.0))) // 3 km in 2 s
        assertEquals(950.0, e.alt, 0.01) // GPS fallback outside terrain
    }

    @Test fun climbUsesThreeMeterSteps() {
        val e = RideEngine({ _, _ -> null }, { 185 })
        e.start(0, 0)
        // 1 m of altitude noise must not count; a 30 m steady climb must
        ride(e, 100, 15.0, alt = { i -> 900.0 + (if (i % 2 == 0) 1.0 else -1.0) })
        assertEquals(0.0, e.climb, 0.01)
        ride(e, 100, 15.0, t0 = 100, startLon = e.lon, alt = { i -> 900.0 + i * 0.3 })
        assertEquals(30.0, e.climb, 4.0)
        assertNotNull(e.grade)
        assertEquals(30.0 / (15 / 3.6 * 100) * 100, e.grade!!, 1.5)
    }

    @Test fun crashNeedsAllThreeChecks() {
        val c = CrashDetector()
        var t = 0L
        fun accel(sec: Int, g: () -> Double): Boolean { var fired = false; repeat(sec * 50) { t += 20; if (c.onAccel(t, g())) fired = true }; return fired }
        // riding, pothole: impact but keeps riding -> no alarm
        c.onSpeed(t, 25.0); accel(1) { 1.0 }
        assertFalse(c.onAccel(t, 5.0))
        for (i in 0 until 20) { c.onSpeed(t, 24.0); accel(1) { 1.0 } }
        assertEquals(CrashDetector.Stage.WATCH, c.stage)
        // dropped at a stop: impact while slow -> no alarm
        for (i in 0 until 6) { c.onSpeed(t, 0.0); accel(1) { 1.0 } }
        c.onAccel(t, 6.0)
        assertEquals(CrashDetector.Stage.WATCH, c.stage)
        // real crash: moving, impact, stops, lies still 30 s
        c.onSpeed(t, 28.0); accel(1) { 1.0 }
        c.onAccel(t, 6.0)
        assertEquals(CrashDetector.Stage.IMPACT, c.stage)
        accel(2) { 1.0 }; c.onSpeed(t, 0.5)
        assertEquals(CrashDetector.Stage.STILL, c.stage)
        assertFalse(accel(20) { 1.0 })
        assertTrue(accel(11) { 1.0 })
    }

    @Test fun hrParsing() {
        assertEquals(72, parseHr(byteArrayOf(0, 72)))
        assertEquals(300, parseHr(byteArrayOf(1, 0x2C, 0x01)))
    }

    @Test fun terrainAndMapAssets() {
        val t = FileInputStream(File(assets, "terrain.bin")).use { Terrain(it) }
        val h = t.height(-25.4597, -49.5292)!!   // Campo Largo centre ~ 950 m
        assertTrue("$h", h in 900.0..1000.0)
        assertNull(t.height(-24.0, -49.5))
        val m = FileInputStream(File(assets, "map.bin")).use { MapData(it) }
        assertTrue(m.size > 5000)
        assertTrue(m.labelText.any { it.contains("Campo Largo") })
    }

    @Test fun fitAndGpxExport() {
        val e = RideEngine({ _, _ -> 950.0 }, { 185 })
        e.start(1_700_000_000_000, 0)
        e.hr = 140
        val pts = ride(e, 300, 25.0).map { it.copy(t = it.t + 1_700_000_000_000) }
        val row = RideRow(1, pts.first().t, pts.last().t, e.distM, e.movingMs, 300_000, e.climb, e.descent, e.maxSpeed,
            e.avgHr, e.hrMax, e.maxGrade, e.zoneMs, true)
        val rep = Report(row, pts, emptyList(), 87.0)
        assertTrue(rep.energyKj > 10)
        val f = File.createTempFile("ride", ".fit")
        Export.fit(f, rep)
        var records = 0; var dist = 0f
        val mb = MesgBroadcaster(Decode())
        mb.addListener(RecordMesgListener { records++ })
        mb.addListener(SessionMesgListener { dist = it.totalDistance })
        FileInputStream(f).use { assertTrue(Decode().checkFileIntegrity(it)) }
        FileInputStream(f).use { mb.run(it) }
        assertEquals(pts.size, records)
        assertEquals(e.distM.toFloat(), dist, 1f)
        val g = File.createTempFile("ride", ".gpx")
        Export.gpx(g, rep)
        assertTrue(g.readText().contains("<gpxtpx:hr>140</gpxtpx:hr>"))
    }
}
