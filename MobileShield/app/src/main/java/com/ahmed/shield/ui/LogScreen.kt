package com.ahmed.shield.ui

import com.ahmed.shield.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahmed.shield.Kind
import com.ahmed.shield.LogEntry
import com.ahmed.shield.LogStore
import com.ahmed.shield.NetType
import com.ahmed.shield.Prefs
import com.ahmed.shield.Verdict
import com.ahmed.shield.blocked
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import com.ahmed.shield.LogExport

private val FILTERS get() = listOf(tr("الكل", "All"), tr("المحظور", "Blocked"), tr("الإعلانات", "Ads"), tr("IP مباشر", "Direct IP"), "DNS", tr("بيانات الجوال", "Mobile data"), tr("واي فاي", "Wi-Fi"))

private val LogEntry.isConnection get() = kind == Kind.TCP || kind == Kind.UDP || kind == Kind.ICMP

private val LogEntry.isDirect
    get() = verdict == Verdict.DIRECT_SEEN || verdict == Verdict.BLOCKED_DIRECT || verdict == Verdict.BLOCKED_BYPASS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen() {
    // null = live log; otherwise one of the last 7 saved days
    var day by rememberSaveable { mutableStateOf<String?>(null) }
    val days = remember { com.ahmed.shield.LogDisk.days() }
    val entries by produceState(LogStore.snapshot(), day) {
        val d = day
        if (d == null) {
            while (true) {
                value = LogStore.snapshot()
                delay(1000)
            }
        } else {
            value = emptyList()
            value = withContext(Dispatchers.IO) { com.ahmed.shield.LogDisk.readConnections(d) }
        }
    }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<LogEntry?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<Pair<Uri, String>?>(null) }
    var saveNote by remember { mutableStateOf("") }

    val shown = remember(entries, filter, query) {
        entries.filter { e ->
            val byType = when (filter) {
                1 -> e.verdict.blocked
                2 -> e.verdict == Verdict.BLOCKED_AD
                3 -> e.isDirect
                4 -> e.kind == Kind.DNS || e.kind == Kind.TLS
                5 -> e.net == NetType.CELLULAR
                6 -> e.net == NetType.WIFI
                else -> true
            }
            byType && (
                query.isBlank() || e.target.contains(query, true) ||
                    e.app.contains(query, true) || e.domain?.contains(query, true) == true
                )
        }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            singleLine = true,
            placeholder = { Text(tr("ابحث بالنطاق أو العنوان أو التطبيق", "Search by domain, address or app")) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FILTERS.forEachIndexed { i, label ->
                FilterChip(
                    selected = filter == i,
                    onClick = { filter = i },
                    label = { Text(label) },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            TextButton(onClick = {
                saveNote = tr("جارٍ الحفظ…", "Saving…")
                scope.launch {
                    saveNote = try {
                        val r = withContext(Dispatchers.IO) { LogExport.export(ctx) }
                        saved = r
                        tr("حُفظ في ${r.second}", "Saved to ${r.second}")
                    } catch (e: Exception) {
                        tr("تعذّر الحفظ: ${e.message}", "Could not save: ${e.message}")
                    }
                }
            }) { Text(tr("حفظ السجلات", "Save logs")) }
            saved?.let { (uri, _) ->
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("application/zip")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    ctx.startActivity(Intent.createChooser(send, tr("مشاركة السجلات", "Share logs")))
                }) { Text(tr("مشاركة", "Share")) }
            }
            TextButton(onClick = { LogStore.clear() }) { Text(tr("مسح السجل", "Clear log")) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            FilterChip(day == null, { day = null }, { Text(tr("الآن (مباشر)", "Live")) }, Modifier.padding(horizontal = 4.dp))
            days.forEach { d ->
                FilterChip(day == d, { day = d }, { Text(d.substring(5).replace('-', '/')) }, Modifier.padding(horizontal = 4.dp))
            }
        }
        if (saveNote.isNotEmpty()) {
            Text(
                saveNote,
                Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Green,
            )
        }
        if (shown.isEmpty()) {
            Text(
                tr("لا توجد اتصالات بعد. شغّل الحماية واستخدم الجوال كالمعتاد.", "No connections yet. Turn protection on and use the phone as usual."),
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown) { e ->
                LogRow(e) { selected = e }
                HorizontalDivider()
            }
        }
    }

    selected?.let { e -> EntryDialog(e) { selected = null } }
}

@Composable
private fun EntryDialog(e: LogEntry, onDismiss: () -> Unit) {
    val domain = if (e.isConnection) e.domain else e.target
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(domain ?: e.target) },
        text = {
            Column {
                Text(tr("التطبيق: ${e.app}", "App: ${e.app}"))
                e.pkg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(tr("النوع: ${kindName(e.kind)}", "Type: ${kindName(e.kind)}"))
                if (e.isConnection) {
                    Text(tr("العنوان: ${e.target}", "Address: ${e.target}") + if (e.port > 0) " : ${e.port}" else "")
                    if (e.domain == null) Text(tr("لم يُطلب هذا العنوان عبر DNS (اتصال مباشر)", "This address was not looked up through DNS (direct connection)"))
                }
                Text(tr("الشبكة: ${netName(e.net)}", "Network: ${netName(e.net)}"))
                Text(tr("النتيجة: ${verdictName(e.verdict)}", "Result: ${verdictName(e.verdict)}"))
                Text(tr("الوقت: ${formatTime(e.time)}", "Time: ${formatTime(e.time)}"))
            }
        },
        confirmButton = {
            when {
                e.isDirect && e.pkg != null -> TextButton(onClick = {
                    Prefs.setRule(e.pkg, Prefs.rule(e.pkg) or Prefs.ALLOW_DIRECT)
                    onDismiss()
                }) { Text(tr("اسمح لهذا التطبيق بالاتصال المباشر", "Allow this app to connect directly")) }
                domain != null -> TextButton(onClick = { Prefs.allow(domain); onDismiss() }) {
                    Text(tr("اسمح بالنطاق دائمًا", "Always allow this domain"))
                }
                else -> TextButton(onClick = onDismiss) { Text(tr("إغلاق", "Close")) }
            }
        },
        dismissButton = {
            if (domain != null) {
                TextButton(onClick = { Prefs.block(domain); onDismiss() }) { Text(tr("احظر النطاق دائمًا", "Always block this domain")) }
            }
        },
    )
}

@Composable
private fun LogRow(e: LogEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when (e.net) {
                NetType.WIFI -> Icons.Filled.Wifi
                NetType.CELLULAR -> Icons.Filled.SignalCellularAlt
                else -> Icons.Filled.Public
            },
            contentDescription = netName(e.net),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            val title = if (e.isConnection) (e.domain ?: e.target) else e.target
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val addr = if (e.isConnection && e.domain != null) " · ${e.target}" else ""
            Text(
                "${e.app} · ${formatTime(e.time)} · ${kindName(e.kind)}$addr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Badge(verdictName(e.verdict), verdictColor(e.verdict))
    }
}
