package com.oldmobile.biketracker

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*

/** 8. Settings: plain Android widgets, styled black/white/yellow, 64 dp targets. */
class SettingsView(private val act: MainActivity) : ScrollView(act) {
    private val prefs = act.app.prefs
    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }

    private lateinit var rider: EditText
    private lateinit var bike: EditText
    private lateinit var maxHr: EditText
    private lateinit var c1n: EditText
    private lateinit var c1p: EditText
    private lateinit var c2n: EditText
    private lateinit var c2p: EditText
    private lateinit var lang: Button
    private lateinit var hrLabel: TextView
    private lateinit var found: LinearLayout
    private var scanner: HrScanner? = null

    init {
        setBackgroundColor(C.BG)
        addView(col, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(btn("SAVE & BACK", C.ACCENT, C.BG) { save(); act.show(HomeScreen(act)) }, LinearLayout.LayoutParams(0, dp(64), 1f))
        top.addView(title("SETTINGS"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(24) })
        col.addView(top)

        section("RIDER")
        val r1 = row()
        rider = field(r1, "Rider mass (kg)", fmt(prefs.riderKg), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        bike = field(r1, "Bike mass (kg)", fmt(prefs.bikeKg), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        maxHr = field(r1, "Max HR (bpm)", prefs.maxHr.toString(), InputType.TYPE_CLASS_NUMBER)

        section("EMERGENCY CONTACTS (SMS + automatic call)")
        val r2 = row()
        c1n = field(r2, "Contact 1 name", prefs.c1Name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        c1p = field(r2, "Contact 1 phone", prefs.c1Phone, InputType.TYPE_CLASS_PHONE)
        val r3 = row()
        c2n = field(r3, "Contact 2 name", prefs.c2Name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
        c2p = field(r3, "Contact 2 phone", prefs.c2Phone, InputType.TYPE_CLASS_PHONE)
        val r4 = row()
        lang = btn("") { prefs.smsPortuguese = !prefs.smsPortuguese; updateLang() }
        r4.addView(lang, LinearLayout.LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(12) })
        r4.addView(btn("RUN CRASH TEST (no SMS, no call)", C.ALERT, C.FG) { save(); RideService.send(act, RideService.ACTION_TEST) },
            LinearLayout.LayoutParams(0, dp(64), 1f))
        updateLang()

        section("HEART RATE SENSOR (BLE 0x180D)")
        val r5 = row()
        hrLabel = label("")
        r5.addView(hrLabel, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r5.addView(btn("SCAN 10 s") { scan() }, LinearLayout.LayoutParams(0, dp(64), 0.6f).apply { rightMargin = dp(12) })
        r5.addView(btn("ANY SENSOR") { prefs.hrAddress = ""; prefs.hrName = ""; updateHr() }, LinearLayout.LayoutParams(0, dp(64), 0.6f))
        found = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        col.addView(found)
        updateHr()

        section("PHONE")
        val r6 = row()
        r6.addView(btn("BATTERY OPTIMIZATION EXEMPTION") { act.requestBatteryExemption() }, LinearLayout.LayoutParams(0, dp(64), 1f).apply { rightMargin = dp(12) })
        r6.addView(btn("PERMISSIONS") { act.requestPerms() }, LinearLayout.LayoutParams(0, dp(64), 1f))
        col.addView(label("Exports are saved in Android/data/com.oldmobile.biketracker/files/exports (reachable over USB)."))
    }

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else v.toString()

    private fun updateLang() { lang.text = "SMS LANGUAGE: " + if (prefs.smsPortuguese) "PORTUGUÊS" else "ENGLISH" }
    private fun updateHr() { hrLabel.text = "Sensor: " + if (prefs.hrAddress.isBlank()) "first one found" else "${prefs.hrName} (${prefs.hrAddress})" }

    private fun scan() {
        found.removeAllViews()
        val h = Handler(Looper.getMainLooper())
        scanner?.stop()
        val s = HrScanner(act, h, { name, addr ->
            found.addView(btn("USE  $name  ($addr)", C.DIM, C.ACCENT) {
                prefs.hrAddress = addr; prefs.hrName = name; updateHr(); found.removeAllViews()
            }, LinearLayout.LayoutParams(MATCH_PARENT, dp(64)).apply { topMargin = dp(8) })
        }, { if (found.childCount == 0) found.addView(label("No HR broadcaster found. Start HeartCast/HeartBLE on the watch, or wear the strap.")) })
        scanner = s
        if (!s.start()) found.addView(label("Bluetooth is off or unavailable."))
        else found.addView(label("Scanning…"))
    }

    fun save() {
        scanner?.stop()
        rider.text.toString().toDoubleOrNull()?.let { if (it in 20.0..250.0) prefs.riderKg = it }
        bike.text.toString().toDoubleOrNull()?.let { if (it in 3.0..60.0) prefs.bikeKg = it }
        maxHr.text.toString().toIntOrNull()?.let { if (it in 100..240) prefs.maxHr = it }
        prefs.c1Name = c1n.text.toString().trim(); prefs.c1Phone = c1p.text.toString().trim()
        prefs.c2Name = c2n.text.toString().trim(); prefs.c2Phone = c2p.text.toString().trim()
    }

    // --- widget helpers ---
    private fun title(t: String) = TextView(act).apply { text = t; setTextColor(C.ACCENT); textSize = 26f; typeface = Typeface.DEFAULT_BOLD }
    private fun label(t: String) = TextView(act).apply { text = t; setTextColor(C.LABEL); textSize = 16f; setPadding(0, dp(8), 0, dp(8)) }
    private fun section(t: String) = col.addView(TextView(act).apply {
        text = t; setTextColor(C.LABEL); textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(20), 0, dp(6))
    })
    private fun row() = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }.also { col.addView(it) }

    private fun field(parent: LinearLayout, hintText: String, value: String, type: Int): EditText {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(act).apply { text = hintText; setTextColor(C.LABEL); textSize = 13f })
        val e = EditText(act).apply {
            setText(value); inputType = type; setTextColor(C.FG); textSize = 22f; minHeight = dp(56)
            setSingleLine(); backgroundTintList = ColorStateList.valueOf(C.ACCENT); setHintTextColor(C.LABEL)
        }
        box.addView(e, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        parent.addView(box, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
        return e
    }

    private fun btn(t: String, bg: Int = C.DIM, fg: Int = C.FG, onClick: () -> Unit) = Button(act).apply {
        text = t; setTextColor(fg); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; isAllCaps = false
        background = GradientDrawable().apply { setColor(bg); cornerRadius = 10 * d }
        stateListAnimator = null
        setOnClickListener { onClick() }
        minHeight = dp(64)
        textAlignment = View.TEXT_ALIGNMENT_CENTER
    }
}
