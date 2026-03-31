package com.example.temiapp

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.io.File

class AudioRecorderHelper(
    private val context: Context
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    fun startRecording(): File {
        stopRecording(deleteBrokenFile = true)

        val file = File(
            context.cacheDir,
            "hospital_asr_${System.currentTimeMillis()}.m4a"
        )

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(64000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        recorder = mediaRecorder
        outputFile = file
        return file
    }

    fun stopRecording(deleteBrokenFile: Boolean = false): File? {
        val currentRecorder = recorder ?: return outputFile
        val currentFile = outputFile

        try {
            currentRecorder.stop()
        } catch (e: RuntimeException) {
            Log.w(TAG, "stopRecording failed", e)
            if (deleteBrokenFile) {
                currentFile?.delete()
            }
            return null
        } finally {
            runCatching { currentRecorder.reset() }
            runCatching { currentRecorder.release() }
            recorder = null
            outputFile = null
        }

        return currentFile
    }

    fun cancelRecording() {
        stopRecording(deleteBrokenFile = true)
    }

    companion object {
        private const val TAG = "AudioRecorderHelper"
    }
}
