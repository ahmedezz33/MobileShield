package com.ahmed.shield

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

data class Source(val id: String, val name: String, val url: String)

/** Ad / tracker domain lists, downloaded once and kept in a local file. */
object Blocklist {
    val SOURCES get() = listOf(
        Source(
            "adguard", "AdGuard DNS filter",
            "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt"
        ),
        Source(
            "stevenblack", tr("StevenBlack (إعلانات + برمجيات ضارة)", "StevenBlack (ads + malware)"),
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        ),
        Source(
            "peterlowe", tr("Peter Lowe (إعلانات ومتتبعات)", "Peter Lowe (ads and trackers)"),
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext"
        ),
    )

    @Volatile
    private var domains: Set<String> = emptySet()
    private lateinit var file: File

    val count = MutableStateFlow(0)
    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "blocklist.txt")
        thread(name = "blocklist-load") { load() }
    }

    fun hasFile(): Boolean = file.exists()

    private fun load() {
        if (!file.exists()) return
        val set = HashSet<String>(200_000)
        file.forEachLine { if (it.isNotBlank()) set.add(it) }
        domains = set
        count.value = set.size
    }

    fun isBlocked(domain: String): Boolean =
        matches(domain, Prefs.customBlocked.value) || matches(domain, domains)

    fun isAllowed(domain: String): Boolean =
        matches(domain, Prefs.allowed.value) || matches(domain, SITE_NEEDS)

    /**
     * Names the ad lists carry but that pages need to show anything: cookie-consent
     * dialogs that keep the page hidden until they load, A/B "anti-flicker" scripts,
     * video players and news sites' own content APIs. Found in the log of news sites
     * that stayed blank.
     */
    val SITE_NEEDS = setOf(
        "cdn.cookielaw.org", "geolocation.onetrust.com", "cookie-cdn.cookiepro.com",
        "fundingchoicesmessages.google.com", "consent.cookiebot.com", "cdn.privacy-mgmt.com",
        "cdn.optimizely.com", "t.co",
        "registry.api.cnn.io", "lightning.cnn.com",
        "entitlements.jwplayer.com", "jwpsrv.com", "cdn.jwplayer.com", "jwpcdn.com", "jwplatform.com",
    )

    /** Public DNS-over-HTTPS resolvers that apps use to skip the system DNS. */
    private val DOH_HOSTS = setOf(
        "dns.google", "dns.google.com", "8888.google",
        "cloudflare-dns.com", "one.one.one.one", "1dot1dot1dot1.cloudflare-dns.com",
        "dns.quad9.net", "dns9.quad9.net", "dns10.quad9.net", "dns11.quad9.net",
        "doh.opendns.com", "dns.opendns.com", "doh.familyshield.opendns.com",
        "dns.adguard.com", "dns.adguard-dns.com", "unfiltered.adguard-dns.com",
        "family.adguard-dns.com", "dns-unfiltered.adguard.com",
        "dns.nextdns.io", "doh.cleanbrowsing.org", "doh.dns.sb", "dns.sb",
        "doh.mullvad.net", "dns.mullvad.net", "dns.alidns.com", "doh.pub",
        "dns.controld.com", "freedns.controld.com", "dns0.eu", "doh.libredns.gr",
        "ordns.he.net", "doh.applied-privacy.net", "dns.twnic.tw",
    )

    fun isDoh(domain: String): Boolean = matches(domain, DOH_HOSTS)

    /**
     * Addresses of public resolvers. Browsers (Chrome's "automatic secure DNS") open
     * encrypted DNS straight to these IPs on 443/853, skipping any name lookup.
     */
    val DOH_IPS = setOf(
        "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001",
        "104.16.248.249", "104.16.249.249", "162.159.36.1", "162.159.46.1",
        "8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844",
        "9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9",
        "94.140.14.14", "94.140.15.15", "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",
        "208.67.222.222", "208.67.220.220", "45.90.28.0", "45.90.30.0",
    )

    /**
     * Usage-data (telemetry) endpoints. Only collection hosts are listed, so the apps
     * themselves (Copilot, Link to Windows, ...) keep working.
     */
    private val TELEMETRY_HOSTS = setOf(
        "events.data.microsoft.com", "pipe.aria.microsoft.com", "vortex.data.microsoft.com",
        "telemetry.microsoft.com", "watson.microsoft.com", "data.microsoft.com.akadns.net",
        "browser.events.data.msn.com",
    )

    fun isTelemetry(domain: String): Boolean = matches(domain, TELEMETRY_HOSTS)

    /** True if the domain or any parent domain is in the set. */
    private fun matches(domain: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        var name = domain
        while (true) {
            if (name in set) return true
            val i = name.indexOf('.')
            if (i < 0) return false
            name = name.substring(i + 1)
        }
    }

    suspend fun update(): Boolean = withContext(Dispatchers.IO) {
        busy.value = true
        val set = HashSet<String>(200_000)
        val failed = mutableListOf<String>()
        try {
            for (src in SOURCES.filter { it.id in Prefs.enabledLists.value }) {
                status.value = tr("جارٍ تنزيل ${src.name}…", "Downloading ${src.name}…")
                try {
                    download(src.url) { line -> parseLine(line)?.let { set.add(it) } }
                } catch (e: Exception) {
                    failed += src.name
                }
            }
            if (set.isEmpty() && failed.isNotEmpty()) {
                status.value = tr("فشل التنزيل، تأكد من الاتصال بالإنترنت", "Download failed, check your internet connection")
                return@withContext false
            }
            file.bufferedWriter().use { w ->
                for (d in set) {
                    w.write(d)
                    w.newLine()
                }
            }
            domains = set
            count.value = set.size
            status.value = if (failed.isEmpty()) tr("تم التحديث", "Updated")
            else tr("تم التحديث، وتعذّر تنزيل: ${failed.joinToString("، ")}", "Updated; could not download: ${failed.joinToString(", ")}")
            true
        } finally {
            busy.value = false
        }
    }

    private fun download(url: String, onLine: (String) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "MobileShield/1.0")
        try {
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
        } finally {
            conn.disconnect()
        }
    }

    private val whitespace = Regex("\\s+")

    /** Accepts hosts-file lines, plain domains and adblock "||domain^" rules. */
    fun parseLine(raw: String): String? {
        var line = raw.substringBefore('#').trim()
        if (line.isEmpty() || line[0] == '!' || line[0] == '[' || line.startsWith("@@")) return null
        if (line.startsWith("||")) {
            line = line.substring(2)
            val end = line.indexOf('^')
            if (end < 0) return null
            line = line.substring(0, end)
        } else {
            val parts = line.split(whitespace)
            line = if (parts.size >= 2) parts[1] else parts[0]
        }
        line = line.lowercase().trimEnd('.')
        if ('.' !in line) return null
        if (line.any { !(it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' || it == '_') }) return null
        if (line.all { it in '0'..'9' || it == '.' }) return null
        if (line == "localhost.localdomain") return null
        return line
    }
}
