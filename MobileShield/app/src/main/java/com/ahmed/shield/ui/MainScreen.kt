package com.ahmed.shield.ui

import com.ahmed.shield.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import com.ahmed.shield.PrivateDnsGuard
import com.ahmed.shield.Lang
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ahmed.shield.Blocklist
import com.ahmed.shield.LogStore
import com.ahmed.shield.ShieldVpnService
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onToggle: (Boolean) -> Unit) {
    val running by ShieldVpnService.running.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        if (!Blocklist.hasFile() && !Blocklist.busy.value) Blocklist.update()
    }

    val alwaysOn by ShieldVpnService.alwaysOn.collectAsState()
    val lockdown by ShieldVpnService.lockdown.collectAsState()
    val ctx = LocalContext.current

    val tabs = listOf(
        tr("التطبيقات", "Apps") to Icons.Filled.Apps,
        tr("السجل", "Log") to Icons.Filled.History,
        tr("المراقبة", "Monitor") to Icons.Filled.Visibility,
        tr("التحكم", "Control") to Icons.Filled.Tune,
        tr("الملفات", "Files") to Icons.Filled.Folder,
        tr("الإعدادات", "Settings") to Icons.Filled.Settings,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("درع الجوال", "MobileShield"), fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = { Lang.set(ctx, !Lang.english.value) }) {
                        Text(if (Lang.english.value) "عربي" else "English")
                    }
                    Text(
                        if (running) tr("يعمل", "On") else tr("متوقف", "Off"),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (running) Green else MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = running, onCheckedChange = onToggle)
                    Spacer(Modifier.width(12.dp))
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StatsBar(running)
            val hardened = alwaysOn && lockdown && PrivateDnsGuard.canWrite(ctx)
            if (running && !hardened && tab != 5) {
                Text(
                    tr("الحماية تعمل لكنها غير مُحكمة بالكامل. اضغط هنا لإكمال الإعداد.", "Protection is on but not fully locked down. Tap here to finish setup."),
                    Modifier
                        .fillMaxWidth()
                        .clickable { tab = 5 }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> AppsScreen()
                    1 -> LogScreen()
                    2 -> MonitorScreen()
                    3 -> ControlScreen()
                    4 -> FilesScreen()
                    else -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun StatsBar(running: Boolean) {
    val stats by produceState(longArrayOf(0, 0, 0, 0)) {
        while (true) {
            value = longArrayOf(
                LogStore.total.get(), LogStore.blockedAds.get(),
                LogStore.blockedFirewall.get(), LogStore.blockedDirect.get(),
            )
            delay(1000)
        }
    }
    Surface(
        color = if (running) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (running) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat(tr("الأحداث", "Events"), stats[0], Modifier.weight(1f))
                Stat(tr("إعلانات", "Ads"), stats[1], Modifier.weight(1f))
                Stat(tr("حظر الجدار", "Firewall"), stats[2], Modifier.weight(1f))
                Stat(tr("IP/DNS مباشر", "Direct IP/DNS"), stats[3], Modifier.weight(1f))
            }
        } else {
            Text(
                tr("الحماية متوقفة. شغّلها من المفتاح في الأعلى.", "Protection is off. Turn it on with the switch at the top."),
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: Long, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
