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
    private lateinit var powerManager: PowerManager
    private var screenStateReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Active sensors and their configurations
    private val activeSensors = mutableMapOf<Int, Pair<Sensor, SensorConfig>>()
    private var isListenerRegistered = false
    
    // wake‑up flag
    private var isWakingUp = false

    // Motion detection state
    private val sensorStates = mutableMapOf<Int, SensorState>()
    
    data class SensorState(
        var lastPeakTime: Long = 0L,
        var peakCount: Int = 0,
        var lastPeakX: Float = 0f,
        var lastPeakY: Float = 0f,
        var lastPeakZ: Float = 0f,
        var haveLastPeak: Boolean = false
    )

    // Detection thresholds
    private val MIN_TIME_BETWEEN_SHAKES = 150L
    private val MAX_TIME_BETWEEN_PEAKS = 500L
    private val REQUIRED_PEAKS = 4

    // one‑shot trigger listener for significant‑motion
    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            if (isWakingUp) return
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

        // Initialize available sensors
        initializeSensors()

        // listen for screen on/off to start/stop sensing
        screenStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d("ShakeService", "Screen off → start sensing")
                        registerSensorListeners()
                        isWakingUp = false
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d("ShakeService", "Screen on → stop sensing")
                        unregisterSensorListeners()
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
            registerSensorListeners()
        }
    }

    private fun initializeSensors() {
        activeSensors.clear()
        sensorStates.clear()
        
        Settings.getSavedSensors(this).forEach { config ->
            Log.d("ShakeService", "Adding sensor ${config.name}: ${config.enabled}")
            if (!config.enabled) {
                return@forEach
            }
            var requiresWakeup = true;
            // First try wake-up variant
            var sensor = sensorManager.getDefaultSensor(config.type, true)

            if (sensor == null) {
                sensor = sensorManager.getDefaultSensor(config.type, false)
                requiresWakeup = false
            }
            
            if (sensor != null) {
                val sensorConfig = config.copy(
                    requiresWakeup = requiresWakeup,
                )
                activeSensors[config.type] = Pair(sensor, sensorConfig)
                sensorStates[config.type] = SensorState()
                Log.d("ShakeService", "Added wake sensor: ${config.name}")
            }
        }
        
        if (activeSensors.isEmpty()) {
            Log.w("ShakeService", "No suitable motion sensors found")
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.d("ShakeService", "Service onDestroy")
        unregisterSensorListeners()
        screenStateReceiver?.let { unregisterReceiver(it) }
        // Make sure wake lock is released
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle sensor configuration updates
        intent?.getParcelableArrayListExtra<SensorConfig>("sensors")?.let { configs ->
            configs.forEach { config ->
                activeSensors[config.type]?.let { (sensor, _) ->
                    activeSensors[config.type] = Pair(sensor, config)
                }
            }
            // Restart sensor listeners if needed
            if (isListenerRegistered) {
                unregisterSensorListeners()
                registerSensorListeners()
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

    override fun onSensorChanged(event: SensorEvent) {
        if (isWakingUp) return
        
        val sensorType = event.sensor.type
        val (_, config) = activeSensors[sensorType] ?: return
        if (!config.enabled) return
        
        val state = sensorStates[sensorType] ?: return
        val now = System.currentTimeMillis()
        
        when (sensorType) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt(x*x + y*y + z*z)

                if (magnitude > config.threshold) {
                    val dt = now - state.lastPeakTime
                    if (dt > MIN_TIME_BETWEEN_SHAKES) {
                        val dot = x * state.lastPeakX + y * state.lastPeakY + z * state.lastPeakZ

                        if (state.haveLastPeak && dot < 0f && dt < MAX_TIME_BETWEEN_PEAKS) {
                            state.peakCount++
                            Log.d("ShakeService", "${config.name} peak #${state.peakCount}")

                            if (state.peakCount >= REQUIRED_PEAKS) {
                                isWakingUp = true
                                wakeUpDevice()
                            }
                        } else {
                            state.peakCount = 1
                        }

                        state.lastPeakTime = now
                        state.lastPeakX = x
                        state.lastPeakY = y
                        state.lastPeakZ = z
                        state.haveLastPeak = true
                    }
                }
            }
            
            Sensor.TYPE_GYROSCOPE -> {
                val rotationRate = sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                
                if (rotationRate > config.threshold) {
                    val dt = now - state.lastPeakTime
                    if (dt > MIN_TIME_BETWEEN_SHAKES) {
                        state.peakCount++
                        state.lastPeakTime = now
                        
                        if (state.peakCount >= REQUIRED_PEAKS) {
                            isWakingUp = true
                            wakeUpDevice()
                        }
                    }
                }
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
            .setContentTitle("Motion Detection Service")
            .setContentText("Monitoring for wake gestures")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun registerSensorListeners() {
        try {
            isListenerRegistered = false
            var anyRegistered = false
            var needsWakeLock = false
            
            activeSensors.forEach { (type, pair) ->
                val (sensor, config) = pair
                if (!config.enabled) return@forEach
                
                // Check if we need wake lock for any enabled non-wake sensor
                if (!config.requiresWakeup) {
                    needsWakeLock = true
                }
                
                when (type) {
                    Sensor.TYPE_SIGNIFICANT_MOTION -> {
                        if (sensorManager.requestTriggerSensor(triggerListener, sensor)) {
                            anyRegistered = true
                        }
                    }
                    else -> {
                        // Register with wake=true only if sensor supports it
                        if (sensorManager.registerListener(
                            this,
                            sensor,
                            SensorManager.SENSOR_DELAY_GAME,
                            if (config.requiresWakeup) SensorManager.SENSOR_DELAY_GAME else 0
                        )) {
                            anyRegistered = true
                        }
                    }
                }
            }
            
            // Acquire wake lock if needed
            if (needsWakeLock) {
                wakeLock?.release()
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:SensorWakeLock"
                ).apply {
                    acquire(12*60*60*1000L)
                }
            }
            
            isListenerRegistered = anyRegistered
            Log.d("ShakeService", "Sensors registered: $isListenerRegistered, wakeLock: $needsWakeLock")
        } catch (e: Exception) {
            Log.e("ShakeService", "Failed to register sensors", e)
        }
    }

    private fun unregisterSensorListeners() {
        try {
            activeSensors.forEach { (type, pair) ->
                val (sensor, _) = pair
                when (type) {
                    Sensor.TYPE_SIGNIFICANT_MOTION -> {
                        sensorManager.cancelTriggerSensor(triggerListener, sensor)
                    }
                    else -> {
                        sensorManager.unregisterListener(this, sensor)
                    }
                }
            }
            
            // Release wake lock if held
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
                wakeLock = null
            }
            
            isListenerRegistered = false
            Log.d("ShakeService", "Sensors unregistered")
        } catch (e: Exception) {
            Log.e("ShakeService", "Failed to unregister sensors", e)
        }
    }
}
