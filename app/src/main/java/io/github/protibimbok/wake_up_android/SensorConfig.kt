package io.github.protibimbok.wake_up_android

import android.hardware.Sensor
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class SensorConfig(
    val type: Int,
    val name: String,
    val requiresWakeup: Boolean = true,
    val threshold: Float = 0f,
    val enabled: Boolean = false
) : Parcelable

object AvailableSensors {
    val SUPPORTED_SENSORS = listOf(
        SensorConfig(
            type = Sensor.TYPE_SIGNIFICANT_MOTION,
            name = "Significant Motion",
            threshold = 0f
        ),
        SensorConfig(
            type = Sensor.TYPE_ACCELEROMETER,
            name = "Accelerometer",
            threshold = 10f
        ),
        SensorConfig(
            type = Sensor.TYPE_GYROSCOPE,
            name = "Gyroscope",
            threshold = 3f
        ),
        SensorConfig(
            type = Sensor.TYPE_LINEAR_ACCELERATION,
            name = "Linear Acceleration",
            threshold = 8f
        )
    )
} 