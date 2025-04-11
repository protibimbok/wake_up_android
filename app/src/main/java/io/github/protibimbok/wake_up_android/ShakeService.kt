package io.github.protibimbok.wake_up_android

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

class ShakeService : Service() {
    private lateinit var powerManager: PowerManager
    private var screenStateReceiver: BroadcastReceiver? = null
    private lateinit var sensorDetector: SensorDetector
    private lateinit var soundDetector: SoundDetector
    private var wakeLock: PowerManager.WakeLock? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d("ShakeService", "Service onCreate")
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // Initialize detectors
        sensorDetector = SensorDetector(this) { wakeUpDevice() }
        soundDetector = SoundDetector(this) { wakeUpDevice() }

        // Initialize sensors
        sensorDetector.initializeSensors()

        // listen for screen on/off to start/stop sensing
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d("ShakeService", "Screen off → start sensing")
                        var needsWakeLock = sensorDetector.start()
                        if (Settings.isSoundEnabled(this@ShakeService)) {
                            needsWakeLock = true
                            soundDetector.start()
                        }
                        if (needsWakeLock) {
                            Log.d("ShakeService", "Wake Lock acquired")
                            acquireWakeLock()
                        }
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d("ShakeService", "Screen on → stop sensing")
                        sensorDetector.stop()
                        soundDetector.stop()
                        releaseWakeLock()
                    }
                }
            }
        }
        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        )

        // foreground so service isn't killed
        startForeground(1, buildNotification())

        // if screen already off, kick off sensing
        if (!powerManager.isInteractive) {
            acquireWakeLock()
            sensorDetector.start()
            if (Settings.isSoundEnabled(this)) {
                soundDetector.start()
            }
        }
    }

    private fun acquireWakeLock() {
        wakeLock?.release()
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:WakeLock"
        ).apply {
            acquire(12*60*60*1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
            wakeLock = null
        }
    }

    override fun onDestroy() {
        Log.d("ShakeService", "Service onDestroy")
        sensorDetector.stop()
        soundDetector.stop()
        screenStateReceiver?.let { unregisterReceiver(it) }
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle sensor configuration updates
        intent?.getParcelableArrayListExtra<SensorConfig>("sensors")?.let { configs ->
            sensorDetector.initializeSensors()
        }
        
        // Check if sound detection should be running
        if (!powerManager.isInteractive) {
            if (Settings.isSoundEnabled(this)) {
                soundDetector.start()
            } else {
                soundDetector.stop()
            }
        }
        
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restart = Intent(applicationContext, ShakeService::class.java).apply {
            setPackage(packageName)
        }
        val pi = PendingIntent.getService(
            this, 1, restart,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            pi
        )
        super.onTaskRemoved(rootIntent)
    }

    private fun wakeUpDevice() {
        if (!powerManager.isInteractive) {
            val wl = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$packageName:WakeUpLock"
            )
            wl.acquire(3000L)
            wl.release()
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
    }

    private fun buildNotification(): Notification {
        val channelId = "shake_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Shake to Wake", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Motion Detection Service")
            .setContentText("Monitoring for wake gestures")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
