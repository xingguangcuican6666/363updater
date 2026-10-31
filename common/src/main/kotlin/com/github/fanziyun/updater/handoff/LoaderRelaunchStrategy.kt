package com.github.fanziyun.updater.handoff

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.data.FabricLoaderUpdater
import com.github.fanziyun.updater.data.LoaderLibraryRewrite
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.transaction.PreparedTransaction
import java.nio.file.Path

class LoaderRelaunchException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Makes an in-process relaunch boot the loader a transaction installed.
 *
 * Both relaunch paths rebuild the game process from the command line the launcher started this game
 * with. That command line pins every library jar by absolute path, including the loader, so replaying
 * it unchanged would start the new mods on the old loader and fail dependency resolution. Reading the
 * command line is only the first step: the loader jars in it are rewritten to the ones the patched
 * profile declares, which is what makes the relaunch consistent with a cold launcher start.
 *
 * When no loader was replaced this is the identity function. When the profile declares a loader jar
 * that is not on disk the relaunch is refused rather than started with a stale jar, because a boot on
 * the wrong loader version is the failure this exists to prevent.
 */
object LoaderRelaunchStrategy {
    /**
     * Returns [command] with the loader jar paths swapped for the installed ones.
     *
     * @throws LoaderRelaunchException when the rewritten classpath would still carry a loader jar the
     * profile no longer declares, or the rewrite itself fails
     */
    fun prepare(
        command: CurrentJvmCommand,
        transaction: PreparedTransaction,
        gameDir: Path = Platform.INSTANCE.gameDir,
    ): CurrentJvmCommand {
        if (!loaderChanged(transaction)) return command
        val plan = plan(command, transaction, gameDir)
        plan.unresolved.firstOrNull()?.let { stale ->
            throw LoaderRelaunchException(
                "Loader library declared by the instance profile is missing on disk: $stale",
            )
        }
        if (plan.replacements.isEmpty()) {
            Updater.LOGGER.info("Relaunch keeps its captured loader jars; the profile already declares them")
            return command
        }
        return command.withReplacedJarPaths(plan.replacements)
    }

    /** True when [transaction] replaced the loader, so a relaunch has to load different jars. */
    fun loaderChanged(transaction: PreparedTransaction): Boolean =
        transaction.record.loaderInstalledVersion.isNotBlank()

    /**
     * Resolves the rewrite without applying it. Split out so the capability report can say whether a
     * relaunch is possible at all, using the same resolution the relaunch itself will use.
     */
    fun plan(
        command: CurrentJvmCommand,
        transaction: PreparedTransaction,
        gameDir: Path = Platform.INSTANCE.gameDir,
    ): LoaderLibraryRewrite.Plan {
        val versionId = FabricLoaderUpdater.versionIdFromArguments(command.arguments())
        return runCatching {
            FabricLoaderUpdater.classpathPlan(gameDir, command.arguments(), versionId)
        }.getOrElse {
            throw LoaderRelaunchException(
                "Unable to resolve the loader libraries for a relaunch",
                it,
            )
        }
    }
}
