package com.ahmed.shield

import android.content.Context
import java.io.File

data class IntegrityCheck(val id: String, val title: String, val value: String, val ok: Boolean)

/**
 * Watches the signs that something gained more power than Android itself:
 * verified boot, bootloader lock, the Knox warranty fuse, SELinux, root binaries.
 * An app cannot stop a kernel exploit, but on a Samsung phone Knox (RKP, DEFEX)
 * blocks most of them, and any that succeeds tends to change one of these.
 */
object Integrity {
    private val SU_PATHS = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su", "/system/sbin/su",
        "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su", "/data/local/tmp/su",
        "/system/app/Superuser.apk", "/sbin/.magisk", "/cache/.disable_magisk", "/dev/.magisk.unblock",
    )

    private fun prop(name: String): String = try {
        ProcessBuilder("getprop", name).start().inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) {
        ""
    }

    private fun selinux(): String {
        val viaApi = try {
            val c = Class.forName("android.os.SELinux")
            val enabled = c.getMethod("isSELinuxEnabled").invoke(null) as Boolean
            val enforced = c.getMethod("isSELinuxEnforced").invoke(null) as Boolean
            if (!enabled) "Disabled" else if (enforced) "Enforcing" else "Permissive"
        } catch (_: Exception) {
            null
        }
        if (viaApi != null) return viaApi
        return try {
            ProcessBuilder("getenforce").start().inputStream.bufferedReader().readText().trim().ifEmpty { "?" }
        } catch (_: Exception) {
            "?"
        }
    }

    fun check(): List<IntegrityCheck> {
        val boot = prop("ro.boot.verifiedbootstate")
        val locked = prop("ro.boot.flash.locked")
        val warranty = prop("ro.boot.warranty_bit")
        val debuggable = prop("ro.debuggable")
        val tags = prop("ro.build.tags")
        val se = selinux()
        val su = SU_PATHS.filter { File(it).exists() }
        return listOf(
            IntegrityCheck("boot", tr("التمهيد الموثَّق", "Verified boot"), boot, boot == "green"),
            IntegrityCheck("lock", tr("قفل محمّل الإقلاع", "Bootloader lock"), locked, locked == "1"),
            IntegrityCheck("knox", tr("مؤشر Knox (الضمان)", "Knox warranty bit"), warranty.ifEmpty { "?" }, warranty == "0" || warranty.isEmpty()),
            IntegrityCheck("selinux", "SELinux", se, se == "Enforcing" || se == "?"),
            IntegrityCheck("debug", tr("نسخة النظام", "System build"), "$tags / debuggable=$debuggable", tags == "release-keys" && debuggable != "1"),
            IntegrityCheck("su", tr("ملفات الروت", "Root files"), if (su.isEmpty()) tr("لا يوجد", "none") else su.joinToString(), su.isEmpty()),
        )
    }

    /**
     * Compares with the first healthy reading and alerts once for every check that
     * changed. Returns the checks for display.
     */
    fun watch(ctx: Context): List<IntegrityCheck> {
        val now = check()
        val prefs = ctx.getSharedPreferences("integrity", Context.MODE_PRIVATE)
        val changed = ArrayList<IntegrityCheck>()
        val edit = prefs.edit()
        for (c in now) {
            val base = prefs.getString("base_${c.id}", null)
            if (base == null) {
                edit.putString("base_${c.id}", c.value)
            } else if (base != c.value && prefs.getString("alerted_${c.id}", null) != c.value) {
                changed += c
                edit.putString("alerted_${c.id}", c.value)
            }
        }
        edit.apply()
        val failing = now.filter { !it.ok && prefs.getString("alerted_bad_${it.id}", null) != it.value }
        failing.forEach { prefs.edit().putString("alerted_bad_${it.id}", it.value).apply() }
        val report = (changed + failing).distinctBy { it.id }
        if (report.isNotEmpty()) {
            Notifier.alert(
                ctx, 60, tr("تحذير: تغيّرت سلامة النظام", "Warning: system integrity changed"),
                report.joinToString("\n") { "${it.title}: ${it.value}" } + "\n" +
                    tr(
                        "قد يعني هذا أن شيئًا حصل على صلاحيات أعلى من أندرويد. افصل الإنترنت، وانقل بياناتك المهمة، واطلب فحصًا متخصصًا.",
                        "This may mean something gained more power than Android. Disconnect from the internet, move your important data, and ask for an expert check.",
                    ),
            )
        }
        return now
    }
}
