package com.ahmed.shield

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.provider.Settings
import android.telephony.TelephonyManager

data class Messenger(val pkg: String, val name: String, val pathAr: String, val pathEn: String)

/**
 * Reads the state of the phone's radios and opens the system page that controls each.
 * Android lets ordinary apps read these switches but not flip them, so the actual
 * on/off happens on the page we open (one tap for the user).
 */
object Controls {

    fun wifiOn(ctx: Context): Boolean? = try {
        ctx.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled
    } catch (_: Exception) {
        null
    }

    fun bluetoothOn(ctx: Context): Boolean? = try {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled
    } catch (_: Exception) {
        null
    }

    fun nfcOn(ctx: Context): Boolean? = try {
        NfcAdapter.getDefaultAdapter(ctx)?.isEnabled
    } catch (_: Exception) {
        null
    }

    fun airplaneOn(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    fun mobileDataOn(ctx: Context): Boolean? = try {
        ctx.getSystemService(TelephonyManager::class.java).isDataEnabled
    } catch (_: Exception) {
        null
    }

    fun usbDebugging(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    fun developerOptions(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** Starts the first intent the phone can handle. */
    fun open(ctx: Context, vararg intents: Intent) {
        for (i in intents) {
            try {
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
        ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun quickShareIntent(): Intent = Intent().setComponent(
        ComponentName(
            "com.samsung.android.app.sharelive",
            "com.samsung.android.app.sharelive.presentation.settings.LaunchSettingsActivity",
        )
    )

    fun granted(ctx: Context, pkg: String, perm: String): Boolean =
        ctx.packageManager.checkPermission(perm, pkg) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** WhatsApp variants installed on the phone, with their display names. */
    fun installedWhatsApps(ctx: Context): List<Pair<String, String>> =
        listOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business").filter { (pkg, _) ->
            try {
                ctx.packageManager.getApplicationInfo(pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }

    /** Apps that may currently use the camera or the microphone. */
    fun sensorApps(ctx: Context): List<PermApp> = MonitorReader.permissionApps(
        ctx,
        mapOf(
            Manifest.permission.CAMERA to tr("الكاميرا", "Camera"),
            Manifest.permission.RECORD_AUDIO to tr("المايكروفون", "Microphone"),
        ),
    )

    private val MESSENGERS
        get() = listOf(
            Messenger(
                "com.whatsapp", "WhatsApp",
                "الإعدادات ← التخزين والبيانات ← التنزيل التلقائي للوسائط: اختر «لا وسائط» في الخيارات الثلاثة",
                "Settings → Storage and data → Media auto-download: choose \"No media\" in all three",
            ),
            Messenger(
                "com.whatsapp.w4b", "WhatsApp Business",
                "الإعدادات ← التخزين والبيانات ← التنزيل التلقائي للوسائط: اختر «لا وسائط» في الخيارات الثلاثة",
                "Settings → Storage and data → Media auto-download: choose \"No media\" in all three",
            ),
            Messenger(
                "org.telegram.messenger", "Telegram",
                "الإعدادات ← البيانات والتخزين ← التنزيل التلقائي للوسائط: أوقف الخيارات الثلاثة",
                "Settings → Data and Storage → Automatic media download: turn off all three",
            ),
            Messenger(
                "com.samsung.android.messaging", "Samsung Messages",
                "⋮ ← الإعدادات ← إعدادات أخرى ← رسائل الوسائط المتعددة ← الاسترداد التلقائي: إيقاف",
                "⋮ → Settings → More settings → Multimedia messages → Auto retrieve: off",
            ),
            Messenger(
                "com.google.android.apps.messaging", "Google Messages",
                "الإعدادات ← متقدم ← تنزيل رسائل الوسائط المتعددة تلقائيًا: إيقاف",
                "Settings → Advanced → Auto-download MMS: off",
            ),
            Messenger(
                "org.thoughtcrime.securesms", "Signal",
                "الإعدادات ← البيانات والتخزين ← التنزيل التلقائي للوسائط: أوقف الكل",
                "Settings → Data and storage → Media auto-download: turn all off",
            ),
            Messenger(
                "com.viber.voip", "Viber",
                "المزيد ← الإعدادات ← الوسائط ← التنزيل التلقائي: إيقاف",
                "More → Settings → Media → Auto download: off",
            ),
        )

    fun installedMessengers(ctx: Context): List<Messenger> {
        val pm = ctx.packageManager
        return MESSENGERS.filter {
            try {
                pm.getApplicationInfo(it.pkg, 0)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** WhatsApp's media servers; blocking them stops every media download, manual ones too. */
    val WHATSAPP_MEDIA = listOf("mmg.whatsapp.net", "mmg-fna.whatsapp.net", "cdn.whatsapp.net")

    fun whatsappMediaBlocked(): Boolean = WHATSAPP_MEDIA.all { it in Prefs.customBlocked.value }

    fun setWhatsappMediaBlocked(on: Boolean) {
        WHATSAPP_MEDIA.forEach { if (on) Prefs.block(it) else Prefs.unblock(it) }
    }
}
