package io.github.protibimbok.wake_up_android

import android.content.Context
import android.content.SharedPreferences
import android.hardware.SensorManager
import org.json.JSONArray
import org.json.JSONObject

object Settings {
    private const val PREFS_NAME = "wake_up_settings"
    private const val KEY_SENSOR_CONFIGS = "sensor_configs"
    private const val KEY_SOUND_ENABLED = "sound_enabled"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isSoundEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SOUND_ENABLED, false)
    }

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_SOUND_ENABLED, enabled)
            .apply()
    }

    fun getAvailableWakeSensors(context: Context): List<SensorConfig> {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val savedConfigs = getSavedSensorConfigs(context)

        return AvailableSensors.SUPPORTED_SENSORS.mapNotNull { config ->
            // First try wake-up variant
            val wakeSensor = sensorManager.getDefaultSensor(config.type, true)
            // Then try normal variant
            val normalSensor = sensorManager.getDefaultSensor(config.type, false)
            
            when {
                // If wake sensor exists, use that configuration
                wakeSensor != null -> {
                    config.copy(
                        enabled = savedConfigs[config.type]?.enabled ?: false,
                        requiresWakeup = true,
                    )
                }
                // If only normal sensor exists, use that but mark as non-wake
                normalSensor != null -> {
                    config.copy(
                        enabled = savedConfigs[config.type]?.enabled ?: false,
                        requiresWakeup = false,
                    )
                }
                // No sensor of this type available
                else -> null
            }
        }
    }

    public fun getSavedSensors(context: Context) : List<SensorConfig> {
        val json = getPrefs(context).getString(KEY_SENSOR_CONFIGS, "[]")
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SensorConfig(
                    type = obj.getInt("type"),
                    name = obj.getString("name"),
                    enabled = obj.getBoolean("enabled"),
                    requiresWakeup = obj.getBoolean("requiresWakeup"),
                    threshold = obj.getDouble("threshold").toFloat()
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getSavedSensorConfigs(context: Context): Map<Int, SensorConfig> {
        val json = getPrefs(context).getString(KEY_SENSOR_CONFIGS, "[]")
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val config = SensorConfig(
                    type = obj.getInt("type"),
                    name = obj.getString("name"),
                    enabled = obj.getBoolean("enabled"),
                    requiresWakeup = obj.getBoolean("requiresWakeup"),
                    threshold = obj.getDouble("threshold").toFloat()
                )
                config.type to config
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun updateEnabledSensors(context: Context, configs: List<SensorConfig>) {
        val array = JSONArray()
        configs.forEach { config ->
            array.put(JSONObject().apply {
                put("type", config.type)
                put("name", config.name)
                put("enabled", config.enabled)
                put("requiresWakeup", config.requiresWakeup)
                put("threshold", config.threshold)
            })
        }

        getPrefs(context).edit()
            .putString(KEY_SENSOR_CONFIGS, array.toString())
            .apply()
    }
} 