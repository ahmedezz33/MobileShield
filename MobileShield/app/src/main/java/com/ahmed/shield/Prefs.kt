package com.ahmed.shield

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

data class Upstream(val primary: String, val secondary: String, val name: String)

/** Per-app firewall rules and user settings, cached in memory for the packet path. */
object Prefs {
    const val BLOCK_WIFI = 1
    const val BLOCK_CELL = 2
    const val BLOCK_LOCKED = 4
    const val ALLOW_DIRECT = 8

    const val DIRECT_OFF = 0
    const val DIRECT_LEARN = 1
    const val DIRECT_ENFORCE = 2

    val UPSTREAMS get() = listOf(
        Upstream("1.1.1.1", "1.0.0.1", "Cloudflare"),
        Upstream("8.8.8.8", "8.8.4.4", "Google"),
        Upstream("9.9.9.9", "149.112.112.112", tr("Quad9 (يحجب المواقع الضارة)", "Quad9 (blocks malicious sites)")),
        Upstream("94.140.14.14", "94.140.15.15", tr("AdGuard (حجب إضافي للإعلانات)", "AdGuard (extra ad blocking)")),
    )

    private lateinit var rules: SharedPreferences
    private lateinit var settings: SharedPreferences
    private val ruleCache = ConcurrentHashMap<String, Int>()

    val rulesVersion = MutableStateFlow(0)

    /** Cut every app off the internet on this kind of network. */
    val killWifi = MutableStateFlow(false)
    val killCell = MutableStateFlow(false)

    /** Mobile generations (2..5) on which mobile data may be used. */
    val allowedGens = MutableStateFlow(setOf(2, 3, 4, 5))
    val adBlock = MutableStateFlow(true)
    val blockDoh = MutableStateFlow(true)
    val blockTelemetry = MutableStateFlow(true)
    val directMode = MutableStateFlow(DIRECT_LEARN)
    val upstream = MutableStateFlow(UPSTREAMS[0])
    val enabledLists = MutableStateFlow<Set<String>>(emptySet())
    val allowed = MutableStateFlow<Set<String>>(emptySet())
    val customBlocked = MutableStateFlow<Set<String>>(emptySet())

    fun init(ctx: Context) {
        rules = ctx.getSharedPreferences("rules", Context.MODE_PRIVATE)
        settings = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        for ((k, v) in rules.all) if (v is Int) ruleCache[k] = v
        adBlock.value = settings.getBoolean("adblock", true)
        killWifi.value = settings.getBoolean("kill_wifi", false)
        killCell.value = settings.getBoolean("kill_cell", false)
        allowedGens.value = settings.getStringSet("gens", null)?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?: setOf(2, 3, 4, 5)
        blockDoh.value = settings.getBoolean("doh", true)
        blockTelemetry.value = settings.getBoolean("telemetry", true)
        directMode.value = settings.getInt("direct", DIRECT_LEARN)
        val up = settings.getString("upstream", null)
        upstream.value = UPSTREAMS.firstOrNull { it.primary == up } ?: UPSTREAMS[0]
        enabledLists.value = settings.getStringSet("lists", null)?.toSet()
            ?: Blocklist.SOURCES.map { it.id }.toSet()
        allowed.value = settings.getStringSet("allowed", null)?.toSet() ?: emptySet()
        customBlocked.value = settings.getStringSet("blocked", null)?.toSet() ?: emptySet()
    }

    fun rule(pkg: String): Int = ruleCache[pkg] ?: 0

    fun anyLockRule(): Boolean = ruleCache.values.any { (it and BLOCK_LOCKED) != 0 }

    fun setRule(pkg: String, mask: Int) {
        if (mask == 0) {
            ruleCache.remove(pkg)
            rules.edit().remove(pkg).apply()
        } else {
            ruleCache[pkg] = mask
            rules.edit().putInt(pkg, mask).apply()
        }
        rulesVersion.value++
        // drop open connections so the new rule applies at once
        ShieldVpnService.current?.requestReset()
    }

    fun setKillWifi(on: Boolean) {
        killWifi.value = on
        settings.edit().putBoolean("kill_wifi", on).apply()
        ShieldVpnService.current?.requestReset()
    }

    fun setKillCell(on: Boolean) {
        killCell.value = on
        settings.edit().putBoolean("kill_cell", on).apply()
        ShieldVpnService.current?.requestReset()
    }

    fun setGenAllowed(gen: Int, on: Boolean) {
        val s = if (on) allowedGens.value + gen else allowedGens.value - gen
        allowedGens.value = s
        settings.edit().putStringSet("gens", s.map { it.toString() }.toSet()).apply()
        ShieldVpnService.current?.requestReset()
    }

    /** Unknown generation (0) is never blocked, so a brief modem gap does not cut data. */
    fun genAllowed(gen: Int): Boolean = gen == 0 || gen in allowedGens.value

    fun setAdBlock(on: Boolean) {
        adBlock.value = on
        settings.edit().putBoolean("adblock", on).apply()
    }

    fun setBlockDoh(on: Boolean) {
        blockDoh.value = on
        settings.edit().putBoolean("doh", on).apply()
    }

    fun setBlockTelemetry(on: Boolean) {
        blockTelemetry.value = on
        settings.edit().putBoolean("telemetry", on).apply()
    }

    fun setDirectMode(mode: Int) {
        directMode.value = mode
        settings.edit().putInt("direct", mode).apply()
        ShieldVpnService.current?.requestReset()
    }

    fun setUpstream(u: Upstream) {
        upstream.value = u
        settings.edit().putString("upstream", u.primary).apply()
    }

    fun setListEnabled(id: String, on: Boolean) {
        val s = if (on) enabledLists.value + id else enabledLists.value - id
        enabledLists.value = s
        settings.edit().putStringSet("lists", HashSet(s)).apply()
    }

    fun allow(domain: String) {
        allowed.value = allowed.value + domain
        customBlocked.value = customBlocked.value - domain
        saveDomains()
    }

    fun block(domain: String) {
        customBlocked.value = customBlocked.value + domain
        allowed.value = allowed.value - domain
        saveDomains()
    }

    fun unallow(domain: String) {
        allowed.value = allowed.value - domain
        saveDomains()
    }

    fun unblock(domain: String) {
        customBlocked.value = customBlocked.value - domain
        saveDomains()
    }

    private fun saveDomains() {
        settings.edit()
            .putStringSet("allowed", HashSet(allowed.value))
            .putStringSet("blocked", HashSet(customBlocked.value))
            .apply()
    }
}
