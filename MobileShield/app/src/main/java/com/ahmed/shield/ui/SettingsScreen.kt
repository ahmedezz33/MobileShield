package com.ahmed.shield.ui

import com.ahmed.shield.tr
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.ahmed.shield.AppNames
import com.ahmed.shield.Blocklist
import com.ahmed.shield.LogStore
import com.ahmed.shield.MonitorReader
import com.ahmed.shield.Prefs
import com.ahmed.shield.PrivateDnsGuard
import com.ahmed.shield.ShieldVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DOMAIN_RE = Regex("^[a-z0-9_-]+(\\.[a-z0-9_-]+)+$")

private fun openVpnSettings(ctx: Context) =
    ctx.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))

private fun openAppSettings(ctx: Context) = ctx.startActivity(
    Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${ctx.packageName}"),
    )
)

@Composable
fun SettingsScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SpywareCard()
        SecurityChecksCard()
        HardeningCard()
        DirectIpCard()
        AdBlockCard()
        DnsServerCard()
        CustomDomainsCard()
    }
}

/**
 * Zero-click attacks exploit system components (message and image parsers, radios)
 * that no app can patch; this card checks the settings that shrink that surface.
 */
@Composable
private fun SecurityChecksCard() {
    val ctx = LocalContext.current
    val state by produceState<List<Triple<Boolean, String, String>>>(emptyList()) {
        while (true) {
            val patch = android.os.Build.VERSION.SECURITY_PATCH
            val age = try {
                java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(patch), java.time.LocalDate.now())
            } catch (_: Exception) {
                -1L
            }
            value = listOf(
                Triple(
                    age in 0..60,
                    tr("تحديث الأمان: $patch", "Security patch: $patch"),
                    if (age in 0..60) tr("حديث (منذ $age يومًا).", "Recent ($age days old).")
                    else tr("قديم، حدّث الجوال: أهم حماية من هجمات «بدون نقرة».", "Old: update the phone, the main defence against zero-click attacks."),
                ),
                Triple(
                    !com.ahmed.shield.Controls.usbDebugging(ctx),
                    tr("تصحيح USB", "USB debugging"),
                    if (com.ahmed.shield.Controls.usbDebugging(ctx)) tr("مفعّل: أطفئه بعد الانتهاء، فالصلاحيات الممنوحة تبقى.", "On: turn it off when done; granted permissions stay.")
                    else tr("مطفأ.", "Off."),
                ),
                Triple(
                    com.ahmed.shield.Controls.bluetoothOn(ctx) != true,
                    tr("البلوتوث", "Bluetooth"),
                    if (com.ahmed.shield.Controls.bluetoothOn(ctx) == true) tr("يعمل: أطفئه عند عدم الحاجة.", "On: turn it off when not needed.")
                    else tr("مطفأ.", "Off."),
                ),
                Triple(
                    com.ahmed.shield.Controls.nfcOn(ctx) != true,
                    "NFC",
                    if (com.ahmed.shield.Controls.nfcOn(ctx) == true) tr("يعمل: أطفئه إن لم تستخدم الدفع باللمس.", "On: turn it off if you do not pay by tap.")
                    else tr("مطفأ.", "Off."),
                ),
                Triple(
                    2 !in Prefs.allowedGens.value,
                    tr("بيانات الجوال على 2G", "Mobile data on 2G"),
                    if (2 in Prefs.allowedGens.value) tr("مسموحة: ألغِها من تبويب التحكم، وأطفئ «السماح بشبكة 2G» في الإعدادات.", "Allowed: untick it in the Control tab and turn off \"Allow 2G\" in Settings.")
                    else tr("محظورة في جدار الحماية.", "Blocked by the firewall."),
                ),
            )
            kotlinx.coroutines.delay(3000)
        }
    }
    SectionCard(tr("الحماية من هجمات «بدون نقرة»", "Zero-click attack protection")) {
        Text(
            tr(
                "هذه الهجمات تستغل ثغرات في مكونات النظام نفسه، فلا يوقفها أي تطبيق بالكامل. ما يقلل الخطر فعلًا: التحديثات، و«الحماية المتقدمة» أو «Auto Blocker» من سامسونج (يتضمن Message Guard لفتح صور الرسائل في عزل)، وإغلاق ما لا تحتاجه.",
                "These attacks exploit flaws in system components themselves, so no app can stop them completely. What really lowers the risk: updates, Samsung's Advanced Protection or Auto Blocker (with Message Guard, which opens message images in isolation), and closing what you do not need.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        state.forEach { (ok, title, detail) -> CheckLine(ok, title, detail, null) }
        val integrity by produceState(emptyList<com.ahmed.shield.IntegrityCheck>()) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ahmed.shield.Integrity.check() }
        }
        Text(
            tr("سلامة النظام (يراقبها درع الجوال كل دقيقة وينذرك فورًا عند أي تغيّر):", "System integrity (checked every minute, instant alert on any change):"),
            style = MaterialTheme.typography.titleSmall,
        )
        integrity.forEach { CheckLine(it.ok, it.title, it.value, null) }
        Text(
            tr(
                "حظر رفع الصلاحيات تقوم به سامسونج داخل الجهاز: Knox RKP يحمي النواة من التعديل، وDEFEX يوقف أي عملية تحصل على الروت بطريقة غير مشروعة. هذه البنود تؤكد أنها سليمة.",
                "Blocking privilege escalation is done by Samsung inside the device: Knox RKP protects the kernel from changes and DEFEX stops any process that gains root illegitimately. These items confirm they are intact.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = {
            com.ahmed.shield.Controls.open(ctx, Intent("android.settings.SYSTEM_UPDATE_SETTINGS"))
        }) { Text(tr("تحديث البرنامج", "Software update")) }
        OutlinedButton(onClick = {
            com.ahmed.shield.Controls.open(ctx, Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
        }) { Text(tr("الحماية المتقدمة / Auto Blocker", "Advanced Protection / Auto Blocker")) }
        Text(
            tr(
                "تنبيه: Auto Blocker يمنع تثبيت التطبيقات من خارج المتجر وأوامر USB، فتتوقف تحديثات درع الجوال عبر الكابل أثناء تفعيله. الحماية المثبّتة تبقى تعمل.",
                "Note: Auto Blocker blocks installing apps from outside the store and USB commands, so MobileShield updates over the cable stop while it is on. The installed protection keeps working.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun HardeningCard() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val running by ShieldVpnService.running.collectAsState()
    val alwaysOn by ShieldVpnService.alwaysOn.collectAsState()
    val lockdown by ShieldVpnService.lockdown.collectAsState()
    var pdnsLock by remember { mutableStateOf(PrivateDnsGuard.canWrite(ctx)) }
    var usageAccess by remember { mutableStateOf(MonitorReader.hasUsageAccess(ctx)) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            pdnsLock = PrivateDnsGuard.canWrite(ctx)
            usageAccess = MonitorReader.hasUsageAccess(ctx)
        }
    }

    SectionCard(tr("إحكام الحماية", "Protection lockdown")) {
        if (!running) {
            Text(
                tr("شغّل الحماية أولًا ليظهر وضع البندين الأولين.", "Turn protection on first to see the state of the first two items."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        CheckLine(
            alwaysOn, tr("VPN دائم التشغيل", "Always-on VPN"),
            tr("يعيد تشغيل الحماية تلقائيًا بعد إعادة تشغيل الجوال.", "Restarts protection automatically after the phone reboots."),
        ) { openVpnSettings(ctx) }
        CheckLine(
            lockdown, tr("حظر الاتصالات بدون VPN", "Block connections without VPN"),
            tr("إذا توقف التطبيق لأي سبب ينقطع الإنترنت بدل أن تمر البيانات بلا فحص. ", "If the app stops for any reason, the internet is cut instead of data flowing unchecked. ") +
                tr("يُفعّل من نفس صفحة «VPN دائم التشغيل».", "Enabled on the same page as Always-on VPN."),
        ) { openVpnSettings(ctx) }
        CheckLine(
            pdnsLock, tr("قفل «DNS الخاص» على الإيقاف", "Lock Private DNS off"),
            tr("يمنع تجاوز الفلتر عبر DNS المشفّر، ويعيد الإعداد إلى الإيقاف فورًا إن غيّره أحد.", "Prevents bypassing the filter with encrypted DNS, and switches the setting back off at once if anyone changes it."),
            null,
        )
        if (!pdnsLock) {
            Text(
                tr("لتفعيله مرة واحدة: فعّل «تصحيح USB» من خيارات المطوّر، وصِل الجوال بالكمبيوتر، ثم نفّذ:", "To enable once: turn on USB debugging in Developer options, connect the phone to a computer, then run:"),
                style = MaterialTheme.typography.bodySmall,
            )
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        PrivateDnsGuard.ADB_COMMAND,
                        Modifier.padding(8.dp),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(PrivateDnsGuard.ADB_COMMAND)) }) {
                Text(tr("نسخ الأمر", "Copy command"))
            }
        }
        CheckLine(
            usageAccess, tr("الوصول إلى الاستخدام", "Usage access"),
            tr("لقراءة استهلاك كل تطبيق من عدّاد النظام في شاشة المراقبة.", "To read each app's usage from the system counter on the Monitor screen."),
        ) { ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        OutlinedButton(onClick = { openAppSettings(ctx) }) { Text(tr("البطارية: اجعلها «غير مقيّد»", "Battery: set to \"Unrestricted\"")) }
    }
}

@Composable
private fun CheckLine(ok: Boolean, title: String, detail: String, onFix: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = null,
            tint = if (ok) Green else MaterialTheme.colorScheme.error,
        )
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!ok && onFix != null) TextButton(onClick = onFix) { Text(tr("فعّل", "Enable")) }
    }
}

@Composable
private fun DirectIpCard() {
    val mode by Prefs.directMode.collectAsState()
    val blockDoh by Prefs.blockDoh.collectAsState()
    val seen by produceState(emptyList<Pair<Int, Int>>()) {
        while (true) {
            value = LogStore.directByUid.map { (uid, n) -> uid to n.get() }.sortedByDescending { it.second }
            delay(3000)
        }
    }
    val version by Prefs.rulesVersion.collectAsState()

    SectionCard(tr("الاتصال المباشر بعناوين IP", "Direct connections to IP addresses")) {
        Text(
            tr("«مباشر» يعني اتصال تطبيق بعنوان IP لم يطلبه عبر DNS، وهي طريقة تستخدمها بعض التطبيقات لتجاوز الفلترة. ", "\"Direct\" means an app connects to an IP address it never looked up through DNS, a trick some apps use to dodge filtering. ") +
                tr("مكالمات واتساب وتيليجرام والألعاب تستخدمها أيضًا بشكل سليم، لذلك ابدأ بوضع التعلّم.", "WhatsApp and Telegram calls and games also use it legitimately, so start in learning mode."),
            style = MaterialTheme.typography.bodySmall,
        )
        listOf(
            Prefs.DIRECT_OFF to tr("إيقاف", "Stop"),
            Prefs.DIRECT_LEARN to tr("تعلّم: سجّل فقط ولا تحظر", "Learn: log only, do not block"),
            Prefs.DIRECT_ENFORCE to tr("حظر: امنع كل اتصال مباشر إلا للتطبيقات المستثناة", "Block: stop every direct connection except for exempted apps"),
        ).forEach { (m, label) ->
            Row(
                Modifier.fillMaxWidth().clickable { Prefs.setDirectMode(m) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == m, onClick = { Prefs.setDirectMode(m) })
                Text(label)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("حظر DNS المشفّر (DoH وDoT) الذي يتجاوز الفلتر", "Block encrypted DNS (DoH and DoT) that bypasses the filter"), Modifier.weight(1f))
            Switch(checked = blockDoh, onCheckedChange = { Prefs.setBlockDoh(it) })
        }
        if (seen.isNotEmpty()) {
            Text(tr("تطبيقات اتصلت مباشرة منذ التشغيل:", "Apps that connected directly since start:"), style = MaterialTheme.typography.titleSmall)
            seen.take(15).forEach { (uid, count) ->
                val (label, pkg) = AppNames.label(uid)
                val excepted = pkg != null && remember(version, pkg) { Prefs.rule(pkg) and Prefs.ALLOW_DIRECT != 0 }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label)
                        Text(
                            tr("$count اتصال", "$count connections") + if (excepted) tr(" · مستثنى", " · exempted") else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pkg != null) {
                        TextButton(onClick = { Prefs.setRule(pkg, Prefs.rule(pkg) xor Prefs.ALLOW_DIRECT) }) {
                            Text(if (excepted) tr("ألغِ الاستثناء", "Remove exemption") else tr("استثنِ", "Exempt"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdBlockCard() {
    val scope = rememberCoroutineScope()
    val adBlock by Prefs.adBlock.collectAsState()
    val groups by com.ahmed.shield.Privacy.enabled.collectAsState()
    val telemetry = "microsoft" in groups
    val ctx = LocalContext.current
    val lists by Prefs.enabledLists.collectAsState()
    val count by Blocklist.count.collectAsState()
    val status by Blocklist.status.collectAsState()
    val busy by Blocklist.busy.collectAsState()

    SectionCard(tr("حجب الإعلانات", "Ad blocking")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("حجب الإعلانات والمتتبعات", "Block ads and trackers"), Modifier.weight(1f))
            Switch(checked = adBlock, onCheckedChange = { Prefs.setAdBlock(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("حظر بيانات الاستخدام (Telemetry)", "Block usage data (telemetry)"))
                Text(
                    tr(
                        "يمنع إرسال بيانات استخدامك إلى Microsoft (Copilot وLink to Windows وغيرها) دون تعطيل التطبيقات.",
                        "Stops usage data being sent to Microsoft (Copilot, Link to Windows and others) without breaking the apps.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = telemetry, onCheckedChange = { com.ahmed.shield.Privacy.setGroup(ctx, "microsoft", it) })
        }
        Text(tr("عدد النطاقات في القوائم: $count", "Domains in the lists: $count"), style = MaterialTheme.typography.bodySmall)
        Blocklist.SOURCES.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clickable { Prefs.setListEnabled(s.id, s.id !in lists) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = s.id in lists, onCheckedChange = { Prefs.setListEnabled(s.id, it) })
                Text(s.name)
            }
        }
        Button(enabled = !busy, onClick = { scope.launch { Blocklist.update() } }) {
            Text(if (busy) tr("جارٍ التحديث…", "Updating…") else tr("تحديث القوائم الآن", "Update lists now"))
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DnsServerCard() {
    val ctx = LocalContext.current
    val upstream by Prefs.upstream.collectAsState()
    SectionCard(tr("خادم DNS", "DNS server")) {
        Prefs.UPSTREAMS.forEach { u ->
            val choose = {
                if (u.primary != upstream.primary) {
                    Prefs.setUpstream(u)
                    if (ShieldVpnService.running.value) {
                        ctx.startService(
                            Intent(ctx, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_RELOAD)
                        )
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable(onClick = choose),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = u.primary == upstream.primary, onClick = choose)
                Column {
                    Text(u.name)
                    Text(
                        "${u.primary} / ${u.secondary}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomDomainsCard() {
    val allowed by Prefs.allowed.collectAsState()
    val blocked by Prefs.customBlocked.collectAsState()
    var newDomain by remember { mutableStateOf("") }
    val validDomain = DOMAIN_RE.matches(newDomain)

    SectionCard(tr("نطاقات مخصّصة", "Custom domains")) {
        OutlinedTextField(
            value = newDomain,
            onValueChange = { newDomain = it.trim().lowercase() },
            label = { Text(tr("مثال: ads.example.com", "Example: ads.example.com")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            Button(enabled = validDomain, onClick = { Prefs.block(newDomain); newDomain = "" }) {
                Text(tr("احظر", "Block"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = validDomain, onClick = { Prefs.allow(newDomain); newDomain = "" }) {
                Text(tr("اسمح دائمًا", "Always allow"))
            }
        }
        if (blocked.isNotEmpty()) {
            Text(tr("محظورة يدويًا", "Blocked manually"), style = MaterialTheme.typography.titleSmall)
            blocked.sorted().forEach { d -> DomainLine(d) { Prefs.unblock(d) } }
        }
        if (allowed.isNotEmpty()) {
            Text(tr("مسموحة دائمًا", "Always allowed"), style = MaterialTheme.typography.titleSmall)
            allowed.sorted().forEach { d -> DomainLine(d) { Prefs.unallow(d) } }
        }
    }
}

@Composable
private fun DomainLine(domain: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(domain, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = tr("حذف", "Delete")) }
    }
}

/** On-device check against the public spyware indicators that MVT uses. */
@Composable
private fun SpywareCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val count by com.ahmed.shield.Spyware.count.collectAsState()
    val sets by com.ahmed.shield.Spyware.sets.collectAsState()
    val updated by com.ahmed.shield.Spyware.updatedAt.collectAsState()
    val busy by com.ahmed.shield.Spyware.busy.collectAsState()
    val status by com.ahmed.shield.Spyware.status.collectAsState()
    val progress by com.ahmed.shield.Spyware.progress.collectAsState()
    val report by com.ahmed.shield.Spyware.report.collectAsState()
    val date = java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", java.util.Locale.US)

    SectionCard(tr("فحص برامج التجسس (مؤشرات MVT)", "Spyware scan (MVT indicators)")) {
        Text(
            tr(
                "يقارن الجوال بالمؤشرات العامة لبرامج التجسس المعروفة، وهي نفس مصادر أداة MVT من منظمة العفو الدولية: Pegasus وPredator وCandiru وQuaDream وCellebrite وبرامج المراقبة وغيرها. يفحص التطبيقات المثبّتة وتواقيعها، وبصمة ملفاتها، وسجل الاتصالات لآخر 7 أيام، وملفات النظام وخصائصه.",
                "Compares the phone with the public indicators of known spyware, the same sources as Amnesty's MVT: Pegasus, Predator, Candiru, QuaDream, Cellebrite, stalkerware and more. It checks installed apps and their signatures, their file hashes, the last 7 days of connections, and system files and properties.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (count == 0) tr("المؤشرات لم تُنزَّل بعد.", "Indicators not downloaded yet.")
            else tr(
                "$count مؤشر من $sets مجموعة · آخر تحديث ${date.format(java.util.Date(updated))}",
                "$count indicators from $sets sets · updated ${date.format(java.util.Date(updated))}",
            ),
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        )
        if (count > 0) {
            Text(
                tr("🛡️ الحماية المباشرة تعمل: أي اتصال بخادم من هذه القوائم يُحظر فورًا وتصلك تنبيه.", "🛡️ Live protection on: any contact with a server on these lists is blocked at once and you are alerted."),
                style = MaterialTheme.typography.bodySmall,
                color = Green,
            )
        }
        Row {
            OutlinedButton(enabled = !busy, onClick = { scope.launch { com.ahmed.shield.Spyware.update() } }) {
                Text(tr("تحديث المؤشرات", "Update indicators"))
            }
            Spacer(Modifier.width(8.dp))
            Button(enabled = !busy && count > 0, onClick = { scope.launch { com.ahmed.shield.Spyware.scan(ctx) } }) {
                Text(tr("افحص الآن", "Scan now"))
            }
        }
        if (busy) {
            if (progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
        report?.let { r ->
            if (r.findings.isEmpty()) {
                CheckLine(
                    true, tr("لا يوجد أي تطابق", "No matches"),
                    tr(
                        "${date.format(java.util.Date(r.time))} · ${r.apps} تطبيق، ${r.apkHashed} ملف، ${r.domainsChecked} عنوان، مقابل ${r.indicators} مؤشر.",
                        "${date.format(java.util.Date(r.time))} · ${r.apps} apps, ${r.apkHashed} files, ${r.domainsChecked} addresses, against ${r.indicators} indicators.",
                    ),
                    null,
                )
            } else {
                Text(
                    tr("⚠️ ${r.findings.size} تطابق مع مؤشرات برامج تجسس", "⚠️ ${r.findings.size} matches with spyware indicators"),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                )
                r.findings.forEach { f ->
                    CheckLine(false, "${f.type}: ${f.value}", "${f.set} · ${f.detail}", null)
                }
                Text(
                    tr(
                        "التطابق لا يعني اختراقًا مؤكدًا، لكنه يستحق التحقق: افصل الإنترنت، وانقل بياناتك المهمة، واطلب فحصًا متخصصًا مجانيًا من Access Now Helpline أو Amnesty Security Lab.",
                        "A match is not proof of infection, but it needs checking: disconnect from the internet, move your important data, and ask for a free expert check from Access Now Helpline or Amnesty Security Lab.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Text(
            tr(
                "الحدود: أندرويد يمنع التطبيقات من رؤية العمليات الجارية، فهذا البند يحتاج فحص MVT الكامل من الكمبيوتر. ونتيجة «لا تطابق» تعني عدم وجود برنامج معروف، ولا تنفي وجود برنامج جديد.",
                "Limits: Android hides running processes from apps, so that item needs the full MVT check from a computer. \"No matches\" means no known spyware; it cannot rule out a new one.",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
