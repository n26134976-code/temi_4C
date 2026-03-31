package com.example.temiapp

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.robotemi.sdk.Robot
import com.robotemi.sdk.TtsRequest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SpeechManager(
    context: Context,
    private val robot: Robot? = null
) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()

    private var tts: TextToSpeech? = null
    private var ttsInitFinished = false
    private var localTtsReady = false
    private var pendingSpeak: PendingSpeak? = null
    private var temiListener: Robot.TtsListener? = null

    data class PendingSpeak(
        val text: String,
        val onComplete: (() -> Unit)?
    )

    init {
        tts = TextToSpeech(appContext) { status ->
            ttsInitFinished = true
            localTtsReady = if (status == TextToSpeech.SUCCESS) {
                configureLanguage(tts)
            } else {
                false
            }

            if (!localTtsReady) {
                Log.w(TAG, "Android TextToSpeech 初始化失敗或不支援中文，將改用備援流程")
            }

            pendingSpeak?.let { pending ->
                pendingSpeak = null
                speak(pending.text, pending.onComplete)
            }
        }.also { engine ->
            engine.setSpeechRate(0.95f)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { id ->
                        callbacks.remove(id)?.let { callback ->
                            mainHandler.post(callback)
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { id ->
                        callbacks.remove(id)?.let { callback ->
                            mainHandler.post(callback)
                        }
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    onError(utteranceId)
                }
            })
        }
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        val safeText = text.trim()
        if (safeText.isEmpty()) {
            onComplete?.let { mainHandler.post(it) }
            return
        }

        clearTemiListener()

        if (!ttsInitFinished) {
            pendingSpeak = PendingSpeak(safeText, onComplete)
            return
        }

        if (localTtsReady) {
            val utteranceId = UUID.randomUUID().toString()
            if (onComplete != null) {
                callbacks[utteranceId] = onComplete
            }

            val result = tts?.speak(
                safeText,
                TextToSpeech.QUEUE_FLUSH,
                Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                },
                utteranceId
            ) ?: TextToSpeech.ERROR

            if (result != TextToSpeech.SUCCESS) {
                callbacks.remove(utteranceId)
                speakWithFallback(safeText, onComplete)
            }
            return
        }

        speakWithFallback(safeText, onComplete)
    }

    fun stop() {
        pendingSpeak = null
        callbacks.clear()
        runCatching { tts?.stop() }
        clearTemiListener()
        runCatching { robot?.cancelAllTtsRequests() }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun speakWithFallback(text: String, onComplete: (() -> Unit)?) {
        val temiRobot = if (AppRuntimeConfig.ENABLE_TEMI_TTS_FALLBACK) robot else null

        if (temiRobot != null) {
            if (onComplete != null) {
                val listener = object : Robot.TtsListener {
                    override fun onTtsStatusChanged(ttsRequest: TtsRequest) {
                        if (ttsRequest.status == TtsRequest.Status.COMPLETED ||
                            ttsRequest.status == TtsRequest.Status.ERROR
                        ) {
                            runCatching { temiRobot.removeTtsListener(this) }
                            temiListener = null
                            mainHandler.post(onComplete)
                        }
                    }
                }
                temiListener = listener
                runCatching { temiRobot.addTtsListener(listener) }
            }
            temiRobot.speak(TtsRequest.create(text, false))
            return
        }

        Log.w(TAG, "沒有可用的 TTS，引導改用文字顯示：$text")
        onComplete?.let { mainHandler.postDelayed(it, 300) }
    }

    private fun clearTemiListener() {
        temiListener?.let { listener ->
            runCatching { robot?.removeTtsListener(listener) }
        }
        temiListener = null
    }

    private fun configureLanguage(engine: TextToSpeech?): Boolean {
        val ttsEngine = engine ?: return false
        val candidates = listOf(Locale.TAIWAN, Locale.TRADITIONAL_CHINESE, Locale.CHINESE)
        for (locale in candidates) {
            val result = ttsEngine.setLanguage(locale)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "SpeechManager"
    }
}