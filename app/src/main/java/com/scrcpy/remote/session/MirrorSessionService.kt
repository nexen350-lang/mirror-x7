package com.scrcpy.remote.session

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.scrcpy.remote.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Keeps a mirroring session running while the app is not on screen.
 *
 * Android freezes and then kills background processes, which would drop the ADB connection and
 * silence the remote audio within seconds. The service exists purely to hold the process up and
 * to give the user a visible, dismissible handle on the still-running session; it deliberately
 * owns no session state, so it can never disagree with [MirrorViewModel] about what is connected.
 */
class MirrorSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            _stopRequests.value++
            synchronized(startLock) {
                stopPending = true
                if (pendingForegroundStarts == 0) stopSelf(startId)
            }
            return START_NOT_STICKY
        }
        val device = intent?.getStringExtra(EXTRA_DEVICE)
        val hold = runCatching {
            SessionHold.valueOf(intent?.getStringExtra(EXTRA_HOLD) ?: SessionHold.CONNECTED.name)
        }.getOrDefault(SessionHold.CONNECTED)
        startForegroundCompat(notification(device, hold), hold)
        synchronized(startLock) {
            if (pendingForegroundStarts > 0) pendingForegroundStarts--
            if (stopPending && pendingForegroundStarts == 0) stopSelf(startId)
        }
        // The session lives in the Activity's ViewModel, so a restart with no session would only
        // show a notification for something that is not running.
        return START_NOT_STICKY
    }

    /**
     * Declares the type that matches why the process is being held up. The mapping lives on
     * [SessionHold] so it can be verified without an Android runtime.
     */
    private fun startForegroundCompat(notification: Notification, hold: SessionHold) {
        // A refused type throws from startForeground, which would take the whole app down at the
        // moment the user connects. Stopping quietly loses the background hold; crashing loses
        // the session and everything else with it.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, hold.foregroundServiceType())
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not hold the process up for $hold", e)
            _startFailures.value++
            stopSelf()
        }
    }

    /**
     * Android's cap on how long a background hold may last.
     *
     * A `dataSync` service may run for six hours in any twenty-four, after which the system calls
     * this and gives the service a few seconds to stop. Not stopping is fatal: the platform raises
     * `RemoteServiceException: a foreground service of type dataSync did not stop within its
     * timeout`, which takes the whole app down and with it the session this was protecting.
     *
     * Stopping is reported the same way a refused start is, because the consequence for the user
     * is the same: the session keeps working while the app is on screen, but is no longer
     * guaranteed to survive being hidden.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service type $fgsType reached its time limit")
        _startFailures.value++
        stopSelf()
    }

    private fun notification(device: String?, hold: SessionHold): Notification {
        createChannel()
        // Brings the app's existing task back rather than clearing to its first screen. Clearing
        // finished the mirror screen sitting above it, so tapping a notification that says a
        // session is running was a way to end that very session.
        val open = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: Intent(),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MirrorSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_session)
            .setContentTitle(getString(
                if (hold.transfer) R.string.transfer_notification_title
                else R.string.background_notification_title))
            .setContentText(
                if (device.isNullOrBlank()) getString(hold.notificationText())
                else getString(hold.namedNotificationText(), device),
            )
            .setContentIntent(open)
            .addAction(0, getString(R.string.background_notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        // minSdk is 26, so notification channels always exist here.
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.background_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = getString(R.string.background_channel_description)
                    setShowBadge(false)
                },
        )
    }

    companion object {
        private const val CHANNEL_ID = "mirror-session"
        private const val TAG = "MirrorSessionService"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.scrcpy.remote.STOP_BACKGROUND_SESSION"
        private const val EXTRA_DEVICE = "device"
        private const val EXTRA_HOLD = "hold"

        private val _stopRequests = MutableStateFlow(0L)
        private val _startFailures = MutableStateFlow(0L)
        private val startLock = Any()
        private var pendingForegroundStarts = 0
        private var stopPending = false

        /** Increments when the process could not be held up, so the caller can say so once. */
        val startFailures: StateFlow<Long> = _startFailures.asStateFlow()

        /**
         * Increments when the notification's Disconnect action is used. The session owner watches
         * this rather than the service watching the session, so the service stays stateless.
         */
        val stopRequests: StateFlow<Long> = _stopRequests.asStateFlow()

        /**
         * Starting a foreground service is rejected once the app is already in the cached state,
         * so callers must invoke this while the Activity is still stopping rather than after.
         *
         * A rejection counts as a failure to hold the process up, exactly like a `startForeground`
         * that throws once the service is running. Returning only `false` meant the one caller had
         * to remember to report it, and it reported only the audio case — so a session that took a
         * while to connect while the user was elsewhere ended up with no foreground protection and
         * nothing said about it.
         */
        fun start(context: Context, device: String?, hold: SessionHold): Boolean = synchronized(startLock) {
            val previousStop = stopPending
            stopPending = false
            pendingForegroundStarts++
            runCatching {
                checkNotNull(context.startForegroundService(
                    Intent(context, MirrorSessionService::class.java)
                        .putExtra(EXTRA_DEVICE, device)
                        .putExtra(EXTRA_HOLD, hold.name),
                )) { "Android did not accept the foreground service request" }
            }.onFailure {
                pendingForegroundStarts--
                stopPending = previousStop
                Log.w(TAG, "Could not ask for a foreground hold for $hold", it)
                _startFailures.value++
            }.isSuccess
        }

        fun stop(context: Context) {
            synchronized(startLock) {
                stopPending = true
                // Stopping before the first startForeground() acknowledgement can crash the
                // entire app, even before Android's startup deadline. Let that callback drain.
                if (pendingForegroundStarts == 0) {
                    context.stopService(Intent(context, MirrorSessionService::class.java))
                }
            }
        }
    }
}
