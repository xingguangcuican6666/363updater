package com.github.fanziyun.updater.reload

import com.github.fanziyun.updater.Updater
import me.shedaniel.autoconfig.ConfigData
import net.minecraft.client.Minecraft
import java.util.ServiceLoader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

interface ConfigReloader {
    val id: String
    fun reload(): Boolean
}

data class ReloadReport(val attempted: Boolean, val failures: List<String>)

object HotReloadService {
    private const val INVOCATION_TIMEOUT_SECONDS = 10L
    private const val RELOAD_TIMEOUT_SECONDS = 60L

    fun reload(enabled: Boolean): ReloadReport {
        if (!enabled) return ReloadReport(false, emptyList())
        val failures = mutableListOf<String>()
        var attempted = false

        attempted = true
        runCatching { onClientThread { MinecraftReloadBridge.reloadOptions() } }
            .onFailure { failures += "Minecraft options: ${it.message ?: it.javaClass.simpleName}" }

        runCatching { invokeResourceReload() }
            .onFailure { failures += "Minecraft resource reload: ${it.message ?: it.javaClass.simpleName}" }

        runCatching { reloadAutoConfigHolders() }
            .onFailure { failures += "Cloth AutoConfig holders: ${it.message ?: it.javaClass.simpleName}" }

        runCatching { refreshChangelog363() }
            .onFailure { failures += "363Changelog: ${it.message ?: it.javaClass.simpleName}" }

        runCatching {
            ServiceLoader.load(ConfigReloader::class.java, ConfigReloader::class.java.classLoader).forEach { reloader ->
                attempted = true
                runCatching { if (!reloader.reload()) failures += reloader.id }
                    .onFailure { failures += "${reloader.id}: ${it.message ?: it.javaClass.simpleName}" }
            }
        }.onFailure { failures += "Config reloader discovery: ${it.message ?: it.javaClass.simpleName}" }
        if (failures.isNotEmpty()) Updater.LOGGER.warn("Some updater reload attempts failed: {}", failures)
        return ReloadReport(attempted, failures)
    }

    private fun invokeResourceReload() {
        val result = onClientThread { MinecraftReloadBridge.reloadResources() }
        result.get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun reloadAutoConfigHolders() {
        val autoConfig = Class.forName("me.shedaniel.autoconfig.AutoConfig")
        val holdersField = autoConfig.getDeclaredField("holders")
        holdersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val holders = holdersField.get(null) as? Map<Any, Any> ?: return
        holders.values.forEach { holder ->
            val load = holder.javaClass.methods.firstOrNull { it.name == "load" && it.parameterCount == 0 }
                ?: throw NoSuchMethodException("Config holder load()")
            val loaded = load.invoke(holder) as? Boolean
            if (loaded == false) throw IllegalStateException("Config holder reported a failed load")
        }
    }

    private fun refreshChangelog363() {
        val serviceClass = listOf(
            "com.github.fanziyun.ChangelogService",
            "com.github.fanziyun.client.ChangelogService",
        ).firstNotNullOfOrNull { name -> runCatching { Class.forName(name) }.getOrNull() } ?: return
        val service = serviceClass.getField("INSTANCE").get(null)

        val configField = serviceClass.getDeclaredField("config").apply { isAccessible = true }
        val currentConfig = configField.get(service) ?: return
        val configClass = currentConfig.javaClass
        @Suppress("UNCHECKED_CAST")
        val holder = me.shedaniel.autoconfig.AutoConfig.getConfigHolder(
            configClass as Class<ConfigData>,
        )
        holder.load()
        configField.set(service, holder.config)

        val ensure = serviceClass.methods.firstOrNull {
            it.name == "ensureChangelogLoaded" && it.parameterCount == 1 && it.parameterTypes[0] == Boolean::class.javaPrimitiveType
        } ?: return
        val refreshed = onClientThread { ensure.invoke(service, false) }
        if (refreshed is CompletableFuture<*>) refreshed.get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun <T> onClientThread(action: () -> T): T {
        val minecraft = Minecraft.getInstance()
        if (minecraft.isSameThread) return action()
        val invocation = CompletableFuture<T>()
        minecraft.execute {
            runCatching(action)
                .onSuccess(invocation::complete)
                .onFailure(invocation::completeExceptionally)
        }
        return invocation.get(INVOCATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }
}
