package com.ahmed.shield.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahmed.shield.AppMedia
import com.ahmed.shield.Recovery
import com.ahmed.shield.Shredder
import com.ahmed.shield.TrashItem
import com.ahmed.shield.formatBytes
import com.ahmed.shield.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shredding and recovery share one tab: they work on the same hidden copies. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen() {
    var part by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp)) {
            FilterChip(part == 0, { part = 0 }, { Text(tr("الإتلاف", "Shred")) }, Modifier.padding(4.dp))
            FilterChip(part == 1, { part = 1 }, { Text(tr("الاسترجاع", "Recover")) }, Modifier.padding(4.dp))
        }
        if (part == 0) ShredScreen() else RecoveryScreen()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecoveryScreen() {
    val ctx = LocalContext.current
    if (!Shredder.hasAllFilesAccess()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("الاسترجاع يحتاج «الوصول إلى كل الملفات».", "Recovery needs \"All files access\"."))
            Button(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                )
            }) { Text(tr("فعّل", "Enable")) }
        }
        return
    }
    var section by rememberSaveable { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Text(
            tr(
                "يُسترجع ما زال موجودًا فعليًا على الجوال. ما حُذف نهائيًا لا يعود: الذاكرة مشفّرة وتُفرَّغ فور الحذف.",
                "Only what still physically exists can be recovered. Files deleted for good cannot return: storage is encrypted and cleared right after deletion.",
            ),
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            listOf(
                tr("سلة المحذوفات", "Recycle bin"),
                tr("مصغّرات الصور", "Thumbnails"),
                tr("نسخ في التطبيقات", "Copies in apps"),
            ).forEachIndexed { i, label ->
                FilterChip(section == i, { section = i; note = "" }, { Text(label) }, Modifier.padding(4.dp))
            }
        }
        if (note.isNotEmpty()) {
            Text(note, Modifier.padding(horizontal = 12.dp), color = Green, fontWeight = FontWeight.Medium)
        }
        when (section) {
            0 -> TrashSection(refresh, onChanged = { msg -> note = msg; refresh++ })
            1 -> {
                val files by produceState<List<File>?>(null, refresh) {
                    value = withContext(Dispatchers.IO) { Recovery.thumbnails() }
                }
                MediaGrid(files, { note = it })
            }
            else -> AppCopiesSection { note = it }
        }
    }
}

@Composable
private fun TrashSection(refresh: Int, onChanged: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by produceState<List<TrashItem>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { Recovery.trashItems() }
    }
    val list = items
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (list.isEmpty()) tr("سلة المحذوفات فارغة.", "The recycle bin is empty.")
                    else tr("${list.size} ملف في السلة", "${list.size} files in the bin"),
                    Modifier.weight(1f),
                )
                if (list.isNotEmpty()) {
                    TextButton(onClick = {
                        scope.launch {
                            val n = withContext(Dispatchers.IO) { list.count { Recovery.restore(ctx, it) } }
                            onChanged(tr("استُرجع $n ملف.", "Restored $n files."))
                        }
                    }) { Text(tr("استرجاع الكل", "Restore all")) }
                }
            }
            HorizontalDivider()
        }
        items(list) { t ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(t.file, Modifier.padding(end = 8.dp).fillMaxWidth(0.18f))
                Column(Modifier.weight(1f)) {
                    Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        formatBytes(t.size) + " · " + t.target.parent.orEmpty().removePrefix(Shredder.root.absolutePath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Recovery.restore(ctx, t) }
                        onChanged(if (ok) tr("استُرجع: ${t.name}", "Restored: ${t.name}") else tr("تعذّر الاسترجاع", "Could not restore"))
                    }
                }) { Text(tr("استرجاع", "Restore")) }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppCopiesSection(onSaved: (String) -> Unit) {
    val groups by produceState<List<AppMedia>?>(null) {
        value = withContext(Dispatchers.IO) { Recovery.appCopies() }
    }
    var pick by rememberSaveable { mutableIntStateOf(0) }
    val g = groups
    if (g == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (g.isEmpty()) {
        Text(tr("لا توجد مجلدات وسائط لتطبيقات المراسلة.", "No messaging media folders found."), Modifier.padding(16.dp))
        return
    }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            g.forEachIndexed { i, a ->
                FilterChip(pick == i, { pick = i }, { Text("${a.label} (${a.files.size})") }, Modifier.padding(4.dp))
            }
        }
        MediaGrid(g.getOrNull(pick)?.files, onSaved)
    }
}

@Composable
private fun MediaGrid(files: List<File>?, onSaved: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    if (files == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (files.isEmpty()) {
        Text(tr("لا يوجد شيء هنا.", "Nothing here."), Modifier.padding(16.dp))
        return
    }
    Column {
        Text(
            tr("المس أي صورة لحفظ نسخة منها في Pictures/Recovered.", "Tap any picture to save a copy to Pictures/Recovered."),
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(4.dp),
        ) {
            items(files, key = { it.absolutePath }) { f ->
                Thumb(
                    f,
                    Modifier.padding(2.dp).clickable {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { Recovery.saveCopy(ctx, f) }
                            onSaved(
                                if (saved != null) tr("حُفظ: ${saved.name}", "Saved: ${saved.name}")
                                else tr("تعذّر الحفظ", "Could not save")
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun Thumb(f: File, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            try {
                if (Recovery.isVideo(f)) {
                    ThumbnailUtils.createVideoThumbnail(f, Size(256, 256), null).asImageBitmap()
                } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.absolutePath, o)
                    var sample = 1
                    while (o.outWidth / sample > 512 || o.outHeight / sample > 512) sample *= 2
                    BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?.asImageBitmap()
                }
            } catch (_: Exception) {
                null
            }
        }
    }
    Box(
        modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(b, contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(f.extension.ifEmpty { "?" }.uppercase(), style = MaterialTheme.typography.labelSmall)
        }
    }
}
