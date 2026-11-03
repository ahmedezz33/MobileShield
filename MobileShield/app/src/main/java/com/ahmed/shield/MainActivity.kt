package com.ahmed.shield

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.content.ContextCompat
import com.ahmed.shield.ui.MainScreen
import com.ahmed.shield.ui.ShieldTheme

class MainActivity : ComponentActivity() {

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startShield()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            ShieldTheme {
                val english by Lang.english.collectAsState()
                // rebuild every screen in the new language and direction
                key(english) {
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (english) LayoutDirection.Ltr else LayoutDirection.Rtl
                    ) {
                        MainScreen(onToggle = ::toggle)
                    }
                }
            }
        }
    }

    private fun toggle(on: Boolean) {
        if (on) {
            val consent = VpnService.prepare(this)
            if (consent != null) vpnConsent.launch(consent) else startShield()
        } else {
            startService(Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP))
        }
    }

    private fun startShield() {
        ContextCompat.startForegroundService(
            this, Intent(this, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_START)
        )
    }
}
