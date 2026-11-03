package com.ahmed.shield

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.io.File
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * IP address -> domain, learned from every DNS answer and TLS server name.
 * A connection to an address that is not in here was made "directly", without DNS.
 */
object DnsMap {
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private const val MAX_SIZE = 100_000

    private class Entry(val domain: String, val time: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    fun put(ip: String, domain: String, now: Long = System.currentTimeMillis()) {
        map[ip] = Entry(domain, now)
        if (map.size > MAX_SIZE) prune(now)
    }

    fun isKnown(ip: String, now: Long = System.currentTimeMillis()): Boolean =
        map[ip]?.let { now - it.time < MAX_AGE_MS } == true

    fun name(ip: String): String? = map[ip]?.domain

    private fun prune(now: Long) {
        map.entries.removeIf { now - it.value.time >= MAX_AGE_MS }
    }

    fun load(file: File) {
        if (!file.exists()) return
        val now = System.currentTimeMillis()
        try {
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size == 3) {
                    val t = p[2].toLongOrNull() ?: return@forEachLine
                    if (now - t < MAX_AGE_MS) map.putIfAbsent(p[0], Entry(p[1], t))
                }
            }
        } catch (_: Exception) {
        }
    }

    fun clear(file: File) {
        map.clear()
        file.delete()
    }

    fun save(file: File) {
        try {
            prune(System.currentTimeMillis())
            file.bufferedWriter().use { w ->
                for ((ip, e) in map) w.write("$ip\t${e.domain}\t${e.time}\n")
            }
        } catch (_: Exception) {
        }
    }
}

object IpUtil {
    /** Local, private, link-local, multicast or carrier-NAT addresses never count as "direct". */
    fun isLocal(ip: String): Boolean {
        if (ip.isEmpty() || !(ip[0].isDigit() || ip[0] in 'a'..'f' || ip[0] in 'A'..'F' || ip[0] == ':')) return false
        val a = try {
            InetAddress.getByName(ip) // numeric literal, no lookup
        } catch (_: Exception) {
            return false
        }
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress ||
            a.isSiteLocalAddress || a.isMulticastAddress
        ) return true
        val b = a.address
        if (b.size == 4) {
            val x = b[0].toInt() and 0xff
            val y = b[1].toInt() and 0xff
            return (x == 100 && y in 64..127) || x == 255
        }
        return (b[0].toInt() and 0xfe) == 0xfc // fc00::/7 unique local
    }
}

/** Bytes per app measured by the engine since the protection started. */
object UsageStore {
    // [wifi sent, wifi received, cellular sent, cellular received]
    private val map = ConcurrentHashMap<Int, LongArray>()

    fun add(uid: Int, sent: Long, received: Long, net: NetType) {
        val a = map.getOrPut(uid) { LongArray(4) }
        val i = if (net == NetType.CELLULAR) 2 else 0
        synchronized(a) {
            a[i] += sent
            a[i + 1] += received
        }
    }

    fun snapshot(): Map<Int, LongArray> = map.mapValues { (_, a) -> synchronized(a) { a.copyOf() } }

    fun clear() = map.clear()
}

/**
 * Blocked names are answered with an address unique to the name (see sinkhole_answers
 * in dns.c). A connection to such an address reveals exactly which app wanted the name.
 */
object Sinkhole {
    private val names = ConcurrentHashMap<Int, String>()

    /** FNV-1a over the name, identical to the native code. */
    fun indexOf(name: String): Int {
        var h = 0x811c9dc5.toInt()
        for (b in name.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toInt() and 0xff)
            h *= 16777619
        }
        return ((h.toLong() and 0xffffffffL) % 131072).toInt()
    }

    fun register(name: String) {
        names[indexOf(name)] = name
    }

    /** Domain behind a sinkhole address, or null when [ip] is not one. */
    fun nameFor(ip: String): String? {
        val idx = when {
            ip.startsWith("198.18.") || ip.startsWith("198.19.") -> {
                val p = ip.split('.')
                if (p.size != 4) return null
                ((p[1].toInt() - 18) shl 16) or (p[2].toInt() shl 8) or p[3].toInt()
            }
            ip.startsWith("2001:db8:") -> try {
                val b = InetAddress.getByName(ip).address
                ((b[13].toInt() and 0xff) shl 16) or ((b[14].toInt() and 0xff) shl 8) or (b[15].toInt() and 0xff)
            } catch (_: Exception) {
                return null
            }
            else -> return null
        }
        return names[idx] ?: tr("نطاق محظور", "Blocked domain")
    }
}

/** The app on screen, used to estimate who asked for a blocked ad domain. */
object Foreground {
    private const val LAUNCHER = "com.sec.android.app.launcher"

    @Volatile
    private var cached: String? = null

    @Volatile
    private var checkedAt = 0L

    fun pkg(ctx: Context): String? {
        val now = System.currentTimeMillis()
        if (now - checkedAt < 2000) return cached
        checkedAt = now
        cached = try {
            val usm = ctx.getSystemService(UsageStatsManager::class.java)
            val events = usm.queryEvents(now - 10 * 60_000, now)
            val e = UsageEvents.Event()
            var last: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
            }
            last?.takeIf { it != ctx.packageName && it != LAUNCHER }
        } catch (_: Exception) {
            null
        }
        return cached
    }
}

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", b / (1L shl 10).toDouble())
    else -> "$b B"
}
