package com.sivrad.core.tools.builtin

import com.sivrad.core.tools.Tool

object BuiltinTools {
    fun all(): List<Tool> = listOf(
        SetTimerTool(),
        SetAlarmTool(),
        SendSmsTool(),
        HttpRequestTool(),
        OpenAppTool(),
    )
}
