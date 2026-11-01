package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.handoff.CurrentJvmCommand
import com.github.fanziyun.updater.merge.FileAction
import com.github.fanziyun.updater.merge.UpdatePlan
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.progress.UpdatePhase
import com.github.fanziyun.updater.progress.UpdateProgressTracker
import com.github.fanziyun.updater.reload.HotReloadService
import com.github.fanziyun.updater.reload.ReloadReport
import com.github.fanziyun.updater.util.AtomicFiles
import com.github.fanziyun.updater.util.PathSafety
import com.github.fanziyun.updater.transaction.LoaderInstallRecord
import com.github.fanziyun.updater.transaction.UpdateTransactionManager
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

data class ApplyResult(
    val backup: BackupInfo?,
    val reloaded: Boolean,
    val reloadFailures: List<String>,
    val transactionId: String? = null,
    val requiresRestart: Boolean = false,
    /** Set when the loader was replaced; the player must restart through the launcher. */
    val loaderUpdate: LoaderUpdate? = null,
)

/** A loader version the updater installed over the running one. */
data class LoaderUpdate(val from: String, val to: String)

object UpdateExecutor {
    /**
     * Applies [plan]. The caller ([com.github.fanziyun.updater.UpdaterService.apply]) has already
     * reset the progress tracker with this plan's totals so the progress page starts from zero; this
     * method only publishes phases and per-file counters.
     */
    fun apply(plan: UpdatePlan, config: UpdaterConfig): ApplyResult {
        validateCurrentFiles(plan)
        check(!plan.hasConflicts) { "Resolve update conflicts before applying the update" }
        // The loader goes first and its failure is fatal when the option is on: a target pack whose
        // mods need the newer loader cannot boot on the old one, so committing the pack without it
        // leaves the instance unstartable. Updating the loader and then failing leaves a bootable
        // instance on the newer loader, which loaders tolerate.
        val loaderInstall = updateFabricLoader(plan, config, timeoutMs(config))
        if (plan.requiresRestart) {
            UpdateProgressTracker.phase(UpdatePhase.PREPARE, plan.targetVersion)
            val transaction = UpdateTransactionManager(
                timeoutMs = timeoutMs(config),
                listener = UpdateProgressTracker.listener,
            ).prepare(plan, config, loaderInstall)
            UpdateProgressTracker.done(transaction.id)
            return ApplyResult(
                backup = null,
                reloaded = false,
                reloadFailures = emptyList(),
                transactionId = transaction.id,
                requiresRestart = true,
                loaderUpdate = loaderInstall?.let { LoaderUpdate(it.previousVersion, it.installedVersion) },
            )
        }
        UpdateProgressTracker.phase(UpdatePhase.BACKUP)
        val backup = BackupManager.create(config.backupCount)
        try {
            UpdateProgressTracker.phase(UpdatePhase.WRITE)
            plan.changedFiles.forEach { file ->
                val target = PathSafety.resolveGamePath(file.relativePath)
                UpdateProgressTracker.file(file.relativePath, file.size)
                when (file.action) {
                    FileAction.WRITE -> AtomicFiles.write(target, file.bytes ?: error("Missing bytes for ${file.relativePath}"))
                    FileAction.DELETE -> Files.deleteIfExists(target)
                    FileAction.UNCHANGED -> Unit
                }
                UpdateProgressTracker.completeItem(file.relativePath, file.size)
            }
            VersionTracker.write(
                config = config,
                version = plan.targetVersion,
                versionId = plan.targetVersionId,
                project = plan.project.ifBlank { config.modrinthProject.trim().ifBlank { "363fan" } },
                minecraftVersion = plan.minecraftVersion.ifBlank {
                    config.minecraftVersion.trim().ifBlank { com.github.fanziyun.updater.platform.Platform.INSTANCE.minecraftVersion }
                },
                loader = plan.loader.ifBlank {
                    config.loader.trim().ifBlank { com.github.fanziyun.updater.platform.Platform.INSTANCE.loaderId }
                },
                managedFiles = plan.managedFiles,
            )
        } catch (exception: Exception) {
            UpdateProgressTracker.failed(exception.message ?: exception.javaClass.simpleName)
            val rollbackFailure = runCatching { BackupManager.restore(backup) }.exceptionOrNull()
            if (rollbackFailure != null) {
                Updater.LOGGER.error("Updater rollback failed", rollbackFailure)
                exception.addSuppressed(rollbackFailure)
                throw IllegalStateException("Updater failed and the automatic restore also failed", exception)
            }
            throw IllegalStateException("Updater failed and restored the previous configuration", exception)
        }

        UpdateProgressTracker.phase(UpdatePhase.RELOAD)
        val reload = runCatching { HotReloadService.reload(config.experimentalHotReload) }
            .getOrElse { ReloadReport(true, listOf("Hot reload: ${it.message ?: it.javaClass.simpleName}")) }
        UpdateProgressTracker.done()
        return ApplyResult(
            backup = backup,
            reloaded = reload.attempted && reload.failures.isEmpty(),
            reloadFailures = reload.failures,
            loaderUpdate = loaderInstall?.let { LoaderUpdate(it.previousVersion, it.installedVersion) },
        )
    }

    private fun timeoutMs(config: UpdaterConfig): Int = config.networkTimeoutSeconds.coerceIn(5, 120) * 1_000

    /**
     * Installs the loader version the target mrpack declares, and returns what to record.
     *
     * This runs before any pack file is written: a target pack whose mods require the newer loader
     * cannot boot on the old one, so committing the pack without the loader would leave the instance
     * unstartable, while updating the loader first leaves it bootable either way. A failure is fatal
     * when the option is on for the same reason — the caller must not commit a pack it cannot run.
     *
     * Only two facts come from the running process: the classpath it resolved (which names the
     * `libraries/` root to install into) and its loader version. Nothing launcher-specific is read or
     * written; the relaunch rebuilds its classpath from those same argv-derived facts.
     */
    internal fun updateFabricLoader(
        plan: UpdatePlan,
        config: UpdaterConfig,
        timeoutMs: Int,
        gameDir: Path = Platform.INSTANCE.gameDir,
        loaderId: String = Platform.INSTANCE.loaderId,
        currentLoaderVersion: String = Platform.INSTANCE.loaderVersion,
        classpath: List<String> = currentClasspath(),
    ): LoaderInstallRecord? {
        if (!config.updateFabricLoader || loaderId != "fabric") return null
        val target = plan.targetLoaderVersion
        if (!FabricLoaderUpdater.shouldUpdate(currentLoaderVersion, target)) return null
        val minecraftVersion = plan.minecraftVersion.ifBlank {
            config.minecraftVersion.trim().ifBlank { Platform.INSTANCE.minecraftVersion }
        }
        val librariesRoot = FabricLoaderUpdater.resolveLibrariesRoot(gameDir, classpath)
        val install = try {
            FabricLoaderUpdater.install(
                minecraftVersion = minecraftVersion,
                currentLoaderVersion = currentLoaderVersion,
                targetLoaderVersion = target,
                librariesRoot = librariesRoot,
                timeoutMs = timeoutMs,
                listener = UpdateProgressTracker.listener,
            )
        } catch (exception: Exception) {
            UpdateProgressTracker.failed(exception.message ?: exception.javaClass.simpleName)
            throw IllegalStateException(
                "Unable to install Fabric loader $target into $librariesRoot; the update was not applied",
                exception,
            )
        } ?: return null
        Updater.LOGGER.info("Fabric loader {} replaced {}", install.resolveLoaderVersion(), currentLoaderVersion)
        return LoaderInstallRecord(
            previousVersion = currentLoaderVersion,
            installedVersion = install.resolveLoaderVersion().orEmpty(),
            libraries = install.libraries.map { it.name },
            librariesRoot = install.librariesRoot,
            mainClass = install.mainClass,
        )
    }

    /** The classpath the running JVM was started with; the only launcher-neutral source for it. */
    internal fun currentClasspath(): List<String> = runCatching {
        CurrentJvmCommand.capture().classpathEntries()
    }.getOrDefault(emptyList())

    fun rollbackLatest() {
        BackupManager.latest()?.let(BackupManager::restore)
            ?: throw IllegalStateException("No updater backup is available")
    }

    internal fun validateCurrentFiles(plan: UpdatePlan, gameDir: Path = Platform.INSTANCE.gameDir) {
        check(!plan.hasConflicts) { "Resolve update conflicts before applying the update" }
        plan.changedFiles.forEach { file ->
            val path = if (file.managedMod) {
                PathSafety.resolveManagedPath(gameDir, file.relativePath)
            } else {
                PathSafety.resolveUnder(gameDir, file.relativePath)
            }
            require(
                !Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
            ) { "Updater only supports regular files: ${file.relativePath}" }
            val currentExists = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            val expectedHashes = file.expectedCurrentHashes
            val unchanged = if (file.managedMod) {
                when {
                    expectedHashes == null -> !currentExists
                    !currentExists -> false
                    else -> expectedHashes.matches(path)
                }
            } else {
                val current = path.takeIf { currentExists }?.let(Files::readAllBytes)
                val expected = file.expectedCurrent
                when {
                    current == null && expected == null -> true
                    current != null && expected != null -> current.contentEquals(expected)
                    else -> false
                }
            }
            check(unchanged) {
                "${file.relativePath} changed after the update preview; reopen the difference view"
            }
        }
    }
}
