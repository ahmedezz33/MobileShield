package com.ahmed.shield.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ahmed.shield.Controls
import com.ahmed.shield.NetState
import com.ahmed.shield.PermApp
import com.ahmed.shield.Prefs
import com.ahmed.shield.Privacy
import androidx.compose.material3.HorizontalDivider
import com.ahmed.shield.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private class RadioState(
    val wifi: Boolean?,
    val bluetooth: Boolean?,
    val nfc: Boolean?,
    val airplane: Boolean,
    val mobileData: Boolean?,
    val gen: Int,
)

@Composable
fun ControlScreen() {
    val ctx = LocalContext.current
    val radios by produceState<RadioState?>(null) {
        while (true) {
            value = RadioState(
                Controls.wifiOn(ctx), Controls.bluetoothOn(ctx), Controls.nfcOn(ctx),
                Controls.airplaneOn(ctx), Controls.mobileDataOn(ctx), NetState.cellGen,
            )
            delay(2000)
        }
    }
    val sensorApps by produceState(emptyList<PermApp>()) {
        value = withContext(Dispatchers.IO) { Controls.sensorApps(ctx) }
    }
    val killWifi by Prefs.killWifi.collectAsState()
    val killCell by Prefs.killCell.collectAsState()
    val gens by Prefs.allowedGens.collectAsState()
    val blocked by Prefs.customBlocked.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(tr("قطع الإنترنت (ينفّذه جدار الحماية فورًا)", "Cut the internet (applied by the firewall at once)")) {
            SwitchLine(
                tr("قطع الإنترنت عن كل التطبيقات على الواي فاي", "Cut every app off the internet on Wi-Fi"),
                killWifi,
            ) { Prefs.setKillWifi(it) }
            SwitchLine(
                tr("قطع الإنترنت عن كل التطبيقات على بيانات الجوال", "Cut every app off the internet on mobile data"),
                killCell,
            ) { Prefs.setKillCell(it) }
            Text(
                tr("السماح ببيانات الجوال على:", "Allow mobile data on:"),
                style = MaterialTheme.typography.titleSmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(2, 3, 4, 5).forEach { g ->
                    Checkbox(checked = g in gens, onCheckedChange = { Prefs.setGenAllowed(g, it) })
                    Text("${g}G", fontWeight = if (radios?.gen == g) FontWeight.Bold else FontWeight.Normal)
                    Spacer(Modifier.width(4.dp))
                }
            }
            Text(
                tr(
                    "الشبكة الحالية: ${radios?.gen?.takeIf { it > 0 }?.let { "${it}G" } ?: "غير معروفة"}. " +
                        "إذا نزل الجوال إلى جيل غير مسموح تنقطع البيانات تلقائيًا. ألغِ 2G لتتقي أبراج الاتصال المزيّفة. " +
                        "المكالمات العادية والرسائل لا تمر بالإنترنت فلا تتأثر.",
                    "Current network: ${radios?.gen?.takeIf { it > 0 }?.let { "${it}G" } ?: "unknown"}. " +
                        "If the phone drops to a generation that is not allowed, data is cut automatically. Untick 2G to guard against fake cell towers. " +
                        "Regular calls and SMS do not use the internet and are not affected.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(tr("الاتصالات اللاسلكية", "Wireless connections")) {
            Text(
                tr(
                    "أندرويد يسمح للتطبيقات بقراءة هذه المفاتيح لا بتغييرها، فزر «تحكم» يفتح صفحتها مباشرة لتغلقها بلمسة.",
                    "Android lets apps read these switches but not flip them, so \"Control\" opens each page directly for a one-tap change.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val r = radios
            RadioLine("Wi-Fi", r?.wifi) { Controls.open(ctx, Intent(Settings.Panel.ACTION_WIFI), Intent(Settings.ACTION_WIFI_SETTINGS)) }
            RadioLine(tr("البلوتوث", "Bluetooth"), r?.bluetooth) { Controls.open(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            RadioLine("NFC", r?.nfc) { Controls.open(ctx, Intent(Settings.ACTION_NFC_SETTINGS)) }
            RadioLine("Quick Share", null) {
                Controls.open(ctx, Controls.quickShareIntent(), Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
            RadioLine(tr("بيانات الجوال ونوع الشبكة (2G/3G/4G/5G)", "Mobile data and network type (2G/3G/4G/5G)"), r?.mobileData) {
                Controls.open(ctx, Intent(Settings.ACTION_DATA_ROAMING_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
            RadioLine(tr("وضع الطيران (يغلق كل شيء)", "Airplane mode (turns everything off)"), r?.airplane) {
                Controls.open(ctx, Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
            }
            Text(
                tr(
                    "في صفحة شبكات الجوال: «وضع الشبكة» يحدد الأجيال، و«السماح بشبكة 2G» أطفئه.",
                    "On the Mobile networks page: \"Network mode\" picks the generations; turn \"Allow 2G\" off.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard(tr("المايكروفون والكاميرا", "Microphone and camera")) {
            Text(
                tr(
                    "لإيقاف المايكروفون أو الكاميرا لكل التطبيقات: اسحب لوحة الإعدادات السريعة واضغط «الوصول إلى الميكروفون» و«الوصول إلى الكاميرا». " +
                        "أندرويد لا يفصل الكاميرا الأمامية عن الخلفية؛ المفتاح يغلقهما معًا. ويصلك تنبيه من درع الجوال كلما فُتحت أي منهما.",
                    "To turn the microphone or camera off for every app: pull down Quick Settings and tap \"Mic access\" and \"Camera access\". " +
                        "Android does not separate the front and back cameras; the switch turns both off. MobileShield alerts you whenever either is opened.",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { Controls.open(ctx, Intent(Settings.ACTION_PRIVACY_SETTINGS)) }) {
                Text(tr("فتح إعدادات الخصوصية", "Open privacy settings"))
            }
            Controls.installedWhatsApps(ctx).forEach { (pkg, name) ->
                // re-read on every refresh tick so the state updates after returning from settings
                val cam = remember(radios) { Controls.granted(ctx, pkg, android.Manifest.permission.CAMERA) }
                val mic = remember(radios) { Controls.granted(ctx, pkg, android.Manifest.permission.RECORD_AUDIO) }
                Text(name, style = MaterialTheme.typography.titleSmall)
                listOf(tr("الكاميرا", "Camera") to cam, tr("المايكروفون", "Microphone") to mic).forEach { (label, on) ->
                    RadioLine(label, on) {
                        Controls.open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                    }
                }
            }
            if (Controls.installedWhatsApps(ctx).isNotEmpty()) {
                Text(
                    tr(
                        "«تحكم» يفتح صفحة واتساب: الأذونات ← الكاميرا أو الميكروفون ← «عدم السماح» لإيقافها، أو «السماح أثناء الاستخدام» لإعادتها.",
                        "\"Control\" opens WhatsApp's page: Permissions → Camera or Microphone → \"Don't allow\" to turn it off, or \"Allow only while using\" to restore it.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                tr("تطبيقات تملك الآن صلاحية الكاميرا أو المايكروفون:", "Apps that can use the camera or microphone now:"),
                style = MaterialTheme.typography.titleSmall,
            )
            sensorApps.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label)
                        Text(
                            a.perms.joinToString(tr("، ", ", ")),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        Controls.open(
                            ctx,
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}")),
                        )
                    }) { Text(tr("سحب", "Revoke")) }
                }
            }
        }

        PrivacyCard()

        SectionCard(tr("منع التحليل التلقائي (مدخل هجمات «بدون نقرة»)", "Stop automatic parsing (the zero-click entry point)")) {
            Text(
                tr(
                    "الهجوم يبدأ عندما يحلّل الجوال ملفًا وصله دون أن تفتحه: صورة أو معاينة رابط أو رسالة وسائط. أغلق هذه المداخل الثلاثة:",
                    "The attack starts when the phone parses a file you never opened: a picture, a link preview or a media message. Close these three entry points:",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            StepLine(
                tr("1. Auto Blocker مع Message Guard", "1. Auto Blocker with Message Guard"),
                tr(
                    "يفتح صور الرسائل داخل صندوق معزول، فلا تصل الثغرة إلى النظام. الإعدادات ← الأمان والخصوصية ← Auto Blocker.",
                    "Opens message images inside an isolated box, so the flaw cannot reach the system. Settings → Security and privacy → Auto Blocker.",
                ),
            ) { Controls.open(ctx, Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            Controls.installedWhatsApps(ctx).forEach { (pkg, name) ->
                StepLine(
                    tr("2. معاينات الروابط في $name", "2. Link previews in $name"),
                    tr(
                        "الإعدادات ← الخصوصية ← متقدم ← «تعطيل معاينات الروابط»: يمنع واتساب من جلب صفحات وصور الروابط تلقائيًا.",
                        "Settings → Privacy → Advanced → \"Disable link previews\": stops WhatsApp fetching link pages and images automatically.",
                    ),
                ) { ctx.packageManager.getLaunchIntentForPackage(pkg)?.let { Controls.open(ctx, it) } }
            }
            StepLine(
                tr("3. التنزيل التلقائي للوسائط", "3. Media auto-download"),
                tr(
                    "أوقفه في كل تطبيق من البطاقة التالية، أو امنع خوادم وسائط واتساب كليًا: الملف الذي لا يصل لا يُحلَّل.",
                    "Turn it off in every app from the next card, or block WhatsApp's media servers entirely: a file that never arrives is never parsed.",
                ),
                null,
            )
            Text(
                tr(
                    "ومن بطاقة الاتصالات أعلاه: أطفئ البلوتوث وNFC وQuick Share عند عدم الحاجة، فهي مداخل أخرى لنفس النوع من الهجمات.",
                    "And from the connections card above: turn Bluetooth, NFC and Quick Share off when not needed; they are other entry points for the same attacks.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(tr("التنزيل التلقائي للوسائط", "Media auto-download")) {
            Text(
                tr(
                    "لا يسمح أندرويد لأي تطبيق بتغيير إعدادات تطبيق آخر، فهذا الخيار داخل كل تطبيق. المسار لكل تطبيق مثبّت عندك:",
                    "Android does not let any app change another app's settings, so this option lives inside each app. The path for each installed app:",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Controls.installedMessengers(ctx).forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontWeight = FontWeight.Medium)
                        Text(tr(m.pathAr, m.pathEn), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = {
                        ctx.packageManager.getLaunchIntentForPackage(m.pkg)?.let { Controls.open(ctx, it) }
                    }) { Text(tr("فتح", "Open")) }
                }
            }
            val waBlocked = remember(blocked) { Controls.whatsappMediaBlocked() }
            SwitchLine(
                tr(
                    "حظر خوادم وسائط واتساب (يمنع كل تنزيل للصور والفيديو والصوت، حتى اليدوي)",
                    "Block WhatsApp media servers (stops every photo, video and voice download, manual ones too)",
                ),
                waBlocked,
            ) { Controls.setWhatsappMediaBlocked(it) }
        }
    }
}

/** Per-service switches: block only the usage data, or cut the whole app off. */
@Composable
private fun PrivacyCard() {
    val ctx = LocalContext.current
    val groups by Privacy.enabled.collectAsState()
    val version by Prefs.rulesVersion.collectAsState()
    SectionCard(tr("إيقاف الاتصالات حسب الخدمة", "Stop connections per service")) {
        Text(
            tr(
                "«بيانات الاستخدام» يحجب خوادم جمع البيانات فقط، والخدمة تبقى تعمل. «قطع كامل» يمنع التطبيق من الإنترنت تمامًا.",
                "\"Usage data\" blocks only the data-collection servers; the service keeps working. \"Full block\" cuts the app off the internet completely.",
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Privacy.TARGETS.forEach { t ->
            val installed = remember(t.id) {
                t.packages.filter {
                    try {
                        ctx.packageManager.getApplicationInfo(it, 0)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
            if (t.group == null && installed.isEmpty()) return@forEach
            HorizontalDivider()
            Text(t.name, fontWeight = FontWeight.Bold)
            Text(
                tr(t.whyAr, t.whyEn),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            t.group?.let { g ->
                SwitchLine(tr("حظر بيانات الاستخدام", "Block usage data"), g in groups) {
                    Privacy.setGroup(ctx, g, it)
                }
            }
            if (installed.isNotEmpty()) {
                val full = remember(version) { Privacy.fullyBlocked(installed) }
                SwitchLine(tr("قطع كامل عن الإنترنت", "Full block from the internet"), full) {
                    Privacy.setFullBlock(installed, it)
                }
                if (t.fullAr.isNotEmpty()) {
                    Text(
                        tr("عند القطع الكامل: ${t.fullAr}", "With full block: ${t.fullEn}"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepLine(title: String, detail: String, onOpen: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onOpen != null) TextButton(onClick = onOpen) { Text(tr("فتح", "Open")) }
    }
}

@Composable
private fun SwitchLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RadioLine(label: String, on: Boolean?, onControl: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        when (on) {
            true -> Badge(tr("يعمل", "On"), MaterialTheme.colorScheme.error)
            false -> Badge(tr("مغلق", "Off"), Green)
            null -> {}
        }
        TextButton(onClick = onControl) { Text(tr("تحكم", "Control")) }
    }
}
