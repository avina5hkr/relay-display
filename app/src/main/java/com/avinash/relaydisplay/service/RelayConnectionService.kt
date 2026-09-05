package com.avinash.relaydisplay.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.avinash.relaydisplay.MainActivity
import com.avinash.relaydisplay.R
import com.avinash.relaydisplay.app.RelayApp
import com.avinash.relaydisplay.domain.model.OperatingMode
import com.avinash.relaydisplay.network.session.ConnectionState
import com.avinash.relaydisplay.network.session.SessionCoordinator
import com.avinash.relaydisplay.ui.common.ConnectionStatusText
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a local-network session alive while the app is not in the foreground.
 *
 * Design rules this service follows:
 *  - It owns no connection state. [SessionCoordinator] does. The service only mirrors state into
 *    a notification and forwards user taps back as ordinary requests.
 *  - `startForeground` happens in the first moments of `onStartCommand`, on every path, so the
 *    platform deadline cannot be missed.
 *  - It returns START_NOT_STICKY. A restarted process has no socket, no cipher state and no
 *    peer; claiming otherwise by returning START_STICKY would put a "Connected" notification on
 *    screen with nothing behind it.
 *  - Every stop path is idempotent.
 */
class RelayConnectionService : LifecycleService() {

    private val coordinator: SessionCoordinator
        get() = (application as RelayApp).container.sessionCoordinator

    private var started = false

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        // Nothing binds to this service: UI observes the coordinator's flows instead, which is
        // what keeps service state and UI state from drifting apart.
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // Promote first, decide second. Any early return below still leaves a valid foreground
        // state, and stopSelf() tears it down again.
        promoteToForeground(ConnectionState.Preparing)

        when (intent?.action) {
            ACTION_START -> {
                started = true
                observeState()
                coordinator.requestSession()
            }

            ACTION_DISCONNECT -> {
                coordinator.releaseSession()
                stopEverything()
            }

            ACTION_PAUSE -> {
                lifecycleScope.launch {
                    val container = (application as RelayApp).container
                    // Persist first: a pause the user asked for must survive process death even
                    // if the teardown below is interrupted.
                    container.settingsRepository.setOperatingMode(OperatingMode.PAUSED)
                    coordinator.onOperatingModeChanged(OperatingMode.PAUSED)
                    stopEverything()
                }
            }

            else -> {
                // An unknown or null action (a restart we did not ask for) is not a licence to
                // start networking. Shut down cleanly instead.
                stopEverything()
            }
        }
        return START_NOT_STICKY
    }

    private fun observeState() {
        lifecycleScope.launch {
            coordinator.state.collectLatest { state ->
                if (!started) return@collectLatest
                when (state) {
                    ConnectionState.Paused, ConnectionState.Idle -> stopEverything()
                    else -> notify(buildNotification(state))
                }
            }
        }
    }

    private fun promoteToForeground(state: ConnectionState) {
        val notification = buildNotification(state)
        val type = if (Build.VERSION.SDK_INT >= 29) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, RelayNotifications.NOTIFICATION_SESSION, notification, type)
    }

    private fun notify(notification: Notification) {
        val manager = androidx.core.app.NotificationManagerCompat.from(this)
        // Posting can be refused when POST_NOTIFICATIONS was denied. The session keeps running;
        // the user simply does not get the ongoing row. Never treat this as a session failure.
        try {
            manager.notify(RelayNotifications.NOTIFICATION_SESSION, notification)
        } catch (e: SecurityException) {
            // Denied notification permission; nothing to do and nothing to report as an error.
        }
    }

    private fun buildNotification(state: ConnectionState): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            pendingIntentFlags(),
        )
        return NotificationCompat.Builder(this, RelayNotifications.CHANNEL_SESSION)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(ConnectionStatusText.headline(state))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Nothing about the peer or the content appears on the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(0, getString(R.string.notification_action_disconnect), servicePendingIntent(ACTION_DISCONNECT, 1))
            .addAction(0, getString(R.string.notification_action_pause), servicePendingIntent(ACTION_PAUSE, 2))
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, RelayConnectionService::class.java).setAction(action),
            pendingIntentFlags(),
        )

    /**
     * Immutable, always. These intents carry no extras a caller could fill in, and a mutable
     * PendingIntent handed out through a notification is a well-known way to let another app
     * act as this one.
     */
    private fun pendingIntentFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun stopEverything() {
        started = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        started = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.avinash.relaydisplay.action.START"
        const val ACTION_DISCONNECT = "com.avinash.relaydisplay.action.DISCONNECT"
        const val ACTION_PAUSE = "com.avinash.relaydisplay.action.PAUSE"

        fun start(context: Context) {
            val intent = Intent(context, RelayConnectionService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RelayConnectionService::class.java))
        }
    }
}
