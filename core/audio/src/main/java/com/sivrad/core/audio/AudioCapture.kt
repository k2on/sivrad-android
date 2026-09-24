package com.sivrad.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.Executors

/**
 * Microphone capture: 16 kHz mono 16-bit PCM from AudioRecord, delivered as
 * float frames in [-1, 1] of [FRAME_SAMPLES] samples (32 ms, one Silero
 * window). Cold: recording starts when the flow is collected and the mic is
 * released when collection stops, however it stops.
 */
object AudioCapture {
    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 512

    private val audioThread = Executors.newSingleThreadExecutor { r ->
        Thread(r, "audio-capture").apply { priority = Thread.MAX_PRIORITY }
    }.asCoroutineDispatcher()

    /** Caller must hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun frames(): Flow<FloatArray> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuf > 0) { "16 kHz mono PCM capture unsupported (getMinBufferSize=$minBuf)" }
        val record = AudioRecord.Builder()
            // VOICE_RECOGNITION: tuned for ASR (no AGC/NS that smear onsets).
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAME_SAMPLES * 2 * 8))
            .build()
        check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord failed to initialise (mic in use?)" }
        try {
            record.startRecording()
            // TODO(on-device): if recording from the lock screen yields silence,
            // the platform is blocking background mic access; check that the
            // session window is showing before capture starts.
            val pcm = ShortArray(FRAME_SAMPLES)
            while (true) {
                currentCoroutineContext().ensureActive()
                var filled = 0
                while (filled < FRAME_SAMPLES) {
                    val n = record.read(pcm, filled, FRAME_SAMPLES - filled, AudioRecord.READ_BLOCKING)
                    check(n >= 0) { "AudioRecord.read failed: $n" }
                    filled += n
                }
                emit(FloatArray(FRAME_SAMPLES) { pcm[it] / 32768f })
            }
        } finally {
            runCatching { record.stop() }.onFailure { Log.w("AudioCapture", "stop failed", it) }
            record.release()
        }
    }.flowOn(audioThread)
}
