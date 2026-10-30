package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.merge.FileAction
import com.github.fanziyun.updater.merge.UpdatePlan
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.progress.UpdatePhase
import com.github.fanziyun.updater.progress.UpdateProgressTracker
import com.github.fanziyun.updater.reload.HotReloadService
import com.github.fanziyun.updater.reload.ReloadReport
import com.github.fanziyun.updater.util.AtomicFiles
import com.github.fanziyun.updater.util.PathSafety
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
)

object UpdateExecutor {
    /**
     * Applies [plan]. The caller ([com.github.fanziyun.updater.UpdaterService.apply]) has already
     * reset the progress tracker with this plan's totals so the progress page starts from zero; this
     * method only publishes phases and per-file counters.
     */
    fun apply(plan: UpdatePlan, config: UpdaterConfig): ApplyResult {
        validateCurrentFiles(plan)
        check(!plan.hasConflicts) { "Resolve update conflicts before applying the update" }
        if (plan.requiresRestart) {
            UpdateProgressTracker.phase(UpdatePhase.PREPARE, plan.targetVersion)
            val timeoutMs = config.networkTimeoutSeconds.coerceIn(5, 120) * 1_000
            val transaction = UpdateTransactionManager(
                timeoutMs = timeoutMs,
                listener = UpdateProgressTracker.listener,
            ).prepare(plan, config)
            updateFabricLoader(plan, config, timeoutMs)
            UpdateProgressTracker.done(transaction.id)
            return ApplyResult(
                backup = null,
                reloaded = false,
                reloadFailures = emptyList(),
                transactionId = transaction.id,
                requiresRestart = true,
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
        // A loader-only bump has no jar changes, so it lands here; the new profile applies at next launch.
        updateFabricLoader(plan, config, config.networkTimeoutSeconds.coerceIn(5, 120) * 1_000)
        UpdateProgressTracker.done()
        return ApplyResult(backup, reload.attempted && reload.failures.isEmpty(), reload.failures)
    }

    /**
     * Overwrite-installs the Fabric loader version declared by the target mrpack.
     *
     * The transaction or configuration update has already been staged at this point, so a loader
     * failure is recorded and swallowed rather than aborting the run: the modpack update stays
     * usable on the current loader and the player sees why the loader step did not complete.
     */
    internal fun updateFabricLoader(
        plan: UpdatePlan,
        config: UpdaterConfig,
        timeoutMs: Int,
        gameDir: Path = Platform.INSTANCE.gameDir,
        loaderId: String = Platform.INSTANCE.loaderId,
        currentLoaderVersion: String = Platform.INSTANCE.loaderVersion,
    ) {
        if (!config.updateFabricLoader || loaderId != "fabric") return
        val target = plan.targetLoaderVersion
        if (!FabricLoaderUpdater.shouldUpdate(currentLoaderVersion, target)) return
        val minecraftVersion = plan.minecraftVersion.ifBlank {
            config.minecraftVersion.trim().ifBlank { Platform.INSTANCE.minecraftVersion }
        }
        try {
            val installed = FabricLoaderUpdater.update(
                gameDir = gameDir,
                minecraftVersion = minecraftVersion,
                currentLoaderVersion = currentLoaderVersion,
                targetLoaderVersion = target,
                timeoutMs = timeoutMs,
                listener = UpdateProgressTracker.listener,
            )
            if (installed != null) {
                Updater.LOGGER.info("Fabric loader {} is now installed over {}", installed, currentLoaderVersion)
            }
        } catch (exception: Exception) {
            Updater.LOGGER.error("Fabric loader update to {} failed", target, exception)
            UpdateProgressTracker.message("Fabric loader update failed: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }

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
