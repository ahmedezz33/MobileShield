package com.ahmed.shield

import android.content.ClipboardManager
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.SecureRandom
import java.util.Random

/**
 * Overwrites files with random data before deleting them, and clears the places
 * where copies hide: thumbnails, the gallery recycle bin, notifications, the
 * clipboard and the free space of the storage.
 */
object Shredder {
    private const val CHUNK = 1 shl 20
    private const val FREE_SPACE_RESERVE = 300L shl 20

    val busy = MutableStateFlow(false)

    /** 0..1, or -1 while the amount of work is unknown. */
    val progress = MutableStateFlow(0f)
    val status = MutableStateFlow("")

    @Volatile
    private var cancelled = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val seeds = SecureRandom()

    val root: File get() = Environment.getExternalStorageDirectory()

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun cancel() {
        cancelled = true
    }

    private fun run(title: String, block: () -> String) {
        if (busy.value) return
        cancelled = false
        busy.value = true
        progress.value = -1f
        status.value = title
        scope.launch {
            status.value = try {
                block()
            } catch (e: Exception) {
                tr("خطأ: ${e.message}", "Error: ${e.message}")
            }
            busy.value = false
        }
    }

    // ---- Overwriting ----

    private fun overwrite(channel: FileChannel, length: Long, passes: Int) {
        val buf = ByteBuffer.allocate(CHUNK)
        repeat(passes) {
            val rnd = Random(seeds.nextLong())
            channel.position(0)
            var left = length
            while (left > 0 && !cancelled) {
                rnd.nextBytes(buf.array())
                buf.clear()
                buf.limit(minOf(CHUNK.toLong(), left).toInt())
                while (buf.hasRemaining()) channel.write(buf)
                left -= buf.limit()
            }
            channel.force(true) // push this pass to the flash before the next
        }
    }

    /** Overwrites, truncates, renames to a random name and deletes one file. */
    fun shredFile(ctx: Context, f: File, passes: Int): Boolean {
        if (!f.isFile) return false
        try {
            RandomAccessFile(f, "rw").use { raf ->
                overwrite(raf.channel, raf.length(), passes)
                raf.setLength(0)
            }
        } catch (_: Exception) {
            // unreadable file: still try to delete it below
        }
        val hidden = File(f.parentFile, ".x" + java.lang.Long.toHexString(seeds.nextLong()))
        val gone = if (f.renameTo(hidden)) hidden.delete() else f.delete()
        // drop the gallery's index entry and its thumbnail
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), null, null)
        return gone
    }

    private fun collect(f: File, out: MutableList<File>) {
        if (f.isDirectory) f.listFiles()?.forEach { collect(it, out) } else if (f.isFile) out += f
    }

    private fun shredAll(ctx: Context, targets: List<File>, passes: Int): String {
        val files = ArrayList<File>()
        targets.forEach { collect(it, files) }
        if (files.isEmpty()) return tr("لا يوجد شيء لإتلافه.", "Nothing to shred.")
        val total = files.sumOf { it.length() }.coerceAtLeast(1)
        var done = 0L
        var ok = 0
        for (f in files) {
            if (cancelled) break
            val len = f.length()
            if (shredFile(ctx, f, passes)) ok++
            done += len
            progress.value = done.toFloat() / total
        }
        // remove the now-empty folders (deepest first)
        targets.filter { it.isDirectory }.forEach { dir ->
            dir.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
        }
        return (if (cancelled) tr("أُوقف. ", "Stopped. ") else "") +
            tr("أُتلف $ok من ${files.size} ملف (${formatBytes(done)}).", "Shredded $ok of ${files.size} files (${formatBytes(done)}).")
    }

    fun shredFiles(ctx: Context, files: List<File>, passes: Int) =
        run(tr("جارٍ الإتلاف…", "Shredding…")) { shredAll(ctx, files, passes) }

    /** Files picked through the system file picker. */
    fun shredUris(ctx: Context, uris: List<Uri>, passes: Int) = run(tr("جارٍ إتلاف الملفات المختارة…", "Shredding the selected files…")) {
        var ok = 0
        uris.forEachIndexed { i, uri ->
            if (cancelled) return@forEachIndexed
            if (shredUri(ctx, uri, passes)) ok++
            progress.value = (i + 1f) / uris.size
        }
        tr("أُتلف $ok من ${uris.size} ملف.", "Shredded $ok of ${uris.size} files.")
    }

    private fun shredUri(ctx: Context, uri: Uri, passes: Int): Boolean {
        val cr = ctx.contentResolver
        try {
            cr.openFileDescriptor(uri, "rw")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).channel.use { overwrite(it, pfd.statSize, passes) }
            }
            cr.openFileDescriptor(uri, "rwt")?.close() // truncate to zero
        } catch (_: Exception) {
        }
        return try {
            DocumentsContract.deleteDocument(cr, uri)
        } catch (_: Exception) {
            try {
                cr.delete(uri, null, null) > 0
            } catch (_: Exception) {
                false
            }
        }
    }

    /** A folder chosen in the picker, mapped to its path on the phone storage. */
    fun treeToFile(uri: Uri): File? {
        val id = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            return null
        }
        val parts = id.split(':', limit = 2)
        if (parts[0] != "primary") return null
        return File(root, parts.getOrElse(1) { "" })
    }

    // ---- Hidden copies ----

    /** Thumbnail caches kept next to photos and videos. */
    fun thumbnailTargets(): List<File> {
        val out = LinkedHashSet<File>()
        listOf("DCIM/.thumbnails", "Pictures/.thumbnails", "Movies/.thumbnails", "Music/.thumbnails")
            .map { File(root, it) }.filterTo(out) { it.exists() }
        // other apps' .thumbnails folders, a few levels down
        root.walkTopDown().maxDepth(4)
            .onEnter { it.name != "Android" || it == root }
            .filter { it.isDirectory && it.name.equals(".thumbnails", true) }
            .forEach { out += it }
        return out.toList()
    }

    /** Files deleted to the recycle bin, which still exist until it is emptied. */
    fun trashTargets(): List<File> {
        val out = LinkedHashSet<File>()
        File(root, "Android/.Trash").takeIf { it.exists() }?.let { out += it }
        root.walkTopDown().maxDepth(6)
            .onEnter { !(it.parentFile == File(root, "Android") && (it.name == "data" || it.name == "obb")) }
            .filter { it.name.startsWith(".trashed-") }
            .forEach { out += it }
        return out.toList()
    }

    fun shredThumbnails(ctx: Context, passes: Int) =
        run(tr("جارٍ إتلاف المصغّرات…", "Shredding thumbnails…")) { shredAll(ctx, thumbnailTargets(), passes) }

    fun shredTrash(ctx: Context, passes: Int) =
        run(tr("جارٍ إتلاف سلة المحذوفات…", "Shredding the recycle bin…")) { shredAll(ctx, trashTargets(), passes) }

    /**
     * Fills the free space with random data and deletes it, overwriting what is
     * left of files that were deleted normally in the past.
     */
    fun wipeFreeSpace() = run(tr("جارٍ الكتابة فوق المساحة الفارغة…", "Overwriting free space…")) {
        val f = File(root, ".shield_wipe_" + java.lang.Long.toHexString(seeds.nextLong()))
        val target = (root.usableSpace - FREE_SPACE_RESERVE).coerceAtLeast(1)
        var written = 0L
        try {
            FileOutputStream(f).channel.use { ch ->
                val buf = ByteBuffer.allocate(4 * CHUNK)
                val rnd = Random(seeds.nextLong())
                while (!cancelled && root.usableSpace > FREE_SPACE_RESERVE) {
                    rnd.nextBytes(buf.array())
                    buf.clear()
                    while (buf.hasRemaining()) ch.write(buf)
                    written += buf.capacity()
                    progress.value = (written.toFloat() / target).coerceAtMost(1f)
                    if (written % (256L shl 20) == 0L) ch.force(false)
                }
                ch.force(true)
            }
        } finally {
            f.delete()
        }
        (if (cancelled) tr("أُوقف. ", "Stopped. ") else "") + tr("كُتب فوق ${formatBytes(written)} من المساحة الفارغة ثم حُذف.", "Overwrote ${formatBytes(written)} of free space, then deleted it.")
    }

    // ---- Other traces ----

    fun clearNotifications(): String {
        val l = ShieldNotificationListener.instance ?: return tr("امنح «الوصول إلى الإشعارات» أولًا.", "Grant notification access first.")
        val before = try {
            l.activeNotifications?.size ?: 0
        } catch (_: Exception) {
            0
        }
        l.cancelAllNotifications()
        return tr("مُسحت الإشعارات الحالية ($before). الإشعارات الدائمة لا يمكن مسحها.", "Cleared the current notifications ($before). Ongoing notifications cannot be cleared.")
    }

    fun clearClipboard(ctx: Context): String {
        ctx.getSystemService(ClipboardManager::class.java).clearPrimaryClip()
        return tr("مُسحت الحافظة.", "Clipboard cleared.")
    }

    // ---- Deep clean ----

    private val PACKAGE_NAME = Regex("^[a-zA-Z][\\w]*(\\.[a-zA-Z][\\w]*)+$")
    private val TEMP_EXT = setOf("tmp", "temp", "crdownload", "part", "partial", "download", "swp")
    private val CACHE_DIRS = setOf("cache", ".cache", "caches", ".tmp", "tmp", "temp")

    /** User media folders are never touched by the deep clean. */
    private val PROTECTED = setOf("DCIM", "Pictures", "Movies", "Music", "Recordings", "Documents", "Download")

    /**
     * Looks for what apps leave behind in shared storage. Android already deletes an
     * app's private data when it is uninstalled; only shared folders can be left over.
     */
    fun deepScan(ctx: Context): List<CleanGroup> {
        @Suppress("DEPRECATION")
        val installed = ctx.packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        val leftovers = ArrayList<File>()
        File(root, "Android/media").listFiles()?.filterTo(leftovers) {
            it.isDirectory && PACKAGE_NAME.matches(it.name) && it.name !in installed
        }
        root.listFiles()?.filterTo(leftovers) {
            it.isDirectory && PACKAGE_NAME.matches(it.name) && it.name !in installed
        }

        val temp = ArrayList<File>()
        val logs = ArrayList<File>()
        val caches = ArrayList<File>()
        root.walkTopDown().maxDepth(8)
            .onEnter { dir ->
                val rel = dir.relativeTo(root).path.replace('\\', '/')
                rel != "Android/data" && rel != "Android/obb" && dir !in leftovers
            }
            .forEach { f ->
                if (f.isDirectory) {
                    val top = f.relativeTo(root).path.substringBefore('/').substringBefore('\\')
                    if (f.name.lowercase() in CACHE_DIRS && top !in PROTECTED && f != root) caches += f
                    return@forEach
                }
                val name = f.name.lowercase()
                when {
                    name.substringAfterLast('.', "") in TEMP_EXT || name.startsWith("~$") ||
                        name.startsWith(".pending-") -> temp += f
                    name.endsWith(".log") || Regex("\\.log\\.\\d+$").containsMatchIn(name) -> logs += f
                }
            }

        fun files(dirs: List<File>) = dirs.flatMap { d -> d.walkTopDown().filter { it.isFile }.toList() }
        return listOf(
            CleanGroup(
                "leftovers",
                tr("بقايا تطبيقات محذوفة (${leftovers.size} مجلد)", "Leftovers of removed apps (${leftovers.size} folders)"),
                files(leftovers), leftovers,
            ),
            CleanGroup("temp", tr("ملفات مؤقتة وتنزيلات ناقصة", "Temporary files and incomplete downloads"), temp, emptyList()),
            CleanGroup("logs", tr("ملفات سجلات (.log)", "Log files (.log)"), logs, emptyList()),
            CleanGroup("caches", tr("مجلدات كاش في الذاكرة المشتركة", "Cache folders in shared storage"), files(caches), caches),
        ).filter { it.files.isNotEmpty() || it.dirs.isNotEmpty() }
    }

    fun deepClean(ctx: Context, groups: List<CleanGroup>, passes: Int) = run(tr("جارٍ التنظيف العميق…", "Deep cleaning…")) {
        val result = shredAll(ctx, groups.flatMap { it.files }.distinct(), passes)
        groups.flatMap { it.dirs }.forEach { d ->
            d.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
        }
        result
    }

    /** Forgets everything MobileShield itself recorded about apps and connections. */
    fun clearOwnHistory(ctx: Context): String {
        LogStore.clear()
        VoiceMonitor.clear()
        UsageStore.clear()
        DnsMap.clear(File(ctx.filesDir, "dnsmap.txt"))
        return tr("مُسحت سجلات درع الجوال وتاريخه.", "MobileShield's logs and history were cleared.")
    }
}

data class CleanGroup(val id: String, val label: String, val files: List<File>, val dirs: List<File>) {
    val bytes: Long = files.sumOf { it.length() }
}

/** Gives the shredder access to the notifications shown on the phone. */
class ShieldNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile
        var instance: ShieldNotificationListener? = null
            private set
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        NotificationLog.add(this, sbn)
    }
}
