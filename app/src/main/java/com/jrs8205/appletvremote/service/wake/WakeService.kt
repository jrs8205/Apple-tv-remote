package com.jrs8205.appletvremote.service.wake

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.jrs8205.appletvremote.MainActivity
import com.jrs8205.appletvremote.R
import com.jrs8205.appletvremote.appContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the app running while a wake-up is in progress. Android cuts the network of an app a few
 * seconds after it leaves the screen (magic packets then fail with EPERM) and lets the CPU sleep once
 * the phone is locked, and a swipe from the recent apps ends the process. A wake-up that waits a minute
 * or more for the LG TV would die in any of these; as a foreground service holding a wake lock it runs
 * to the end. Started at a power tap that may wake the TV, it stops itself once no wake-up is running
 * or pending.
 */
class WakeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = appContainer
        // Into the foreground first, even when the wake-up is already over: startForegroundService demands it.
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            container.connectionLog.log { "wake service could not enter the foreground: $e" }
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_UP_MS)
            }
        }
        if (watch == null) {
            container.connectionLog.log { "wake service running in the foreground" }
            watch = scope.launch {
                container.remoteController.state.first { !it.wakeUpPending }
                container.connectionLog.log { "wake service stopping: wake-up over" }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(): Notification {
        NotificationManagerCompat.from(this).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW).setName(getString(R.string.wake_channel)).build(),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_remote)
            .setContentTitle(getString(R.string.state_waking_tv))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "wake"
        const val NOTIFICATION_ID = 2
        const val WAKE_LOCK_TAG = "appletvremote:wake"
        /** Longer than the LG phase and the Apple TV's attempts together; the lock lapses on its own if something hangs. */
        const val MAX_WAKE_UP_MS = 5 * 60_000L
    }
}
