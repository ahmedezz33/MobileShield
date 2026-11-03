package com.ahmed.shield.ui

import com.ahmed.shield.tr
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ahmed.shield.Kind
import com.ahmed.shield.NetType
import com.ahmed.shield.Verdict
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Green = Color(0xFF2E7D32)

@Composable
fun ShieldTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

fun verdictName(v: Verdict): String = when (v) {
    Verdict.ALLOWED -> tr("مسموح", "Allowed")
    Verdict.DIRECT_SEEN -> tr("IP مباشر (تعلّم)", "Direct IP (learning)")
    Verdict.BLOCKED_AD -> tr("إعلان", "Ad")
    Verdict.BLOCKED_FIREWALL -> tr("محظور", "Blocked")
    Verdict.BLOCKED_LOCKED -> tr("قفل الشاشة", "Screen locked")
    Verdict.BLOCKED_DIRECT -> tr("IP مباشر", "Direct IP")
    Verdict.BLOCKED_BYPASS -> tr("تجاوز DNS", "DNS bypass")
}

fun verdictColor(v: Verdict): Color = when (v) {
    Verdict.ALLOWED -> Green
    Verdict.DIRECT_SEEN -> Color(0xFFF9A825)
    Verdict.BLOCKED_AD -> Color(0xFFE65100)
    Verdict.BLOCKED_FIREWALL -> Color(0xFFC62828)
    Verdict.BLOCKED_LOCKED -> Color(0xFF6A1B9A)
    Verdict.BLOCKED_DIRECT -> Color(0xFFAD1457)
    Verdict.BLOCKED_BYPASS -> Color(0xFF4E342E)
}

fun kindName(k: Kind): String = when (k) {
    Kind.DNS -> "DNS"
    Kind.TLS -> "HTTPS"
    Kind.TCP -> "TCP"
    Kind.UDP -> "UDP"
    Kind.ICMP -> "ICMP"
}

fun netName(n: NetType): String = when (n) {
    NetType.WIFI -> tr("واي فاي", "Wi-Fi")
    NetType.CELLULAR -> tr("بيانات الجوال", "Mobile data")
    NetType.OTHER -> tr("شبكة أخرى", "Other network")
    NetType.NONE -> tr("لا يوجد اتصال", "No connection")
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

fun formatTime(t: Long): String = synchronized(timeFormat) { timeFormat.format(Date(t)) }
