package com.rickle8.mvswitch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.rickle8.mvswitch.R
import com.rickle8.mvswitch.core.Heard
import com.rickle8.mvswitch.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps listening and switching with the screen off. */
class SwitcherService : Service() {
    companion object {
        private const val CHANNEL = "switcher"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "stop"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SwitcherService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, SwitcherService::class.java).setAction(ACTION_STOP))
        }

        fun restart(context: Context) {
            Engine.restarting = true
            Engine.stop()
            // Give the loop a moment to release the mic, then start fresh.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                start(context) // re-runs startForeground so the mic setting takes effect
                Engine.restarting = false
            }, 1500)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Audio switching", NotificationManager.IMPORTANCE_LOW),
        )
        scope.launch {
            var wasRunning = false
            Engine.state
                .map { listOf(it.running, it.paused, it.currentBox, it.heard, it.error, it.lockedBox, it.micOn) }
                .distinctUntilChanged()
                .collect {
                    val running = Engine.state.value.running
                    if (running) {
                        nm.notify(NOTIF_ID, buildNotification())
                    } else if (wasRunning && !Engine.restarting) {
                        // Engine ended on its own (error or stop): close the service too.
                        releaseLocks()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    wasRunning = running
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Engine.stop()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> { Engine.setPaused(true); return START_NOT_STICKY }
            ACTION_RESUME -> { Engine.setPaused(false); return START_NOT_STICKY }
        }
        val useMic = Engine.micAllowed()
        val type = if (useMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotification(), type)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        acquireLocks()
        Engine.start()
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val st = Engine.state.value
        val text = when {
            st.error != null -> st.error
            st.paused -> "Paused"
            st.lockedBox != null -> "Locked on box ${st.lockedBox}"
            st.currentBox == null -> "Starting…"
            else -> {
                val heard = when {
                    !st.micOn -> "using live data"
                    st.heard == Heard.AD -> "hearing a commercial"
                    st.heard == Heard.GAME -> "hearing the game"
                    else -> "listening"
                }
                "Audio on box ${st.currentBox}, $heard"
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String, req: Int) = PendingIntent.getService(
            this, req, Intent(this, SwitcherService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Multiview Audio")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (st.paused) "Resume" else "Pause",
                action(if (st.paused) ACTION_RESUME else ACTION_PAUSE, 1))
            .addAction(0, "Stop", action(ACTION_STOP, 2))
            .build()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mvswitch:run")
                .apply { acquire(6 * 60 * 60 * 1000L) } // max 6 hours
        }
        if (wifiLock == null) {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "mvswitch:wifi")
                .apply { acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }
}
