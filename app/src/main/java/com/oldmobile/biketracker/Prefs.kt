package com.oldmobile.biketracker

import android.content.Context

/** Settings: rider/bike mass, max HR, emergency contacts, HR sensor, SMS language. */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var riderKg: Double
        get() = p.getFloat("rider_kg", 75f).toDouble()
        set(v) = p.edit().putFloat("rider_kg", v.toFloat()).apply()
    var bikeKg: Double
        get() = p.getFloat("bike_kg", 12f).toDouble()
        set(v) = p.edit().putFloat("bike_kg", v.toFloat()).apply()
    var maxHr: Int
        get() = p.getInt("max_hr", 185)
        set(v) = p.edit().putInt("max_hr", v).apply()
    var c1Name: String
        get() = p.getString("c1_name", "") ?: ""
        set(v) = p.edit().putString("c1_name", v).apply()
    var c1Phone: String
        get() = p.getString("c1_phone", "") ?: ""
        set(v) = p.edit().putString("c1_phone", v).apply()
    var c2Name: String
        get() = p.getString("c2_name", "") ?: ""
        set(v) = p.edit().putString("c2_name", v).apply()
    var c2Phone: String
        get() = p.getString("c2_phone", "") ?: ""
        set(v) = p.edit().putString("c2_phone", v).apply()
    var smsPortuguese: Boolean
        get() = p.getBoolean("sms_pt", true)
        set(v) = p.edit().putBoolean("sms_pt", v).apply()
    var hrAddress: String
        get() = p.getString("hr_addr", "") ?: ""
        set(v) = p.edit().putString("hr_addr", v).apply()
    var hrName: String
        get() = p.getString("hr_name", "") ?: ""
        set(v) = p.edit().putString("hr_name", v).apply()
    var mapZoom: Int
        get() = p.getInt("map_zoom", 15)
        set(v) = p.edit().putInt("map_zoom", v).apply()
    var firstRunDone: Boolean
        get() = p.getBoolean("first_run", false)
        set(v) = p.edit().putBoolean("first_run", v).apply()

    data class Contact(val name: String, val phone: String)

    fun contacts(): List<Contact> = listOf(Contact(c1Name, c1Phone), Contact(c2Name, c2Phone))
        .filter { it.phone.isNotBlank() }
}
