package com.sivrad.assistant.service

import android.service.voice.VoiceInteractionService
import android.util.Log
import com.sivrad.assistant.sivrad

/**
 * The default-assistant entry point. The system binds it for as long as
 * Sivrad is the selected assistant, which is what keeps the process — and
 * with it the loaded models — alive between invocations.
 */
class AssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        Log.i(TAG, "assistant service ready; loading models")
        sivrad.engine.ensureLoaded()
    }

    private companion object {
        const val TAG = "AssistantService"
    }
}
