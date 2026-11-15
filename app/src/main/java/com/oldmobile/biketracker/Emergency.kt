package com.oldmobile.biketracker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager

/**
 * Crash response: countdown with alarm -> SMS to both contacts -> call contact 1 (45 s, speaker)
 * -> call contact 2 (45 s, speaker). Test mode runs the same steps without SMS or calls.
 */
class Emergency(
    private val ctx: Context,
    private val handler: Handler,
    private val prefs: Prefs,
    private val db: Db,
    private val position: () -> Pair<Double, Double>?,
    private val rideId: () -> Long,
    private val publish: (Alarm?) -> Unit,
) {
    private var player: MediaPlayer? = null
    private var active = false
    private var test = false
    private var steps = ArrayList<Runnable>()
    private var log = ""
    private var oldAlarmVol = -1
    private var callActive = false
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val isActive get() = active

    fun begin(testMode: Boolean, skipCountdown: Boolean, reason: String) {
        if (active) return
        active = true; test = testMode; log = ""
        db.event(rideId(), if (testMode) "alarm_test" else "alarm", reason)
        if (skipCountdown) send(reason) else countdown(COUNTDOWN_S, reason)
    }

    private fun countdown(s: Int, reason: String) {
        if (!active) return
        if (s == COUNTDOWN_S) startAlarm()
        if (s <= 0) { stopAlarm(); send(reason); return }
        publish(Alarm(Alarm.Phase.COUNTDOWN, s, if (test) "TEST MODE" else "CRASH DETECTED", test))
        post(1000) { countdown(s - 1, reason) }
    }

    private fun post(ms: Long, r: () -> Unit) {
        val run = Runnable(r)
        steps.add(run)
        handler.postDelayed(run, ms)
    }

    private fun status(line: String) {
        log = line
        publish(Alarm(Alarm.Phase.SENDING, 0, line, test))
    }

    private fun send(reason: String) {
        val contacts = prefs.contacts()
        if (contacts.isEmpty()) {
            publish(Alarm(Alarm.Phase.DONE, 0, "No emergency contacts set", test)); return
        }
        val text = smsText(reason == "sos")
        for (c in contacts) {
            if (test) continue
            try {
                @Suppress("DEPRECATION") val sms = SmsManager.getDefault()
                sms.sendMultipartTextMessage(c.phone, null, sms.divideMessage(text), null, null)
                db.event(rideId(), "sms", c.phone)
            } catch (e: Exception) { db.event(rideId(), "sms_fail", "${c.phone}: ${e.message}") }
        }
        status((if (test) "TEST: would send SMS to " else "SMS sent to ") + contacts.joinToString { it.name.ifBlank { it.phone } })
        var delay = 2000L
        for (c in contacts) {
            post(delay) { call(c) }
            post(delay + CALL_SLOT_MS) { endCall() }
            delay += CALL_SLOT_MS + 3000
        }
        post(delay) {
            publish(Alarm(Alarm.Phase.DONE, 0, if (test) "TEST complete: no SMS, no call" else "Contacts notified", test))
        }
    }

    @SuppressLint("MissingPermission")
    private fun call(c: Prefs.Contact) {
        if (!active) return
        val who = c.name.ifBlank { c.phone }
        if (test) { status("TEST: would call $who for 45 s"); return }
        status("Calling $who (45 s, speaker)")
        try {
            callActive = true
            ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + c.phone)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            db.event(rideId(), "call", c.phone)
            post(2500) { speaker(true) }
        } catch (e: Exception) { db.event(rideId(), "call_fail", "${c.phone}: ${e.message}") }
    }

    @Suppress("DEPRECATION")
    private fun speaker(on: Boolean) {
        try {
            if (on) {
                audio.mode = AudioManager.MODE_IN_CALL
                audio.isSpeakerphoneOn = true
                audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0)
            } else {
                audio.isSpeakerphoneOn = false
                audio.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun endCall() {
        if (test || !callActive) return
        callActive = false
        speaker(false)
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).endCall()
            } else {
                // Android 8.0: ITelephony.endCall is guarded by CALL_PHONE.
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                val m = tm.javaClass.getDeclaredMethod("getITelephony").apply { isAccessible = true }
                val it = m.invoke(tm)
                it.javaClass.getMethod("endCall").invoke(it)
            }
        } catch (_: Exception) {}
    }

    private fun smsText(sos: Boolean): String {
        val p = position()
        val t = Fmt.hm(System.currentTimeMillis())
        val loc = p?.let { String.format(java.util.Locale.US, "https://maps.google.com/?q=%.6f,%.6f", it.first, it.second) }
        return if (prefs.smsPortuguese) {
            (if (sos) "SOS manual - BikeTracker. " else "ALERTA DE QUEDA - BikeTracker. Possivel acidente de bicicleta. ") +
                "Local: ${loc ?: "desconhecido"} as $t. Vou ligar agora."
        } else {
            (if (sos) "Manual SOS - BikeTracker. " else "CRASH ALERT - BikeTracker. Possible bike crash. ") +
                "Location: ${loc ?: "unknown"} at $t. Calling now."
        }
    }

    private fun startAlarm() {
        try {
            oldAlarmVol = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(ctx, uri)
                isLooping = true
                prepare(); start()
            }
        } catch (_: Exception) {}
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
        v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 300), 0))
    }

    private fun stopAlarm() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        if (oldAlarmVol >= 0) try { audio.setStreamVolume(AudioManager.STREAM_ALARM, oldAlarmVol, 0) } catch (_: Exception) {}
        oldAlarmVol = -1
        (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?)?.cancel()
    }

    /** CANCEL during the countdown stops everything; during calls it hangs up and stops the sequence. */
    fun cancel() {
        active = false
        for (r in steps) handler.removeCallbacks(r)
        steps.clear()
        stopAlarm()
        endCall()
        db.event(rideId(), "alarm_cancel", log)
        publish(null)
    }

    companion object {
        const val COUNTDOWN_S = 30
        const val CALL_SLOT_MS = 45_000L
    }
}
