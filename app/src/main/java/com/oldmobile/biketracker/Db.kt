package com.oldmobile.biketracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class RideRow(
    val id: Long,
    val start: Long,
    val end: Long,
    val dist: Double,
    val movingMs: Long,
    val elapsedMs: Long,
    val climb: Double,
    val descent: Double,
    val maxSpeed: Double,
    val avgHr: Int,
    val maxHr: Int,
    val maxGrade: Double,
    val zoneMs: LongArray,
    val saved: Boolean,
) {
    val avgSpeed get() = if (movingMs > 0) dist / (movingMs / 1000.0) else 0.0
}

data class EventRow(val t: Long, val type: String, val info: String)

/** Plain SQLite: rides, points, laps, events. Points are written in batched transactions. */
class Db(ctx: Context) : SQLiteOpenHelper(ctx, "rides.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE rides(id INTEGER PRIMARY KEY AUTOINCREMENT, start INTEGER, end INTEGER,
            dist REAL, moving INTEGER, elapsed INTEGER, climb REAL, descent REAL, max_speed REAL,
            avg_hr INTEGER, max_hr INTEGER, max_grade REAL, zones TEXT, saved INTEGER)""")
        db.execSQL("""CREATE TABLE points(ride_id INTEGER, t INTEGER, lat REAL, lon REAL, alt REAL,
            speed REAL, dist REAL, hr INTEGER, moving INTEGER)""")
        db.execSQL("CREATE INDEX points_ride ON points(ride_id, t)")
        db.execSQL("CREATE TABLE laps(ride_id INTEGER, n INTEGER, t INTEGER, dist REAL, moving INTEGER, elapsed INTEGER)")
        db.execSQL("CREATE TABLE events(ride_id INTEGER, t INTEGER, type TEXT, info TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    override fun onConfigure(db: SQLiteDatabase) { db.enableWriteAheadLogging() }

    fun newRide(start: Long): Long = writableDatabase.insert("rides", null, ContentValues().apply {
        put("start", start); put("end", start); put("dist", 0.0); put("moving", 0); put("elapsed", 0)
        put("climb", 0.0); put("descent", 0.0); put("max_speed", 0.0); put("avg_hr", 0); put("max_hr", 0)
        put("max_grade", 0.0); put("zones", ""); put("saved", 0)
    })

    fun updateTotals(id: Long, e: RideEngine, end: Long, elapsedMs: Long) {
        writableDatabase.update("rides", ContentValues().apply {
            put("end", end); put("dist", e.distM); put("moving", e.movingMs); put("elapsed", elapsedMs)
            put("climb", e.climb); put("descent", e.descent); put("max_speed", e.maxSpeed)
            put("avg_hr", e.avgHr); put("max_hr", e.hrMax); put("max_grade", e.maxGrade)
            put("zones", e.zoneMs.joinToString(","))
        }, "id=?", arrayOf(id.toString()))
    }

    fun insertPoints(id: Long, pts: List<TrackPoint>) {
        if (pts.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("INSERT INTO points VALUES(?,?,?,?,?,?,?,?,?)")
            for (p in pts) {
                st.bindLong(1, id); st.bindLong(2, p.t); st.bindDouble(3, p.lat); st.bindDouble(4, p.lon)
                st.bindDouble(5, p.alt); st.bindDouble(6, p.speed); st.bindDouble(7, p.dist)
                st.bindLong(8, p.hr.toLong()); st.bindLong(9, if (p.moving) 1 else 0)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun insertLap(id: Long, l: Lap) {
        writableDatabase.insert("laps", null, ContentValues().apply {
            put("ride_id", id); put("n", l.n); put("t", l.t); put("dist", l.dist); put("moving", l.movingMs); put("elapsed", l.elapsedMs)
        })
    }

    fun event(id: Long, type: String, info: String = "") {
        writableDatabase.insert("events", null, ContentValues().apply {
            put("ride_id", id); put("t", System.currentTimeMillis()); put("type", type); put("info", info)
        })
    }

    fun setSaved(id: Long) {
        writableDatabase.update("rides", ContentValues().apply { put("saved", 1) }, "id=?", arrayOf(id.toString()))
    }

    fun delete(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (t in listOf("points", "laps", "events")) db.delete(t, "ride_id=?", arrayOf(id.toString()))
            db.delete("rides", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun rowsFrom(where: String?, args: Array<String>?): List<RideRow> {
        val out = ArrayList<RideRow>()
        readableDatabase.query("rides", null, where, args, null, null, "start DESC").use { c ->
            while (c.moveToNext()) {
                val z = c.getString(12).split(',').mapNotNull { it.toLongOrNull() }
                out.add(RideRow(c.getLong(0), c.getLong(1), c.getLong(2), c.getDouble(3), c.getLong(4), c.getLong(5),
                    c.getDouble(6), c.getDouble(7), c.getDouble(8), c.getInt(9), c.getInt(10), c.getDouble(11),
                    LongArray(6) { z.getOrElse(it) { 0L } }, c.getInt(13) == 1))
            }
        }
        return out
    }

    fun rides(): List<RideRow> = rowsFrom("saved=1", null)
    fun unsaved(): List<RideRow> = rowsFrom("saved=0", null)
    fun ride(id: Long): RideRow? = rowsFrom("id=?", arrayOf(id.toString())).firstOrNull()

    fun points(id: Long): List<TrackPoint> {
        val out = ArrayList<TrackPoint>()
        readableDatabase.rawQuery("SELECT t,lat,lon,alt,speed,dist,hr,moving FROM points WHERE ride_id=? ORDER BY t", arrayOf(id.toString())).use { c ->
            while (c.moveToNext()) out.add(TrackPoint(c.getLong(0), c.getDouble(1), c.getDouble(2), c.getDouble(3),
                c.getDouble(4), c.getDouble(5), c.getInt(6), c.getInt(7) == 1))
        }
        return out
    }

    fun laps(id: Long): List<Lap> {
        val out = ArrayList<Lap>()
        readableDatabase.rawQuery("SELECT n,t,dist,moving,elapsed FROM laps WHERE ride_id=? ORDER BY n", arrayOf(id.toString())).use { c ->
            while (c.moveToNext()) out.add(Lap(c.getInt(0), c.getLong(1), c.getDouble(2), c.getLong(3), c.getLong(4)))
        }
        return out
    }

    fun events(id: Long): List<EventRow> {
        val out = ArrayList<EventRow>()
        readableDatabase.rawQuery("SELECT t,type,info FROM events WHERE ride_id=? ORDER BY t", arrayOf(id.toString())).use { c ->
            while (c.moveToNext()) out.add(EventRow(c.getLong(0), c.getString(1), c.getString(2) ?: ""))
        }
        return out
    }
}
