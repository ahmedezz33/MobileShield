package com.ahmed.shield

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Lets a computer save the logs over USB:
 *   adb shell am broadcast -n com.ahmed.shield/.ExportReceiver
 * Guarded by android.permission.DUMP in the manifest, which only the adb shell and
 * the system hold, so no app on the phone can trigger it.
 */
class ExportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "export") {
            try {
                val (_, path) = LogExport.export(context.applicationContext)
                pending.resultCode = 1
                pending.resultData = path
            } catch (e: Exception) {
                pending.resultCode = 0
                pending.resultData = e.toString()
            } finally {
                pending.finish()
            }
        }
    }
}
