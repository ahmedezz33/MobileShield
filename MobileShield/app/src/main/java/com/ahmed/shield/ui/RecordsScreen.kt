package com.ahmed.shield.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ahmed.shield.AppStorage
import com.ahmed.shield.MonitorReader
import com.ahmed.shield.NotificationLog
import com.ahmed.shield.Records
import com.ahmed.shield.ShieldNotificationListener
import com.ahmed.shield.TimelineRow
import com.ahmed.shield.formatBytes
import com.ahmed.shield.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val stamp = SimpleDateFormat("MM/dd HH:mm:ss", Locale.US)
private fun fmt(t: Long) = synchronized(stamp) { stamp.format(Date(t)) }

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun NeedUsageAccess() {
    val ctx = LocalContext.current
    Column(Modifier.padding(16.dp)) {
        Text(tr("يحتاج «الوصول إلى الاستخدام».", "Needs \"Usage access\"."))
        TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) { Text(tr("فعّل", "Enable")) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageHistorySection() {
    val ctx = LocalContext.current
    if (!MonitorReader.hasUsageAccess(ctx)) return NeedUsageAccess()
    var days by rememberSaveable { mutableIntStateOf(1) }
    var totals by rememberSaveable { mutableStateOf(false) }
    val timeline by produceState<List<TimelineRow>?>(null, days) {
        value = withContext(Dispatchers.IO) { Records.usageTimeline(ctx, days) }
    }
    val sums by produceState<List<Pair<String, Long>>?>(null, days) {
        value = withContext(Dispatchers.IO) { Records.usageTotals(ctx, days) }
    }
    Column {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            listOf(1 to tr("اليوم", "Today"), 3 to tr("3 أيام", "3 days"), 7 to tr("7 أيام", "7 days")).forEach { (d, l) ->
                FilterChip(days == d, { days = d }, { Text(l) }, Modifier.padding(4.dp))
            }
            FilterChip(!totals, { totals = false }, { Text(tr("التسلسل الزمني", "Timeline")) }, Modifier.padding(4.dp))
            FilterChip(totals, { totals = true }, { Text(tr("المدة لكل تطبيق", "Time per app")) }, Modifier.padding(4.dp))
        }
        if (totals) {
            val s = sums ?: return Loading()
            LazyColumn(Modifier.fillMaxSize()) {
                items(s) { (app, ms) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(app, Modifier.weight(1f))
                        Text("${ms / 3_600_000}:${"%02d".format(ms / 60_000 % 60)}", fontWeight = FontWeight.Medium)
                    }
                    HorizontalDivider()
                }
            }
        } else {
            val t = timeline ?: return Loading()
            LazyColumn(Modifier.fillMaxSize()) {
                items(t) { r ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, fontWeight = FontWeight.Medium)
                            if (r.detail.isNotEmpty()) {
                                Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(fmt(r.time), style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun AppStorageSection() {
    val ctx = LocalContext.current
    if (!MonitorReader.hasUsageAccess(ctx)) return NeedUsageAccess()
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val list by produceState<List<AppStorage>?>(null) {
        value = withContext(Dispatchers.IO) { Records.storage(ctx) }
    }
    val l = list ?: return Loading()
    val shown = remember(l, showSystem) { l.filter { showSystem || !it.system } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(
                    tr(
                        "«مسح» يفتح صفحة التطبيق: اختر «التخزين» ثم «مسح البيانات» لحذف كل بياناته وسجله الداخلي، أو «مسح الكاش» فقط.",
                        "\"Clear\" opens the app's page: choose Storage, then Clear data to delete all its data and internal history, or Clear cache only.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(Modifier.clickable { showSystem = !showSystem }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(showSystem, { showSystem = it })
                    Text(tr("إظهار تطبيقات النظام", "Show system apps"))
                }
            }
            HorizontalDivider()
        }
        items(shown, key = { it.pkg }) { a ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        tr(
                            "البيانات ${formatBytes(a.data)} · الكاش ${formatBytes(a.cache)} · التطبيق ${formatBytes(a.app)}",
                            "Data ${formatBytes(a.data)} · Cache ${formatBytes(a.cache)} · App ${formatBytes(a.app)}",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")))
                }) { Text(tr("مسح", "Clear")) }
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun SensorAccessSection() {
    val ctx = LocalContext.current
    val list by produceState<List<com.ahmed.shield.SensorAccess>?>(emptyList()) {
        value = withContext(Dispatchers.IO) { Records.sensorAccess(ctx) }
    }
    val l = list
    if (l == null) {
        Text(
            tr("يحتاج صلاحية GET_APP_OPS_STATS، تُمنح مرة واحدة عبر الكابل.", "Needs GET_APP_OPS_STATS, granted once over USB."),
            Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Text(
                tr(
                    "آخر مرة استخدم فيها كل تطبيق الكاميرا أو المايكروفون أو الموقع أو جهات الاتصال أو الرسائل أو سجل المكالمات، كما يسجّلها أندرويد نفسه.",
                    "The last time each app used the camera, microphone, location, contacts, messages or call log, as recorded by Android itself.",
                ),
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
        }
        items(l) { a ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, fontWeight = FontWeight.Medium)
                    Text(a.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(fmt(a.time), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}

@Composable
fun NotificationLogSection() {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val rows by produceState(NotificationLog.snapshot(), refresh) { value = NotificationLog.snapshot() }
    val shown = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter { it.app.contains(query, true) || it.title.contains(query, true) || it.text.contains(query, true) }
    }
    Column {
        if (ShieldNotificationListener.instance == null) {
            Text(
                tr("فعّل «الوصول إلى الإشعارات» ليبدأ التسجيل.", "Enable Notification access to start recording."),
                Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text(tr("فعّل", "Enable"))
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                query, { query = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text(tr("ابحث", "Search")) },
            )
            TextButton(onClick = { NotificationLog.clear(); refresh++ }) { Text(tr("مسح السجل", "Clear log")) }
        }
        Text(
            tr(
                "كل إشعار يظهر على الجوال يُحفظ هنا من لحظة منح الصلاحية، حتى لو حذفه صاحبه أو مسحته من الشاشة.",
                "Every notification shown on the phone is kept here from the moment access was granted, even if its sender deleted it or you swiped it away.",
            ),
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown) { n ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row {
                        Text(n.app, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(fmt(n.time), style = MaterialTheme.typography.bodySmall)
                    }
                    if (n.title.isNotEmpty()) Text(n.title, fontWeight = FontWeight.Medium)
                    if (n.text.isNotEmpty()) Text(n.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
fun SystemLogSection() {
    val ctx = LocalContext.current
    if (!Records.canReadLogs(ctx)) {
        Text(
            tr(
                "يحتاج صلاحية READ_LOGS، تُمنح مرة واحدة عبر الكابل:\nadb shell pm grant com.ahmed.shield android.permission.READ_LOGS",
                "Needs READ_LOGS, granted once over USB:\nadb shell pm grant com.ahmed.shield android.permission.READ_LOGS",
            ),
            Modifier.padding(16.dp),
        )
        return
    }
    var refresh by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val lines by produceState<List<String>?>(null, refresh) {
        value = withContext(Dispatchers.IO) { Records.systemLog() }
    }
    Column {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text(tr("ابحث، مثال: اسم تطبيق", "Search, e.g. an app name")) })
            TextButton(onClick = { refresh++ }) { Text(tr("تحديث", "Refresh")) }
        }
        Text(
            tr(
                "آخر 3000 سطر من سجل النظام. أندرويد يكتب فوقه باستمرار ويمسحه عند إعادة التشغيل، وقد يطلب منك موافقة لمرة واحدة.",
                "The last 3000 lines of the system log. Android overwrites it constantly and clears it on reboot; it may ask for a one-time approval.",
            ),
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        val l = lines ?: return Loading()
        val shown = remember(l, query) { if (query.isBlank()) l else l.filter { it.contains(query, true) } }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(shown) { line ->
                    Text(
                        line,
                        Modifier.padding(horizontal = 8.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}
