package com.ahmed.shield

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Process
import android.provider.CallLog
import android.provider.Telephony
import java.net.NetworkInterface

data class CallRow(
    val number: String,
    val name: String?,
    val type: Int,
    val date: Long,
    val duration: Long,
    val features: Int,
)

data class SmsRow(val address: String, val type: Int, val date: Long, val body: String)

data class PermApp(val label: String, val pkg: String, val perms: List<String>, val system: Boolean)

data class UsageRow(val label: String, val uid: Int, val rx: Long, val tx: Long)

data class Tethering(val wifi: Boolean, val usb: Boolean, val bluetooth: Boolean) {
    val active get() = wifi || usb || bluetooth
}

/** Read-only views of calls, SMS, system data counters and tethering. */
object MonitorReader {

    val WATCHED_PERMISSIONS get() = linkedMapOf(
        Manifest.permission.SEND_SMS to tr("إرسال رسائل SMS", "Send SMS"),
        Manifest.permission.READ_SMS to tr("قراءة الرسائل", "Read messages"),
        Manifest.permission.RECEIVE_SMS to tr("استقبال الرسائل", "Receive messages"),
        Manifest.permission.CALL_PHONE to tr("إجراء مكالمات", "Make calls"),
        Manifest.permission.READ_CALL_LOG to tr("قراءة سجل المكالمات", "Read call log"),
        Manifest.permission.ANSWER_PHONE_CALLS to tr("الرد على المكالمات", "Answer calls"),
    )

    fun calls(ctx: Context, limit: Int = 100): List<CallRow> {
        val out = ArrayList<CallRow>()
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE,
                    CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.FEATURES,
                ),
                null, null, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += CallRow(
                        c.getString(0) ?: "", c.getString(1), c.getInt(2),
                        c.getLong(3), c.getLong(4), c.getInt(5),
                    )
                }
            }
        } catch (_: SecurityException) {
        }
        return out
    }

    fun sms(ctx: Context, limit: Int = 100): List<SmsRow> {
        val out = ArrayList<SmsRow>()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.TYPE, Telephony.Sms.DATE, Telephony.Sms.BODY),
                null, null, "${Telephony.Sms.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += SmsRow(c.getString(0) ?: "", c.getInt(1), c.getLong(2), c.getString(3) ?: "")
                }
            }
        } catch (_: SecurityException) {
        }
        return out
    }

    /** Installed apps currently granted any SMS / call permission. */
    fun permissionApps(ctx: Context, watched: Map<String, String> = WATCHED_PERMISSIONS): List<PermApp> {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val packages: List<PackageInfo> = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val out = ArrayList<PermApp>()
        for (p in packages) {
            val req = p.requestedPermissions ?: continue
            val flags = p.requestedPermissionsFlags ?: continue
            val granted = req.indices
                .filter { (flags[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }
                .mapNotNull { watched[req[it]] }
            if (granted.isEmpty()) continue
            val ai = p.applicationInfo ?: continue
            out += PermApp(
                pm.getApplicationLabel(ai).toString(), p.packageName, granted,
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }
        return out.sortedWith(compareBy<PermApp> { it.system }.thenBy { it.label })
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Per-app traffic from Android's own counters. Unlike the VPN, these include
     * everything that went over the network, so nothing can hide from them.
     */
    fun usage(ctx: Context, cellular: Boolean, since: Long): List<UsageRow> {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java)
        @Suppress("DEPRECATION")
        val type = if (cellular) ConnectivityManager.TYPE_MOBILE else ConnectivityManager.TYPE_WIFI
        val totals = HashMap<Int, LongArray>()
        try {
            val stats = nsm.querySummary(type, null, since, System.currentTimeMillis())
            val b = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                stats.getNextBucket(b)
                val a = totals.getOrPut(b.uid) { LongArray(2) }
                a[0] += b.rxBytes
                a[1] += b.txBytes
            }
            stats.close()
        } catch (_: Exception) {
            return emptyList()
        }
        return totals.map { (uid, a) ->
            val label = when (uid) {
                NetworkStats.Bucket.UID_TETHERING -> tr("نقطة الاتصال (أجهزة أخرى)", "Hotspot (other devices)")
                NetworkStats.Bucket.UID_REMOVED -> tr("تطبيقات محذوفة", "Removed apps")
                else -> AppNames.label(uid).first
            }
            UsageRow(label, uid, a[0], a[1])
        }.filter { it.rx + it.tx > 0 }.sortedByDescending { it.rx + it.tx }
    }

    /** Detects active tethering from the network interfaces the system brings up for it. */
    fun tethering(): Tethering {
        var wifi = false
        var usb = false
        var bt = false
        try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (!ni.isUp || ni.inetAddresses.toList().isEmpty()) return@forEach
                val n = ni.name
                when {
                    n.startsWith("swlan") || n.startsWith("ap") || n.startsWith("softap") -> wifi = true
                    n.startsWith("rndis") || n.startsWith("usb") || n.startsWith("ncm") -> usb = true
                    n.startsWith("bt-pan") -> bt = true
                }
            }
        } catch (_: Exception) {
        }
        return Tethering(wifi, usb, bt)
    }

    /** Best-effort extra check through WifiManager's hidden API. */
    fun wifiApEnabled(ctx: Context): Boolean = try {
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as Boolean
    } catch (_: Exception) {
        false
    }
}
