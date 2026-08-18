package com.github.fanziyun.updater.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeEnvironmentTest {
    @Test
    fun `detects Android runtime properties`() {
        assertTrue(
            RuntimeEnvironment.isAndroid(
                property = { key -> if (key == "java.vm.name") "Dalvik" else null },
                androidBuildClassAvailable = { false },
            ),
        )
    }

    @Test
    fun `detects Android build class when runtime properties are inconclusive`() {
        assertTrue(
            RuntimeEnvironment.isAndroid(
                property = { null },
                androidBuildClassAvailable = { true },
            ),
        )
    }

    @Test
    fun `does not classify a standard JVM as Android`() {
        assertFalse(
            RuntimeEnvironment.isAndroid(
                property = { key -> if (key == "java.vm.name") "OpenJDK 64-Bit Server VM" else null },
                androidBuildClassAvailable = { false },
            ),
        )
    }
}
