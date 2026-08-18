package com.github.fanziyun.updater.platform

object RuntimeEnvironment {
    val isAndroid: Boolean
        get() = isAndroid(System::getProperty) { classAvailable("android.os.Build") }

    internal fun isAndroid(
        property: (String) -> String?,
        androidBuildClassAvailable: () -> Boolean,
    ): Boolean {
        val runtimeDetails = listOf(
            property("java.runtime.name"),
            property("java.vm.name"),
            property("java.vm.vendor"),
            property("java.vendor"),
        ).filterNotNull().joinToString(" ").lowercase()
        return "android" in runtimeDetails || "dalvik" in runtimeDetails || androidBuildClassAvailable()
    }

    private fun classAvailable(name: String): Boolean = runCatching {
        Class.forName(name, false, RuntimeEnvironment::class.java.classLoader)
    }.isSuccess
}
