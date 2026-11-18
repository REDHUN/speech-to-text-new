package com.livespeechrecorder.live_speech_recorder.recognition

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognitionService
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
 * Detects speech recognition capabilities.
 *
 * Android has no query for whether a recognition service honors injected audio, so
 * [RecognitionCapability.supportsAudioSourceInjection] means the device can attempt it. The
 * backend verifies this at runtime and reports AUDIO_INJECTION_UNSUPPORTED if it is ignored.
 */
object RecognitionCapabilityDetector {

    fun detect(context: Context): RecognitionCapability {
        val hasSpeechRecognizer = SpeechRecognizer.isRecognitionAvailable(context)
        val supportsOffline = try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } catch (e: RuntimeException) {
            false
        }

        val services = try {
            context.packageManager
                .queryIntentServices(
                    Intent(RecognitionService.SERVICE_INTERFACE),
                    PackageManager.ResolveInfoFlags.of(0)
                )
                .mapNotNull { it.serviceInfo?.packageName }
        } catch (e: RuntimeException) {
            emptyList()
        }

        return RecognitionCapability(
            hasSpeechRecognizer = hasSpeechRecognizer,
            supportsAudioSourceInjection = hasSpeechRecognizer || supportsOffline,
            supportsOfflineRecognition = supportsOffline,
            availableServices = services,
            defaultServicePackage = services.firstOrNull { it.contains("google", ignoreCase = true) },
            recommendedMode = if (hasSpeechRecognizer && !supportsOffline) {
                RecognitionConfig.MODE_ONLINE_ALLOWED
            } else {
                RecognitionConfig.MODE_AUTOMATIC
            }
        )
    }
}
