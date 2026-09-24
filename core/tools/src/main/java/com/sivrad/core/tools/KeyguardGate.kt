package com.sivrad.core.tools

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Lock-screen gating for tools.
 *
 * The assistant overlay is allowed over the keyguard, so anything that can
 * run there can be triggered by whoever is holding the phone. Tools marked
 * [Tool.requiresUnlock] go through [ensureUnlocked] first, which asks the
 * system to dismiss the keyguard — for a secure lock that means the user
 * authenticates (PIN/fingerprint) — and only returns true once that has
 * actually happened.
 *
 * [KeyguardManager.requestDismissKeyguard] only accepts an Activity, and a
 * VoiceInteractionSession is not one, so [UnlockActivity] is started through
 * [launch] (the session's `startAssistantActivity`) purely to make the call.
 */
class KeyguardGate(
    private val context: Context,
    private val launch: (Intent) -> Unit,
) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    fun isLocked(): Boolean = keyguard.isKeyguardLocked

    /** Whether an unlock prompt is currently on screen. */
    val inFlight: Boolean get() = UnlockActivity.pending.isNotEmpty()

    suspend fun ensureUnlocked(timeoutMillis: Long = 60_000): Boolean {
        if (!keyguard.isKeyguardLocked) return true
        val id = UnlockActivity.nextId.incrementAndGet()
        val result = CompletableDeferred<Boolean>()
        UnlockActivity.pending[id] = result
        try {
            launch(
                Intent(context, UnlockActivity::class.java)
                    .putExtra(UnlockActivity.EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val unlocked = withTimeoutOrNull(timeoutMillis) { result.await() } ?: false
            // Trust the keyguard, not the callback alone.
            return unlocked && !keyguard.isKeyguardLocked
        } finally {
            UnlockActivity.pending.remove(id)
        }
    }
}

class UnlockActivity : Activity() {
    private var id = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getLongExtra(EXTRA_ID, 0L)
        if (pending[id] == null) {
            finish()
            return
        }
        val km = getSystemService(KeyguardManager::class.java)
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = done(true)
            override fun onDismissCancelled() = done(false)
            override fun onDismissError() {
                Log.w(TAG, "requestDismissKeyguard failed")
                done(false)
            }
        })
    }

    override fun onDestroy() {
        // Destroyed without an answer (e.g. the user backed out): not unlocked.
        pending[id]?.complete(false)
        super.onDestroy()
    }

    private fun done(unlocked: Boolean) {
        pending[id]?.complete(unlocked)
        finish()
    }

    internal companion object {
        const val TAG = "UnlockActivity"
        const val EXTRA_ID = "com.sivrad.core.tools.UNLOCK_ID"
        val nextId = AtomicLong()
        val pending = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    }
}
