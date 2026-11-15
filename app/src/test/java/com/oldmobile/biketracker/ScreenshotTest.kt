package com.oldmobile.biketracker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Renders each screen at the Zenfone 3 resolution (1920x1080, xxhdpi) to build/screens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w640dp-h360dp-land-xxhdpi")
class ScreenshotTest {
    private val out = File("build/screens").apply { mkdirs() }

    private fun snap(v: View, name: String) {
        v.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1920, 1080)
        val b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun screens() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // wait for assets
        repeat(100) { if (App.instance.map == null || App.instance.terrain == null) Thread.sleep(100) }
        val now = System.currentTimeMillis()
        Live.snap = Snap(mode = Snap.MODE_LOCKING, acc = 6f, fixWall = now, battPct = 87, battTemp = 34.0, charging = true)
        snap(HomeScreen(act), "1_gps_lock")

        // fake a ride along the main road
        Live.track.clear()
        val e = RideEngine({ la, lo -> App.instance.terrain?.height(la, lo) }, { 185 })
        e.start(now, 0)
        var lat = -25.475; var lon = -49.56
        for (i in 0 until 1500) {
            lon += 0.00005; lat += 0.00001 * Math.sin(i / 90.0)
            e.hr = 120 + (i / 40)
            e.onFix(Fix(now + i * 1000L, i * 1000L, lat, lon, 4f, 6.5f, 0.4f, 80f, null))?.let { Live.track.add(it) }
        }
        e.lap(now, 600_000)
        Live.snap = Snap(mode = Snap.MODE_RECORDING, acc = 4f, fixWall = now, lat = lat, lon = lon, bearing = 80f,
            speed = 27.4 / 3.6, avg = e.avgSpeed, max = e.maxSpeed, dist = e.distM, movingMs = e.movingMs, elapsedMs = 1_620_000,
            alt = e.alt, climb = e.climb, descent = e.descent, grade = 4.2, hr = 152, hrZone = 4, hrLink = true, lapN = 2,
            lapDist = e.lapDist, paused = false, battPct = 92, battTemp = 38.0, charging = true, splits = ArrayList(e.splits), rideId = 1)
        val ride = RideScreen(act)
        snap(ride, "2_ride_main")
        ride.page = 1; snap(ride, "3_ride_map")
        ride.page = 2; snap(ride, "4_ride_profile")
        Live.snap = Live.snap.copy(hr = 0, battTemp = 46.0)
        ride.page = 0; snap(ride, "2b_ride_no_hr_hot")

        // store it and render summary/history
        val db = App.instance.db
        val id = db.newRide(now)
        val pts = ArrayList<TrackPoint>()
        val e2 = RideEngine({ la, lo -> App.instance.terrain?.height(la, lo) }, { 185 }); e2.start(now, 0)
        lat = -25.475; lon = -49.56
        for (i in 0 until 1500) { lon += 0.00005; lat += 0.00001 * Math.sin(i / 90.0); e2.hr = 120 + i / 40
            e2.onFix(Fix(now + i * 1000L, i * 1000L, lat, lon, 4f, 6.5f, 0.4f, 80f, null))?.let { pts.add(it) } }
        db.insertPoints(id, pts); db.insertLap(id, e2.lap(now + 600_000, 600_000))
        db.updateTotals(id, e2, now + 1_620_000, 1_620_000)
        val sum = ReportScreen(act, id, summary = true)
        repeat(50) { ShadowLooper.idleMainLooper(); Thread.sleep(50) }
        snap(sum, "5_summary")
        db.setSaved(id)
        snap(HistoryScreen(act), "6_history")
        snap(ReportScreen(act, id, summary = false).also { repeat(30) { ShadowLooper.idleMainLooper(); Thread.sleep(50) } }, "7_detail")
        snap(SettingsView(act), "8_settings")
        Live.snap = Live.snap.copy(alarm = Alarm(Alarm.Phase.COUNTDOWN, 23, "CRASH DETECTED", false))
        snap(CrashOverlay(act), "9_crash")
    }
}
