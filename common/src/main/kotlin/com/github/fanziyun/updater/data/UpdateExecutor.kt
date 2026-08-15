package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.merge.FileAction
import com.github.fanziyun.updater.merge.UpdatePlan
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.reload.HotReloadService
import com.github.fanziyun.updater.reload.ReloadReport
import com.github.fanziyun.updater.util.AtomicFiles
import com.github.fanziyun.updater.util.PathSafety
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

data class ApplyResult(
    val backup: BackupInfo,
    val reloaded: Boolean,
    val reloadFailures: List<String>,
)

object UpdateExecutor {
    fun apply(plan: UpdatePlan, config: UpdaterConfig): ApplyResult {
        validateCurrentFiles(plan)
        val backup = BackupManager.create(config.backupCount)
        try {
            plan.changedFiles.forEach { file ->
                val target = PathSafety.resolveGamePath(file.relativePath)
                when (file.action) {
                    FileAction.WRITE -> AtomicFiles.write(target, file.bytes ?: error("Missing bytes for ${file.relativePath}"))
                    FileAction.DELETE -> Files.deleteIfExists(target)
                    FileAction.UNCHANGED -> Unit
                }
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
            )
        } catch (exception: Exception) {
            val rollbackFailure = runCatching { BackupManager.restore(backup) }.exceptionOrNull()
            if (rollbackFailure != null) {
                Updater.LOGGER.error("Updater rollback failed", rollbackFailure)
                exception.addSuppressed(rollbackFailure)
                throw IllegalStateException("Updater failed and the automatic restore also failed", exception)
            }
            throw IllegalStateException("Updater failed and restored the previous configuration", exception)
        }

        val reload = runCatching { HotReloadService.reload(config.experimentalHotReload) }
            .getOrElse { ReloadReport(true, listOf("Hot reload: ${it.message ?: it.javaClass.simpleName}")) }
        return ApplyResult(backup, reload.attempted && reload.failures.isEmpty(), reload.failures)
    }

    fun rollbackLatest() {
        BackupManager.latest()?.let(BackupManager::restore)
            ?: throw IllegalStateException("No updater backup is available")
    }

    internal fun validateCurrentFiles(plan: UpdatePlan, gameDir: Path = Platform.INSTANCE.gameDir) {
        plan.changedFiles.forEach { file ->
            val path = PathSafety.resolveUnder(gameDir, file.relativePath)
            require(
                !Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
            ) { "Updater only supports regular files: ${file.relativePath}" }
            val current = path.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                ?.let(Files::readAllBytes)
            val expected = file.expectedCurrent
            val unchanged = when {
                current == null && expected == null -> true
                current != null && expected != null -> current.contentEquals(expected)
                else -> false
            }
            check(unchanged) {
                "${file.relativePath} changed after the update preview; reopen the difference view"
            }
        }
    }
}
