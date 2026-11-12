package com.ahmed.shield

import android.content.Context
import android.database.ContentObserver
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the phone's location switched off. Apps cannot turn location on by themselves:
 * it takes the user (the quick panel, or approving an app's "turn on location?" dialog).
 * So any switch-on counts as an explicit request and opens a window of [minutes]; when it
 * ends, location goes off again. Wi-Fi and Bluetooth "scanning for location" stay off too.
 * Needs WRITE_SECURE_SETTINGS (granted once over USB).
 */
object LocationLock {
    val enabled = MutableStateFlow(false)
    val minutes = MutableStateFlow(15)
    /** End of the current allowed window, 0 when none. */
    val until = MutableStateFlow(0L)

    private val main = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var app: Context? = null
    private var ourWriteAt = 0L
    private val endRunnable = Runnable { app?.let { closeNow(it, expired = true) } }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("location_lock", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        val p = prefs(ctx)
        enabled.value = p.getBoolean("on", false)
        minutes.value = p.getInt("minutes", 15)
        until.value = p.getLong("until", 0L)
    }

    fun locationOn(ctx: Context): Boolean =
        ctx.getSystemService(LocationManager::class.java)?.isLocationEnabled == true

    /** Called when protection starts: watch the switch and enforce the lock. */
    fun start(ctx: Context) {
        val a = ctx.applicationContext
        app = a
        init(a)
        if (observer == null) {
            val o = object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = onSwitch(a)
            }
            a.contentResolver.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.LOCATION_MODE), false, o)
            observer = o
        }
        enforce(a)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled.value = on
        prefs(ctx).edit().putBoolean("on", on).apply()
        if (on) {
            until.value = 0L
            prefs(ctx).edit().putLong("until", 0L).apply()
            start(ctx)
        } else {
            main.removeCallbacks(endRunnable)
        }
    }

    fun setMinutes(ctx: Context, m: Int) {
        minutes.value = m
        prefs(ctx).edit().putInt("minutes", m).apply()
    }

    /** Opens location for [m] minutes (the in-app "allow" button). */
    fun allowFor(ctx: Context, m: Int) {
        openWindow(ctx.applicationContext, m)
        writeMode(ctx, on = true)
    }

    /** Closes location now and ends any window. */
    fun closeNow(ctx: Context, expired: Boolean = false) {
        main.removeCallbacks(endRunnable)
        until.value = 0L
        prefs(ctx).edit().putLong("until", 0L).apply()
        if (enabled.value && locationOn(ctx)) {
            writeMode(ctx, on = false)
            if (expired) Notifier.alert(
                ctx, 41, tr("أُغلق الموقع", "Location switched off"),
                tr("انتهت المدة المسموح بها، فأغلق درع الجوال الموقع.", "The allowed time ended, so MobileShield switched location off."),
            )
        }
    }

    private fun onSwitch(ctx: Context) {
        if (!enabled.value) return
        if (System.currentTimeMillis() - ourWriteAt < 3000) return
        if (locationOn(ctx) && until.value < System.currentTimeMillis()) {
            // switched on by the user (or an app's dialog they approved): a timed window
            openWindow(ctx, minutes.value)
            Notifier.alert(
                ctx, 41, tr("الموقع مفتوح مؤقتًا", "Location on for a while"),
                tr(
                    "فُتح الموقع، وسيغلقه درع الجوال تلقائيًا عند ${clock(until.value)}. لإغلاقه الآن: التحكم ← الموقع.",
                    "Location was switched on; MobileShield will switch it off at ${clock(until.value)}. To close it now: Control → Location.",
                ),
            )
        } else if (!locationOn(ctx)) {
            main.removeCallbacks(endRunnable)
            until.value = 0L
            prefs(ctx).edit().putLong("until", 0L).apply()
        }
    }

    /** Re-applies the lock: closes location unless a window is open; restores a window's timer. */
    fun enforce(ctx: Context) {
        if (!enabled.value) return
        scanningOff(ctx)
        val left = until.value - System.currentTimeMillis()
        if (left > 0) {
            main.removeCallbacks(endRunnable)
            main.postDelayed(endRunnable, left)
        } else if (locationOn(ctx)) {
            closeNow(ctx)
        }
    }

    private fun openWindow(ctx: Context, m: Int) {
        val end = System.currentTimeMillis() + m * 60_000L
        until.value = end
        prefs(ctx).edit().putLong("until", end).apply()
        main.removeCallbacks(endRunnable)
        main.postDelayed(endRunnable, m * 60_000L)
    }

    private fun writeMode(ctx: Context, on: Boolean) {
        if (!PrivateDnsGuard.canWrite(ctx)) return
        ourWriteAt = System.currentTimeMillis()
        try {
            Settings.Secure.putInt(
                ctx.contentResolver, Settings.Secure.LOCATION_MODE,
                if (on) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF,
            )
        } catch (_: Exception) {
        }
    }

    /** "Wi-Fi scanning" and "Bluetooth scanning" let location see around you with the radios off. */
    private fun scanningOff(ctx: Context) {
        if (!PrivateDnsGuard.canWrite(ctx)) return
        try {
            Settings.Global.putInt(ctx.contentResolver, "wifi_scan_always_enabled", 0)
            Settings.Global.putInt(ctx.contentResolver, "ble_scan_always_enabled", 0)
        } catch (_: Exception) {
        }
    }

    fun clock(t: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(t))
}
