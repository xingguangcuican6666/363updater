package com.github.fanziyun.updater

import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.data.ApplyResult
import com.github.fanziyun.updater.data.ModrinthClient
import com.github.fanziyun.updater.data.ManagedFileCache
import com.github.fanziyun.updater.data.MrpackReader
import com.github.fanziyun.updater.data.UpdateExecutor
import com.github.fanziyun.updater.data.VersionResolver
import com.github.fanziyun.updater.data.VersionSelection
import com.github.fanziyun.updater.data.VersionTracker
import com.github.fanziyun.updater.merge.MergeOptions
import com.github.fanziyun.updater.merge.PackageMerger
import com.github.fanziyun.updater.merge.UpdatePlan
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.handoff.HandoffChildSession
import com.github.fanziyun.updater.handoff.HandoffProtocol
import com.github.fanziyun.updater.handoff.RestartCapabilities
import com.github.fanziyun.updater.handoff.RestartCoordinator
import com.github.fanziyun.updater.handoff.RestartMode
import com.github.fanziyun.updater.handoff.RestartSession
import com.github.fanziyun.updater.screen.ClientScreens
import com.github.fanziyun.updater.screen.UpdatePromptScreen
import com.github.fanziyun.updater.transaction.PreparedTransaction
import com.github.fanziyun.updater.transaction.UpdateTransactionManager
import net.minecraft.client.gui.screens.TitleScreen
import me.shedaniel.autoconfig.AutoConfig
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer
import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
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
    @Volatile private var activeTransactionId: String? = null

    lateinit var config: UpdaterConfig
        private set

    val state: UpdateState get() = stateRef.get()

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        AutoConfig.register(UpdaterConfig::class.java) { definition, clazz -> GsonConfigSerializer(definition, clazz) }
        config = AutoConfig.getConfigHolder(UpdaterConfig::class.java).config
        recoverHelperCommits()
        if (HandoffChildSession.active) {
            activeTransactionId = HandoffChildSession.transactionId
            HandoffChildSession.start(Platform.INSTANCE.loaderId)
            return
        }
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
        val holder = AutoConfig.getConfigHolder(UpdaterConfig::class.java)
        val value = holder.config
        val builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable("text.autoconfig.updater363.title"))
            .setDoesConfirmSave(false)
        val entries = ConfigEntryBuilder.create()
        val basic = builder.getOrCreateCategory(Component.translatable("text.autoconfig.updater363.category.basic"))
        val advanced = builder.getOrCreateCategory(Component.translatable("text.autoconfig.updater363.category.advanced"))
        val restart = builder.getOrCreateCategory(Component.translatable("text.autoconfig.updater363.category.restart"))

        fun string(category: me.shedaniel.clothconfig2.api.ConfigCategory, key: String, current: String, save: (String) -> Unit) {
            category.addEntry(entries.startStrField(Component.translatable("text.autoconfig.updater363.option.$key"), current)
                .setSaveConsumer(save)
                .build())
        }

        fun toggle(category: me.shedaniel.clothconfig2.api.ConfigCategory, key: String, current: Boolean, save: (Boolean) -> Unit) {
            category.addEntry(entries.startBooleanToggle(Component.translatable("text.autoconfig.updater363.option.$key"), current)
                .setSaveConsumer(save)
                .build())
        }

        fun integer(category: me.shedaniel.clothconfig2.api.ConfigCategory, key: String, current: Int, min: Int, max: Int, save: (Int) -> Unit) {
            category.addEntry(entries.startIntSlider(Component.translatable("text.autoconfig.updater363.option.$key"), current, min, max)
                .setSaveConsumer(save)
                .build())
        }

        string(basic, "modrinthProject", value.modrinthProject) { value.modrinthProject = it }
        string(basic, "minecraftVersion", value.minecraftVersion) { value.minecraftVersion = it }
        string(basic, "loader", value.loader) { value.loader = it }
        string(basic, "versionChannels", value.versionChannels) { value.versionChannels = it }
        toggle(basic, "autoCheck", value.autoCheck) { value.autoCheck = it }
        integer(basic, "checkIntervalMinutes", value.checkIntervalMinutes, 5, 1440) { value.checkIntervalMinutes = it }

        integer(advanced, "networkTimeoutSeconds", value.networkTimeoutSeconds, 5, 120) { value.networkTimeoutSeconds = it }
        string(advanced, "modrinthApiRoot", value.modrinthApiRoot) { value.modrinthApiRoot = it }
        integer(advanced, "backupCount", value.backupCount, 1, 10) { value.backupCount = it }
        toggle(advanced, "cachePackages", value.cachePackages) { value.cachePackages = it }
        toggle(advanced, "allowTargetDeletes", value.allowTargetDeletes) { value.allowTargetDeletes = it }
        toggle(advanced, "allowUnknownFormatReplacement", value.allowUnknownFormatReplacement) { value.allowUnknownFormatReplacement = it }
        toggle(advanced, "experimentalHotReload", value.experimentalHotReload) { value.experimentalHotReload = it }
        string(advanced, "currentVersionOverride", value.currentVersionOverride) { value.currentVersionOverride = it }
        toggle(advanced, "syncChangelog363Version", value.syncChangelog363Version) { value.syncChangelog363Version = it }
        string(advanced, "targetVersionOverride", value.targetVersionOverride) { value.targetVersionOverride = it }

        toggle(restart, "updateManagedMods", value.updateManagedMods) { value.updateManagedMods = it }
        if (supportsFastRestartProfile()) {
            toggle(restart, "experimentalFastRestart", value.experimentalFastRestart) { value.experimentalFastRestart = it }
        }
        toggle(restart, "trimOldProcessDuringRestart", value.trimOldProcessDuringRestart) { value.trimOldProcessDuringRestart = it }
        builder.setSavingRunnable { holder.save() }
        return builder.build()
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
                val contentCache = ManagedFileCache(timeoutMs())
                val oldSnapshot = contentCache.materializeForPreview(MrpackReader.read(old, resolved.current.number))
                val targetSnapshot = contentCache.materializeForPreview(MrpackReader.read(target, resolved.target.number))
                val installation = VersionTracker.readInstallation(
                    resolved.project,
                    resolved.minecraftVersion,
                    resolved.loader,
                )
                val result = PackageMerger.preview(
                    oldSnapshot,
                    targetSnapshot,
                    MergeOptions(
                        allowTargetDeletes = config.allowTargetDeletes,
                        allowUnknownFormatReplacement = config.allowUnknownFormatReplacement,
                        updateManagedMods = config.updateManagedMods,
                        installedManagedFiles = installation?.managedFiles.orEmpty(),
                        protectedPaths = Platform.INSTANCE.protectedModPaths,
                    ),
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
            if (result.requiresRestart) {
                activeTransactionId = result.transactionId
            } else {
                stateRef.set(UpdateState(currentVersion = updatePlan.targetVersion, targetVersion = updatePlan.targetVersion))
            }
            result
        }, executor)

    fun activeTransaction(): PreparedTransaction? = activeTransactionId?.let { id ->
        runCatching { transactionManager().load(id) }.getOrNull()
    }

    fun activateTransaction(id: String = activeTransactionId ?: error("No updater transaction is active")):
        CompletableFuture<PreparedTransaction> = CompletableFuture.supplyAsync({
            transactionManager().activateConfiguration(transactionManager().load(id))
        }, executor)

    fun commitTransaction(id: String = activeTransactionId ?: error("No updater transaction is active")):
        CompletableFuture<PreparedTransaction> = CompletableFuture.supplyAsync({
            val committed = transactionManager().commit(transactionManager().load(id), config)
            activeTransactionId = null
            refreshConfigReference()
            stateRef.set(UpdateState(currentVersion = committed.record.targetVersion, targetVersion = committed.record.targetVersion))
            committed
        }, executor)

    fun rollbackTransaction(id: String = activeTransactionId ?: error("No updater transaction is active")):
        CompletableFuture<PreparedTransaction> = CompletableFuture.supplyAsync({
            transactionManager().rollbackBeforeReady(transactionManager().load(id))
        }, executor)

    fun pendingTransactions(): List<PreparedTransaction> = transactionManager().pending()

    @Synchronized
    fun trimForRestart() {
        checking?.cancel(true)
        checking = null
        selection = null
        plan = null
    }

    fun restartCapabilities(id: String = activeTransactionId ?: error("No updater transaction is active")): RestartCapabilities {
        val manager = transactionManager()
        return RestartCoordinator.capabilities(manager.refreshUpdaterCopies(manager.load(id)))
    }

    fun startRestart(
        mode: RestartMode,
        allowLowMemory: Boolean = false,
        id: String = activeTransactionId ?: error("No updater transaction is active"),
    ): RestartSession {
        val manager = transactionManager()
        return RestartCoordinator.start(manager.refreshUpdaterCopies(manager.load(id)), config, mode, allowLowMemory)
    }

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

    fun shouldPrompt(): Boolean = activeTransactionId == null && state.hasUpdate &&
        ignoredVersion != state.targetVersion && promptVersion != state.targetVersion

    fun markPromptShown() { promptVersion = state.targetVersion }

    fun timeoutMs(): Int = config.networkTimeoutSeconds.coerceIn(5, 120) * 1_000

    private fun modrinthClient(): ModrinthClient = ModrinthClient(timeoutMs(), config.modrinthApiRoot)

    private fun transactionManager(): UpdateTransactionManager = UpdateTransactionManager(timeoutMs = timeoutMs())

    fun packagePath(version: com.github.fanziyun.updater.data.ModrinthVersion): Path {
        val project = config.modrinthProject.trim().ifBlank { "363fan" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val number = version.number.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return Platform.INSTANCE.gameDir.resolve(".cache").resolve(Updater.MOD_ID).resolve(project)
            .resolve("$number-${version.id}.mrpack")
    }

    private fun schedulePrompt() {
        ClientScreens.execute {
            if (activeTransactionId == null && shouldPrompt() && ClientScreens.current() is TitleScreen) {
                markPromptShown()
                ClientScreens.set(UpdatePromptScreen(ClientScreens.current()))
            }
        }
    }

    private fun refreshConfigReference() {
        config = AutoConfig.getConfigHolder(UpdaterConfig::class.java).config
    }

    private fun supportsFastRestartProfile(): Boolean =
        com.github.fanziyun.updater.handoff.FastRestartPlatformPolicy.supportsProfile(
            com.github.fanziyun.updater.BuildInfo.minecraftVersion,
            Platform.INSTANCE.loaderId,
            System.getProperty("os.name", ""),
        )

    private fun recoverHelperCommits() {
        val manager = transactionManager()
        val requested = System.getenv(HandoffProtocol.ENV_RECOVERY_TRANSACTION)
        val candidates = buildList {
            requested?.takeIf(String::isNotBlank)?.let { id -> runCatching { manager.load(id) }.getOrNull()?.let(::add) }
            manager.pending().forEach { transaction -> if (none { it.id == transaction.id }) add(transaction) }
        }
        candidates.forEach { original ->
            when {
                manager.hasExternalCommit(original) -> runCatching { manager.commit(original, config) }
                    .onFailure { Updater.LOGGER.error("Unable to finalize updater transaction {}", original.id, it) }
                else -> {
                    val transaction = runCatching { manager.refreshUpdaterCopies(original) }
                        .onFailure { Updater.LOGGER.warn("Unable to refresh updater copies in transaction {}", original.id, it) }
                        .getOrDefault(original)
                    if (manager.helperFailure(transaction) != null || activeTransactionId == null) {
                        activeTransactionId = transaction.id
                    }
                }
            }
        }
    }
}
