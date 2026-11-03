package com.ahmed.shield.ui

import com.ahmed.shield.tr
import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PublicOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.ahmed.shield.Prefs
import com.ahmed.shield.UsageStore
import com.ahmed.shield.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class AppItem(val pkg: String, val label: String, val uid: Int, val system: Boolean) {
    /** Core Android processes share system uids; blocking them would break the phone. */
    val core get() = uid % 100_000 < Process.FIRST_APPLICATION_UID
}

private fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    @Suppress("DEPRECATION")
    return pm.getInstalledApplications(0)
        .asSequence()
        .filter { it.packageName != ctx.packageName }
        .filter {
            pm.checkPermission(Manifest.permission.INTERNET, it.packageName) ==
                PackageManager.PERMISSION_GRANTED
        }
        .map { ai ->
            // system apps that have a launcher icon (Chrome, YouTube…) count as normal apps
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                pm.getLaunchIntentForPackage(ai.packageName) == null
            AppItem(ai.packageName, pm.getApplicationLabel(ai).toString(), ai.uid, system)
        }
        .sortedBy { it.label.lowercase() }
        .toList()
}

@Composable
fun AppsScreen() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSystem by rememberSaveable { mutableStateOf(false) }
    val version by Prefs.rulesVersion.collectAsState()
    val usage by produceState(UsageStore.snapshot()) {
        while (true) {
            delay(3000)
            value = UsageStore.snapshot()
        }
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(ctx) }
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            singleLine = true,
            placeholder = { Text(tr("ابحث عن تطبيق", "Search apps")) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
            Text(tr("تطبيقات النظام", "System apps"), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                tr("واي فاي · بيانات · قفل · IP مباشر", "Wi-Fi · Data · Locked · Direct IP"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
        }
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            val filtered = remember(list, query, showSystem) {
                list.filter {
                    (showSystem || !it.system) &&
                        (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(filtered, key = { it.pkg }) { app ->
                    AppRow(app, version, usage[app.uid])
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppItem, version: Int, usage: LongArray?) {
    val ctx = LocalContext.current
    val mask = remember(version, app.pkg) { Prefs.rule(app.pkg) }
    val icon by produceState<ImageBitmap?>(null, app.pkg) {
        value = withContext(Dispatchers.IO) {
            try {
                ctx.packageManager.getApplicationIcon(app.pkg).toBitmap(96, 96).asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bitmap = icon
        if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
        else Spacer(Modifier.size(40.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                app.label, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = when {
                app.core -> tr("خدمة نظام أساسية: تخضع للفحص ولا يمكن حظرها", "Core system service: inspected, but cannot be blocked")
                usage != null -> tr("واي فاي ↓${formatBytes(usage[1])} ↑${formatBytes(usage[0])} · ", "Wi-Fi ↓${formatBytes(usage[1])} ↑${formatBytes(usage[0])} · ") +
                    tr("بيانات ↓${formatBytes(usage[3])} ↑${formatBytes(usage[2])}", "Data ↓${formatBytes(usage[3])} ↑${formatBytes(usage[2])}")
                else -> app.pkg
            }
            Text(
                sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (!app.core) {
            RuleToggle(mask and Prefs.BLOCK_WIFI != 0, Icons.Filled.Wifi, Icons.Filled.WifiOff) {
                Prefs.setRule(app.pkg, mask xor Prefs.BLOCK_WIFI)
            }
            RuleToggle(mask and Prefs.BLOCK_CELL != 0, Icons.Filled.SignalCellularAlt, Icons.Filled.SignalCellularOff) {
                Prefs.setRule(app.pkg, mask xor Prefs.BLOCK_CELL)
            }
            RuleToggle(mask and Prefs.BLOCK_LOCKED != 0, Icons.Filled.LockOpen, Icons.Filled.Lock) {
                Prefs.setRule(app.pkg, mask xor Prefs.BLOCK_LOCKED)
            }
            // inverted: the bit grants an exception from direct-IP blocking
            RuleToggle(
                mask and Prefs.ALLOW_DIRECT == 0, Icons.Filled.Public, Icons.Filled.PublicOff,
                blockedTint = MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Prefs.setRule(app.pkg, mask xor Prefs.ALLOW_DIRECT)
            }
        }
    }
}

@Composable
private fun RuleToggle(
    blocked: Boolean,
    allowedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    blockedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    blockedTint: Color = MaterialTheme.colorScheme.error,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            if (blocked) blockedIcon else allowedIcon,
            contentDescription = null,
            tint = if (blocked) blockedTint else Green,
        )
    }
}
