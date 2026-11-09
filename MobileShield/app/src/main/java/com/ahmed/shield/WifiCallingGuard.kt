package com.ahmed.shield

import android.content.Context
import android.provider.Settings

/**
 * Wi-Fi Calling reaches the carrier (e.g. epdg.mobily.com.sa) over IKE/IPsec. Through the
 * tunnel the IKE exchange never completes, and outside it "Block connections without VPN"
 * drops it. So, while the switch is on, the call services are left out of the tunnel and
 * put on Android's lockdown allow list. The allow list is read when the always-on VPN is
 * set up, which is at boot: the phone needs one restart after a change.
 */
object WifiCallingGuard {
    private const val KEY = "always_on_vpn_lockdown_whitelist"

    /** Updates the allow list; returns true when it changed (a restart is then needed). */
    fun apply(ctx: Context): Boolean {
        if (!PrivateDnsGuard.canWrite(ctx)) return false
        val cr = ctx.contentResolver
        // Apps targeting Android 12+ may not read hidden settings; writing still works.
        val now = try {
            Settings.Secure.getString(cr, KEY)
        } catch (_: SecurityException) {
            ctx.getSharedPreferences("wfc", Context.MODE_PRIVATE).getString("written", "")
        }
        val current = now.orEmpty().split(':').filter { it.isNotBlank() }
        val others = current.filter { it !in ShieldVpnService.TELEPHONY_PACKAGES }
        val installed = ShieldVpnService.TELEPHONY_PACKAGES.filter {
            try {
                ctx.packageManager.getPackageInfo(it, 0); true
            } catch (_: Exception) {
                false
            }
        }
        val wanted = if (Prefs.wifiCalling.value) others + installed else others
        if (wanted.toSet() == current.toSet()) return false
        return try {
            val value = wanted.joinToString(":")
            Settings.Secure.putString(cr, KEY, value).also {
                if (it) ctx.getSharedPreferences("wfc", Context.MODE_PRIVATE).edit().putString("written", value).apply()
            }
        } catch (_: Exception) {
            false
        }
    }
}
