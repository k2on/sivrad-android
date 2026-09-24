package com.sivrad.core.tools

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface Tool {
    val name: String
    val description: String
    val parameters: ObjectSchema

    /**
     * Whether the device must be unlocked before [prepare] runs. Tools that
     * read personal data, act on the user's behalf, or launch activities
     * (which the keyguard blocks) set this.
     */
    val requiresUnlock: Boolean

    /**
     * When the call succeeds, show its [ToolResult.Success.content] as the
     * reply instead of asking the model to phrase one, saving a model call.
     * For tools whose success message is already a complete answer.
     */
    val replyDirectly: Boolean get() = false

    /**
     * Resolves already-validated [args] into something runnable. Must have no
     * side effects: the user may still decline the confirmation. Runs after
     * the unlock (if [requiresUnlock]).
     */
    suspend fun prepare(args: JsonObject, env: ToolEnvironment): Preparation
}

sealed interface Preparation {
    /** Runnable. If [confirmation] is non-null the user must approve it on screen first. */
    class Ready(val confirmation: Confirmation?, val run: suspend () -> ToolResult) : Preparation

    /** Not runnable; [reason] goes back to the model. */
    class Rejected(val reason: String) : Preparation
}

/** What the confirmation card shows. */
data class Confirmation(
    val title: String,
    val fields: List<Pair<String, String>>,
    val confirmLabel: String,
)

sealed interface ToolResult {
    data class Success(val content: String) : ToolResult
    data class Error(val message: String) : ToolResult

    /** The JSON the model sees inside `<tool_response>`. */
    fun toModelJson(): JsonObject = when (this) {
        is Success -> buildJsonObject { put("ok", true); put("result", content) }
        is Error -> buildJsonObject { put("ok", false); put("error", message) }
    }
}

/** What a tool may touch. Implemented by the assistant session. */
interface ToolEnvironment {
    val context: Context

    /** Base URLs `http_request` may reach. */
    val httpAllowlist: List<String>

    /**
     * Starts an activity on the user's behalf from the assistant session,
     * closing the overlay afterwards if [dismissAssistant] (so the started
     * app is not hidden behind it). Throws if the platform refuses.
     */
    fun startActivity(intent: Intent, dismissAssistant: Boolean = false)
}
