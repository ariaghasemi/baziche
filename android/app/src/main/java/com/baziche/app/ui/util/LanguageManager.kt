package com.baziche.app.ui.util

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app language (fa/en) WITHOUT appcompat.
 * API 33+: platform LocaleManager (persisted by the system).
 * API 26-32: persisted tag in SharedPreferences + configuration wrap + Activity.recreate().
 */
class LanguageManager(private val app: Context) {
    companion object {
        const val FA = "fa"
        const val EN = "en"
        private const val PREFS = "baziche_settings"
        private const val KEY = "locale"

        /** Wrap base context with the saved locale (call in Application.attachBaseContext). */
        fun wrap(base: Context): Context {
            val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, FA) ?: FA
            if (Build.VERSION.SDK_INT >= 33) return base // system persists it
            val locale = Locale.forLanguageTag(tag)
            val config = base.resources.configuration
            config.setLocale(locale)
            return base.createConfigurationContext(config)
        }
    }

    fun current(): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = app.getSystemService(LocaleManager::class.java)
            val tag = lm?.applicationLocales?.toLanguageTags()
            if (!tag.isNullOrEmpty()) return tag.split(",").first().split("-").first()
        }
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, FA) ?: FA
    }

    fun set(tag: String) {
        require(tag == FA || tag == EN) { "unsupported locale" }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            app.getSystemService(LocaleManager::class.java)?.applicationLocales = LocaleList.forLanguageTags(tag)
        }
    }
}

@Suppress("unused")
private class LocaleWrapper(base: Context) : ContextWrapper(base)
