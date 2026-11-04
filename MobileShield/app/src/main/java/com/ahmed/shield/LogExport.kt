package com.ahmed.shield

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Saves everything MobileShield recorded into one ZIP of CSV files in Downloads,
 * so it can be analysed later (Excel, a computer, or shared with an expert).
 */
object LogExport {
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private fun time(t: Long) = synchronized(stamp) { stamp.format(Date(t)) }

    /** Quotes a CSV field when needed. */
    private fun f(v: Any?): String {
        val s = v?.toString().orEmpty()
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    private fun row(vararg v: Any?) = v.joinToString(",") { f(it) } + "\r\n"

    /** Returns the saved file's Uri and its display path. */
    fun export(ctx: Context): Pair<Uri, String> {
        val name = "MobileShield-logs-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/MobileShield")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("cannot create the file")
        try {
            cr.openOutputStream(uri)?.use { write(ctx, it) } ?: throw IllegalStateException("cannot open the file")
            cr.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            throw e
        }
        return uri to "Download/MobileShield/$name"
    }

    private fun write(ctx: Context, out: OutputStream) {
        // UTF-8 BOM so Excel shows Arabic correctly
        val bom = "﻿"
        ZipOutputStream(out).use { zip ->
            fun entry(file: String, body: StringBuilder) {
                zip.putNextEntry(ZipEntry(file))
                zip.write((bom + body).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            val log = LogStore.snapshot()
            entry("connections.csv", StringBuilder().apply {
                append(row("time", "app", "package", "uid", "kind", "target", "port", "domain", "result", "network"))
                for (e in log) append(row(time(e.time), e.app, e.pkg, e.uid, e.kind, e.target, e.port.takeIf { it > 0 }, e.domain, e.verdict, e.net))
            })

            val voice = VoiceMonitor.snapshot()
            entry("mic_camera_calls.csv", StringBuilder().apply {
                append(row("time", "event", "title", "detail", "screen_locked", "package"))
                for (e in voice) append(row(time(e.time), e.kind, e.title, e.detail.replace('\n', ' '), e.locked, e.pkg))
            })

            val notes = NotificationLog.snapshot()
            entry("notifications.csv", StringBuilder().apply {
                append(row("time", "app", "title", "text"))
                for (n in notes) append(row(time(n.time), n.app, n.title, n.text))
            })

            entry("data_usage.csv", StringBuilder().apply {
                append(row("app", "uid", "wifi_sent", "wifi_received", "mobile_sent", "mobile_received"))
                for ((uid, a) in UsageStore.snapshot()) append(row(AppNames.label(uid).first, uid, a[0], a[1], a[2], a[3]))
            })

            entry("integrity.csv", StringBuilder().apply {
                append(row("check", "value", "ok"))
                for (c in Integrity.check()) append(row(c.title, c.value, c.ok))
            })

            val version = try {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            } catch (_: Exception) {
                "?"
            }
            entry("summary.txt", StringBuilder().apply {
                append("MobileShield $version log export\r\n")
                append("saved: ${time(System.currentTimeMillis())}\r\n")
                append("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), patch ${Build.VERSION.SECURITY_PATCH}\r\n")
                append("protection running: ${ShieldVpnService.running.value}\r\n")
                append("connections/lookups: ${log.size} (total since start ${LogStore.total.get()}, ads ${LogStore.blockedAds.get()}, firewall ${LogStore.blockedFirewall.get()}, direct ${LogStore.blockedDirect.get()})\r\n")
                append("mic/camera/call events: ${voice.size}\r\n")
                append("notifications: ${notes.size}\r\n")
                if (log.isNotEmpty()) append("log covers: ${time(log.last().time)} → ${time(log.first().time)}\r\n")
                append("\r\nThe connection log keeps the latest 4000 events in memory; save regularly to keep a longer history.\r\n")
            })
        }
    }
}
