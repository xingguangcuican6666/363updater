package com.github.fanziyun.updater.util

import kotlin.test.Test
import kotlin.test.assertTrue

class SemVerTest {
    @Test
    fun `orders normal and prerelease versions`() {
        assertTrue(SemVer.compare("0.21.1", "0.20.2") > 0)
        assertTrue(SemVer.compare("1.0.0", "1.0.0-beta.2") > 0)
        assertTrue(SemVer.compare("1.0.0-beta.10", "1.0.0-beta.2") > 0)
        assertTrue(SemVer.compare("v26.2", "26.1.2") > 0)
    }
}
