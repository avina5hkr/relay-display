package com.avinash.relaydisplay.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.avinash.relaydisplay.R

/**
 * Notification channels and ids. One low-importance channel: this app should never buzz.
 *
 * The version check is written inline as `Build.VERSION.SDK_INT >= 26` rather than delegated to
 * [com.avinash.relaydisplay.platform.PermissionPolicy]. That is deliberate: an indirect check is
 * invisible to lint *and* to ART's class verifier, and on API 23 a method whose body references
 * `NotificationChannel` can fail verification the moment it is first resolved. Policy decisions
 * still live in one place; the platform call sites keep a check the toolchain can see.
 */
object RelayNotifications {

    const val CHANNEL_SESSION = "relay_session"
    const val NOTIFICATION_SESSION = 1001

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createSessionChannel(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createSessionChannel(context: Context) {
        val manager = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_SESSION) != null) return
        val channel = NotificationChannel(
            CHANNEL_SESSION,
            context.getString(R.string.notification_channel_session),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_session_desc)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * IMPORTANCE_LOW is an inlined constant that predates the channel API, so referencing it is
     * safe on any level; the annotation records that this was checked rather than overlooked.
     */
    @SuppressLint("InlinedApi")
    const val SESSION_IMPORTANCE: Int = NotificationManager.IMPORTANCE_LOW
}
