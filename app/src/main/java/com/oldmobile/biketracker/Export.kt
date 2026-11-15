package com.oldmobile.biketracker

import com.garmin.fit.*
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** FIT (for Flow Sync / HealthFit -> Apple Health) and GPX export. */
object Export {
    private fun semicircles(deg: Double) = (deg * (2147483648.0 / 180.0)).toInt()
    private fun dt(ms: Long) = DateTime(Date(ms))

    fun fit(file: File, r: Report) {
        val ride = r.ride
        val pts = r.points
        val enc = FileEncoder(file, Fit.ProtocolVersion.V2_0)
        try {
            val start = pts.firstOrNull()?.t ?: ride.start
            val end = pts.lastOrNull()?.t ?: ride.end

            enc.write(FileIdMesg().apply {
                type = com.garmin.fit.File.ACTIVITY
                manufacturer = Manufacturer.DEVELOPMENT
                product = 1
                serialNumber = 26_0001L
                timeCreated = dt(start)
            })
            enc.write(DeviceInfoMesg().apply {
                deviceIndex = DeviceIndex.CREATOR
                manufacturer = Manufacturer.DEVELOPMENT
                product = 1
                productName = "BikeTracker ZE520KL"
                softwareVersion = 1.0f
                timestamp = dt(start)
            })
            enc.write(EventMesg().apply {
                timestamp = dt(start); event = Event.TIMER; eventType = EventType.START
            })
            for (p in pts) {
                enc.write(RecordMesg().apply {
                    timestamp = dt(p.t)
                    positionLat = semicircles(p.lat)
                    positionLong = semicircles(p.lon)
                    enhancedAltitude = p.alt.toFloat()
                    enhancedSpeed = p.speed.toFloat()
                    distance = p.dist.toFloat()
                    if (p.hr > 0) heartRate = p.hr.toShort()
                })
            }
            enc.write(EventMesg().apply {
                timestamp = dt(end); event = Event.TIMER; eventType = EventType.STOP_ALL
            })

            // laps: the marked ones, or one lap for the whole ride
            val laps = if (r.laps.isEmpty()) listOf(Lap(1, end, ride.dist, ride.movingMs, ride.elapsedMs)) else r.laps
            var lapStart = start
            laps.forEachIndexed { i, l ->
                enc.write(LapMesg().apply {
                    messageIndex = i
                    timestamp = dt(l.t)
                    startTime = dt(lapStart)
                    totalElapsedTime = l.elapsedMs / 1000f
                    totalTimerTime = l.movingMs / 1000f
                    totalDistance = l.dist.toFloat()
                    event = Event.LAP; eventType = EventType.STOP
                    sport = Sport.CYCLING
                })
                lapStart = l.t
            }
            enc.write(SessionMesg().apply {
                messageIndex = 0
                timestamp = dt(end)
                startTime = dt(start)
                totalElapsedTime = ride.elapsedMs / 1000f
                totalTimerTime = ride.movingMs / 1000f
                totalDistance = ride.dist.toFloat()
                totalAscent = ride.climb.toInt()
                totalDescent = ride.descent.toInt()
                enhancedMaxSpeed = ride.maxSpeed.toFloat()
                enhancedAvgSpeed = ride.avgSpeed.toFloat()
                if (ride.avgHr > 0) avgHeartRate = ride.avgHr.toShort()
                if (ride.maxHr > 0) maxHeartRate = ride.maxHr.toShort()
                totalCalories = r.energyKj.toInt() // mechanical kJ ~ metabolic kcal at ~24 % efficiency
                sport = Sport.CYCLING; subSport = SubSport.ROAD
                firstLapIndex = 0; numLaps = laps.size
                event = Event.SESSION; eventType = EventType.STOP
                trigger = SessionTrigger.ACTIVITY_END
            })
            val tzOffsetS = TimeZone.getDefault().getOffset(end) / 1000
            enc.write(ActivityMesg().apply {
                timestamp = dt(end)
                totalTimerTime = ride.movingMs / 1000f
                numSessions = 1
                type = Activity.MANUAL
                event = Event.ACTIVITY; eventType = EventType.STOP
                localTimestamp = dt(end).timestamp + tzOffsetS
            })
        } finally {
            enc.close()
        }
    }

    fun gpx(file: File, r: Report) {
        val L = Locale.US
        file.bufferedWriter().use { w ->
            w.write("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="BikeTracker" xmlns="http://www.topografix.com/GPX/1/1"
 xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
<metadata><time>${Fmt.isoUtc(r.ride.start)}</time></metadata>
<trk><name>Ride ${Fmt.date(r.ride.start)} ${Fmt.hm(r.ride.start)}</name><type>cycling</type><trkseg>
""")
            for (p in r.points) {
                w.write(String.format(L, "<trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele><time>%s</time>", p.lat, p.lon, p.alt, Fmt.isoUtc(p.t)))
                if (p.hr > 0) w.write("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>${p.hr}</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
                w.write("</trkpt>\n")
            }
            w.write("</trkseg></trk>\n</gpx>\n")
        }
    }
}
