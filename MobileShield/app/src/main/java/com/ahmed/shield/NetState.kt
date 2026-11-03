package com.ahmed.shield

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import java.net.Inet6Address
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/** Tracks the real (non-VPN) network in use and whether the screen is locked. */
object NetState {
    @Volatile
    var type = NetType.NONE
        private set

    @Volatile
    var locked = false

    private var cm: ConnectivityManager? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = refresh()
    }

    fun start(ctx: Context) {
        val m = ctx.getSystemService(ConnectivityManager::class.java)
        cm = m
        // the default request excludes VPN networks, so only real networks are tracked
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        m.registerNetworkCallback(req, callback)
        refresh()
        startTelephony(ctx)
    }

    /** Mobile generation in use: 2, 3, 4 or 5 (0 when unknown). */
    @Volatile
    var cellGen = 0
        private set

    private var telephony: TelephonyManager? = null
    private var telephonyCallback: TelephonyCallback? = null

    private fun startTelephony(ctx: Context) {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                val g = generation(info.networkType, info.overrideNetworkType)
                if (g != cellGen) {
                    cellGen = g
                    listener?.invoke()
                }
            }
        }
        try {
            tm.registerTelephonyCallback(ctx.mainExecutor, cb)
            telephony = tm
            telephonyCallback = cb
        } catch (_: SecurityException) {
        }
    }

    fun generation(type: Int, override: Int): Int = when {
        type == TelephonyManager.NETWORK_TYPE_NR ||
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
            override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> 5
        type == TelephonyManager.NETWORK_TYPE_LTE || type == TelephonyManager.NETWORK_TYPE_IWLAN -> 4
        type == TelephonyManager.NETWORK_TYPE_UMTS || type == TelephonyManager.NETWORK_TYPE_HSDPA ||
            type == TelephonyManager.NETWORK_TYPE_HSUPA || type == TelephonyManager.NETWORK_TYPE_HSPA ||
            type == TelephonyManager.NETWORK_TYPE_HSPAP || type == TelephonyManager.NETWORK_TYPE_TD_SCDMA ||
            type == TelephonyManager.NETWORK_TYPE_EVDO_0 || type == TelephonyManager.NETWORK_TYPE_EVDO_A ||
            type == TelephonyManager.NETWORK_TYPE_EVDO_B || type == TelephonyManager.NETWORK_TYPE_EHRPD -> 3
        type == TelephonyManager.NETWORK_TYPE_GPRS || type == TelephonyManager.NETWORK_TYPE_EDGE ||
            type == TelephonyManager.NETWORK_TYPE_GSM || type == TelephonyManager.NETWORK_TYPE_CDMA ||
            type == TelephonyManager.NETWORK_TYPE_1xRTT || type == TelephonyManager.NETWORK_TYPE_IDEN -> 2
        else -> 0
    }

    fun stop() {
        try {
            cm?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        cm = null
        telephonyCallback?.let { cb -> telephony?.unregisterTelephonyCallback(cb) }
        telephonyCallback = null
        telephony = null
    }

    /** True when a real network has public IPv6 connectivity. */
    @Volatile
    var ipv6 = false
        private set

    /** Called when the network type or its IPv6 availability changes. */
    @Volatile
    var listener: (() -> Unit)? = null

    fun refresh() {
        val m = cm ?: return
        val oldType = type
        val oldV6 = ipv6
        type = current(m)
        ipv6 = hasPublicIpv6(m)
        if (oldType != type || oldV6 != ipv6) listener?.invoke()
    }

    /**
     * Only the network actually carrying traffic counts: mobile data often stays up
     * in the background with IPv6 while Wi-Fi (IPv4 only) is in use.
     */
    fun hasPublicIpv6(m: ConnectivityManager): Boolean {
        val type = current(m)
        @Suppress("DEPRECATION")
        for (n in m.allNetworks) {
            val c = m.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val matches = when (type) {
                NetType.WIFI -> c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                NetType.CELLULAR -> c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                else -> !c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
            if (!matches) continue
            val lp = m.getLinkProperties(n) ?: continue
            val route = lp.routes.any { it.isDefaultRoute && it.destination.address is Inet6Address }
            val addr = lp.linkAddresses.any {
                val a = it.address
                a is Inet6Address && !a.isLinkLocalAddress && !a.isSiteLocalAddress &&
                    (a.address[0].toInt() and 0xfe) != 0xfc
            }
            if (route && addr) return true
        }
        return false
    }

    fun current(m: ConnectivityManager): NetType {
        var wifi = 0
        var cell = 0
        var other = 0
        @Suppress("DEPRECATION")
        for (n in m.allNetworks) {
            val c = m.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val score = if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 1
            when {
                c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifi = maxOf(wifi, score)
                c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cell = maxOf(cell, score)
                else -> other = maxOf(other, score)
            }
        }
        // Android prefers validated Wi-Fi over cellular
        return when {
            wifi == 2 -> NetType.WIFI
            cell > 0 -> NetType.CELLULAR
            wifi > 0 -> NetType.WIFI
            other > 0 -> NetType.OTHER
            else -> NetType.NONE
        }
    }
}
