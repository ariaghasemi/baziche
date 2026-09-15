package com.baziche.app

import android.app.Application
import android.content.Context
import com.baziche.app.di.AppContainer
import com.baziche.app.ui.util.LanguageManager

class BazicheApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, BuildConfig.API_BASE_URL, BuildConfig.DEBUG)
    }
}
