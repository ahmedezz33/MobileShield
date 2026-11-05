package com.ahmed.shield

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Keeps 7 days of history on the phone: one CSV file per day for connections and for
 * microphone/camera/call events. The live log in memory holds only the latest events.
 */
object LogDisk {
    const val DAYS = 7
    const val CONN = "conn"
    const val VOICE = "voice"

    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val queue = ConcurrentLinkedQueue<Pair<String, String>>()
    private var dir: File? = null

    fun init(ctx: Context) {
        val d = File(ctx.filesDir, "history").apply { mkdirs() }
        dir = d
        prune()
        thread(name = "log-disk", isDaemon = true) {
            var lastPrune = System.currentTimeMillis()
            while (true) {
                Thread.sleep(2000)
                flush()
                if (System.currentTimeMillis() - lastPrune > 3_600_000) {
                    prune()
                    lastPrune = System.currentTimeMillis()
                }
            }
        }
    }

    private fun dayOf(t: Long) = synchronized(day) { day.format(Date(t)) }

    private fun file(kind: String, date: String) = File(dir, "$kind-$date.csv")

    // ---- CSV ----

    private fun q(v: Any?): String {
        val s = v?.toString().orEmpty()
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun parse(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var inQ = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQ && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQ = !inQ
                c == ',' && !inQ -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    // ---- write ----

    fun add(e: LogEntry) {
        queue += CONN to listOf(e.time, e.uid, q(e.app), q(e.pkg), e.kind, q(e.target), e.port, q(e.domain), e.verdict, e.net)
            .joinToString(",")
    }

    fun add(e: VoiceEvent) {
        queue += VOICE to listOf(e.time, e.kind, q(e.title), q(e.detail), e.locked, q(e.pkg)).joinToString(",")
    }

    @Synchronized
    fun flush() {
        if (dir == null || queue.isEmpty()) return
        val byFile = LinkedHashMap<File, StringBuilder>()
        while (true) {
            val (kind, line) = queue.poll() ?: break
            val t = line.substringBefore(',').toLongOrNull() ?: continue
            byFile.getOrPut(file(kind, dayOf(t))) { StringBuilder() }.append(line).append('\n')
        }
        for ((f, text) in byFile) {
            try {
                f.appendText(text.toString())
            } catch (_: Exception) {
            }
        }
    }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - DAYS * 86_400_000L
        dir?.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    // ---- read ----

    /** The days that have saved history, newest first. */
    fun days(): List<String> = dir?.listFiles()
        ?.filter { it.name.startsWith("$CONN-") }
        ?.map { it.name.removePrefix("$CONN-").removeSuffix(".csv") }
        ?.sortedDescending()
        .orEmpty()

    fun files(kind: String): List<File> =
        dir?.listFiles()?.filter { it.name.startsWith("$kind-") }?.sortedBy { it.name }.orEmpty()

    /** Connections of one day, newest first (at most [limit]). */
    fun readConnections(date: String, limit: Int = 100_000): List<LogEntry> {
        flush()
        val f = file(CONN, date)
        if (!f.exists()) return emptyList()
        val out = ArrayList<LogEntry>()
        f.forEachLine { line ->
            val p = parse(line)
            if (p.size < 10) return@forEachLine
            try {
                out += LogEntry(
                    p[0].toLong(), p[1].toInt(), p[2], p[3].ifEmpty { null },
                    Kind.valueOf(p[4]), p[5], p[6].toIntOrNull() ?: 0, p[7].ifEmpty { null },
                    Verdict.valueOf(p[8]), NetType.valueOf(p[9]),
                )
            } catch (_: Exception) {
            }
        }
        return out.asReversed().take(limit)
    }

    /** Microphone, camera and call events of the last 7 days, newest first. */
    fun readVoice(limit: Int = 5000): List<VoiceEvent> {
        flush()
        val out = ArrayList<VoiceEvent>()
        for (f in files(VOICE)) {
            f.forEachLine { line ->
                val p = parse(line)
                if (p.size < 6) return@forEachLine
                try {
                    out += VoiceEvent(p[0].toLong(), VoiceKind.valueOf(p[1]), p[2], p[3], p[4].toBoolean(), p[5].ifEmpty { null })
                } catch (_: Exception) {
                }
            }
        }
        return out.sortedByDescending { it.time }.take(limit)
    }
}
