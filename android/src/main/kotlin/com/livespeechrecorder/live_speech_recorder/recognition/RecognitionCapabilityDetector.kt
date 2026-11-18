package com.livespeechrecorder.live_speech_recorder.recognition

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

data class RecognitionCapability(
    val hasSpeechRecognizer: Boolean,
    val supportsAudioSourceInjection: Boolean,
    val supportsOfflineRecognition: Boolean,
    val availableServices: List<String>,
    val defaultServicePackage: String?,
    val recommendedMode: String
)

/**
 * Detects device capabilities for speech recognition and audio source injection at runtime.
 */
object RecognitionCapabilityDetector {

    fun detect(context: Context): RecognitionCapability {
        val hasSpeechRecognizer = SpeechRecognizer.isRecognitionAvailable(context)

        // Android 13 (API 33) introduced RecognizerIntent.EXTRA_AUDIO_SOURCE
        val supportsAudioSourceInjection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        // Android 12 (API 31) introduced SpeechRecognizer.isOnDeviceRecognitionAvailable
        val supportsOffline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }

        // Query available recognition service packages
        val availableServices = mutableListOf<String>()
        var defaultServicePackage: String? = null

        try {
            val intent = Intent(RecognitionService.SERVICE_INTERFACE)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PackageManager.ResolveInfoFlags.of(0)
            } else {
                0
            }

            val resolveInfoList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.queryIntentServices(intent, flags as PackageManager.ResolveInfoFlags)
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentServices(intent, flags as Int)
            }

            for (resolveInfo in resolveInfoList) {
                val serviceInfo = resolveInfo.serviceInfo
                if (serviceInfo != null) {
                    val pkg = serviceInfo.packageName
                    availableServices.add(pkg)
                    if (defaultServicePackage == null && pkg.contains("google", ignoreCase = true)) {
                        defaultServicePackage = pkg
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore package query failures
        }

        val recommendedMode = when {
            supportsOffline -> "offlineOnly"
            hasSpeechRecognizer -> "automatic"
            else -> "fallback"
        }

        return RecognitionCapability(
            hasSpeechRecognizer = hasSpeechRecognizer,
            supportsAudioSourceInjection = supportsAudioSourceInjection,
            supportsOfflineRecognition = supportsOffline,
            availableServices = availableServices,
            defaultServicePackage = defaultServicePackage,
            recommendedMode = recommendedMode
        )
    }
}
