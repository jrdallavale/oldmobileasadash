package com.oldmobile.biketracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    lateinit var db: Db; private set
    lateinit var prefs: Prefs; private set
    @Volatile var terrain: Terrain? = null; private set
    @Volatile var map: MapData? = null; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = Db(this)
        prefs = Prefs(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RIDE, "Ride recording", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Crash alarm", NotificationManager.IMPORTANCE_HIGH))
        // Heavy assets load off the main thread so cold start stays under 2 s.
        Thread({
            try { terrain = assets.open("terrain.bin").use { Terrain(it) } } catch (_: Exception) {}
            try { map = assets.open("map.bin").use { MapData(it) } } catch (_: Exception) {}
        }, "assets").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    companion object {
        lateinit var instance: App; private set
        const val CH_RIDE = "ride"
        const val CH_ALARM = "alarm"
    }
}
