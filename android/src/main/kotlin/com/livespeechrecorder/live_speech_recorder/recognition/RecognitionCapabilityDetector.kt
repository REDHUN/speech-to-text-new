package com.livespeechrecorder.live_speech_recorder.recognition

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
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
 * Live transcription needs audio injection, which requires Android 13. Android has no query for
 * whether a recognition service honors it, so
 * [RecognitionCapability.supportsAudioSourceInjection] means the device can attempt it. The
 * backend verifies this at runtime and reports AUDIO_INJECTION_UNSUPPORTED if it is ignored.
 */
object RecognitionCapabilityDetector {

    fun detect(context: Context): RecognitionCapability {
        val hasSpeechRecognizer = SpeechRecognizer.isRecognitionAvailable(context)
        val canInjectAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val supportsOffline = canInjectAudio && try {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        } catch (e: RuntimeException) {
            false
        }

        val services = try {
            queryRecognitionServices(context).mapNotNull { it.serviceInfo?.packageName }
        } catch (e: RuntimeException) {
            emptyList()
        }

        return RecognitionCapability(
            hasSpeechRecognizer = hasSpeechRecognizer,
            supportsAudioSourceInjection = canInjectAudio && (hasSpeechRecognizer || supportsOffline),
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

    private fun queryRecognitionServices(context: Context): List<ResolveInfo> {
        val intent = Intent(RecognitionService.SERVICE_INTERFACE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentServices(intent, 0)
        }
    }
}
