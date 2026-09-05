package com.avinash.relaydisplay.app

import android.app.Application
import android.os.Build
import android.os.StrictMode
import com.avinash.relaydisplay.BuildConfig
import com.avinash.relaydisplay.service.RelayNotifications

/**
 * Application entry point. Builds the dependency container and, in debug builds, turns on
 * StrictMode so an accidental disk or network touch on the main thread is loud rather than an
 * intermittent ANR on the slower phone.
 */
class RelayApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) enableStrictMode()
        container = AppContainer(this)
        RelayNotifications.ensureChannels(this)
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .apply { if (Build.VERSION.SDK_INT >= 26) detectContentUriWithoutPermission() }
                .penaltyLog()
                .build(),
        )
    }
}
