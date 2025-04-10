package io.github.protibimbok.wake_up_android

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat

class ShakeService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null

    // shake detection parameters
    private var lastShakeTime = 0L
    private val shakeThreshold = 12f  // m/s²
    private val shakeIntervalMs = 1000L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        // pick a wake‑up accelerometer if available
        val allAccels = sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER)
        accelSensor = allAccels.firstOrNull { it.isWakeUpSensor }
            ?: allAccels.firstOrNull()

        if (accelSensor == null) {
            Log.w("ShakeService", "No accelerometer available")
            stopSelf()
            return
        }

        // run in foreground so service stays alive with screen off
        startForeground(1, buildNotification())
        sensorManager.registerListener(
            this,
            accelSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val g = Math.sqrt((x*x + y*y + z*z).toDouble()).toFloat()
        val now = System.currentTimeMillis()

        if (g > shakeThreshold && now - lastShakeTime > shakeIntervalMs) {
            lastShakeTime = now
            Log.d("ShakeService", "Shake detected, waking device")
            wakeUpDevice()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // no‑op
    }

    private fun wakeUpDevice() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$packageName:ShakeWakeLock"
            )
            wakeLock.acquire(3000L)
            wakeLock.release()
        }

        // launch your activity so it appears over the lock screen
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun buildNotification(): Notification {
        val channelId = "shake_service"
        val channelName = "Shake to Wake"

        // On Oreo+ we must register the channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val chan = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(chan)
        }

        // Use NotificationCompat so it works on API 24–25 as well
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Shake to Wake")
            .setContentText("Listening for shake gestures")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // if the service is killed it will be recreated
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // schedule a restart in 1 second
        val restartIntent = Intent(applicationContext, ShakeService::class.java).apply {
            setPackage(packageName)
        }
        val pi = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            pi
        )
        super.onTaskRemoved(rootIntent)
    }

}
