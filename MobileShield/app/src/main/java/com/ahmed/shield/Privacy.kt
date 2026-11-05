package com.ahmed.shield

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** A set of domains that one switch blocks, e.g. a vendor's usage-data servers. */
data class DomainGroup(val id: String, val domains: Set<String>, val defaultOn: Boolean)

/** One service the user can rein in: its data-collection domains and/or the whole app. */
data class PrivacyTarget(
    val id: String,
    val name: String,
    val whyAr: String,
    val whyEn: String,
    /** Domain group blocked by the "usage data" switch, if any. */
    val group: String?,
    /** Packages cut off the internet by the "full block" switch. */
    val packages: List<String>,
    /** Shown under the full-block switch: what stops working. */
    val fullAr: String = "",
    val fullEn: String = "",
)

/**
 * Per-service privacy switches. "Usage data" blocks only collection servers, so the
 * service keeps working; "full block" cuts the app off the internet through the firewall.
 */
object Privacy {
    val GROUPS = listOf(
        DomainGroup(
            "microsoft",
            setOf(
                "events.data.microsoft.com", "pipe.aria.microsoft.com", "vortex.data.microsoft.com",
                "telemetry.microsoft.com", "watson.microsoft.com", "browser.events.data.msn.com",
                "exp-tas.com", "singular.net",
            ),
            true,
        ),
        DomainGroup(
            "google_analytics",
            setOf(
                "app-measurement.com", "firebaselogging.googleapis.com", "firebaselogging-pa.googleapis.com",
                "google-analytics.com", "analytics.google.com", "crashlytics.com",
            ),
            true,
        ),
        DomainGroup(
            "google_ads",
            setOf(
                "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagmanager.com",
                "googletagservices.com", "adservice.google.com", "ogads-pa.googleapis.com", "admob.com",
            ),
            true,
        ),
        DomainGroup(
            "chrome_reports",
            setOf(
                "beacons.gvt2.com", "beacons.gcp.gvt2.com", "beacons2.gvt2.com", "beacons3.gvt2.com",
                "beacons4.gvt2.com", "beacons5.gvt2.com", "clientservices.googleapis.com",
            ),
            true,
        ),
        DomainGroup(
            "claude_telemetry",
            setOf(
                "datadoghq.com", "datadoghq.eu", "siftscience.com", "sift.com",
                // Datadog's intake hosts carry the region inside the name, not as a subdomain
                "browser-intake-datadoghq.com", "browser-intake-us3-datadoghq.com",
                "browser-intake-us5-datadoghq.com", "browser-intake-ap1-datadoghq.com",
                "browser-intake-datadoghq.eu",
            ),
            true,
        ),
        DomainGroup(
            "samsung_telemetry",
            setOf(
                "dqa.samsung.com", "di.atlas.samsung.com", "samsungads.com", "samsungadhub.com",
                "stats.samsungpass.com", "sia.internet.apps.samsung.com", "samsungacr.com",
            ),
            true,
        ),
        DomainGroup("hiya", setOf("hiyaapi.com", "hiya.com"), false),
        DomainGroup("galaxy_ai", setOf("bixbyllm.com"), false),
    )

    val TARGETS
        get() = listOf(
            PrivacyTarget(
                "link_windows", "Link to Windows",
                "يربط الجوال بكمبيوتر Windows، ويرسل بيانات استخدامك إلى Microsoft كل 25 ثانية تقريبًا.",
                "Links the phone to a Windows PC, and sends your usage data to Microsoft about every 25 seconds.",
                "microsoft", listOf("com.microsoft.appmanager", "com.microsoft.deviceintegrationservice"),
                "يتوقف ربط الجوال بالكمبيوتر.", "Linking the phone to the PC stops.",
            ),
            PrivacyTarget(
                "hiya", "Hiya",
                "يرسل أرقام من يتصلون بك إلى شركة Hiya ليعرف هل الرقم مزعج.",
                "Sends the numbers of people calling you to Hiya to check whether they are spam.",
                "hiya", listOf("com.hiya.star"),
                "يتوقف كشف المتصلين المزعجين.", "Spam caller detection stops.",
            ),
            PrivacyTarget(
                "samsung_cloud", "Samsung Cloud",
                "يزامن صورك وجهات الاتصال والنسخ الاحتياطي مع سحابة سامسونج.",
                "Syncs your photos, contacts and backups with Samsung's cloud.",
                null, listOf("com.samsung.android.scloud", "com.samsung.android.mobileservice"),
                "تتوقف المزامنة والنسخ الاحتياطي والألبومات المشتركة.", "Sync, backup and shared albums stop.",
            ),
            PrivacyTarget(
                "google_play", "Google Play services",
                "يوصل إشعارات كل التطبيقات (ومنها واتساب) والحسابات والموقع، ويرسل تحليلات Firebase. القطع الكامل غير متاح لأنه يوقف كل الإشعارات.",
                "Delivers notifications for every app (WhatsApp included), accounts and location, and sends Firebase analytics. Full block is not offered: it would stop all notifications.",
                "google_analytics", emptyList(),
            ),
            PrivacyTarget(
                "claude", "Claude",
                "يتصل بـ claude.ai للمحادثة، ويرسل بيانات أداء إلى Datadog وفحص احتيال إلى Sift.",
                "Talks to claude.ai for conversations, and sends performance data to Datadog and a fraud check to Sift.",
                "claude_telemetry", listOf("com.anthropic.claude"),
                "التطبيق لن يعمل.", "The app will not work.",
            ),
            PrivacyTarget(
                "office", "Word / OneDrive",
                "يسجّل الدخول ويحفظ الملفات في سحابة Microsoft، ويرسل بيانات استخدام ومتتبع Singular.",
                "Signs in and saves files to Microsoft's cloud, and sends usage data and the Singular tracker.",
                "microsoft", listOf("com.microsoft.office.word", "com.microsoft.skydrive"),
                "يعمل Word على الملفات المحلية فقط، وتتوقف مزامنة OneDrive.", "Word works on local files only; OneDrive sync stops.",
            ),
            PrivacyTarget(
                "google_ads", tr("إعلانات Google", "Google ads"),
                "إعلانات Google داخل التطبيقات والمواقع (DoubleClick وAdMob وGoogle Tag Manager).",
                "Google ads inside apps and websites (DoubleClick, AdMob, Google Tag Manager).",
                "google_ads", emptyList(),
            ),
            PrivacyTarget(
                "chrome", "Chrome",
                "يفتح المواقع التي تزورها أنت، ويتصل للتحديثات والتصفح الآمن، ويرسل تقارير أداء إلى Google.",
                "Opens the sites you visit, checks for updates and Safe Browsing, and sends performance reports to Google.",
                "chrome_reports", listOf("com.android.chrome"),
                "لن يفتح Chrome أي موقع.", "Chrome will not open any site.",
            ),
            PrivacyTarget(
                "samsung", tr("سامسونج", "Samsung"),
                "ترسل سامسونج بيانات تشخيص واستخدام وإعلانات من تطبيقاتها (المتصفح وSamsung Pass والنظام).",
                "Samsung sends diagnostics, usage data and ads from its apps (browser, Samsung Pass, system).",
                "samsung_telemetry", emptyList(),
            ),
            PrivacyTarget(
                "galaxy_ai", "Galaxy AI",
                "خدمة الذكاء الاصطناعي من سامسونج ترسل بعض طلباتك إلى سحابتها (bixbyllm.com).",
                "Samsung's AI service sends some of your requests to its cloud (bixbyllm.com).",
                "galaxy_ai", emptyList(),
            ),
        )

    private val byDomain: List<DomainGroup> get() = GROUPS

    /** Ids of the domain groups currently blocked. */
    val enabled = MutableStateFlow<Set<String>>(emptySet())

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        enabled.value = p.getStringSet("privacy_groups", null)?.toSet()
            ?: GROUPS.filter { it.defaultOn }.map { it.id }.toSet().let { defaults ->
                // keep the earlier Microsoft telemetry switch's choice
                if (p.getBoolean("telemetry", true)) defaults else defaults - "microsoft"
            }
    }

    fun setGroup(ctx: Context, id: String, on: Boolean) {
        val s = if (on) enabled.value + id else enabled.value - id
        enabled.value = s
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putStringSet("privacy_groups", HashSet(s)).apply()
    }

    /** Name of the enabled group that blocks this domain, or null. */
    fun blockingGroup(domain: String): String? {
        val on = enabled.value
        if (on.isEmpty()) return null
        for (g in byDomain) {
            if (g.id !in on) continue
            var name = domain
            while (true) {
                if (name in g.domains) return g.id
                val i = name.indexOf('.')
                if (i < 0) break
                name = name.substring(i + 1)
            }
        }
        return null
    }

    /** True when every installed package of the target is cut off on Wi-Fi and mobile data. */
    fun fullyBlocked(installed: List<String>): Boolean =
        installed.isNotEmpty() && installed.all {
            val m = Prefs.rule(it)
            (m and Prefs.BLOCK_WIFI) != 0 && (m and Prefs.BLOCK_CELL) != 0
        }

    fun setFullBlock(installed: List<String>, on: Boolean) {
        val bits = Prefs.BLOCK_WIFI or Prefs.BLOCK_CELL
        for (pkg in installed) {
            val m = Prefs.rule(pkg)
            Prefs.setRule(pkg, if (on) m or bits else m and bits.inv())
        }
    }
}
