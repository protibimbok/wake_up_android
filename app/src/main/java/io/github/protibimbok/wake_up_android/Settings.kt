package io.github.protibimbok.wake_up_android

import android.content.Context
import android.content.SharedPreferences
import android.hardware.SensorManager

object Settings {
    private const val PREFS_NAME = "wake_up_settings"
    private const val KEY_ENABLED_SENSORS = "enabled_sensors"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getAvailableWakeSensors(context: Context): List<SensorConfig> {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val enabledTypes = getEnabledSensorTypes(context)

        return AvailableSensors.SUPPORTED_SENSORS.mapNotNull { config ->
            // Try to get the wake-up variant of the sensor
            val sensor = sensorManager.getDefaultSensor(config.type, true)
            if (sensor != null) {
                config.copy(enabled = config.type in enabledTypes)
            } else {
                null
            }
        }
    }

    fun getEnabledSensorTypes(context: Context): Set<Int> {
        return getPrefs(context).getStringSet(KEY_ENABLED_SENSORS, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()
    }

    fun updateEnabledSensors(context: Context, enabledConfigs: List<SensorConfig>) {
        val enabledTypes = enabledConfigs
            .filter { it.enabled }
            .map { it.type.toString() }
            .toSet()

        getPrefs(context).edit()
            .putStringSet(KEY_ENABLED_SENSORS, enabledTypes)
            .apply()
    }
} 