package com.ahmed.shield

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap

/** Resolves a Linux uid to its packages and a readable app name. */
object AppNames {
    private lateinit var pm: PackageManager
    private val pkgCache = ConcurrentHashMap<Int, Array<String>>()
    private val labelCache = ConcurrentHashMap<Int, Pair<String, String?>>()

    fun init(ctx: Context) {
        pm = ctx.packageManager
    }

    fun packagesFor(uid: Int): Array<String> =
        pkgCache.getOrPut(uid) { pm.getPackagesForUid(uid) ?: emptyArray() }

    fun clearCache() = labelCache.clear()

    fun labelOf(pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    fun label(uid: Int): Pair<String, String?> = labelCache.getOrPut(uid) {
        when (uid) {
            -1 -> tr("طلب DNS من النظام", "System DNS request") to null
            0 -> tr("النظام (root)", "System (root)") to null
            1000 -> tr("نظام أندرويد", "Android system") to null
            1051 -> tr("خدمة DNS في النظام", "System DNS service") to null
            else -> {
                val pkg = packagesFor(uid).firstOrNull()
                if (pkg == null) {
                    (pm.getNameForUid(uid) ?: "uid $uid") to null
                } else {
                    try {
                        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() to pkg
                    } catch (e: PackageManager.NameNotFoundException) {
                        pkg to pkg
                    }
                }
            }
        }
    }
}
