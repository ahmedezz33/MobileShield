package com.ahmed.shield.ui

import com.ahmed.shield.tr
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.Settings
import android.provider.Telephony
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ahmed.shield.CallRow
import com.ahmed.shield.MonitorReader
import com.ahmed.shield.PermApp
import com.ahmed.shield.SmsRow
import com.ahmed.shield.Tethering
import com.ahmed.shield.UsageRow
import com.ahmed.shield.ShieldVpnService
import com.ahmed.shield.VoiceMonitor
import androidx.compose.runtime.collectAsState
import com.ahmed.shield.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val SECTIONS get() = listOf(tr("المايكروفون والكاميرا", "Microphone & camera"), tr("استهلاك البيانات", "Data usage"), tr("المكالمات", "Calls"), tr("الرسائل", "Messages"), tr("صلاحيات حساسة", "Sensitive permissions"), tr("سجل الاستخدام", "Usage history"), tr("تخزين التطبيقات", "App storage"), tr("آخر وصول للحساسات", "Last sensitive access"), tr("سجل الإشعارات", "Notification log"), tr("سجل النظام", "System log"), tr("الشبكة الخلوية", "Cellular network"))
private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.US)
private fun formatDate(t: Long) = synchronized(dateFormat) { dateFormat.format(Date(t)) }

private fun granted(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen() {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val tethering by produceState(Tethering(false, false, false)) {
        while (true) {
            value = withContext(Dispatchers.IO) { MonitorReader.tethering() }
            delay(5000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        HotspotBanner(tethering)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            SECTIONS.forEachIndexed { i, label ->
                FilterChip(
                    selected = section == i,
                    onClick = { section = i },
                    label = { Text(label) },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        when (section) {
            0 -> VoiceSection()
            1 -> UsageSection()
            2 -> CallsSection()
            3 -> SmsSection()
            4 -> PermissionsSection()
            5 -> UsageHistorySection()
            6 -> AppStorageSection()
            7 -> SensorAccessSection()
            8 -> NotificationLogSection()
            9 -> SystemLogSection()
            else -> CellScreen()
        }
    }
}

@Composable
private fun HotspotBanner(t: Tethering) {
    val text = if (t.active) {
        val kinds = listOfNotNull(
            tr("واي فاي", "Wi-Fi").takeIf { t.wifi }, "USB".takeIf { t.usb }, tr("بلوتوث", "Bluetooth").takeIf { t.bluetooth },
        ).joinToString(tr("، ", ", "))
        tr("نقطة الاتصال مفعّلة ($kinds). حركة الأجهزة المتصلة لا تمر عبر جدار الحماية، وكميتها تظهر في «استهلاك البيانات».", "Hotspot is on ($kinds). Traffic from connected devices does not pass through the firewall; its amount appears under Data usage.")
    } else {
        tr("نقطة الاتصال مطفأة.", "Hotspot is off.")
    }
    Text(
        text,
        Modifier.fillMaxWidth().padding(12.dp),
        color = if (t.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun VoiceSection() {
    val running by ShieldVpnService.running.collectAsState()
    val micOn by VoiceMonitor.micActive.collectAsState()
    val camOn by VoiceMonitor.cameraActive.collectAsState()
    val version by VoiceMonitor.version.collectAsState()
    val events = remember(version) { VoiceMonitor.snapshot() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!running) {
                    Text(tr("المراقبة متوقفة: شغّل الحماية من المفتاح في الأعلى.", "Monitoring is off: turn protection on with the switch at the top."), fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        if (micOn) tr("● المايكروفون يعمل الآن", "● Microphone in use now") else tr("المايكروفون غير مستخدم", "Microphone not in use"),
                        fontWeight = FontWeight.Bold,
                        color = if (micOn) MaterialTheme.colorScheme.error else Green,
                    )
                    Text(
                        if (camOn) tr("● الكاميرا تعمل الآن", "● Camera in use now") else tr("الكاميرا غير مستخدمة", "Camera not in use"),
                        fontWeight = FontWeight.Bold,
                        color = if (camOn) MaterialTheme.colorScheme.error else Green,
                    )
                }
                Text(
                    tr("يُسجَّل هنا كل اتصال هاتفي (عادي أو VoLTE أو عبر الواي فاي) وكل استخدام للمايكروفون أو الكاميرا من أي تطبيق، ", "Every phone call (regular, VoLTE or Wi-Fi) and every use of the microphone or camera by any app is recorded here, ") +
                        tr("أيًا كان طريق الصوت أو الصورة بعدها. يصلك تنبيه فوري عند كل مكالمة صادرة وكل تشغيل للمايكروفون أو الكاميرا.", "whichever way the sound or picture travels afterwards. You get an instant alert for every outgoing call and every time the microphone or camera turns on."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
        if (events.isEmpty()) {
            item {
                Text(
                    tr("لا أحداث بعد.", "No events yet."),
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(events) { e ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.title, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    if (e.locked) Badge(tr("الشاشة مقفلة", "Screen locked"), MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(formatTime(e.time), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    e.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageSection() {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var hasAccess by remember { mutableStateOf(MonitorReader.hasUsageAccess(ctx)) }
    var cellular by rememberSaveable { mutableStateOf(true) }
    var days by rememberSaveable { mutableIntStateOf(1) }
    var rows by remember { mutableStateOf<List<UsageRow>>(emptyList()) }

    // re-check after the user returns from the system settings page
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasAccess = MonitorReader.hasUsageAccess(ctx)
        }
    }
    LaunchedEffect(hasAccess, cellular, days) {
        if (!hasAccess) return@LaunchedEffect
        val since = System.currentTimeMillis() - days * 24L * 3600 * 1000
        rows = withContext(Dispatchers.IO) { MonitorReader.usage(ctx, cellular, since) }
    }

    if (!hasAccess) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr("لقراءة استهلاك كل تطبيق من عدّاد النظام، فعّل «الوصول إلى الاستخدام» لدرع الجوال. ", "To read each app's usage from the system counter, enable Usage access for MobileShield. ") +
                    tr("هذا العدّاد يشمل كل ما خرج من الجوال، حتى ما لم يمر عبر الـ VPN.", "This counter includes everything that left the phone, even traffic that skipped the VPN."),
            )
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                Text(tr("فتح إعداد «الوصول إلى الاستخدام»", "Open Usage access settings"))
            }
        }
        return
    }

    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        FilterChip(cellular, { cellular = true }, { Text(tr("بيانات الجوال", "Mobile data")) }, Modifier.padding(4.dp))
        FilterChip(!cellular, { cellular = false }, { Text(tr("واي فاي", "Wi-Fi")) }, Modifier.padding(4.dp))
        listOf(1 to tr("اليوم", "Today"), 7 to tr("7 أيام", "7 days"), 30 to tr("30 يومًا", "30 days")).forEach { (d, label) ->
            FilterChip(days == d, { days = d }, { Text(label) }, Modifier.padding(4.dp))
        }
    }
    val total = rows.sumOf { it.rx + it.tx }
    Text(
        tr("المجموع: ${formatBytes(total)}", "Total: ${formatBytes(total)}"),
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        fontWeight = FontWeight.Bold,
    )
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows) { r ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "↓ ${formatBytes(r.rx)}  ↑ ${formatBytes(r.tx)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun PermissionGate(perm: String, explanation: String, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var ok by remember { mutableStateOf(granted(ctx, perm)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        ok = granted(ctx, perm)
    }
    if (ok) {
        content()
    } else {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(explanation)
            Button(onClick = { launcher.launch(perm) }) { Text(tr("منح الإذن", "Grant permission")) }
            Text(
                tr("إذا ظهرت رسالة «إعداد مقيّد»: افتح معلومات التطبيق ← ⋮ ← «السماح بالإعدادات المقيّدة»، ثم عُد وحاول مرة أخرى.", "If you see \"Restricted setting\": open App info → ⋮ → \"Allow restricted settings\", then come back and try again."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${ctx.packageName}"),
                    )
                )
            }) { Text(tr("معلومات التطبيق", "App info")) }
        }
    }
}

@Composable
private fun CallsSection() {
    PermissionGate(
        Manifest.permission.READ_CALL_LOG,
        tr("لعرض المكالمات الصادرة والواردة (بما فيها VoLTE ومكالمات الواي فاي) يحتاج التطبيق إذن قراءة سجل المكالمات. ", "To show outgoing and incoming calls (including VoLTE and Wi-Fi calls), the app needs permission to read the call log. ") +
            tr("السجل يُقرأ على جوالك فقط.", "The log is read on your phone only."),
    ) {
        val ctx = LocalContext.current
        val calls by produceState(emptyList<CallRow>()) {
            value = withContext(Dispatchers.IO) { MonitorReader.calls(ctx) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(calls) { c ->
                val type = when (c.type) {
                    CallLog.Calls.OUTGOING_TYPE -> tr("صادرة", "Outgoing")
                    CallLog.Calls.INCOMING_TYPE -> tr("واردة", "Incoming")
                    CallLog.Calls.MISSED_TYPE -> tr("فائتة", "Missed")
                    CallLog.Calls.REJECTED_TYPE -> tr("مرفوضة", "Rejected")
                    CallLog.Calls.BLOCKED_TYPE -> tr("محظورة", "Blocked")
                    else -> tr("أخرى", "Other")
                }
                val tags = listOfNotNull(
                    tr("فيديو", "Video").takeIf { (c.features and CallLog.Calls.FEATURES_VIDEO) != 0 },
                    "HD / VoLTE".takeIf { (c.features and CallLog.Calls.FEATURES_HD_CALL) != 0 },
                    tr("عبر الواي فاي", "Over Wi-Fi").takeIf { (c.features and CallLog.Calls.FEATURES_WIFI) != 0 },
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name ?: c.number.ifBlank { tr("رقم مخفي", "Hidden number") }, fontWeight = FontWeight.Medium)
                        Text(
                            "${formatDate(c.date)} · ${c.duration / 60}:${"%02d".format(c.duration % 60)}" +
                                if (tags.isEmpty()) "" else " · ${tags.joinToString(" · ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Badge(
                        type,
                        if (c.type == CallLog.Calls.OUTGOING_TYPE) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                    )
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SmsSection() {
    PermissionGate(
        Manifest.permission.READ_SMS,
        tr("لعرض الرسائل المرسلة والمستقبلة يحتاج التطبيق إذن قراءة الرسائل. الرسائل تُقرأ على جوالك فقط.", "To show sent and received messages, the app needs permission to read messages. Messages are read on your phone only."),
    ) {
        val ctx = LocalContext.current
        var sentOnly by rememberSaveable { mutableStateOf(true) }
        val all by produceState(emptyList<SmsRow>()) {
            value = withContext(Dispatchers.IO) { MonitorReader.sms(ctx) }
        }
        val shown = remember(all, sentOnly) {
            if (sentOnly) all.filter { it.type != Telephony.Sms.MESSAGE_TYPE_INBOX } else all
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            FilterChip(sentOnly, { sentOnly = true }, { Text(tr("المرسلة فقط", "Sent only")) }, Modifier.padding(4.dp))
            FilterChip(!sentOnly, { sentOnly = false }, { Text(tr("الكل", "All")) }, Modifier.padding(4.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown) { s ->
                val type = when (s.type) {
                    Telephony.Sms.MESSAGE_TYPE_SENT -> tr("مرسلة", "Sent")
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> tr("واردة", "Incoming")
                    Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> tr("قيد الإرسال", "Sending")
                    Telephony.Sms.MESSAGE_TYPE_FAILED -> tr("فشل الإرسال", "Failed")
                    else -> tr("مسودة", "Draft")
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.address, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Badge(type, MaterialTheme.colorScheme.outline)
                    }
                    Text(
                        formatDate(s.date),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(s.body, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PermissionsSection() {
    val ctx = LocalContext.current
    val apps by produceState<List<PermApp>?>(null) {
        value = withContext(Dispatchers.IO) { MonitorReader.permissionApps(ctx) }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Text(
                tr("التطبيقات التي تملك الآن صلاحية إرسال الرسائل أو إجراء المكالمات أو قراءتها. ", "Apps that can currently send messages, make calls or read them. ") +
                    tr("أي تطبيق غير متوقع هنا اسحب منه الصلاحية من إعدادات الجوال.", "If any app here is unexpected, revoke the permission in the phone settings."),
                Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(apps ?: emptyList()) { a ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(a.label, fontWeight = FontWeight.Medium)
                    Text(
                        a.perms.joinToString(tr("، ", ", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${a.pkg}"),
                        )
                    )
                }) { Text(if (a.system) tr("نظام", "System") else tr("إدارة", "Manage")) }
            }
            HorizontalDivider()
        }
    }
}
