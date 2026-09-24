package com.sivrad.assistant.service

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The voice interaction framework will not accept an assistant without a
 * RecognitionService. Sivrad's recognition is internal to its own overlay,
 * so this refuses every request instead of pretending to transcribe.
 */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
