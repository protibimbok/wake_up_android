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
import kotlin.math.sqrt

class ShakeService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private lateinit var powerManager: PowerManager
    private var screenStateReceiver: BroadcastReceiver? = null

    // wake up call
    private var isWakingUp = false

    // shake detection parameters
    private var lastTimestamp: Long = 0L
    private var lastShakeTime: Long = 0L
    private var lastPeakTime: Long = 0L
    private var lastAccel: Float = 0f
    private var peakCount: Int = 0

    // Constants for shake detection
    private val SHAKE_THRESHOLD = 10f // m/s²
    private val MIN_TIME_BETWEEN_SHAKES = 150L // milliseconds
    private val MAX_TIME_BETWEEN_PEAKS = 500L // milliseconds
    private val REQUIRED_PEAKS = 4 // number of direction changes needed
    


    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // pick a wake‑up accelerometer if available
        // val allAccels = sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER)
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        if (accelSensor == null) {
            Log.w("ShakeService", "No accelerometer available")
            stopSelf()
            return
        }

        // Register screen state receiver
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d("ShakeService", "Screen off, starting sensor listener")
                        sensorManager.registerListener(
                            this@ShakeService,
                            accelSensor,
                            SensorManager.SENSOR_DELAY_GAME
                        )
                        isWakingUp = false
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d("ShakeService", "Screen on, stopping sensor listener")
                        sensorManager.unregisterListener(this@ShakeService)
                        isWakingUp = false
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenStateReceiver, filter)

        // run in foreground so service stays alive with screen off
        startForeground(1, buildNotification())

        // Start listening if screen is already off
        if (!powerManager.isInteractive) {
            sensorManager.registerListener(
                this,
                accelSensor,
                SensorManager.SENSOR_DELAY_GAME
            )
        }

    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        screenStateReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null



    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION || isWakingUp) return

        val now = event.timestamp
        val nowMillis = System.currentTimeMillis()
        
        if (lastTimestamp == 0L) {
            lastTimestamp = now
            return
        }

        // Calculate total acceleration
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val accel = sqrt(x * x + y * y + z * z)

        // Detect peak (direction change)
        if (accel > SHAKE_THRESHOLD) {
            val timeSinceLastPeak = nowMillis - lastPeakTime
            
            // Check if this is a new peak within time window
            if (timeSinceLastPeak > MIN_TIME_BETWEEN_SHAKES) {
                if (timeSinceLastPeak < MAX_TIME_BETWEEN_PEAKS) {
                    peakCount++
                    Log.d("ShakeService", "Peak detected: $peakCount")
                    
                    // Check if we've detected enough peaks for a shake
                    if (peakCount >= REQUIRED_PEAKS) {
                        if (!isWakingUp) {
                            isWakingUp = true
                            wakeUpDevice()
                        }
                    }
                } else {
                    // Too much time passed, reset counter
                    peakCount = 1
                }
                lastPeakTime = nowMillis
            }
        }

        lastAccel = accel
        lastTimestamp = now
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
