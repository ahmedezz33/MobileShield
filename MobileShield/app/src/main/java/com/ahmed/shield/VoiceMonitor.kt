package com.ahmed.shield

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

enum class VoiceKind { CALL_OUT, CALL_IN, CALL_END, MIC_ON, MIC_OFF, CAM_ON, CAM_OFF }

data class VoiceEvent(
    val time: Long,
    val kind: VoiceKind,
    val title: String,
    val detail: String,
    /** True when the screen was locked: the user was probably not using the phone. */
    val locked: Boolean,
)

/**
 * Watches every telephony call (cellular voice, VoLTE, Wi-Fi calling), every
 * microphone capture and every camera opening by any app, whichever way the
 * audio or picture then leaves the phone.
 */
object VoiceMonitor {
    private const val TAG = "VoiceMonitor"
    private const val MAX = 500
    private const val HOTWORD_SOURCE = 1999 // MediaRecorder.AudioSource.HOTWORD, a system API

    // Samsung's own source "SEC_BARGEIN_DRIVING", used by com.samsung.android.ese (SamsungSeAgent);
    // identified with "dumpsys audio" on a Galaxy S23 Ultra running One UI 9
    private const val SAMSUNG_BARGEIN_SOURCE = 16

    private val events = ArrayDeque<VoiceEvent>()
    val version = MutableStateFlow(0)
    val micActive = MutableStateFlow(false)
    val cameraActive = MutableStateFlow(false)

    private var appContext: Context? = null
    private var callCallback: TelephonyCallback? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var cameraCallback: CameraManager.AvailabilityCallback? = null
    private var torchCallback: CameraManager.TorchCallback? = null
    private val torchOn = HashSet<String>()
    private val openCameras = HashSet<String>()
    private var callState = TelephonyManager.CALL_STATE_IDLE
    private var callStartedAt = 0L
    private var activeSessions = setOf<Int>()
    private val main = Handler(Looper.getMainLooper())

    fun snapshot(): List<VoiceEvent> = synchronized(events) { events.toList() }

    fun clear() {
        synchronized(events) { events.clear() }
        version.value++
    }

    private fun add(e: VoiceEvent, alert: Boolean) {
        synchronized(events) {
            events.addFirst(e)
            if (events.size > MAX) events.removeLast()
        }
        version.value++
        val ctx = appContext ?: return
        if (alert) {
            val text = e.detail + if (e.locked) tr("\nحدث هذا والشاشة مقفلة.", "\nThis happened while the screen was locked.") else ""
            Notifier.alert(ctx, 40 + e.kind.ordinal, e.title, text)
        }
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appContext = app
        startCalls(app)
        startMicrophone(app)
        startCamera(app)
    }

    fun stop() {
        val app = appContext ?: return
        callCallback?.let {
            app.getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(it)
        }
        callCallback = null
        recordingCallback?.let {
            app.getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(it)
        }
        recordingCallback = null
        val cams = app.getSystemService(CameraManager::class.java)
        cameraCallback?.let { cams.unregisterAvailabilityCallback(it) }
        torchCallback?.let { cams.unregisterTorchCallback(it) }
        cameraCallback = null
        torchCallback = null
        openCameras.clear()
        activeSessions = emptySet()
        micActive.value = false
        cameraActive.value = false
    }

    // ---- Calls ----

    private fun startCalls(ctx: Context) {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = onCallState(ctx, state)
        }
        try {
            ctx.getSystemService(TelephonyManager::class.java).registerTelephonyCallback(ctx.mainExecutor, cb)
            callCallback = cb
            callState = ctx.getSystemService(TelephonyManager::class.java).callStateForSubscription
        } catch (e: SecurityException) {
            Log.w(TAG, "call state unavailable", e)
        }
    }

    private fun onCallState(ctx: Context, state: Int) {
        val previous = callState
        callState = state
        val locked = NetState.locked
        when {
            state == TelephonyManager.CALL_STATE_RINGING -> add(
                VoiceEvent(System.currentTimeMillis(), VoiceKind.CALL_IN, tr("مكالمة واردة", "Incoming call"),
                    tr("يرن الهاتف الآن بمكالمة عبر شبكة الجوال.", "The phone is ringing with a call over the mobile network."), locked),
                alert = false,
            )
            state == TelephonyManager.CALL_STATE_OFFHOOK -> {
                callStartedAt = System.currentTimeMillis()
                val outgoing = previous == TelephonyManager.CALL_STATE_IDLE
                add(
                    VoiceEvent(
                        callStartedAt,
                        if (outgoing) VoiceKind.CALL_OUT else VoiceKind.CALL_IN,
                        if (outgoing) tr("بدأت مكالمة صادرة", "Outgoing call started") else tr("تم الرد على مكالمة", "Call answered"),
                        if (outgoing) tr("خط الصوت مفتوح الآن عبر شبكة الجوال (اتصال صادر).", "The voice line is now open over the mobile network (outgoing call).")
                        else tr("خط الصوت مفتوح الآن عبر شبكة الجوال (مكالمة واردة).", "The voice line is now open over the mobile network (incoming call)."),
                        locked,
                    ),
                    // an outgoing call that starts while the screen is locked is the suspicious case
                    alert = outgoing,
                )
            }
            state == TelephonyManager.CALL_STATE_IDLE && previous != TelephonyManager.CALL_STATE_IDLE -> {
                val seconds = if (callStartedAt > 0) (System.currentTimeMillis() - callStartedAt) / 1000 else 0
                callStartedAt = 0
                // the call log entry is written a moment after the call ends
                main.postDelayed({
                    val last = MonitorReader.calls(ctx, 1).firstOrNull()
                        ?.takeIf { System.currentTimeMillis() - it.date < 5 * 60_000 }
                    val who = last?.let { it.name ?: it.number.ifBlank { tr("رقم مخفي", "Hidden number") } } ?: tr("غير معروف", "Unknown")
                    val tech = last?.let { callTech(it.features) } ?: ""
                    add(
                        VoiceEvent(
                            System.currentTimeMillis(), VoiceKind.CALL_END, tr("انتهت مكالمة", "Call ended"),
                            tr("الطرف: $who · المدة: ${seconds / 60}:${"%02d".format(seconds % 60)}$tech", "Party: $who · Duration: ${seconds / 60}:${"%02d".format(seconds % 60)}$tech"),
                            NetState.locked,
                        ),
                        alert = false,
                    )
                }, 2000)
            }
        }
    }

    private fun callTech(features: Int): String {
        val tags = listOfNotNull(
            tr("فيديو", "Video").takeIf { (features and android.provider.CallLog.Calls.FEATURES_VIDEO) != 0 },
            "HD / VoLTE".takeIf { (features and android.provider.CallLog.Calls.FEATURES_HD_CALL) != 0 },
            tr("عبر الواي فاي", "Over Wi-Fi").takeIf { (features and android.provider.CallLog.Calls.FEATURES_WIFI) != 0 },
        )
        return if (tags.isEmpty()) "" else " · ${tags.joinToString(" · ")}"
    }

    // ---- Microphone ----

    private fun startMicrophone(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) =
                onRecordings(ctx, configs)
        }
        am.registerAudioRecordingCallback(cb, main)
        recordingCallback = cb
        onRecordings(ctx, am.activeRecordingConfigurations)
    }

    private fun onRecordings(ctx: Context, configs: List<AudioRecordingConfiguration>) {
        val now = configs.map { it.clientAudioSessionId }.toSet()
        val started = configs.filter { it.clientAudioSessionId !in activeSessions }
        val stoppedCount = activeSessions.count { it !in now }
        activeSessions = now
        micActive.value = configs.any { it.clientAudioSource != HOTWORD_SOURCE }

        if (started.isNotEmpty()) {
            val apps = micApps(ctx)
            val who = when {
                apps != null && apps.isNotEmpty() -> apps.joinToString(tr("، ", ", ")) { AppNames.labelOf(it) }
                apps != null -> tr("تطبيق نظام", "System app")
                else -> Foreground.pkg(ctx)?.let { AppNames.labelOf(it) + tr(" (تقديري)", " (estimated)") } ?: tr("غير محدد", "Unidentified")
            }
            for (c in started) {
                val source = sourceName(c.clientAudioSource)
                val who = if (c.clientAudioSource == SAMSUNG_BARGEIN_SOURCE && apps.isNullOrEmpty()) {
                    "Samsung SE Agent (com.samsung.android.ese)"
                } else who
                val hotword = c.clientAudioSource == HOTWORD_SOURCE
                val inCall = callState != TelephonyManager.CALL_STATE_IDLE
                val silenced = c.isClientSilenced
                add(
                    VoiceEvent(
                        System.currentTimeMillis(), VoiceKind.MIC_ON,
                        if (hotword) tr("استماع لكلمة التنبيه", "Listening for wake word") else tr("المايكروفون يعمل الآن", "Microphone in use"),
                        tr("التطبيق: $who\nالنوع: $source", "App: $who\nType: $source") +
                            (if (inCall) tr(" · أثناء مكالمة", " · during a call") else "") +
                            (if (silenced) tr(" · كتمه النظام (لا يصل صوت)", " · silenced by the system (no audio)") else ""),
                        NetState.locked,
                    ),
                    // wake-word listening is constant and expected; everything else is reported
                    alert = !hotword,
                )
            }
        }
        if (stoppedCount > 0 && now.isEmpty()) {
            add(
                VoiceEvent(System.currentTimeMillis(), VoiceKind.MIC_OFF, tr("توقف المايكروفون", "Microphone stopped"),
                    tr("لا يوجد تطبيق يستخدم المايكروفون الآن.", "No app is using the microphone now."), NetState.locked),
                alert = false,
            )
        }
    }

    private fun sourceName(s: Int): String = when (s) {
        MediaRecorder.AudioSource.MIC -> tr("تسجيل صوت", "Audio recording")
        MediaRecorder.AudioSource.CAMCORDER -> tr("تصوير فيديو", "Video recording")
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> tr("مكالمة إنترنت (واتساب وغيره)", "Internet call (WhatsApp etc.)")
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> tr("التعرف على الكلام", "Speech recognition")
        MediaRecorder.AudioSource.VOICE_CALL,
        MediaRecorder.AudioSource.VOICE_UPLINK,
        MediaRecorder.AudioSource.VOICE_DOWNLINK -> tr("تسجيل مكالمة هاتفية", "Phone call recording")
        MediaRecorder.AudioSource.UNPROCESSED -> tr("تسجيل خام", "Raw recording")
        MediaRecorder.AudioSource.VOICE_PERFORMANCE -> tr("أداء صوتي", "Voice performance")
        HOTWORD_SOURCE -> tr("كلمة التنبيه (مساعد صوتي)", "Wake word (voice assistant)")
        SAMSUNG_BARGEIN_SOURCE -> tr(
            "استماع سامسونج للأوامر الصوتية (SEC_BARGEIN_DRIVING)",
            "Samsung voice-command listening (SEC_BARGEIN_DRIVING)",
        )
        else -> tr("مصدر $s", "Source $s")
    }

    // ---- Camera ----

    private fun startCamera(ctx: Context) {
        val cams = ctx.getSystemService(CameraManager::class.java)
        val torch = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (enabled) torchOn.add(cameraId) else torchOn.remove(cameraId)
            }
        }
        val cb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) {
                // the flashlight also takes the camera; wait for its callback before deciding
                main.postDelayed({
                    if (cameraId !in torchOn && openCameras.add(cameraId)) cameraOpened(ctx, cameraId)
                }, 600)
            }

            override fun onCameraAvailable(cameraId: String) {
                if (openCameras.remove(cameraId)) {
                    cameraActive.value = openCameras.isNotEmpty()
                    add(
                        VoiceEvent(System.currentTimeMillis(), VoiceKind.CAM_OFF, tr("أُغلقت الكاميرا", "Camera closed"),
                            tr("الكاميرا ${facing(ctx, cameraId)} لم تعد مستخدمة.", "The ${facing(ctx, cameraId)} camera is no longer in use."), NetState.locked),
                        alert = false,
                    )
                }
            }
        }
        cams.registerTorchCallback(torch, main)
        cams.registerAvailabilityCallback(cb, main)
        torchCallback = torch
        cameraCallback = cb
    }

    private fun cameraOpened(ctx: Context, id: String) {
        cameraActive.value = true
        val apps = appOpsUsers(ctx, AppOpsManager.OPSTR_CAMERA)
        val who = when {
            apps != null && apps.isNotEmpty() -> apps.joinToString(tr("، ", ", ")) { AppNames.labelOf(it) }
            apps != null -> tr("تطبيق نظام", "System app")
            else -> Foreground.pkg(ctx)?.let { AppNames.labelOf(it) + tr(" (تقديري)", " (estimated)") } ?: tr("غير محدد", "Unidentified")
        }
        add(
            VoiceEvent(
                System.currentTimeMillis(), VoiceKind.CAM_ON, tr("الكاميرا تعمل الآن", "Camera in use"),
                tr("التطبيق: $who\nالكاميرا: ${facing(ctx, id)}", "App: $who\nCamera: ${facing(ctx, id)}"), NetState.locked,
            ),
            alert = true,
        )
    }

    private fun facing(ctx: Context, id: String): String = try {
        when (ctx.getSystemService(CameraManager::class.java).getCameraCharacteristics(id)
            .get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> tr("الأمامية", "front")
            CameraCharacteristics.LENS_FACING_BACK -> tr("الخلفية", "back")
            else -> tr("الخارجية", "external")
        }
    } catch (_: Exception) {
        tr("رقم $id", "number $id")
    }

    fun micApps(ctx: Context): List<String>? = appOpsUsers(ctx, AppOpsManager.OPSTR_RECORD_AUDIO)

    /**
     * Packages currently using [op] (microphone or camera), read from app-ops. Needs GET_APP_OPS_STATS
     * (granted once over ADB); returns null when that is not available.
     */
    private fun appOpsUsers(ctx: Context, op: String): List<String>? {
        if (ctx.checkSelfPermission("android.permission.GET_APP_OPS_STATS") != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val ops = ctx.getSystemService(AppOpsManager::class.java)
            val m = AppOpsManager::class.java.getMethod("getPackagesForOps", Array<String>::class.java)
            val list = m.invoke(ops, arrayOf(op)) as? List<*> ?: return emptyList()
            val out = ArrayList<String>()
            for (po in list) {
                po ?: continue
                val pkg = po.javaClass.getMethod("getPackageName").invoke(po) as String
                val entries = po.javaClass.getMethod("getOps").invoke(po) as List<*>
                val running = entries.any { e ->
                    e != null && e.javaClass.getMethod("isRunning").invoke(e) as Boolean
                }
                if (running && pkg != ctx.packageName) out += pkg
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "app-ops lookup failed", e)
            null
        }
    }
}
