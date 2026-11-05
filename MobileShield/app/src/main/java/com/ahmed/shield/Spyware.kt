package com.ahmed.shield

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class Finding(val type: String, val value: String, val set: String, val detail: String)

data class ScanReport(
    val time: Long,
    val findings: List<Finding>,
    val apps: Int,
    val apkHashed: Int,
    val domainsChecked: Int,
    val indicators: Int,
)

/**
 * On-device spyware check with the public indicators MVT uses (Amnesty, Citizen Lab,
 * Meta, ...): known spyware apps, signing certificates, APK hashes, C2 domains and IPs,
 * file paths and system properties. Known C2 domains and IPs are also blocked live.
 */
object Spyware {
    private const val INDEX = "https://raw.githubusercontent.com/mvt-project/mvt-indicators/main/indicators.yaml"
    private const val RAW = "https://raw.githubusercontent.com/%s/%s/%s/%s"

    // type -> value -> indicator set name
    @Volatile private var domains: Map<String, String> = emptyMap()
    @Volatile private var ips: Map<String, String> = emptyMap()
    @Volatile private var appIds: Map<String, String> = emptyMap()
    @Volatile private var certs: Map<String, String> = emptyMap()
    @Volatile private var hashes: Map<String, String> = emptyMap()
    @Volatile private var paths: Map<String, String> = emptyMap()
    @Volatile private var props: Map<String, String> = emptyMap()

    val count = MutableStateFlow(0)
    val sets = MutableStateFlow(0)
    val updatedAt = MutableStateFlow(0L)
    val busy = MutableStateFlow(false)
    val status = MutableStateFlow("")
    val progress = MutableStateFlow(0f)
    val report = MutableStateFlow<ScanReport?>(null)

    private lateinit var file: File
    private val alerted = HashSet<String>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "ioc.tsv")
        if (file.exists()) {
            updatedAt.value = file.lastModified()
            load(file.readLines())
        }
    }

    // ---- indicators ----

    /** Parses "type<TAB>value<TAB>set" lines into the lookup tables. */
    fun load(lines: List<String>) {
        val by = HashMap<String, HashMap<String, String>>()
        val setNames = HashSet<String>()
        for (l in lines) {
            val p = l.split('\t')
            if (p.size != 3) continue
            by.getOrPut(p[0]) { HashMap() }[p[1]] = p[2]
            setNames += p[2]
        }
        domains = by["domain"].orEmpty()
        ips = by["ip"].orEmpty()
        appIds = by["app"].orEmpty()
        certs = by["cert"].orEmpty()
        hashes = by["hash"].orEmpty()
        paths = by["path"].orEmpty()
        props = by["prop"].orEmpty()
        count.value = by.values.sumOf { it.size }
        sets.value = setNames.size
    }

    private val ENTRY = Regex("\\[([a-z0-9:_\\-.]+)\\s*=\\s*'([^']*)'")
    private val PATTERN = Regex("\"pattern\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    /** Turns one STIX2 file into indicator lines. */
    fun parseStix(text: String, set: String): List<String> {
        val out = ArrayList<String>()
        for (pm in PATTERN.findAll(text)) {
            val pattern = pm.groupValues[1].replace("\\'", "'")
            for (m in ENTRY.findAll(pattern)) {
                val v = m.groupValues[2].trim()
                if (v.isEmpty()) continue
                val type = when (m.groupValues[1]) {
                    "domain-name:value" -> "domain"
                    "ipv4-addr:value" -> "ip"
                    "app:id" -> "app"
                    "app:cert.sha1", "app:cert.sha256" -> "cert"
                    "file:hashes.sha256" -> "hash"
                    "file:path" -> "path"
                    "android-property:name" -> "prop"
                    else -> null
                } ?: continue
                val value = if (type == "app" || type == "path" || type == "prop") v else v.lowercase()
                out += "$type\t$value\t$set"
            }
        }
        return out
    }

    /** (set name, raw URL) for every file listed in the MVT index. */
    fun parseIndex(yaml: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        var name = ""
        val gh = HashMap<String, String>()
        var download = ""
        fun flush() {
            if (download.isNotEmpty()) out += name to download
            else if (gh.size == 4) out += name to RAW.format(gh["owner"], gh["repo"], gh["branch"], gh["path"])
            name = ""; download = ""; gh.clear()
        }
        for (raw in yaml.lines()) {
            val line = raw.trim()
            if (line == "-") { flush(); continue }
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "name" -> if (name.isEmpty()) name = value
                "owner", "repo", "branch", "path" -> gh[key] = value
                "download_url" -> download = value
            }
        }
        flush()
        return out
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    suspend fun update(): Boolean = withContext(Dispatchers.IO) {
        busy.value = true
        try {
            status.value = tr("جارٍ تنزيل فهرس المؤشرات…", "Downloading the indicator index…")
            val list = parseIndex(fetch(INDEX))
            val lines = ArrayList<String>()
            var failed = 0
            list.forEachIndexed { i, (name, url) ->
                status.value = tr("تنزيل ${i + 1} من ${list.size}: $name", "Downloading ${i + 1} of ${list.size}: $name")
                progress.value = (i + 1f) / list.size
                try {
                    lines += parseStix(fetch(url), name.replace('\t', ' '))
                } catch (_: Exception) {
                    failed++
                }
            }
            if (lines.isEmpty()) {
                status.value = tr("تعذّر تنزيل المؤشرات.", "Could not download the indicators.")
                return@withContext false
            }
            file.writeText(lines.joinToString("\n"))
            load(lines)
            updatedAt.value = System.currentTimeMillis()
            status.value = tr(
                "حُدّثت: ${count.value} مؤشر من ${sets.value} مجموعة" + if (failed > 0) " (تعذّر $failed)" else "",
                "Updated: ${count.value} indicators from ${sets.value} sets" + if (failed > 0) " ($failed failed)" else "",
            )
            true
        } catch (e: Exception) {
            status.value = tr("تعذّر التحديث: ${e.message}", "Update failed: ${e.message}")
            false
        } finally {
            busy.value = false
        }
    }

    // ---- live protection ----

    /** Spyware set that lists this domain (or a parent), else null. */
    fun domainSet(domain: String): String? {
        val d = domains
        if (d.isEmpty()) return null
        var name = domain
        while (true) {
            d[name]?.let { return it }
            val i = name.indexOf('.')
            if (i < 0) return null
            name = name.substring(i + 1)
        }
    }

    fun ipSet(ip: String): String? = ips[ip]

    /** Urgent alert, once per address per run. */
    fun alertContact(ctx: Context, target: String, set: String, app: String) {
        synchronized(alerted) { if (!alerted.add(target)) return }
        Notifier.alert(
            ctx, 90 + (alerted.size % 10),
            tr("⚠️ اتصال بخادم برنامج تجسس معروف", "⚠️ Contact with a known spyware server"),
            tr(
                "$app حاول الاتصال بـ $target، وهو مدرج في مؤشرات «$set». حُظر الاتصال. افحص الجوال من الإعدادات ← فحص برامج التجسس.",
                "$app tried to reach $target, listed in the \"$set\" indicators. The connection was blocked. Scan the phone from Settings → Spyware scan.",
            ),
        )
    }

    // ---- full scan ----

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    suspend fun scan(ctx: Context): ScanReport = withContext(Dispatchers.IO) {
        busy.value = true
        val findings = ArrayList<Finding>()
        try {
            val pm = ctx.packageManager
            status.value = tr("فحص التطبيقات المثبّتة وتواقيعها…", "Checking installed apps and signatures…")
            @Suppress("DEPRECATION")
            val packages = pm.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES)
            for (p in packages) {
                val label = p.applicationInfo?.let { pm.getApplicationLabel(it).toString() } ?: p.packageName
                appIds[p.packageName]?.let {
                    findings += Finding(tr("تطبيق", "App"), p.packageName, it, label)
                }
                val signers = p.signingInfo?.let {
                    if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
                }.orEmpty()
                for (s in signers) {
                    val bytes = s.toByteArray()
                    for (algo in listOf("SHA-1", "SHA-256")) {
                        val h = hex(MessageDigest.getInstance(algo).digest(bytes))
                        certs[h]?.let { findings += Finding(tr("توقيع تطبيق", "App certificate"), h, it, "$label (${p.packageName})") }
                    }
                }
            }

            // APK hashes for apps the user installed or updated (system images are covered by verified boot)
            val toHash = packages.mapNotNull { it.applicationInfo }.filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            }
            var hashed = 0
            toHash.forEachIndexed { i, ai ->
                status.value = tr("حساب بصمة ملفات التطبيقات ${i + 1} من ${toHash.size}", "Hashing app files ${i + 1} of ${toHash.size}")
                progress.value = (i + 1f) / toHash.size
                for (path in listOfNotNull(ai.sourceDir) + ai.splitSourceDirs.orEmpty()) {
                    try {
                        val h = sha256(File(path))
                        hashed++
                        hashes[h]?.let { findings += Finding(tr("ملف تطبيق", "App file"), h, it, "${pm.getApplicationLabel(ai)} (${ai.packageName})") }
                    } catch (_: Exception) {
                    }
                }
            }

            status.value = tr("فحص سجل الاتصالات لآخر 7 أيام…", "Checking the last 7 days of connections…")
            val seen = HashSet<String>()
            for (day in LogDisk.days()) for (e in LogDisk.readConnections(day, Int.MAX_VALUE)) {
                seen += e.target
                e.domain?.let { seen += it }
            }
            for (e in LogStore.snapshot()) { seen += e.target; e.domain?.let { seen += it } }
            for (t in seen) {
                (domainSet(t) ?: ipSet(t))?.let { findings += Finding(tr("اتصال", "Connection"), t, it, tr("ظهر في سجل الاتصالات", "Seen in the connection log")) }
            }

            status.value = tr("فحص ملفات النظام وخصائصه…", "Checking system files and properties…")
            for ((path, set) in paths) {
                if (!path.contains('*') && File(path).exists()) findings += Finding(tr("ملف", "File"), path, set, tr("موجود على الجوال", "Present on the phone"))
            }
            for ((prop, set) in props) {
                val v = try {
                    ProcessBuilder("getprop", prop).start().inputStream.bufferedReader().readText().trim()
                } catch (_: Exception) {
                    ""
                }
                if (v.isNotEmpty()) findings += Finding(tr("خاصية نظام", "System property"), prop, set, v)
            }

            val r = ScanReport(System.currentTimeMillis(), findings.distinct(), packages.size, hashed, seen.size, count.value)
            report.value = r
            status.value = if (findings.isEmpty()) tr("انتهى الفحص: لا يوجد أي تطابق.", "Scan finished: no matches.")
            else tr("انتهى الفحص: ${findings.size} تطابق!", "Scan finished: ${findings.size} matches!")
            r
        } finally {
            busy.value = false
            progress.value = 0f
        }
    }
}
