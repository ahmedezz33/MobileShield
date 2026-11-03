package com.ahmed.shield

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import java.io.File

data class TrashItem(val file: File, val name: String, val target: File, val size: Long, val time: Long)

data class AppMedia(val label: String, val files: List<File>)

/**
 * Brings back what is still physically on the phone: files waiting in a recycle
 * bin, thumbnail copies of deleted photos, and media kept by messaging apps.
 * Files removed for good cannot come back: storage is encrypted and freed
 * blocks are discarded by the flash controller.
 */
object Recovery {
    private val MEDIA = setOf(
        "jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp",
        "mp4", "3gp", "mkv", "mov", "webm", "opus", "ogg", "mp3", "m4a", "aac", "pdf",
    )
    private val VIDEO = setOf("mp4", "3gp", "mkv", "mov", "webm")

    val recoveredDir: File get() = File(Shredder.root, "Pictures/Recovered")

    fun isVideo(f: File) = f.extension.lowercase() in VIDEO

    fun trashItems(): List<TrashItem> {
        val out = ArrayList<TrashItem>()
        for (t in Shredder.trashTargets()) {
            t.walkTopDown().filter { it.isFile }.forEach { f ->
                if (f.name.startsWith(".trashed-")) {
                    // MediaStore trash: ".trashed-<expiry>-<original name>" next to the original
                    val original = f.name.removePrefix(".trashed-").substringAfter('-')
                    out += TrashItem(f, original, File(f.parentFile, original), f.length(), f.lastModified())
                } else {
                    // Samsung recycle bin: original path is kept in the Gallery's private database
                    val name = withExtension(f)
                    out += TrashItem(f, name, File(recoveredDir, name), f.length(), f.lastModified())
                }
            }
        }
        return out.sortedByDescending { it.time }
    }

    fun restore(ctx: Context, item: TrashItem): Boolean {
        val dest = unique(item.target)
        dest.parentFile?.mkdirs()
        val ok = item.file.renameTo(dest) || try {
            item.file.copyTo(dest)
            item.file.delete()
            true
        } catch (_: Exception) {
            false
        }
        if (ok) MediaScannerConnection.scanFile(ctx, arrayOf(item.file.absolutePath, dest.absolutePath), null, null)
        return ok
    }

    fun thumbnails(): List<File> = Shredder.thumbnailTargets()
        .flatMap { d -> d.walkTopDown().filter { it.isFile && it.length() > 0 }.toList() }
        .sortedByDescending { it.lastModified() }

    fun appCopies(): List<AppMedia> {
        val root = Shredder.root
        val sources = listOf(
            "WhatsApp" to listOf("Android/media/com.whatsapp/WhatsApp/Media"),
            "WhatsApp Business" to listOf("Android/media/com.whatsapp.w4b/WhatsApp Business/Media"),
            "Telegram" to listOf(
                "Android/media/org.telegram.messenger/Telegram", "Telegram",
                "Pictures/Telegram", "Movies/Telegram",
            ),
            "Signal" to listOf("Pictures/Signal"),
        )
        return sources.mapNotNull { (label, dirs) ->
            val files = dirs.map { File(root, it) }.filter { it.isDirectory }
                .flatMap { d ->
                    d.walkTopDown().filter { it.isFile && it.extension.lowercase() in MEDIA }.toList()
                }
                .sortedByDescending { it.lastModified() }
                .take(1500)
            if (files.isEmpty()) null else AppMedia(label, files)
        }
    }

    /** Copies a file into Pictures/Recovered so the Gallery shows it. */
    fun saveCopy(ctx: Context, f: File): File? = try {
        val dest = unique(File(recoveredDir, withExtension(f)))
        dest.parentFile?.mkdirs()
        f.copyTo(dest)
        MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
        dest
    } catch (_: Exception) {
        null
    }

    /** Thumbnail and recycle-bin files often have no extension; guess it from the content. */
    private fun withExtension(f: File): String {
        if (f.extension.isNotEmpty()) return f.name.trimStart('.')
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        val ext = when (o.outMimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heif", "image/heic" -> "heic"
            "image/gif" -> "gif"
            null -> "bin"
            else -> "jpg"
        }
        return f.name.trimStart('.') + "." + ext
    }

    private fun unique(f: File): File {
        if (!f.exists()) return f
        var i = 1
        while (true) {
            val c = File(f.parentFile, "${f.nameWithoutExtension} ($i)" + if (f.extension.isEmpty()) "" else ".${f.extension}")
            if (!c.exists()) return c
            i++
        }
    }
}
