package com.ahmed.shield

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Arabic / English switch for the whole app. */
object Lang {
    val english = MutableStateFlow(false)

    fun init(ctx: Context) {
        english.value = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("english", false)
    }

    fun set(ctx: Context, on: Boolean) {
        english.value = on
        ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("english", on).apply()
        AppNames.clearCache() // cached labels such as "Android system"
    }
}

/** Picks the text for the current language. */
fun tr(ar: String, en: String): String = if (Lang.english.value) en else ar
