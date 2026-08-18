package com.github.fanziyun.updater.config

import kotlin.test.Test
import kotlin.test.assertTrue

class UpdaterConfigTest {
    @Test
    fun `hot reload is enabled by default`() {
        assertTrue(UpdaterConfig().experimentalHotReload)
    }
}
