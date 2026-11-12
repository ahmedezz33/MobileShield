package com.ahmed.shield

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import com.ahmed.shield.engine.Allowed
import com.ahmed.shield.engine.Packet
import com.ahmed.shield.engine.ResourceRecord
import com.ahmed.shield.engine.Usage
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Full-device VPN: every packet from every app goes into the tunnel, where the
 * NetGuard engine (native code) tracks connections and asks [isAddressAllowed]
 * about each new one. Allowed traffic is forwarded through protected sockets.
 */
class ShieldVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.ahmed.shield.START"
        const val ACTION_STOP = "com.ahmed.shield.STOP"
        const val ACTION_RELOAD = "com.ahmed.shield.RELOAD"
        private const val TAG = "ShieldVpn"
        private const val VPN4 = "10.1.10.1"
        private const val VPN6 = "fd00:1:fd00:1:fd00:1:fd00:1"
        private const val NXDOMAIN = 3
        private const val CHANNEL = "shield"
        private const val NOTIFICATION_ID = 1

        /** Carrier call services (IMS, ePDG for Wi-Fi Calling) on Samsung and AOSP phones. */
        val TELEPHONY_PACKAGES = listOf(
            "com.android.phone", "com.sec.epdg", "com.sec.imsservice", "com.samsung.android.ims",
            "com.android.ims.rcsservice", "com.google.android.ims", "com.sec.unifiedwfc",
        )

        val running = MutableStateFlow(false)
        val alwaysOn = MutableStateFlow(false)
        val lockdown = MutableStateFlow(false)

        @Volatile
        var current: ShieldVpnService? = null
            private set

        init {
            System.loadLibrary("netguard")
        }
    }

    private external fun jni_init(sdk: Int): Long
    private external fun jni_start(context: Long, loglevel: Int)
    private external fun jni_run(context: Long, tun: Int, fwd53: Boolean, rcode: Int)
    private external fun jni_stop(context: Long)
    private external fun jni_clear(context: Long)
    private external fun jni_get_mtu(): Int
    private external fun jni_socks5(addr: String, port: Int, username: String, password: String)
    private external fun jni_done(context: Long)

    private var jniContext = 0L
    private var tun: ParcelFileDescriptor? = null
    private var tunnelThread: Thread? = null
    private val engineLock = Any()
    private var active = false
    private var tunIpv6 = false
    private var dnsServers: Set<String> = emptySet()
    private var lastSni: String? = null
    private var lastHotspot = false
    private var lastStrictDns = false
    private var ticks = 0
    private lateinit var cm: ConnectivityManager
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private var ticker: ScheduledExecutorService? = null
    private val dnsMapFile by lazy { File(filesDir, "dnsmap.txt") }

    private val resetRunnable = Runnable {
        background.execute {
            if (tun != null) {
                stopEngine()
                startEngine()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val locked = when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> true
                Intent.ACTION_USER_PRESENT -> false
                else -> isKeyguardLocked()
            }
            if (locked != NetState.locked) {
                NetState.locked = locked
                if (Prefs.anyLockRule()) requestReset()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(ConnectivityManager::class.java)
        jniContext = jni_init(Build.VERSION.SDK_INT)
        DnsMap.load(dnsMapFile)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> if (active) stopVpn()
        }
        startForegroundNotification()
        if (!active) startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn()
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        if (jniContext != 0L) {
            jni_done(jniContext)
            jniContext = 0L
        }
        background.shutdown()
        super.onDestroy()
    }

    private fun startVpn() {
        val up = Prefs.upstream.value
        dnsServers = setOf(up.primary, up.secondary)
        // Without public IPv6 underneath, leaving IPv6 out makes Android block it outright,
        // so apps fall back to IPv4 at once instead of timing out on unreachable addresses.
        tunIpv6 = NetState.hasPublicIpv6(cm)
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(VPN4, 32)
            .addRoute("0.0.0.0", 0)
        if (tunIpv6) {
            builder.addAddress(VPN6, 128)
            builder.addRoute("2000::", 3) // all public IPv6, so nothing leaks around the tunnel
        }
        builder
            .addDnsServer(up.primary)
            .addDnsServer(up.secondary)
            .setMtu(jni_get_mtu())
            .setBlocking(true)
            .setMetered(false)
        try {
            // our own sockets (list downloads) must not loop back into the tunnel
            builder.addDisallowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
        }
        // Wi-Fi Calling and VoLTE run an IPsec (ESP) tunnel to the carrier, which the
        // engine cannot forward (it handles TCP, UDP and ICMP only). The call stack is
        // system telephony, not an app that browses, so it goes around the tunnel.
        WifiCallingGuard.apply(this)
        if (Prefs.wifiCalling.value) for (pkg in TELEPHONY_PACKAGES) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        val pfd = try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "establish failed", e)
            null
        }
        if (pfd == null) {
            stopSelf()
            return
        }
        tun = pfd
        active = true
        current = this

        NetState.start(this)
        var lastType = NetState.type
        var lastGen = NetState.cellGen
        NetState.listener = {
            if (NetState.ipv6 != tunIpv6) {
                main.post { if (active) reload() }
            } else if (NetState.type != lastType || NetState.cellGen != lastGen) {
                lastType = NetState.type
                lastGen = NetState.cellGen
                requestReset()
            }
        }
        NetState.locked = isKeyguardLocked()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        PrivateDnsGuard.start(this)
        LocationLock.start(this)
        VoiceMonitor.start(this)

        startEngine()
        ticker = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({ tick() }, 2, 20, TimeUnit.SECONDS)
        }
        running.value = true
    }

    private fun stopVpn() {
        if (!active) return
        active = false
        main.removeCallbacks(resetRunnable)
        ticker?.shutdownNow()
        ticker = null
        stopEngine()
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        NetState.listener = null
        NetState.stop()
        PrivateDnsGuard.stop(this)
        VoiceMonitor.stop()
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        current = null
        DnsMap.save(dnsMapFile)
        running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun startEngine() {
        synchronized(engineLock) {
            val t = tun ?: return
            if (tunnelThread != null) return
            jni_socks5("", 0, "", "")
            jni_start(jniContext, Log.WARN)
            val fd = t.fd
            tunnelThread = Thread({ jni_run(jniContext, fd, false, NXDOMAIN) }, "shield-engine")
                .also { it.start() }
        }
    }

    private fun stopEngine() {
        synchronized(engineLock) {
            val thread = tunnelThread ?: return
            jni_stop(jniContext)
            thread.join(5000)
            tunnelThread = null
            jni_clear(jniContext) // drops every open connection
        }
    }

    /** Rebuild the tunnel, e.g. when IPv6 appears or disappears on the network. */
    private fun reload() {
        stopVpn()
        startForegroundNotification()
        startVpn()
    }

    /** Restart the engine shortly, closing open connections so new rules apply immediately. */
    fun requestReset() {
        main.removeCallbacks(resetRunnable)
        main.postDelayed(resetRunnable, 800)
    }

    private fun tick() {
        // integrity every minute: verified boot, bootloader, Knox, SELinux, root files
        if (ticks % 3 == 0) main.post { LocationLock.enforce(this) }
        if (ticks++ % 3 == 0) Integrity.watch(this)
        alwaysOn.value = isAlwaysOn
        lockdown.value = isLockdownEnabled

        val hotspot = MonitorReader.tethering().active
        if (hotspot && !lastHotspot) {
            Notifier.alert(
                this, 31, tr("نقطة الاتصال مفعّلة", "Hotspot is on"),
                tr("أجهزة أخرى قد تستخدم بيانات جوالك الآن. حركة هذه الأجهزة لا تمر عبر جدار الحماية، ", "Other devices may be using your mobile data now. Their traffic does not pass through the firewall, ") +
                    tr("وتظهر كميتها في شاشة المراقبة.", "and its amount appears on the Monitor screen.")
            )
        }
        lastHotspot = hotspot

        val strict = !PrivateDnsGuard.canWrite(this) && PrivateDnsGuard.strictActive(this)
        if (strict && !lastStrictDns) {
            Notifier.alert(
                this, 32, tr("DNS الخاص مفعّل", "Private DNS is on"),
                tr("«DNS الخاص» يتجاوز فلتر الإعلانات. أطفئه من الإعدادات، أو امنح التطبيق صلاحية إطفائه تلقائيًا.", "Private DNS bypasses the ad filter. Turn it off in Settings, or let the app turn it off automatically.")
            )
        }
        lastStrictDns = strict
    }

    // ---- Called from native code (engine thread) ----

    @Suppress("unused")
    private fun nativeExit(reason: String?) {
        Log.w(TAG, "native exit: $reason")
        if (reason != null) Notifier.alert(this, 33, tr("توقف محرك الحماية", "Protection engine stopped"), reason)
    }

    @Suppress("unused")
    private fun nativeError(error: Int, message: String?) {
        Log.w(TAG, "native error $error: $message")
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private fun logPacket(packet: Packet) {
        // blocked lookups are logged in isDomainBlocked instead
    }

    @Suppress("unused")
    private fun dnsResolved(rr: ResourceRecord) {
        val name = rr.QName ?: return
        val ip = rr.Resource ?: return
        DnsMap.put(ip, name)
        if (rr.TTL == -1) lastSni = name // TLS server name, isDomainBlocked follows
    }

    @Suppress("unused")
    private fun isDomainBlocked(name: String, uid: Int): Boolean {
        val tls = lastSni == name
        lastSni = null
        val verdict = when {
            Spyware.domainSet(name)?.also { Spyware.alertContact(this, name, it, AppNames.label(uid).first) } != null ->
                Verdict.BLOCKED_SPYWARE
            Prefs.blockDoh.value && Blocklist.isDoh(name) -> Verdict.BLOCKED_BYPASS
            Privacy.blockingGroup(name) != null -> Verdict.BLOCKED_AD
            Prefs.adBlock.value && !Blocklist.isAllowed(name) && Blocklist.isBlocked(name) -> Verdict.BLOCKED_AD
            else -> Verdict.ALLOWED
        }
        if (verdict.blocked) Sinkhole.register(name)
        var (label, pkg) = AppNames.label(uid)
        if (uid < 0 && verdict.blocked) {
            // blocked names get no follow-up connection to attribute them by
            Foreground.pkg(this)?.let { fg ->
                label = AppNames.labelOf(fg) + tr(" (تقديري)", " (estimated)")
                pkg = null
            }
        }
        LogStore.add(
            LogEntry(
                System.currentTimeMillis(), uid, label, pkg,
                if (tls) Kind.TLS else Kind.DNS, name, 0, null, verdict, NetState.type,
            )
        )
        return verdict.blocked
    }

    @Suppress("unused")
    private fun getUidQ(version: Int, protocol: Int, saddr: String, sport: Int, daddr: String, dport: Int): Int {
        if (protocol != 6 && protocol != 17) return Process.INVALID_UID
        return try {
            cm.getConnectionOwnerUid(protocol, InetSocketAddress(saddr, sport), InetSocketAddress(daddr, dport))
        } catch (_: Exception) {
            Process.INVALID_UID
        }
    }

    @Suppress("unused")
    private fun isAddressAllowed(packet: Packet): Allowed? {
        val sinkName = packet.daddr?.let { Sinkhole.nameFor(it) }
        if (sinkName != null) {
            // an app tried to reach a blocked name: now we know exactly which app
            packet.allowed = false
            val (label, pkg) = AppNames.label(packet.uid)
            if (packet.uid >= 0) LogStore.attribute(sinkName, packet.uid, label, pkg, System.currentTimeMillis())
            return null
        }
        val verdict = decide(packet)
        packet.allowed = !verdict.blocked
        val sni = packet.data == "sni"
        val ourDns = packet.dport == 53 && packet.daddr in dnsServers
        if (!sni && !(ourDns && packet.allowed)) {
            // ICMP and some system traffic carry no owner
            val (label, pkg) = when {
                packet.uid >= 0 -> AppNames.label(packet.uid)
                // router solicitations, multicast group reports: sent by the kernel, never leave the LAN
                packet.daddr?.let { IpUtil.isLocal(it) } == true ->
                    tr("نظام الشبكة (رسائل محلية)", "Network system (local messages)") to null
                else -> tr("غير محدد", "Unidentified") to null
            }
            val domain = packet.daddr?.let { DnsMap.name(it) }
            if (domain != null && packet.uid >= 0 && packet.allowed) {
                LogStore.attribute(domain, packet.uid, label, pkg, System.currentTimeMillis())
            }
            val kind = when (packet.protocol) {
                6 -> Kind.TCP
                17 -> Kind.UDP
                else -> Kind.ICMP
            }
            LogStore.add(
                LogEntry(
                    System.currentTimeMillis(), packet.uid, label, pkg, kind,
                    packet.daddr ?: "?", packet.dport, packet.daddr?.let { DnsMap.name(it) },
                    verdict, NetState.type,
                )
            )
        }
        return if (packet.allowed) Allowed() else null
    }

    @Suppress("unused")
    private fun accountUsage(usage: Usage) {
        UsageStore.add(usage.Uid, usage.Sent, usage.Received, NetState.type)
    }

    private fun decide(p: Packet): Verdict {
        val uid = p.uid
        val daddr = p.daddr ?: return Verdict.ALLOWED
        // known spyware infrastructure is refused for every app, before any other rule
        Spyware.ipSet(daddr)?.let {
            Spyware.alertContact(this, daddr, it, AppNames.label(uid).first)
            return Verdict.BLOCKED_SPYWARE
        }
        // network-wide switches apply to every app, the system included
        val net = NetState.type
        if (net == NetType.WIFI && Prefs.killWifi.value) return Verdict.BLOCKED_FIREWALL
        if (net == NetType.CELLULAR &&
            (Prefs.killCell.value || !Prefs.genAllowed(NetState.cellGen))
        ) return Verdict.BLOCKED_FIREWALL
        val isApp = uid >= 0 && uid % 100_000 >= Process.FIRST_APPLICATION_UID
        var mask = 0
        if (isApp) {
            for (pkg in AppNames.packagesFor(uid)) mask = mask or Prefs.rule(pkg)
            if ((mask and Prefs.BLOCK_WIFI) != 0 && net == NetType.WIFI) return ruleBlocked(uid)
            if ((mask and Prefs.BLOCK_CELL) != 0 && net == NetType.CELLULAR) return ruleBlocked(uid)
            if ((mask and Prefs.BLOCK_LOCKED) != 0 && NetState.locked) return Verdict.BLOCKED_LOCKED
        }
        if (p.dport == 853 && Prefs.blockDoh.value) return Verdict.BLOCKED_BYPASS // DNS over TLS
        // encrypted DNS opened straight to a resolver's address (e.g. Chrome's automatic secure DNS)
        if (p.dport == 443 && Prefs.blockDoh.value && daddr in Blocklist.DOH_IPS) return Verdict.BLOCKED_BYPASS
        if (p.dport == 53) {
            return if (daddr in dnsServers) Verdict.ALLOWED else direct(Verdict.BLOCKED_BYPASS, uid)
        }
        if (p.data == "sni") return Verdict.ALLOWED // server name is checked in isDomainBlocked
        // our resolvers are trusted for plain DNS (handled above) and ping only, never for other ports
        if (daddr in dnsServers && p.protocol != 6 && p.protocol != 17) return Verdict.ALLOWED
        if (IpUtil.isLocal(daddr) || DnsMap.isKnown(daddr)) return Verdict.ALLOWED
        if (isApp && (mask and Prefs.ALLOW_DIRECT) != 0) return Verdict.ALLOWED
        return direct(Verdict.BLOCKED_DIRECT, uid)
    }

    private val ruleNoticeAt = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    /**
     * A user rule cut this app off. Say so (at most every 30 minutes per app), so a
     * forgotten switch does not look like a broken website.
     */
    private fun ruleBlocked(uid: Int): Verdict {
        val now = System.currentTimeMillis()
        val last = ruleNoticeAt[uid] ?: 0L
        if (now - last > 30 * 60_000) {
            ruleNoticeAt[uid] = now
            val (label, pkg) = AppNames.label(uid)
            Notifier.alert(
                this, 70 + (uid % 1000),
                tr("حُظر $label من الإنترنت", "$label is blocked from the internet"),
                tr(
                    "درع الجوال يمنع $label بسبب قاعدة حظر وضعتها. لإعادته: التطبيقات ← $label ← أيقونة الواي فاي أو البيانات، أو التحكم ← «قطع كامل».",
                    "MobileShield blocks $label because of a rule you set. To restore it: Apps → $label → the Wi-Fi or data icon, or Control → \"Full block\".",
                ),
            )
        }
        return Verdict.BLOCKED_FIREWALL
    }

    private fun direct(blocked: Verdict, uid: Int): Verdict = when (Prefs.directMode.value) {
        Prefs.DIRECT_OFF -> Verdict.ALLOWED
        Prefs.DIRECT_LEARN -> {
            LogStore.noteDirect(uid)
            Verdict.DIRECT_SEEN
        }
        else -> {
            LogStore.noteDirect(uid)
            noticeDirect(uid)
            blocked
        }
    }

    /** Says when "block direct IP" stops an app, so a site that will not load is explained. */
    private fun noticeDirect(uid: Int) {
        if (uid < 0 || uid % 100_000 < Process.FIRST_APPLICATION_UID) return
        val key = -uid // separate throttle from rule notices
        val now = System.currentTimeMillis()
        if (now - (ruleNoticeAt[key] ?: 0L) < 30 * 60_000) return
        ruleNoticeAt[key] = now
        val label = AppNames.label(uid).first
        Notifier.alert(
            this, 75 + (uid % 1000),
            tr("حُظر اتصال مباشر من $label", "A direct connection from $label was blocked"),
            tr(
                "وضع «الاتصال المباشر» على «حظر» منع $label من الاتصال بعنوان لم يُطلب عبر DNS. إن تعطّل شيء: استثنِ $label (أيقونة 🌐 في التطبيقات) أو اجعل الوضع «تعلّم» من الإعدادات.",
                "\"Direct connection\" mode is set to Block and stopped $label from reaching an address it never looked up. If something broke: exempt $label (🌐 icon in Apps) or set the mode to Learn in Settings.",
            ),
        )
    }

    private fun isKeyguardLocked(): Boolean =
        getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun startForegroundNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("حالة الحماية", "Protection status"), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ShieldVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(tr("درع الجوال يعمل", "MobileShield is running"))
            .setContentText(tr("كل اتصالات الجهاز تمر عبر جدار الحماية", "All device connections pass through the firewall"))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_shield), tr("إيقاف", "Stop"), stop
                ).build()
            )
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
