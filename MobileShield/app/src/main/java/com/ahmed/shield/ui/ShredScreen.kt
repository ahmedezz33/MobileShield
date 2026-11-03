package com.ahmed.shield.ui

import com.ahmed.shield.tr
import android.app.Activity
import android.content.Intent
import android.os.storage.StorageManager
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.rememberCoroutineScope
import com.ahmed.shield.CleanGroup
import com.ahmed.shield.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ahmed.shield.ShieldNotificationListener
import com.ahmed.shield.Shredder

private class Pending(val title: String, val text: String, val action: () -> Unit)

@Composable
fun ShredScreen() {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val busy by Shredder.busy.collectAsState()
    val progress by Shredder.progress.collectAsState()
    val status by Shredder.status.collectAsState()
    var allFiles by remember { mutableStateOf(Shredder.hasAllFilesAccess()) }
    var notifAccess by remember { mutableStateOf(ShieldNotificationListener.instance != null) }
    var passes by rememberSaveable { mutableIntStateOf(1) }
    var pending by remember { mutableStateOf<Pending?>(null) }
    var note by remember { mutableStateOf("") }

    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            allFiles = Shredder.hasAllFilesAccess()
            notifAccess = ShieldNotificationListener.instance != null
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            pending = Pending(
                tr("إتلاف ${uris.size} ملف", "Shred ${uris.size} files"),
                tr("ستُكتب بيانات عشوائية فوق الملفات المختارة ثم تُحذف. لا يمكن استرجاعها بعد ذلك.", "Random data will be written over the selected files, then they will be deleted. They cannot be recovered afterwards."),
            ) { Shredder.shredUris(ctx, uris, passes) }
        }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val dir = uri?.let { Shredder.treeToFile(it) }
        if (uri != null && dir == null) note = tr("هذا المجلد ليس على ذاكرة الجوال الداخلية.", "This folder is not on the phone's internal storage.")
        if (dir != null) {
            pending = Pending(
                tr("إتلاف مجلد كامل", "Shred a whole folder"),
                tr("سيُتلف كل ما في «${dir.absolutePath.removePrefix(Shredder.root.absolutePath)}» وكل مجلداته الفرعية نهائيًا.", "Everything in \"${dir.absolutePath.removePrefix(Shredder.root.absolutePath)}\" and all its subfolders will be shredded permanently."),
            ) { Shredder.shredFiles(ctx, listOf(dir), passes) }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            tr("الإتلاف نهائي: تُكتب بيانات عشوائية فوق الملف ثم يُحذف، فلا تستطيع برامج الاسترجاع إعادته.", "Shredding is permanent: random data is written over the file and then it is deleted, so recovery tools cannot bring it back."),
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )

        SectionCard(tr("الصلاحيات", "Permissions")) {
            AccessLine(allFiles, tr("الوصول إلى كل الملفات", "All files access"), tr("لإتلاف المجلدات والمصغّرات وسلة المحذوفات والمساحة الفارغة.", "To shred folders, thumbnails, the recycle bin and free space.")) {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                )
            }
            AccessLine(notifAccess, tr("الوصول إلى الإشعارات", "Notification access"), tr("لمسح الإشعارات الظاهرة على الجوال.", "To clear the notifications shown on the phone.")) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        DeepCleanCard(allFiles && !busy, passes, ask = { pending = it }, say = { note = it })

        SectionCard(tr("عدد مرات الكتابة", "Overwrite passes")) {
            listOf(1 to tr("مرة واحدة (كافية لذاكرة الجوال)", "Once (enough for phone storage)"), 3 to tr("3 مرات (أبطأ)", "3 times (slower)")).forEach { (n, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { passes = n },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = passes == n, onClick = { passes = n })
                    Text(label)
                }
            }
        }

        if (busy || status.isNotEmpty()) {
            SectionCard(if (busy) tr("جارٍ العمل", "Working") else tr("النتيجة", "Result")) {
                Text(status)
                if (busy) {
                    if (progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { Shredder.cancel() }) { Text(tr("إيقاف", "Stop")) }
                }
            }
        }
        if (note.isNotEmpty()) Text(note, color = MaterialTheme.colorScheme.error)

        SectionCard(tr("الملفات", "Files")) {
            ShredButton(tr("اختيار ملفات وإتلافها", "Choose files and shred them"), !busy) { pickFiles.launch(arrayOf("*/*")) }
            ShredButton(tr("اختيار مجلد وإتلافه بالكامل", "Choose a folder and shred all of it"), !busy && allFiles) { pickFolder.launch(null) }
        }

        SectionCard(tr("النسخ المخفية", "Hidden copies")) {
            ShredButton(tr("مصغّرات الصور والفيديو (thumbnails)", "Photo and video thumbnails"), !busy && allFiles) {
                pending = Pending(
                    tr("إتلاف المصغّرات", "Shred thumbnails"),
                    tr("تُتلف نسخ الصور المصغّرة التي يحتفظ بها الجوال حتى بعد حذف الصور الأصلية. يعيد المعرض إنشاء مصغّرات الصور الموجودة تلقائيًا.", "Shreds the small copies of pictures the phone keeps even after the originals are deleted. The gallery recreates thumbnails for existing pictures automatically."),
                ) { Shredder.shredThumbnails(ctx, passes) }
            }
            ShredButton(tr("سلة المحذوفات (الصور المحذوفة مؤقتًا)", "Recycle bin (temporarily deleted photos)"), !busy && allFiles) {
                pending = Pending(
                    tr("إتلاف سلة المحذوفات", "Shred the recycle bin"),
                    tr("تُتلف نهائيًا الملفات الموجودة في سلة المحذوفات، ولن يمكن استعادتها منها.", "Permanently shreds the files in the recycle bin; they can no longer be restored from it."),
                ) { Shredder.shredTrash(ctx, passes) }
            }
            ShredButton(tr("الإشعارات الظاهرة الآن", "Notifications shown now"), notifAccess) { note = Shredder.clearNotifications() }
            OutlinedButton(onClick = {
                try {
                    ctx.startActivity(Intent("android.settings.NOTIFICATION_HISTORY"))
                } catch (_: Exception) {
                    ctx.startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            }) { Text(tr("فتح سجل الإشعارات لمسحه أو إيقافه", "Open notification history to clear or turn it off")) }
            ShredButton(tr("الحافظة (النص المنسوخ)", "Clipboard (copied text)"), true) { note = Shredder.clearClipboard(ctx) }
        }

        SectionCard(tr("المساحة الفارغة", "Free space")) {
            Text(
                tr("الملفات التي حُذفت سابقًا بالطريقة العادية قد تبقى بقاياها في المساحة الفارغة. ", "Files deleted earlier the normal way can leave remnants in the free space. ") +
                    tr("هذا الخيار يملأ المساحة الفارغة ببيانات عشوائية ثم يحذفها. قد يستغرق وقتًا طويلًا، والجوال على الشاحن أفضل.", "This option fills the free space with random data, then deletes it. It can take a long time; keep the phone on the charger."),
                style = MaterialTheme.typography.bodySmall,
            )
            ShredButton(tr("الكتابة فوق المساحة الفارغة", "Overwrite free space"), !busy && allFiles) {
                pending = Pending(
                    tr("الكتابة فوق المساحة الفارغة", "Overwrite free space"),
                    tr("ستمتلئ ذاكرة الجوال مؤقتًا حتى ينتهي. لا تحذف بها أي ملف تحتاجه، وتبقى ملفاتك الموجودة كما هي.", "Storage will fill up temporarily until it finishes. Do not delete anything you need meanwhile; your existing files stay as they are."),
                ) { Shredder.wipeFreeSpace() }
            }
        }

        SectionCard(tr("حدود الإتلاف", "Shredding limits")) {
            Text(
                tr("• ذاكرة الجوال من نوع فلاش توزّع الكتابة على خلايا مختلفة، فلا يضمن أي برنامج الكتابة فوق الخلية نفسها 100%. ", "• Phone flash storage spreads writes across different cells, so no app can guarantee 100% that the same cell is overwritten. ") +
                    tr("لكن الجوال مشفّر بالكامل، والبقايا غير قابلة للقراءة دون مفتاح الجهاز.\n", "But the phone is fully encrypted, and remnants are unreadable without the device key.\n") +
                    tr("• بيانات التطبيقات الخاصة (مثل ذاكرة المعرض المؤقتة وسجل الإشعارات في النظام) لا يصل إليها أي تطبيق دون روت. ", "• Private app data (such as the gallery cache and the system notification history) is out of reach for any app without root. ") +
                    tr("امسحها من إعدادات كل تطبيق: «التخزين ← مسح البيانات».\n", "Clear it from each app's settings: Storage → Clear data.\n") +
                    tr("• لإتلاف كل شيء على الجوال: إعادة ضبط المصنع تحذف مفتاح التشفير، فتصبح كل البيانات غير قابلة للاسترجاع.", "• To destroy everything on the phone: a factory reset deletes the encryption key, making all data unrecoverable."),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(p.title) },
            text = { Text(p.text) },
            confirmButton = {
                Button(
                    onClick = { pending = null; note = ""; p.action() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(tr("أتلف نهائيًا", "Shred permanently")) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(tr("إلغاء", "Cancel")) } },
        )
    }
}

@Composable
private fun DeepCleanCard(enabled: Boolean, passes: Int, ask: (Pending) -> Unit, say: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var groups by remember { mutableStateOf<List<CleanGroup>?>(null) }
    var chosen by remember { mutableStateOf(setOf<String>()) }
    val clearCache = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        say(
            if (r.resultCode == Activity.RESULT_OK) tr("مُسح كاش كل التطبيقات.", "Every app's cache was cleared.")
            else tr("لم يُمسح الكاش.", "The cache was not cleared.")
        )
    }

    SectionCard(tr("التنظيف العميق", "Deep clean")) {
        Text(
            tr(
                "يمسح الكاش والملفات المؤقتة والسجلات وبقايا التطبيقات التي حُذفت سابقًا.",
                "Clears caches, temporary files, logs and what previously removed apps left behind.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        ShredButton(tr("1. مسح الكاش لكل التطبيقات", "1. Clear every app's cache"), enabled) {
            try {
                clearCache.launch(Intent(StorageManager.ACTION_CLEAR_APP_CACHE))
            } catch (_: Exception) {
                say(tr("هذا الجوال لا يتيح مسح الكاش لكل التطبيقات.", "This phone does not offer clearing every app's cache."))
            }
        }
        ShredButton(tr("2. فحص البقايا والملفات المؤقتة", "2. Scan for leftovers and temporary files"), enabled && !scanning) {
            scanning = true
            groups = null
            scope.launch {
                val g = withContext(Dispatchers.IO) { Shredder.deepScan(ctx) }
                groups = g
                chosen = g.map { it.id }.toSet()
                scanning = false
            }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
        groups?.let { g ->
            if (g.isEmpty()) {
                Text(tr("لم يُعثر على بقايا أو ملفات مؤقتة.", "No leftovers or temporary files were found."), color = Green)
            } else {
                g.forEach { grp ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            chosen = if (grp.id in chosen) chosen - grp.id else chosen + grp.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = grp.id in chosen, onCheckedChange = {
                            chosen = if (it) chosen + grp.id else chosen - grp.id
                        })
                        Column(Modifier.weight(1f)) {
                            Text(grp.label)
                            Text(
                                tr("${grp.files.size} ملف · ${formatBytes(grp.bytes)}", "${grp.files.size} files · ${formatBytes(grp.bytes)}") +
                                    if (grp.id == "leftovers") "\n" + grp.dirs.take(6).joinToString("\n") { it.name } else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val selected = g.filter { it.id in chosen }
                ShredButton(
                    tr("أتلف المحدد (${formatBytes(selected.sumOf { it.bytes })})", "Shred selected (${formatBytes(selected.sumOf { it.bytes })})"),
                    enabled && selected.isNotEmpty(),
                ) {
                    ask(
                        Pending(
                            tr("التنظيف العميق", "Deep clean"),
                            tr(
                                "ستُتلف نهائيًا كل الملفات في الفئات المحددة. صورك ومستنداتك في مجلدات DCIM وPictures وDocuments لا تُلمس.",
                                "Every file in the selected groups will be shredded permanently. Your photos and documents in DCIM, Pictures and Documents are not touched.",
                            ),
                        ) {
                            Shredder.deepClean(ctx, selected, passes)
                            groups = null
                        }
                    )
                }
            }
        }
        ShredButton(tr("3. مسح سجلات درع الجوال وتاريخه", "3. Clear MobileShield's logs and history"), true) {
            ask(
                Pending(
                    tr("مسح سجلات درع الجوال", "Clear MobileShield's logs"),
                    tr(
                        "يُمسح سجل الاتصالات، وأحداث المايكروفون والكاميرا والمكالمات، وذاكرة العناوين، والاستهلاك. القواعد والإعدادات تبقى.",
                        "The connection log, microphone, camera and call events, the address memory and usage are cleared. Rules and settings stay.",
                    ),
                ) { say(Shredder.clearOwnHistory(ctx)) }
            )
        }
        Text(
            tr(
                "عند حذف أي تطبيق يمسح أندرويد بياناته الخاصة تلقائيًا، ولا يبقى منه إلا ما في الذاكرة المشتركة، وهو ما يفحصه البند 2. " +
                    "أما البيانات الخاصة للتطبيقات المثبّتة وسجلات النظام وتاريخ الاستخدام فلا يصل إليها أي تطبيق دون روت: " +
                    "امسحها من الإعدادات ← التطبيقات ← التطبيق ← التخزين ← مسح البيانات.",
                "When an app is uninstalled, Android deletes its private data automatically; only what it put in shared storage can remain, which item 2 scans. " +
                    "Private data of installed apps, system logs and usage history are out of reach for any app without root: " +
                    "clear them in Settings → Apps → the app → Storage → Clear data.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShredButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun AccessLine(ok: Boolean, title: String, detail: String, onFix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (ok) Green else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ok) TextButton(onClick = onFix) { Text(tr("فعّل", "Enable")) }
    }
}
