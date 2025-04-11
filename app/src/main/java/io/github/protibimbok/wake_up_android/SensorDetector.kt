 package io.github.protibimbok.wake_up_android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.util.Log
import kotlin.math.sqrt

class SensorDetector(
    private val context: Context,
    private val onWakeUp: () -> Unit
) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val activeSensors = mutableMapOf<Int, Pair<Sensor, SensorConfig>>()
    private val sensorStates = mutableMapOf<Int, SensorState>()
    private var isListenerRegistered = false
    private var isWakingUp = false

    // Detection thresholds
    private val MIN_TIME_BETWEEN_SHAKES = 150L
    private val MAX_TIME_BETWEEN_PEAKS = 500L
    private val REQUIRED_PEAKS = 4

    // one‑shot trigger listener for significant‑motion
    private val triggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            if (isWakingUp) return
            isWakingUp = true
            Log.d("SensorDetector", "Significant motion detected")
            onWakeUp()
        }
    }

    fun initializeSensors() {
        activeSensors.clear()
        sensorStates.clear()
        
        Settings.getSavedSensors(context).forEach { config ->
            Log.d("SensorDetector", "Adding sensor ${config.name}: ${config.enabled}")
            if (!config.enabled) {
                return@forEach
            }
            var requiresWakeup = true
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
                Log.d("SensorDetector", "Added wake sensor: ${config.name}")
            }
        }
    }

    fun start(): Boolean {
        return registerSensorListeners()
    }

    fun stop() {
        unregisterSensorListeners()
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
                            Log.d("SensorDetector", "${config.name} peak #${state.peakCount}")

                            if (state.peakCount >= REQUIRED_PEAKS) {
                                isWakingUp = true
                                onWakeUp()
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
                            onWakeUp()
                        }
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        // no‑op
    }

    private fun registerSensorListeners() : Boolean {
        var needsWakeLock = false
        try {
            isListenerRegistered = false
            var anyRegistered = false
            
            activeSensors.forEach { (type, pair) ->
                val (sensor, config) = pair
                if (!config.enabled) return@forEach

                if (config.requiresWakeup) {
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
            
            isListenerRegistered = anyRegistered
            Log.d("SensorDetector", "Sensors registered: $isListenerRegistered")
        } catch (e: Exception) {
            Log.e("SensorDetector", "Failed to register sensors", e)
        }
        return needsWakeLock
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
            
            isListenerRegistered = false
            Log.d("SensorDetector", "Sensors unregistered")
        } catch (e: Exception) {
            Log.e("SensorDetector", "Failed to unregister sensors", e)
        }
    }

    data class SensorState(
        var lastPeakTime: Long = 0L,
        var peakCount: Int = 0,
        var lastPeakX: Float = 0f,
        var lastPeakY: Float = 0f,
        var lastPeakZ: Float = 0f,
        var haveLastPeak: Boolean = false
    )
}