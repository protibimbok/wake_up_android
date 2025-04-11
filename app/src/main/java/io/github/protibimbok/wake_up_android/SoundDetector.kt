package io.github.protibimbok.wake_up_android

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

class SoundDetector(
    private val context: Context,
    private val onWakeUp: () -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var isWakingUp = false
    
    // Audio recording parameters
    private val SAMPLE_RATE = 44100
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    
    // Sound detection parameters
    private val SOUND_THRESHOLD = 4000 // Adjust this value based on testing
    private val MIN_SOUND_DURATION = 1000L // 1 second
    private val MAX_SOUND_DURATION = 3000L // 3 seconds
    private var soundStartTime: Long = 0L
    private var isSoundDetected = false

    companion object {
        fun hasAllRequiredPermissions(context: Context): Boolean {
            val hasRecordAudio = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            val hasMicrophoneService = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            return hasRecordAudio && hasMicrophoneService
        }

        fun hasPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRecording) return true
        if (!hasAllRequiredPermissions(context)) return false
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )
            
            audioRecord?.startRecording()
            isRecording = true
            
            CoroutineScope(Dispatchers.IO).launch {
                processAudio()
            }
            return true
        } catch (e: Exception) {
            Log.e("SoundDetector", "Failed to start audio recording", e)
            return false
        }
    }

    fun stop() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun processAudio() {
        val buffer = ShortArray(BUFFER_SIZE)
        
        while (isRecording) {
            val read = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: 0
            
            if (read > 0) {
                // Calculate average amplitude
                var sum = 0L
                for (i in 0 until read) {
                    sum += abs(buffer[i].toLong())
                }
                val averageAmplitude = sum / read

                Log.d("ShakeService", "Sound Amplitude: $averageAmplitude")
                
                // Detect sudden loud sound
                if (averageAmplitude > SOUND_THRESHOLD) {
                    if (!isSoundDetected) {
                        isSoundDetected = true
                        soundStartTime = System.currentTimeMillis()
                    }
                } else {
                    if (isSoundDetected) {
                        val soundDuration = System.currentTimeMillis() - soundStartTime
                        if (soundDuration in MIN_SOUND_DURATION..MAX_SOUND_DURATION && !isWakingUp) {
                            isWakingUp = true
                            onWakeUp()
                        }
                        isSoundDetected = false
                    }
                }
            }
        }
    }
} 