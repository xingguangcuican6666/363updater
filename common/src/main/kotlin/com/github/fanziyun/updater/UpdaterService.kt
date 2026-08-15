package com.github.fanziyun.updater

import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.data.ApplyResult
import com.github.fanziyun.updater.data.ModrinthClient
import com.github.fanziyun.updater.data.MrpackReader
import com.github.fanziyun.updater.data.UpdateExecutor
import com.github.fanziyun.updater.data.VersionResolver
import com.github.fanziyun.updater.data.VersionSelection
import com.github.fanziyun.updater.merge.PackageMerger
import com.github.fanziyun.updater.merge.UpdatePlan
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.screen.ClientScreens
import com.github.fanziyun.updater.screen.UpdatePromptScreen
import net.minecraft.client.gui.screens.TitleScreen
import me.shedaniel.autoconfig.AutoConfig
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer
import net.minecraft.client.gui.screens.Screen
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class UpdateState(
    val checking: Boolean = false,
    val hasUpdate: Boolean = false,
    val currentVersion: String = "",
    val targetVersion: String = "",
    val error: String = "",
)

object UpdaterService {
    private val initialized = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "363Updater-Worker").apply { isDaemon = true }
    }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "363Updater-Scheduler").apply { isDaemon = true }
    }
    private val stateRef = AtomicReference(UpdateState())
    private var checking: CompletableFuture<UpdateState>? = null
    @Volatile private var selection: VersionSelection? = null
    @Volatile private var plan: UpdatePlan? = null
    @Volatile private var promptVersion: String? = null
    @Volatile private var ignoredVersion: String? = null

    lateinit var config: UpdaterConfig
        private set

    val state: UpdateState get() = stateRef.get()

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        AutoConfig.register(UpdaterConfig::class.java) { definition, clazz -> GsonConfigSerializer(definition, clazz) }
        config = AutoConfig.getConfigHolder(UpdaterConfig::class.java).config
        if (config.autoCheck) {
            check()
            val interval = config.checkIntervalMinutes.coerceIn(5, 1440).toLong()
            scheduler.scheduleWithFixedDelay(
                { if (config.autoCheck) check() },
                interval,
                interval,
                TimeUnit.MINUTES,
            )
        }
    }

    fun configScreen(parent: Screen?): Screen {
        val owner = runCatching { Class.forName("me.shedaniel.autoconfig.AutoConfigClient") }
            .getOrElse { AutoConfig::class.java }
        val method = owner.methods.firstOrNull { it.name == "getConfigScreen" && it.parameterCount == 2 }
            ?: error("Cloth AutoConfig has no compatible getConfigScreen method")
        val supplier = method.invoke(null, UpdaterConfig::class.java, parent) as? java.util.function.Supplier<*>
            ?: error("Cloth AutoConfig getConfigScreen did not return a Supplier")
        return supplier.get() as? Screen ?: error("Cloth AutoConfig did not create a Screen")
    }

    fun check(force: Boolean = false): CompletableFuture<UpdateState> {
        synchronized(this) {
            checking?.takeIf { !it.isDone }?.let { return it }
            if (!force && !config.autoCheck) return CompletableFuture.completedFuture(state)
            selection = null
            plan = null
            stateRef.set(state.copy(checking = true, error = ""))
            val future = CompletableFuture.supplyAsync({
                val resolved = VersionResolver(modrinthClient(), config).resolve()
                selection = resolved
                val hasUpdate = VersionResolver.isNewer(resolved.target, resolved.current)
                UpdateState(
                    checking = false,
                    hasUpdate = hasUpdate,
                    currentVersion = resolved.current.number,
                    targetVersion = resolved.target.number,
                )
            }, executor).exceptionally { exception ->
                val cause = exception.cause ?: exception
                Updater.LOGGER.warn("Modrinth update check failed", cause)
                UpdateState(checking = false, error = cause.message ?: cause.javaClass.simpleName)
            }
            checking = future
            future.thenAccept { next ->
                stateRef.set(next)
                if (next.hasUpdate) schedulePrompt()
            }
            return future
        }
    }

    fun preview(): CompletableFuture<UpdatePlan> {
        val resolved = selection ?: return check(force = true).thenCompose { checked ->
            if (checked.error.isNotBlank()) {
                CompletableFuture.failedFuture(IllegalStateException(checked.error))
            } else {
                selection?.let(::preview)
                    ?: CompletableFuture.failedFuture(IllegalStateException("No update selection is available"))
            }
        }
        return preview(resolved)
    }

    private fun preview(resolved: VersionSelection): CompletableFuture<UpdatePlan> {
        return CompletableFuture.supplyAsync({
            val old = packagePath(resolved.current)
            val target = packagePath(resolved.target)
            val keepPackages = config.cachePackages
            try {
                val client = modrinthClient()
                if (!client.cachedFileValid(resolved.current, old)) client.download(resolved.current, old)
                if (!client.cachedFileValid(resolved.target, target)) client.download(resolved.target, target)
                val result = PackageMerger.preview(
                    MrpackReader.read(old, resolved.current.number),
                    MrpackReader.read(target, resolved.target.number),
                    config,
                ).copy(
                    project = resolved.project,
                    minecraftVersion = resolved.minecraftVersion,
                    loader = resolved.loader,
                    targetVersionId = resolved.target.id,
                )
                plan = result
                result
            } finally {
                if (!keepPackages) {
                    Files.deleteIfExists(old)
                    if (target != old) Files.deleteIfExists(target)
                }
            }
        }, executor)
    }

    fun apply(updatePlan: UpdatePlan = plan ?: error("Open the difference view before updating")): CompletableFuture<ApplyResult> =
        CompletableFuture.supplyAsync({
            val result = UpdateExecutor.apply(updatePlan, config)
            refreshConfigReference()
            plan = null
            stateRef.set(UpdateState(currentVersion = updatePlan.targetVersion, targetVersion = updatePlan.targetVersion))
            result
        }, executor)

    fun rollback(): CompletableFuture<Unit> = CompletableFuture.runAsync({
        UpdateExecutor.rollbackLatest()
        val holder = AutoConfig.getConfigHolder(UpdaterConfig::class.java)
        holder.load()
        config = holder.config
        selection = null
        plan = null
        stateRef.set(UpdateState())
    }, executor).thenApply { Unit }

    fun ignore() {
        ignoredVersion = state.targetVersion
        promptVersion = state.targetVersion
    }

    fun shouldPrompt(): Boolean = state.hasUpdate && ignoredVersion != state.targetVersion && promptVersion != state.targetVersion

    fun markPromptShown() { promptVersion = state.targetVersion }

    fun timeoutMs(): Int = config.networkTimeoutSeconds.coerceIn(5, 120) * 1_000

    private fun modrinthClient(): ModrinthClient = ModrinthClient(timeoutMs(), config.modrinthApiRoot)

    fun packagePath(version: com.github.fanziyun.updater.data.ModrinthVersion): Path {
        val project = config.modrinthProject.trim().ifBlank { "363fan" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val number = version.number.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return Platform.INSTANCE.gameDir.resolve(".cache").resolve(Updater.MOD_ID).resolve(project)
            .resolve("$number-${version.id}.mrpack")
    }

    private fun schedulePrompt() {
        ClientScreens.execute {
            if (shouldPrompt() && ClientScreens.current() is TitleScreen) {
                markPromptShown()
                ClientScreens.set(UpdatePromptScreen(ClientScreens.current()))
            }
        }
    }

    private fun refreshConfigReference() {
        config = AutoConfig.getConfigHolder(UpdaterConfig::class.java).config
    }
}
