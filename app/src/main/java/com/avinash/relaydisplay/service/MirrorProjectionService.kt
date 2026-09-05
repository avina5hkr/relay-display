package com.avinash.relaydisplay.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.avinash.relaydisplay.MainActivity
import com.avinash.relaydisplay.R
import com.avinash.relaydisplay.app.RelayApp
import com.avinash.relaydisplay.mirroring.MirrorProfile
import kotlinx.coroutines.launch

/**
 * Holds the screen capture for as long as it is running, and no longer.
 *
 * Android requires a foreground service of type `mediaProjection` to be running *before*
 * `getMediaProjection` is called, so this service starts foreground first and only then asks for
 * the projection. Consent is never cached: the caller passes a result that Android has just
 * granted, and a new session means a new prompt.
 *
 * Every exit path -- user stop, projection revoked, encoder failure, disconnect, service death --
 * runs through [stopMirroring], so the capture indicator never outlives the capture.
 */
class MirrorProjectionService : LifecycleService() {

    private val container get() = (application as RelayApp).container

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Promote before anything else on every path, including the stop path, so the platform
        // deadline is met even when this instance is about to shut down.
        promoteToForeground()

        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, android.app.Activity.RESULT_CANCELED)
                val resultData = intent.parcelableResultData()
                if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
                    container.diagnostics.warn("mirror", "no screen capture consent")
                    stopMirroring()
                } else {
                    beginMirroring(resultCode, resultData)
                }
            }

            else -> stopMirroring()
        }
        // A restarted process holds no consent token, so restarting the service would be a lie.
        return START_NOT_STICKY
    }

    private fun beginMirroring(resultCode: Int, resultData: Intent) {
        val manager = ContextCompat.getSystemService(this, MediaProjectionManager::class.java)
        if (manager == null) {
            container.diagnostics.error("mirror", "no MediaProjectionManager")
            stopMirroring()
            return
        }

        val projection: MediaProjection? = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (e: IllegalStateException) {
            // Thrown when the foreground service requirement was not satisfied in time.
            container.diagnostics.error("mirror", "projection refused by the platform")
            null
        } catch (e: SecurityException) {
            container.diagnostics.error("mirror", "projection consent rejected")
            null
        }

        if (projection == null) {
            stopMirroring()
            return
        }

        val metrics = resources.displayMetrics
        lifecycleScope.launch {
            val started = container.mirrorController.start(
                projection = projection,
                profile = MirrorProfile.default(metrics.widthPixels, metrics.heightPixels),
                densityDpi = metrics.densityDpiOrDefault(),
                onStopped = { stopMirroring() },
            )
            if (!started) stopMirroring()
        }
    }

    private fun promoteToForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MirrorProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, RelayNotifications.CHANNEL_SESSION)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_mirroring))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(0, getString(R.string.notification_action_stop_mirroring), stop)
            .build()
    }

    private fun stopMirroring() {
        lifecycleScope.launch {
            container.mirrorController.stop("service stopping")
            ServiceCompat.stopForeground(this@MirrorProjectionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Belt and braces: if the process is being torn down, the capture goes with it.
        container.mirrorController.stopBlocking("service destroyed")
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelableResultData(): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_RESULT_DATA)
        }

    companion object {
        const val ACTION_START = "com.avinash.relaydisplay.action.MIRROR_START"
        const val ACTION_STOP = "com.avinash.relaydisplay.action.MIRROR_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val NOTIFICATION_ID = 1002

        /**
         * Starts capture with a consent result Android has just produced.
         *
         * The result is passed straight through and used once; nothing about it is stored.
         */
        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, MirrorProjectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MirrorProjectionService::class.java))
        }
    }
}

private fun DisplayMetrics.densityDpiOrDefault(): Int =
    if (densityDpi > 0) densityDpi else DisplayMetrics.DENSITY_DEFAULT
