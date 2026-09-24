package com.sivrad.core.tools.builtin

import android.content.Intent
import android.provider.AlarmClock
import com.sivrad.core.tools.ArrayParam
import com.sivrad.core.tools.IntegerParam
import com.sivrad.core.tools.ObjectSchema
import com.sivrad.core.tools.Preparation
import com.sivrad.core.tools.StringParam
import com.sivrad.core.tools.Tool
import com.sivrad.core.tools.ToolEnvironment
import com.sivrad.core.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import java.util.Calendar

/**
 * Timers and alarms go through the AlarmClock intents with EXTRA_SKIP_UI, so
 * the clock app sets them without coming to the foreground. Neither exposes
 * anything personal, so both run from the lock screen.
 *
 * TODO(on-device): confirm GrapheneOS's clock handles ACTION_SET_TIMER /
 * ACTION_SET_ALARM with EXTRA_SKIP_UI while the keyguard is showing when the
 * intent is started from the assistant session (AOSP DeskClock's
 * HandleApiCalls does; verify no bouncer appears).
 */
class SetTimerTool : Tool {
    override val name = "set_timer"
    override val description = "Start a countdown timer on the phone's clock app."
    override val requiresUnlock = false
    override val parameters = ObjectSchema.of(
        "duration_seconds" to IntegerParam("Timer length in seconds.", min = 1, max = 86_400),
        "label" to StringParam("Optional name shown on the timer.", maxLength = 60),
        required = listOf("duration_seconds"),
    )

    override suspend fun prepare(args: JsonObject, env: ToolEnvironment): Preparation {
        val seconds = args.long("duration_seconds")
        val label = args.optString("label")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (intent.resolveActivity(env.context.packageManager) == null) {
            return Preparation.Rejected("No clock app on this phone can set timers.")
        }
        return Preparation.Ready(confirmation = null) {
            env.startActivity(intent)
            ToolResult.Success("Timer set for ${formatDuration(seconds)}" + (label?.let { " ($it)" } ?: "") + ".")
        }
    }
}

class SetAlarmTool : Tool {
    override val name = "set_alarm"
    override val description = "Set an alarm on the phone's clock app. Repeats weekly on the given days, or rings once if no days are given."
    override val requiresUnlock = false
    override val parameters = ObjectSchema.of(
        "hour" to IntegerParam("Hour, 24-hour clock.", min = 0, max = 23),
        "minute" to IntegerParam("Minute.", min = 0, max = 59),
        "label" to StringParam("Optional alarm name.", maxLength = 60),
        "days" to ArrayParam(
            "Optional days of the week to repeat on.",
            items = StringParam("Day of the week.", enum = DAYS.keys.toList()),
            maxItems = 7,
        ),
        required = listOf("hour", "minute"),
    )

    override suspend fun prepare(args: JsonObject, env: ToolEnvironment): Preparation {
        val hour = args.long("hour").toInt()
        val minute = args.long("minute").toInt()
        val label = args.optString("label")
        val days = args.optStrings("days").distinct()
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (days.isNotEmpty()) {
            intent.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days.map { DAYS.getValue(it) }))
        }
        if (intent.resolveActivity(env.context.packageManager) == null) {
            return Preparation.Rejected("No clock app on this phone can set alarms.")
        }
        return Preparation.Ready(confirmation = null) {
            env.startActivity(intent)
            val time = "%02d:%02d".format(hour, minute)
            val repeat = if (days.isEmpty()) "" else " repeating on ${days.joinToString()}"
            ToolResult.Success("Alarm set for $time$repeat" + (label?.let { " ($it)" } ?: "") + ".")
        }
    }

    private companion object {
        val DAYS = linkedMapOf(
            "monday" to Calendar.MONDAY,
            "tuesday" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY,
            "saturday" to Calendar.SATURDAY,
            "sunday" to Calendar.SUNDAY,
        )
    }
}
