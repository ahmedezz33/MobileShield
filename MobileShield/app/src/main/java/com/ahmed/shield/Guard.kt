package com.ahmed.shield

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.provider.Settings

object Notifier {
    private const val CHANNEL = "alerts"

    fun alert(ctx: Context, id: Int, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("تنبيهات الحماية", "Protection alerts"), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            id,
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}

/**
 * Keeps Android's "Private DNS" switched off while the shield runs, so every lookup
 * goes through our filter. Needs WRITE_SECURE_SETTINGS, granted once over ADB.
 */
object PrivateDnsGuard {
    const val ADB_COMMAND =
        "adb shell pm grant com.ahmed.shield android.permission.WRITE_SECURE_SETTINGS"
    private const val MODE = "private_dns_mode"
    private const val SPECIFIER = "private_dns_specifier"

    private var observer: ContentObserver? = null
    private var lastForce = 0L

    fun canWrite(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    private fun read(ctx: Context, key: String): String? = try {
        Settings.Global.getString(ctx.contentResolver, key)
    } catch (_: SecurityException) {
        null
    }

    /** True when a real network is using a fixed Private DNS hostname (strict mode). */
    fun strictActive(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (cm.getLinkProperties(n)?.privateDnsServerName != null) return true
        }
        return false
    }

    fun start(ctx: Context) {
        if (!canWrite(ctx)) return
        val app = ctx.applicationContext
        val saved = app.getSharedPreferences("private_dns", Context.MODE_PRIVATE)
        if (!saved.getBoolean("saved", false)) {
            saved.edit()
                .putBoolean("saved", true)
                .putString("mode", read(app, MODE) ?: "opportunistic")
                .putString("spec", read(app, SPECIFIER))
                .apply()
        }
        force(app)
        if (observer == null) {
            val o = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val mode = read(app, MODE)
                    if (mode == "off") return
                    if (mode == null && System.currentTimeMillis() - lastForce < 3000) return
                    force(app)
                    Notifier.alert(
                        app, 30, tr("تمت إعادة إيقاف DNS الخاص", "Private DNS switched off again"),
                        tr("حاول أحدهم تفعيل «DNS الخاص» أثناء عمل الحماية، فأعاده درع الجوال إلى الإيقاف.", "Someone tried to turn on Private DNS while protection was running, so MobileShield switched it back off.")
                    )
                }
            }
            app.contentResolver.registerContentObserver(Settings.Global.getUriFor(MODE), false, o)
            observer = o
        }
    }

    private fun force(ctx: Context) {
        try {
            lastForce = System.currentTimeMillis()
            Settings.Global.putString(ctx.contentResolver, MODE, "off")
        } catch (_: SecurityException) {
        }
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        observer?.let { app.contentResolver.unregisterContentObserver(it) }
        observer = null
        if (!canWrite(app)) return
        val saved = app.getSharedPreferences("private_dns", Context.MODE_PRIVATE)
        if (!saved.getBoolean("saved", false)) return
        try {
            saved.getString("spec", null)?.let { Settings.Global.putString(app.contentResolver, SPECIFIER, it) }
            Settings.Global.putString(app.contentResolver, MODE, saved.getString("mode", "opportunistic"))
        } catch (_: SecurityException) {
        }
        saved.edit().clear().apply()
    }
}
