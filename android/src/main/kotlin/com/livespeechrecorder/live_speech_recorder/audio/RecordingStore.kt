package com.livespeechrecorder.live_speech_recorder.audio

import java.io.File

data class StoredRecording(
    val path: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val modifiedAtMs: Long
)

/**
 * Manages the recordings kept in the plugin's own directory. Files are touched only from the
 * background thread. Files recorded to a custom path belong to the host app and are not managed.
 */
class RecordingStore(val directory: File) {

    fun newRecordingFile(uniqueSuffix: String): File =
        File(directory, "recording_${System.currentTimeMillis()}_$uniqueSuffix.wav")

    /** Lists recordings, newest first, repairing any left unfinished by a killed process. */
    fun list(activePath: String?): List<StoredRecording> {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".wav") }
            ?: return emptyList()

        return files
            .filter { it.absolutePath != activePath }
            .mapNotNull { file ->
                val durationMs = WavFileWriter.repairHeader(file) ?: return@mapNotNull null
                StoredRecording(file.absolutePath, file.length(), durationMs, file.lastModified())
            }
            .sortedByDescending { it.modifiedAtMs }
    }

    /** @return true only if the file was inside this store, not active, and was deleted. */
    fun delete(path: String, activePath: String?): Boolean {
        val file = File(path).canonicalFile
        if (file.parentFile != directory.canonicalFile) return false
        if (activePath != null && file == File(activePath).canonicalFile) return false
        return file.isFile && file.delete()
    }
}
