package com.example.temiapp

object AppRuntimeConfig {
    /**
     * 目前院內環境先不要依賴 temi 內建 NLP。
     * 等你提供成大醫院 ASR API 規格後，再把新的 ASR provider 接進來。
     */
    const val ENABLE_TEMI_NLP = false

    /**
     * TTS 預設改走 Android 本機 TextToSpeech。
     * 如果現場確認 temi 的 speak 在你的環境可用，再改成 true 當備援。
     */
    const val ENABLE_TEMI_TTS_FALLBACK = true
}
