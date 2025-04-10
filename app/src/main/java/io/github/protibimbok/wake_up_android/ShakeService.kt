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
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.sqrt

class ShakeService : Service(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var motionSensor: Sensor? = null
    private lateinit var powerManager: PowerManager
    private var screenStateReceiver: BroadcastReceiver? = null
    private var isListenerRegistered = false
    private var useSigMotion = false

    // wake‑up flag
    private var isWakingUp = false

    // shake‑detection state
    private var lastPeakTime: Long = 0L
    private var peakCount: Int = 0
    private var lastPeakX = 0f
    private var lastPeakY = 0f
    private var lastPeakZ = 0f
    private var haveLastPeak = false

    // shake thresholds
    private val SHAKE_THRESHOLD = 10f
    private val MIN_TIME_BETWEEN_SHAKES = 150L
    private val MAX_TIME_BETWEEN_PEAKS = 500L
    private val REQUIRED_PEAKS = 4

    // one‑shot trigger listener for significant‑motion
    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            if (isWakingUp) {
                return
            }
            isWakingUp = true
            Log.d("ShakeService", "Significant motion detected")
            wakeUpDevice()
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("ShakeService", "Service onCreate")
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        // 1. Try to use the one‑shot wake‑up Significant Motion sensor
        sensorManager.getDefaultSensor(
            Sensor.TYPE_SIGNIFICANT_MOTION,
            true
        )?.let {
            motionSensor = it
            useSigMotion = true
            Log.d("ShakeService", "Using TYPE_SIGNIFICANT_MOTION")
        }

        // 2. Fallback to a wake‑up accelerometer if necessary
        if (motionSensor == null) {
            motionSensor = sensorManager.getDefaultSensor(
                Sensor.TYPE_ACCELEROMETER,
                true   // request wake‑up variant
            )
            Log.d("ShakeService", "Using accelerometer sensor")
        }

        if (motionSensor == null) {
            Log.w("ShakeService", "No suitable motion sensor found")
            stopSelf()
            return
        }

        // listen for screen on/off to start/stop sensing
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d("ShakeService", "Screen off → start sensing")
                        registerSensorListener()
                        isWakingUp = false
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d("ShakeService", "Screen on → stop sensing")
                        unregisterSensorListener()
                        isWakingUp = false
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
            registerSensorListener()
        }
    }

    override fun onDestroy() {
        Log.d("ShakeService", "Service onDestroy")
        unregisterSensorListener()
        screenStateReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    // only called when using accelerometer
    override fun onSensorChanged(event: SensorEvent) {
        if (useSigMotion) return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || isWakingUp) return

        val now = System.currentTimeMillis()
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val accel = sqrt(x*x + y*y + z*z)

        if (accel > SHAKE_THRESHOLD) {
            val dt = now - lastPeakTime
            if (dt > MIN_TIME_BETWEEN_SHAKES) {
                // compute dot product with last peak vector
                val dot = x * lastPeakX + y * lastPeakY + z * lastPeakZ

                if (haveLastPeak && dot < 0f && dt < MAX_TIME_BETWEEN_PEAKS) {
                    // opposite‑direction peak
                    peakCount++
                    Log.d("ShakeService", "Opposite peak #$peakCount")

                    if (peakCount >= REQUIRED_PEAKS) {
                        isWakingUp = true
                        wakeUpDevice()
                    }
                } else {
                    // first peak or same‐direction/too‐slow → reset
                    peakCount = 1
                }

                // record this peak for the next comparison
                lastPeakTime = now
                lastPeakX = x
                lastPeakY = y
                lastPeakZ = z
                haveLastPeak = true
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // no‑op
    }

    private fun wakeUpDevice() {
        if (!powerManager.isInteractive) {
            val wl = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "$packageName:ShakeWakeLock"
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
            .setContentTitle("Shake to Wake")
            .setContentText("Listening for shake gestures")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun registerSensorListener() {
        try {
            isListenerRegistered = if (useSigMotion) {
                sensorManager.requestTriggerSensor(triggerListener, motionSensor)
            } else {
                sensorManager.registerListener(
                    this,
                    motionSensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }
            Log.d("ShakeService", "Listener registered: $isListenerRegistered")
        } catch (e: Exception) {
            Log.e("ShakeService", "Failed to register listener", e)
        }
    }

    private fun unregisterSensorListener() {
        try {
            if (useSigMotion) {
                sensorManager.cancelTriggerSensor(triggerListener, motionSensor)
            } else {
                sensorManager.unregisterListener(this)
            }
            isListenerRegistered = false
            Log.d("ShakeService", "Listener unregistered")
        } catch (e: Exception) {
            Log.e("ShakeService", "Failed to unregister listener", e)
        }
    }
}
