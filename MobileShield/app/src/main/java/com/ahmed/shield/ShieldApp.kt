package com.ahmed.shield

import android.app.Application

class ShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lang.init(this)
        Prefs.init(this)
        Privacy.init(this)
        AppNames.init(this)
        Blocklist.init(this)
        LogDisk.init(this)
        Spyware.init(this)
        NotificationLog.init(this)
        LocationLock.init(this)
    }
}
