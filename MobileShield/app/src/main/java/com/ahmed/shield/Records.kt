package com.ahmed.shield

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.storage.StorageManager
import android.service.notification.StatusBarNotification
import java.io.File

data class TimelineRow(val time: Long, val title: String, val detail: String)

data class AppStorage(val label: String, val pkg: String, val app: Long, val data: Long, val cache: Long, val system: Boolean)

data class SensorAccess(val label: String, val pkg: String, val what: String, val time: Long)

data class NotificationRow(val time: Long, val app: String, val title: String, val text: String)

/** Read-only views of what Android itself records about apps. */
object Records {

    // ---- Usage history ----

    fun usageTimeline(ctx: Context, days: Int): List<TimelineRow> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(now - days * 86_400_000L, now)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<TimelineRow>()
        val e = UsageEvents.Event()
        var lastPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (e.packageName != lastPkg) {
                        out += TimelineRow(e.timeStamp, AppNames.labelOf(e.packageName), tr("فُتح", "Opened") + " · " + e.packageName)
                        lastPkg = e.packageName
                    }
                }
                UsageEvents.Event.SCREEN_INTERACTIVE -> out += TimelineRow(e.timeStamp, tr("أُضيئت الشاشة", "Screen on"), "")
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    out += TimelineRow(e.timeStamp, tr("أُطفئت الشاشة", "Screen off"), "")
                    lastPkg = null
                }
                UsageEvents.Event.KEYGUARD_HIDDEN -> out += TimelineRow(e.timeStamp, tr("فُتح القفل", "Unlocked"), "")
                UsageEvents.Event.KEYGUARD_SHOWN -> out += TimelineRow(e.timeStamp, tr("قُفل الجوال", "Locked"), "")
                UsageEvents.Event.DEVICE_STARTUP -> out += TimelineRow(e.timeStamp, tr("تشغيل الجوال", "Phone started"), "")
                UsageEvents.Event.DEVICE_SHUTDOWN -> out += TimelineRow(e.timeStamp, tr("إطفاء الجوال", "Phone shut down"), "")
                UsageEvents.Event.FOREGROUND_SERVICE_START -> out += TimelineRow(
                    e.timeStamp, AppNames.labelOf(e.packageName),
                    tr("بدأ خدمة تعمل في الخلفية", "Started a background service") + " · " + e.packageName,
                )
            }
        }
        return out.asReversed().take(4000)
    }

    /** Minutes on screen per app for the period. */
    fun usageTotals(ctx: Context, days: Int): List<Pair<String, Long>> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        return try {
            usm.queryAndAggregateUsageStats(now - days * 86_400_000L, now)
                .values.filter { it.totalTimeInForeground > 60_000 }
                .sortedByDescending { it.totalTimeInForeground }
                .map { AppNames.labelOf(it.packageName) to it.totalTimeInForeground }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- Storage ----

    fun storage(ctx: Context): List<AppStorage> {
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val pm = ctx.packageManager
        val user = Process.myUserHandle()
        @Suppress("DEPRECATION")
        return pm.getInstalledApplications(0).mapNotNull { ai ->
            try {
                val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, user)
                AppStorage(
                    pm.getApplicationLabel(ai).toString(), ai.packageName,
                    s.appBytes, s.dataBytes, s.cacheBytes,
                    (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.data + it.cache }
    }

    // ---- Sensitive access ----

    private val OPS
        get() = linkedMapOf(
            AppOpsManager.OPSTR_CAMERA to tr("الكاميرا", "Camera"),
            AppOpsManager.OPSTR_RECORD_AUDIO to tr("المايكروفون", "Microphone"),
            AppOpsManager.OPSTR_FINE_LOCATION to tr("الموقع الدقيق", "Precise location"),
            AppOpsManager.OPSTR_COARSE_LOCATION to tr("الموقع التقريبي", "Approximate location"),
            AppOpsManager.OPSTR_READ_CONTACTS to tr("جهات الاتصال", "Contacts"),
            AppOpsManager.OPSTR_READ_SMS to tr("الرسائل", "Messages"),
            AppOpsManager.OPSTR_READ_CALL_LOG to tr("سجل المكالمات", "Call log"),
        )

    /** Last time each app used each sensitive permission (needs GET_APP_OPS_STATS). */
    fun sensorAccess(ctx: Context): List<SensorAccess>? {
        if (ctx.checkSelfPermission("android.permission.GET_APP_OPS_STATS") != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val names = OPS
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            val list = AppOpsManager::class.java.getMethod("getPackagesForOps", Array<String>::class.java)
                .invoke(ops, names.keys.toTypedArray()) as? List<*> ?: return emptyList()
            val out = ArrayList<SensorAccess>()
            for (po in list) {
                po ?: continue
                val pkg = po.javaClass.getMethod("getPackageName").invoke(po) as String
                for (entry in po.javaClass.getMethod("getOps").invoke(po) as List<*>) {
                    entry ?: continue
                    val op = entry.javaClass.getMethod("getOpStr").invoke(entry) as String
                    val time = entry.javaClass.getMethod("getLastAccessTime", Int::class.javaPrimitiveType)
                        .invoke(entry, 0x1F) as Long // OP_FLAGS_ALL
                    if (time > 0) out += SensorAccess(AppNames.labelOf(pkg), pkg, names[op] ?: op, time)
                }
            }
            out.sortedByDescending { it.time }
        } catch (_: Exception) {
            null
        }
    }

    // ---- System log ----

    fun canReadLogs(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    fun systemLog(lines: Int = 3000): List<String> = try {
        val p = ProcessBuilder("logcat", "-d", "-v", "time", "-t", lines.toString())
            .redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readLines().asReversed()
    } catch (e: Exception) {
        listOf(e.toString())
    }
}

/** Every notification shown on the phone from the moment notification access was granted. */
object NotificationLog {
    private const val MAX = 2000
    private val rows = ArrayDeque<NotificationRow>()
    private var file: File? = null

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "notifications.tsv")
        file = f
        if (!f.exists()) return
        synchronized(rows) {
            f.readLines().takeLast(MAX).forEach { line ->
                val p = line.split('\t')
                if (p.size == 4) rows.addFirst(NotificationRow(p[0].toLongOrNull() ?: 0, p[1], p[2], p[3]))
            }
        }
    }

    fun add(ctx: Context, sbn: StatusBarNotification) {
        if (sbn.packageName == ctx.packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = (extras.getCharSequence("android.bigText") ?: extras.getCharSequence("android.text"))?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val row = NotificationRow(sbn.postTime, AppNames.labelOf(sbn.packageName), title.clean(), text.clean())
        synchronized(rows) {
            // apps re-post the same notification on every update
            if (rows.firstOrNull()?.let { it.app == row.app && it.title == row.title && it.text == row.text } == true) return
            rows.addFirst(row)
            if (rows.size > MAX) rows.removeLast()
        }
        try {
            file?.appendText("${row.time}\t${row.app}\t${row.title}\t${row.text}\n")
        } catch (_: Exception) {
        }
    }

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ')

    fun snapshot(): List<NotificationRow> = synchronized(rows) { rows.toList() }

    fun clear() {
        synchronized(rows) { rows.clear() }
        file?.delete()
    }
}
