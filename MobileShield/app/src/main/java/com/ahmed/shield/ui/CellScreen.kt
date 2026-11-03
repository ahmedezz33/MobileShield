package com.ahmed.shield.ui

import com.ahmed.shield.tr
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.CellInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ahmed.shield.CellCard
import com.ahmed.shield.CellInfoReader
import com.ahmed.shield.InfoRow
import com.ahmed.shield.LogStore
import com.ahmed.shield.NetType
import kotlinx.coroutines.delay

private val CELL_PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.READ_PHONE_STATE,
)

private fun hasCellPermissions(ctx: Context) = CELL_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun CellScreen() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(hasCellPermissions(ctx)) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted = hasCellPermissions(ctx) }

    if (granted) {
        CellContent()
    } else {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                tr("لعرض أبراج الاتصال وقوة الإشارة ونوع الشبكة يحتاج التطبيق إذن الموقع وإذن الهاتف. ", "To show cell towers, signal strength and network type, the app needs location and phone permissions. ") +
                    tr("أندرويد يطلب إذن الموقع لأن معرّفات الأبراج تكشف موقعك التقريبي. ", "Android asks for location because tower IDs reveal your approximate position. ") +
                    tr("هذه البيانات تبقى على جوالك ولا تُرسل لأي جهة.", "This data stays on your phone and is never sent anywhere."),
            )
            Button(onClick = { launcher.launch(CELL_PERMISSIONS) }) { Text(tr("منح الأذونات", "Grant permissions")) }
        }
    }
}

@Composable
private fun CellContent() {
    val ctx = LocalContext.current
    var cells by remember { mutableStateOf<List<CellInfo>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            CellInfoReader.request(ctx) { cells = it }
            tick++
            delay(3000)
        }
    }
    val general = remember(cells, tick) { CellInfoReader.general(ctx, cells) }
    val cards = remember(cells) { CellInfoReader.cards(cells) }
    val cellularApps by produceState(emptyList<Pair<String, Int>>()) {
        while (true) {
            value = LogStore.snapshot()
                .filter { it.net == NetType.CELLULAR }
                .groupingBy { it.app }
                .eachCount()
                .toList()
                .sortedByDescending { it.second }
                .take(10)
            delay(2000)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard(tr("الشبكة الخلوية", "Cellular network")) { general.forEach { InfoLine(it) } }
        }
        item {
            SectionCard(tr("تطبيقات اتصلت عبر بيانات الجوال", "Apps that used mobile data")) {
                if (cellularApps.isEmpty()) {
                    Text(
                        tr("لا يوجد بعد. تظهر هنا بعد تشغيل الحماية واستخدام بيانات الجوال.", "Nothing yet. Apps appear here once protection is on and mobile data is used."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cellularApps.forEach { (app, count) -> InfoLine(InfoRow(app, tr("$count طلب", "$count requests"))) }
            }
        }
        if (cards.isEmpty()) {
            item {
                Text(
                    tr("لا تظهر الأبراج؟ تأكد أن خدمة الموقع (GPS) مفعّلة في الجوال.", "No towers shown? Make sure Location (GPS) is turned on."),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(cards) { CellCardView(it) }
    }
}

@Composable
private fun CellCardView(card: CellCard) {
    SectionCard(card.title) {
        Row {
            Badge(
                if (card.registered) tr("البرج المتصل به", "Serving tower") else tr("برج مجاور", "Neighbouring tower"),
                if (card.registered) Green else MaterialTheme.colorScheme.outline,
            )
        }
        card.rows.forEach { InfoLine(it) }
    }
}

@Composable
private fun InfoLine(row: InfoRow) {
    Row(Modifier.fillMaxWidth()) {
        Text(row.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(row.value, fontWeight = FontWeight.Medium)
    }
}
