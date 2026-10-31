package com.github.fanziyun.updater.handoff

import com.github.fanziyun.updater.BuildInfo
import com.github.fanziyun.updater.UpdaterService
import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.data.VersionTracker
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.screen.ClientScreens
import com.github.fanziyun.updater.transaction.PreparedTransaction
import com.github.fanziyun.updater.transaction.TransactionStage
import com.github.fanziyun.updater.transaction.UpdateTransactionManager
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

enum class RestartMode { FAST, AUTOMATIC, DEFERRED }

enum class RestartProgressStage {
    PREPARING,
    WAITING_FOR_CHILD,
    FIRST_FRAME,
    STABILIZING,
    READY,
    TAKING_OVER,
    WAITING_FOR_EXIT,
    FAILED,
}

data class RestartProgress(
    val stage: RestartProgressStage,
    val message: String = "",
    val childPid: Long = -1L,
    val elapsedMillis: Long = 0L,
)

data class RestartCapabilities(
    val fastAvailable: Boolean,
    val fastReason: String = "",
    val lowMemory: Boolean = false,
    val automaticAvailable: Boolean,
    val automaticReason: String = "",
    val deferredAvailable: Boolean,
    /**
     * True when this transaction replaced the loader, so only the launcher can start the new one.
     * [loaderInstalledVersion] names it for the message the player needs to read.
     */
    val loaderRestartRequired: Boolean = false,
    val loaderInstalledVersion: String = "",
)

class RestartSession internal constructor() {
    private val progressRef = AtomicReference(RestartProgress(RestartProgressStage.PREPARING))
    val completion: CompletableFuture<Unit> = CompletableFuture()
    val progress: RestartProgress get() = progressRef.get()

    fun update(progress: RestartProgress) {
        progressRef.set(progress)
    }

    fun fail(message: String, cause: Throwable? = null) {
        val safe = message.take(500)
        progressRef.set(RestartProgress(RestartProgressStage.FAILED, safe))
        if (cause == null) completion.completeExceptionally(IllegalStateException(safe))
        else completion.completeExceptionally(IllegalStateException(safe, cause))
    }
}

object RestartCoordinator {
    fun capabilities(transaction: PreparedTransaction): RestartCapabilities {
        val helperAvailable = transaction.helperJar?.let {
            Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(it)
        } == true
        val command = runCatching { CurrentJvmCommand.capture() }
        val commandError = command.exceptionOrNull()?.message
        // Both in-process restarts relaunch from the captured JVM command line, which pins the old
        // loader jar on the classpath. A loader update only takes effect through the launcher, which
        // re-reads the patched profile, so relaunching here would start the new mods on the old
        // loader and fail dependency resolution.
        val loaderChanged = transaction.record.loaderInstalledVersion.isNotBlank()
        val loaderReason = if (loaderChanged) {
            "The launcher must restart this instance to load fabric-loader " +
                transaction.record.loaderInstalledVersion
        } else ""
        val automaticAvailable = helperAvailable && commandError == null && !loaderChanged
        val supported = FastRestartPlatformPolicy.supportsProfile(
            BuildInfo.minecraftVersion,
            Platform.INSTANCE.loaderId,
            System.getProperty("os.name", ""),
        )
        val fastStageReady = transaction.record.stage == TransactionStage.PREPARED
        val fastCommandError = if (helperAvailable && fastStageReady && command.isSuccess && !loaderChanged) {
            runCatching {
                StagedModsLaunchStrategy.prepare(
                    command.getOrThrow(),
                    Platform.INSTANCE.loaderId,
                    transaction.generationMods,
                    requireNotNull(transaction.helperJar),
                )
            }.exceptionOrNull()?.message
        } else commandError
        val fastReason = when {
            loaderChanged -> loaderReason
            !supported -> "Fast restart is unavailable for this Minecraft, loader, or operating-system profile"
            !fastStageReady -> "Fast restart requires a newly prepared transaction"
            !helperAvailable -> "The copied updater helper JAR is unavailable"
            fastCommandError != null -> fastCommandError
            else -> ""
        }
        val metrics = VersionTracker.readInstallation(
            transaction.record.project,
            transaction.record.minecraftVersion,
            transaction.record.loader,
        )?.metrics
        return RestartCapabilities(
            fastAvailable = supported && fastStageReady && helperAvailable && fastCommandError == null && !loaderChanged,
            fastReason = fastReason,
            lowMemory = supported && RestartLaunchPolicy.lowMemory(metrics?.peakCombinedRssBytes ?: 0L),
            automaticAvailable = automaticAvailable,
            automaticReason = when {
                loaderChanged -> loaderReason
                !helperAvailable -> "The copied updater helper JAR is unavailable"
                commandError != null -> commandError
                else -> ""
            },
            deferredAvailable = helperAvailable,
            loaderRestartRequired = loaderChanged,
            loaderInstalledVersion = transaction.record.loaderInstalledVersion,
        )
    }

    fun start(
        transaction: PreparedTransaction,
        config: UpdaterConfig,
        mode: RestartMode,
        allowLowMemory: Boolean = false,
    ): RestartSession {
        val session = RestartSession()
        val capabilities = capabilities(transaction)
        // Both of these relaunch from the captured JVM command line, which pins the loader jar the
        // game is running now. After a loader update only the launcher can pick the new one up, so
        // they are refused rather than silently booting the new mods on the old loader.
        val loaderChanged = transaction.record.loaderInstalledVersion.isNotBlank()
        when (mode) {
            RestartMode.FAST -> {
                if (loaderChanged) {
                    session.fail(capabilities.fastReason)
                    return session
                }
                if (!config.experimentalFastRestart) {
                    session.fail("Experimental fast restart is disabled")
                    return session
                }
                if (!capabilities.fastAvailable) {
                    session.fail(capabilities.fastReason)
                    return session
                }
                if (capabilities.lowMemory && !allowLowMemory) {
                    session.fail("Available memory is below the fast-restart safety margin")
                    return session
                }
                startFast(transaction, config, session)
            }
            RestartMode.AUTOMATIC -> {
                if (loaderChanged) {
                    session.fail(capabilities.automaticReason)
                    return session
                }
                if (!capabilities.automaticAvailable) {
                    session.fail(capabilities.automaticReason)
                    return session
                }
                startNormal(transaction, relaunch = true, session)
            }
            RestartMode.DEFERRED -> {
                if (!capabilities.deferredAvailable) {
                    session.fail("The copied updater helper JAR is unavailable")
                    return session
                }
                startNormal(transaction, relaunch = false, session)
            }
        }
        return session
    }

    private fun startFast(transaction: PreparedTransaction, config: UpdaterConfig, session: RestartSession) {
        CompletableFuture.runAsync {
            try {
                val type = Class.forName("com.github.fanziyun.updater.handoff.FastRestartCoordinator")
                val method = type.getMethod(
                    "start",
                    PreparedTransaction::class.java,
                    UpdaterConfig::class.java,
                    RestartSession::class.java,
                )
                method.invoke(null, transaction, config, session)
            } catch (exception: Exception) {
                val cause = exception.cause ?: exception
                session.fail(cause.message ?: "Unable to start fast restart", cause)
            }
        }
    }

    private fun startNormal(transaction: PreparedTransaction, relaunch: Boolean, session: RestartSession) {
        CompletableFuture.runAsync {
            val manager = UpdateTransactionManager()
            var activated: PreparedTransaction? = null
            try {
                check(transaction.record.stage == TransactionStage.PREPARED || transaction.record.stage == TransactionStage.COMMIT_FAILED) {
                    "Transaction is not ready for restart: ${transaction.record.stage}"
                }
                activated = manager.activateConfiguration(transaction)
                val command = if (relaunch) CurrentJvmCommand.capture().let {
                    if (HandoffChildSession.active) it.withoutUpdaterHandoffArguments() else it
                } else null
                val request = HelperLaunchRequest(
                    if (relaunch) HelperLaunchRequest.Mode.NORMAL_AUTOMATIC else HelperLaunchRequest.Mode.NORMAL_DEFERRED,
                    ProcessHandle.current().pid(),
                    0,
                    "",
                    activated.id,
                    activated.path,
                    Platform.INSTANCE.gameDir,
                    command,
                )
                HelperProcessLauncher.start(activated.helperJar ?: error("Missing copied updater helper JAR"), request)
                session.update(RestartProgress(
                    RestartProgressStage.WAITING_FOR_EXIT,
                    if (relaunch) "The replacement client will start after this process exits"
                    else "The update will be committed after this process exits",
                ))
                ClientScreens.execute { Minecraft.getInstance().stop() }
            } catch (exception: Exception) {
                activated?.let { runCatching { manager.rollbackBeforeReady(it) } }
                session.fail(exception.message ?: "Unable to schedule restart", exception)
            }
        }
    }
}
