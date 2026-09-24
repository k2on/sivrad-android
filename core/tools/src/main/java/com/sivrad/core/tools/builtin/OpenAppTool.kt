package com.sivrad.core.tools.builtin

import android.content.Intent
import android.content.pm.PackageManager
import com.sivrad.core.tools.ObjectSchema
import com.sivrad.core.tools.Preparation
import com.sivrad.core.tools.StringParam
import com.sivrad.core.tools.Tool
import com.sivrad.core.tools.ToolEnvironment
import com.sivrad.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Launches an installed app. Only while unlocked: the keyguard does not let
 * ordinary activities start over it, so from the lock screen this would
 * silently do nothing.
 *
 * Small models rarely know package names, so a visible app name is accepted
 * too and matched against the launcher labels.
 */
class OpenAppTool : Tool {
    override val name = "open_app"
    override val description =
        "Open an installed app. Pass its package name (e.g. org.mozilla.firefox) or, if unknown, its name as shown in the launcher."
    override val requiresUnlock = true
    override val parameters = ObjectSchema.of(
        "package_name" to StringParam("Package name or visible app name.", maxLength = 200),
        required = listOf("package_name"),
    )

    override suspend fun prepare(args: JsonObject, env: ToolEnvironment): Preparation {
        val query = args.string("package_name").trim()
        val pm = env.context.packageManager
        val (label, intent) = resolve(pm, query)
            ?: return Preparation.Rejected("No launchable app matches \"$query\".")
        return Preparation.Ready(confirmation = null) {
            env.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), dismissAssistant = true)
            ToolResult.Success("Opened $label.")
        }
    }

    private fun resolve(pm: PackageManager, query: String): Pair<String, Intent>? {
        pm.getLaunchIntentForPackage(query)?.let { intent ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(query, 0)).toString() }
                .getOrDefault(query)
            return label to intent
        }
        val launchers = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(0),
        ).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        val match = launchers.firstOrNull { it.first.equals(query, ignoreCase = true) }
            ?: launchers.filter { it.first.contains(query, ignoreCase = true) }.singleOrNull()
            ?: return null
        val intent = pm.getLaunchIntentForPackage(match.second) ?: return null
        return match.first to intent
    }
}
