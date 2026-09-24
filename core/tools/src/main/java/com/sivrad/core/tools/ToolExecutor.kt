package com.sivrad.core.tools

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Runs one tool call end to end: validate, unlock if the tool needs it,
 * prepare, confirm with the user if the tool asks, run. Every failure becomes
 * a [ToolResult.Error] for the model; nothing here throws except cancellation.
 */
class ToolExecutor(
    private val registry: ToolRegistry,
    private val env: ToolEnvironment,
    private val gate: KeyguardGate,
    /** Shows the confirmation card and suspends until the user answers. */
    private val confirm: suspend (Confirmation) -> Boolean,
    private val onStage: (Stage) -> Unit = {},
) {
    enum class Stage { Unlocking, AwaitingConfirmation, Running }

    suspend fun execute(callJson: String): Pair<String?, ToolResult> {
        val call = registry.parse(callJson)
        if (call is ParsedCall.Invalid) return call.name to ToolResult.Error(call.error)
        call as ParsedCall.Valid
        val tool = call.tool
        return tool.name to try {
            run(tool, call)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "tool ${tool.name} failed", e)
            ToolResult.Error("${tool.name} failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun run(tool: Tool, call: ParsedCall.Valid): ToolResult {
        if (tool.requiresUnlock && gate.isLocked()) {
            onStage(Stage.Unlocking)
            if (!gate.ensureUnlocked()) {
                return ToolResult.Error(
                    "The device is locked and the user did not unlock it, so ${tool.name} was not run. " +
                        "Tell the user to unlock the phone and try again.",
                )
            }
        }
        return when (val prep = tool.prepare(call.args, env)) {
            is Preparation.Rejected -> ToolResult.Error(prep.reason)
            is Preparation.Ready -> {
                val c = prep.confirmation
                if (c != null) {
                    onStage(Stage.AwaitingConfirmation)
                    if (!confirm(c)) return ToolResult.Error("The user cancelled ${tool.name}; nothing was done.")
                }
                onStage(Stage.Running)
                prep.run()
            }
        }
    }

    private companion object {
        const val TAG = "ToolExecutor"
    }
}
