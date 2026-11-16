package com.oldmobile.biketracker

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * The one foreground service. Owns GPS, BLE heart rate, accelerometer, battery, recording and the
 * crash sequence. The UI only reads [Live.snap] and sends intents.
 */
class RideService : Service(), LocationListener, SensorEventListener {

    private lateinit var thread: HandlerThread
    private lateinit var h: Handler
    private lateinit var app: App
    private lateinit var lm: LocationManager
    private lateinit var sm: SensorManager
    private var wake: PowerManager.WakeLock? = null
    private var hrClient: HeartRateClient? = null
    private lateinit var emergency: Emergency

    private var gpsOn = false
    private var recording = false
    private var rideId = -1L
    private var engine: RideEngine? = null
    private val buffer = ArrayList<TrackPoint>(32)
    private val crash = CrashDetector()

    // last known values for the snapshot
    private var acc: Float? = null
    private var fixWall = 0L
    private var lat = Double.NaN
    private var lon = Double.NaN
    private var battPct = -1
    private var battTemp = Double.NaN
    private var charging = false
    private var hr = 0
    private var alarm: Alarm? = null
    private var lastNotif = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        app = application as App
        thread = HandlerThread("ride").apply { start() }
        h = Handler(thread.looper)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        emergency = Emergency(this, h, app.prefs, app.db,
            position = { if (lat.isNaN()) null else lat to lon },
            rideId = { rideId },
            publish = { a -> onAlarm(a) })
        running = true
        startForeground(NOTIF_ID, notification("Waiting for GPS"))
        h.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, notification(if (recording) "Recording" else "Waiting for GPS"))
        val action = intent?.action ?: ACTION_WARMUP
        h.post { handle(action) }
        return START_STICKY
    }

    private fun handle(action: String) {
        when (action) {
            ACTION_WARMUP -> startGps()
            ACTION_START -> startRide()
            ACTION_STOP -> stopRide()
            ACTION_LAP -> engine?.let { e ->
                val l = e.lap(System.currentTimeMillis(), SystemClock.elapsedRealtime())
                app.db.insertLap(rideId, l)
                publish()
            }
            ACTION_SHUTDOWN -> if (!recording && !emergency.isActive) shutdown()
            ACTION_SOS -> { startGps(); emergency.begin(testMode = false, skipCountdown = true, reason = "sos") }
            ACTION_TEST -> emergency.begin(testMode = true, skipCountdown = false, reason = "test")
            ACTION_CANCEL -> {
                emergency.cancel()
                if (!recording && !gpsOn) shutdown()
            }
            ACTION_ALARM_CLOSE -> {
                if (emergency.isActive) emergency.cancel()
                if (!recording && !gpsOn) shutdown()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (gpsOn) return
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, thread.looper)
            gpsOn = true
        } catch (_: SecurityException) {}
        if (Live.snap.mode == Snap.MODE_IDLE) publish(Snap.MODE_LOCKING)
    }

    private fun startRide() {
        if (recording) return
        startGps()
        val now = System.currentTimeMillis()
        rideId = app.db.newRide(now)
        engine = RideEngine({ la, lo -> app.terrain?.height(la, lo) }, { app.prefs.maxHr }).also {
            it.start(now, SystemClock.elapsedRealtime())
        }
        app.db.event(rideId, "start")
        Live.track.clear()
        Live.finishedRideId = -1
        crash.reset()
        recording = true
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "biketracker:ride").apply { acquire(12 * 3600_000L) }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, 20_000, h) }
        hrClient = HeartRateClient(this, h, app.prefs) { v -> hr = v; engine?.hr = v }.also { it.start() }
        publish()
    }

    private fun stopRide() {
        if (!recording) { shutdown(); return }
        val e = engine!!
        flush()
        val now = System.currentTimeMillis()
        if (e.laps.isNotEmpty() && e.lapDist > 10) app.db.insertLap(rideId, e.lap(now, SystemClock.elapsedRealtime()))
        app.db.updateTotals(rideId, e, now, e.elapsedMs(SystemClock.elapsedRealtime()))
        app.db.event(rideId, "stop")
        Live.finishedRideId = rideId
        recording = false
        shutdown()
    }

    private fun shutdown() {
        if (emergency.isActive) emergency.cancel()
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        gpsOn = false
        sm.unregisterListener(this)
        hrClient?.stop(); hrClient = null
        try { wake?.release() } catch (_: Exception) {}
        wake = null
        Live.snap = Snap(mode = Snap.MODE_IDLE)
        stopForeground(true)
        stopSelf()
    }

    private fun flush() {
        if (buffer.isEmpty()) return
        app.db.insertPoints(rideId, ArrayList(buffer))
        buffer.clear()
        engine?.let { app.db.updateTotals(rideId, it, System.currentTimeMillis(), it.elapsedMs(SystemClock.elapsedRealtime())) }
    }

    // --- GPS ---
    override fun onLocationChanged(l: Location) {
        acc = if (l.hasAccuracy()) l.accuracy else 99f
        fixWall = System.currentTimeMillis()
        lat = l.latitude; lon = l.longitude
        val e = engine
        if (recording && e != null) {
            val f = Fix(System.currentTimeMillis(), SystemClock.elapsedRealtime(), l.latitude, l.longitude,
                acc!!, if (l.hasSpeed()) l.speed else null,
                if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else null,
                if (l.hasBearing()) l.bearing else null,
                if (l.hasAltitude()) l.altitude else null)
            e.onFix(f)?.let { p ->
                buffer.add(p)
                Live.track.add(p)
                if (buffer.size >= 15) flush()
            }
            crash.onSpeed(SystemClock.elapsedRealtime(), e.speed * 3.6)
        }
        publish()
    }

    @Deprecated("required on API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) { acc = null; publish() }

    // --- Accelerometer, 50 Hz, crash detection only ---
    override fun onSensorChanged(ev: SensorEvent) {
        val x = ev.values[0]; val y = ev.values[1]; val z = ev.values[2]
        val g = sqrt((x * x + y * y + z * z).toDouble()) / SensorManager.GRAVITY_EARTH
        if (crash.onAccel(SystemClock.elapsedRealtime(), g) && !emergency.isActive) {
            app.db.event(rideId, "crash", "auto")
            emergency.begin(testMode = false, skipCountdown = false, reason = "crash")
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // --- 1 Hz tick: elapsed time, battery every 30 s, notification every 10 s ---
    private var tickN = 0
    private val tick = object : Runnable {
        override fun run() {
            if (tickN % 30 == 0) readBattery()
            tickN++
            hrClient?.let { if (!it.hrFresh && hr != 0) { hr = 0; engine?.hr = 0 } }
            publish()
            val now = System.currentTimeMillis()
            if (recording && now - lastNotif > 10_000) {
                lastNotif = now
                engine?.let { e ->
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID,
                        notification("Recording · ${Fmt.dist(e.distM)} · ${Fmt.dur(e.movingMs)}"))
                }
            }
            if (running) h.postDelayed(this, 1000)
        }
    }

    private fun readBattery() {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        battPct = if (level >= 0) level * 100 / scale else -1
        battTemp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1000) / 10.0
        if (battTemp < -50) battTemp = Double.NaN
        charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (recording && battTemp >= 50) app.db.event(rideId, "thermal", Fmt.one(battTemp))
    }

    private fun onAlarm(a: Alarm?) {
        alarm = a
        publish()
        if (a != null && a.phase == Alarm.Phase.COUNTDOWN && a.secondsLeft == Emergency.COUNTDOWN_S) {
            // bring the overlay to the front even if the activity is in the background
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            getSystemService(NotificationManager::class.java).notify(ALARM_NOTIF_ID, Notification.Builder(this, App.CH_ALARM)
                .setSmallIcon(R.drawable.ic_notif).setContentTitle("Crash detected").setContentText("Open to cancel")
                .setCategory(Notification.CATEGORY_ALARM)
                .setFullScreenIntent(PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE), true)
                .setAutoCancel(true).build())
        }
        if (a == null) getSystemService(NotificationManager::class.java).cancel(ALARM_NOTIF_ID)
    }

    private fun publish(mode: Int? = null) {
        val e = engine
        val now = SystemClock.elapsedRealtime()
        val m = mode ?: when { recording -> Snap.MODE_RECORDING; gpsOn -> Snap.MODE_LOCKING; else -> Snap.MODE_IDLE }
        Live.snap = if (recording && e != null) Snap(
            mode = m, acc = acc, fixWall = fixWall, lat = lat, lon = lon, bearing = e.bearing,
            speed = e.liveSpeed(now), avg = e.avgSpeed, max = e.maxSpeed, dist = e.distM, movingMs = e.movingMs,
            elapsedMs = e.elapsedMs(now), alt = e.alt, climb = e.climb, descent = e.descent, grade = e.grade,
            hr = hr, hrZone = hrZone(hr, app.prefs.maxHr), hrLink = hrClient?.connected == true,
            lapN = e.laps.size + 1, lapDist = e.lapDist, paused = e.paused,
            battPct = battPct, battTemp = battTemp, charging = charging,
            splits = ArrayList(e.splits), rideId = rideId, alarm = alarm,
        ) else Snap(mode = m, acc = acc, fixWall = fixWall, lat = lat, lon = lon,
            battPct = battPct, battTemp = battTemp, charging = charging, alarm = alarm)
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, App.CH_RIDE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("BikeTracker")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    override fun onDestroy() {
        running = false
        h.removeCallbacksAndMessages(null)
        if (recording) { // killed mid-ride: keep what we have
            flush()
        }
        try { lm.removeUpdates(this) } catch (_: Exception) {}
        sm.unregisterListener(this)
        hrClient?.stop()
        try { wake?.release() } catch (_: Exception) {}
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false; private set
        const val NOTIF_ID = 1
        const val ALARM_NOTIF_ID = 2
        const val ACTION_WARMUP = "warmup"
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_LAP = "lap"
        const val ACTION_SHUTDOWN = "shutdown"
        const val ACTION_SOS = "sos"
        const val ACTION_TEST = "test"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_ALARM_CLOSE = "alarm_close"

        fun send(ctx: Context, action: String) {
            ctx.startForegroundService(Intent(ctx, RideService::class.java).setAction(action))
        }
    }
}
