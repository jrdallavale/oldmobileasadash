package com.oldmobile.biketracker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import kotlin.math.log10

class MainActivity : Activity(), SensorEventListener {
    lateinit var app: App; private set
    private lateinit var root: FrameLayout
    private lateinit var overlay: CrashOverlay
    private var current: View? = null
    private val ui = Handler(Looper.getMainLooper())
    private var sm: SensorManager? = null
    private var luxBrightness = 0.6f
    private var fullUntil = 0L
    private var waitingSummary = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        overlay = CrashOverlay(this).apply { visibility = View.GONE }
        setContentView(root)
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        sm = getSystemService(SENSOR_SERVICE) as SensorManager

        val s = Live.snap
        when {
            s.mode == Snap.MODE_RECORDING -> show(RideScreen(this))
            Live.finishedRideId > 0 -> show(ReportScreen(this, Live.finishedRideId, summary = true))
            else -> show(HomeScreen(this))
        }
        if (!app.prefs.firstRunDone) {
            app.prefs.firstRunDone = true
            requestPerms()
            requestBatteryExemption()
        }
    }

    // ---------------------------------------------------------------- navigation
    fun show(v: View) {
        (current as? SettingsView)?.save()
        current?.let { root.removeView(it) }
        current = v
        root.addView(v, 0, FrameLayout.LayoutParams(-1, -1))
        immersive()
    }

    fun showSettings() = show(SettingsView(this))

    @Deprecated("Activity back handling on API 26")
    override fun onBackPressed() {
        val c = current
        if (overlay.visibility == View.VISIBLE) return
        if (c is Screen && c.onBack()) return
        if (c is SettingsView) { show(HomeScreen(this)); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    fun startRide() {
        RideService.send(this, RideService.ACTION_START)
        show(RideScreen(this))
    }

    fun stopRide() {
        waitingSummary = true
        RideService.send(this, RideService.ACTION_STOP)
    }

    fun warmup() { if (hasLocation()) RideService.send(this, RideService.ACTION_WARMUP) }

    fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- 1 Hz loop
    private val tick = object : Runnable {
        override fun run() {
            val s = Live.snap
            if (waitingSummary && Live.finishedRideId > 0 && s.mode != Snap.MODE_RECORDING) {
                waitingSummary = false
                show(ReportScreen(this@MainActivity, Live.finishedRideId, summary = true))
                Live.finishedRideId = -1
            }
            (current as? Screen)?.tick()
            val alarmOn = s.alarm != null
            overlay.visibility = if (alarmOn) View.VISIBLE else View.GONE
            if (alarmOn) overlay.invalidate()
            applyBrightness()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        immersive()
        if (Live.snap.mode != Snap.MODE_RECORDING && current is HomeScreen) warmup()
        sm?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
        sm?.unregisterListener(this)
        // leave GPS off when the app is not in use and no ride is running
        if (RideService.running && Live.snap.mode == Snap.MODE_LOCKING && Live.snap.alarm == null && !isChangingConfigurations)
            RideService.send(this, RideService.ACTION_SHUTDOWN)
    }

    // ---------------------------------------------------------------- brightness
    override fun onSensorChanged(e: SensorEvent) {
        val lux = e.values[0].coerceAtLeast(1f)
        // 10 lux -> 30 %, 10 000 lux (sun) -> 100 %
        luxBrightness = (0.3f + (log10(lux) - 1f) / 3f * 0.7f).coerceIn(0.3f, 1f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { fullUntil = System.currentTimeMillis() + 5000; applyBrightness() }
        return super.dispatchTouchEvent(ev)
    }

    private fun applyBrightness() {
        val s = Live.snap
        val b = when {
            s.alarm != null -> 1f
            s.thermal >= 1 -> 0.1f   // heat: dim to minimum
            System.currentTimeMillis() < fullUntil -> 1f
            else -> luxBrightness
        }
        val lp = window.attributes
        if (lp.screenBrightness != b) { lp.screenBrightness = b; window.attributes = lp }
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) immersive() }

    // ---------------------------------------------------------------- permissions
    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun requestPerms() {
        val want = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= 31) { want.add("android.permission.BLUETOOTH_SCAN"); want.add("android.permission.BLUETOOTH_CONNECT") }
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1) else toast("All permissions granted")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasLocation() && current is HomeScreen) warmup()
    }

    @SuppressLint("BatteryLife")
    fun requestBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Battery optimization already off"); return }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---------------------------------------------------------------- export
    fun export(r: Report, fit: Boolean) {
        toast("Exporting…")
        Thread {
            try {
                val name = "ride_${Fmt.fileStamp(r.ride.start)}." + if (fit) "fit" else "gpx"
                val f = java.io.File(ExportProvider.exportDir(this), name)
                if (fit) Export.fit(f, r) else Export.gpx(f, r)
                ui.post {
                    val uri = ExportProvider.uri(this, f)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = if (fit) "application/vnd.ant.fit" else "application/gpx+xml"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Send $name"))
                }
            } catch (e: Exception) {
                ui.post { toast("Export failed: ${e.message}") }
            }
        }.start()
    }
}
