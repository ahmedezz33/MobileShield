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
                if (intent.getStringExtra("do") == "location") {
                    // adb shell am broadcast -n com.ahmed.shield/.ExportReceiver --es do location --es lock on|off
                    val on = intent.getStringExtra("lock") != "off"
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        LocationLock.setEnabled(context.applicationContext, on)
                    }
                    pending.resultCode = 1
                    pending.resultData = "location lock ${if (on) "on" else "off"}"
                    return@thread
                }
                if (intent.getStringExtra("do") == "scan") {
                    // adb shell am broadcast -n com.ahmed.shield/.ExportReceiver --es do scan
                    // A scan takes minutes, longer than a broadcast may run: start it detached
                    // and report the result in logcat under the tag "ShieldScan".
                    val app = context.applicationContext
                    thread(name = "spyware-scan") {
                        val text = try {
                            val r = kotlinx.coroutines.runBlocking {
                                if (Spyware.count.value == 0) Spyware.update()
                                Spyware.scan(app)
                            }
                            "indicators=${r.indicators} apps=${r.apps} apk_hashed=${r.apkHashed} " +
                                "addresses=${r.domainsChecked} findings=${r.findings.size} " +
                                r.findings.joinToString("; ") { "${it.type}:${it.value}[${it.set}]" }
                        } catch (e: Exception) {
                            "failed: $e"
                        }
                        android.util.Log.i("ShieldScan", "RESULT $text")
                    }
                    pending.resultCode = 1
                    pending.resultData = "scan started"
                    return@thread
                }
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
