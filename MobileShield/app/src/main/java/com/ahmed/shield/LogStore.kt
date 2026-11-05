package com.ahmed.shield

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class Verdict {
    ALLOWED,
    DIRECT_SEEN, // direct IP connection let through in learning mode
    BLOCKED_AD,
    BLOCKED_FIREWALL,
    BLOCKED_LOCKED,
    BLOCKED_DIRECT,
    BLOCKED_BYPASS, // encrypted or foreign DNS that would skip our filter
}

val Verdict.blocked: Boolean get() = this != Verdict.ALLOWED && this != Verdict.DIRECT_SEEN

enum class Kind { DNS, TLS, TCP, UDP, ICMP }

enum class NetType { WIFI, CELLULAR, OTHER, NONE }

data class LogEntry(
    val time: Long,
    val uid: Int,
    val app: String,
    val pkg: String?,
    val kind: Kind,
    /** Domain for DNS/TLS entries, IP address for connections. */
    val target: String,
    val port: Int,
    /** Domain the IP was resolved from, when known. */
    val domain: String?,
    val verdict: Verdict,
    val net: NetType,
)

/** In-memory ring buffer of recent lookups and connections. */
object LogStore {
    private const val MAX = 4000
    private val buf = ArrayDeque<LogEntry>()

    val total = AtomicLong()
    val blockedAds = AtomicLong()
    val blockedFirewall = AtomicLong()
    val blockedDirect = AtomicLong()

    /** Direct-IP connections per uid, to suggest exceptions before enforcing. */
    val directByUid = ConcurrentHashMap<Int, AtomicInteger>()

    fun add(e: LogEntry) {
        LogDisk.add(e)
        synchronized(buf) {
            buf.addFirst(e)
            if (buf.size > MAX) buf.removeLast()
        }
        total.incrementAndGet()
        when (e.verdict) {
            Verdict.BLOCKED_AD -> blockedAds.incrementAndGet()
            Verdict.BLOCKED_FIREWALL, Verdict.BLOCKED_LOCKED -> blockedFirewall.incrementAndGet()
            Verdict.BLOCKED_DIRECT, Verdict.BLOCKED_BYPASS -> blockedDirect.incrementAndGet()
            Verdict.ALLOWED, Verdict.DIRECT_SEEN -> {}
        }
    }

    /**
     * Android resolves names in a system service, so DNS entries arrive without an app.
     * When an app then connects to an address of that domain, give the recent entries to it.
     */
    fun attribute(domain: String, uid: Int, app: String, pkg: String?, now: Long) {
        synchronized(buf) {
            var i = 0
            for (e in buf) {
                if (i >= 300 || now - e.time > 60_000) break
                if (e.kind == Kind.DNS && e.uid < 0 && e.target == domain) {
                    buf[i] = e.copy(uid = uid, app = app, pkg = pkg)
                }
                i++
            }
        }
    }

    fun noteDirect(uid: Int) {
        directByUid.getOrPut(uid) { AtomicInteger() }.incrementAndGet()
    }

    fun snapshot(): List<LogEntry> = synchronized(buf) { buf.toList() }

    fun clear() {
        synchronized(buf) { buf.clear() }
        total.set(0)
        blockedAds.set(0)
        blockedFirewall.set(0)
        blockedDirect.set(0)
        directByUid.clear()
    }
}
