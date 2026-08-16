package com.github.fanziyun.updater.screen

import kotlin.test.Test
import kotlin.test.assertEquals

class RestartReasonTextTest {
    @Test
    fun `known restart failures map to translation keys`() {
        assertEquals(
            "screen.updater363.restart.reason.jvm_arguments_unavailable",
            RestartReasonText.reasonKey("The current JVM arguments are unavailable"),
        )
        assertEquals(
            "screen.updater363.restart.reason.helper_unavailable",
            RestartReasonText.reasonKey("The copied updater helper JAR is unavailable"),
        )
        assertEquals(
            "screen.updater363.restart.reason.unsupported_profile",
            RestartReasonText.reasonKey(
                "Fast restart is unavailable for this Minecraft, loader, or operating-system profile",
            ),
        )
    }
}
