package com.github.fanziyun.updater.screen

import net.minecraft.network.chat.Component

object RestartReasonText {
    fun failure(reason: String): String = reasonKey(reason)?.let(::translated) ?: reason

    fun fastCapability(reason: String): String = capabilityReason(
        reason,
        "screen.updater363.restart.reason.fast_command_unavailable",
    )

    fun automaticCapability(reason: String): String = capabilityReason(
        reason,
        "screen.updater363.restart.reason.jvm_arguments_unavailable",
    )

    private fun capabilityReason(reason: String, fallbackKey: String): String {
        if (reason.isBlank()) return ""
        return translated(reasonKey(reason) ?: fallbackKey)
    }

    internal fun reasonKey(reason: String): String? = when {
        reason.startsWith("Fast restart is unavailable for this Minecraft") ->
            "screen.updater363.restart.reason.unsupported_profile"
        reason.startsWith("Fast restart requires a newly prepared transaction") ||
            reason.startsWith("Transaction is not ready for restart") ->
            "screen.updater363.restart.reason.transaction_not_prepared"
        reason.contains("updater helper JAR", ignoreCase = true) ->
            "screen.updater363.restart.reason.helper_unavailable"
        reason.contains("JVM argument", ignoreCase = true) ->
            "screen.updater363.restart.reason.jvm_arguments_unavailable"
        reason.startsWith("Experimental fast restart is disabled") ->
            "screen.updater363.restart.reason.fast_restart_disabled"
        reason.startsWith("Available memory is below the fast-restart safety margin") ->
            "screen.updater363.restart.low_memory"
        reason.startsWith("Unable to inspect restart support") ->
            "screen.updater363.restart.reason.inspect_failed"
        reason.startsWith("Unable to start fast restart") ->
            "screen.updater363.restart.reason.start_failed"
        reason.startsWith("Unable to schedule restart") ->
            "screen.updater363.restart.reason.schedule_failed"
        else -> null
    }

    private fun translated(key: String): String = Component.translatable(key).string
}
