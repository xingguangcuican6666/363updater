package com.github.fanziyun.updater.handoff

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.data.FabricLoaderInstall
import com.github.fanziyun.updater.data.LoaderLibrary
import com.github.fanziyun.updater.data.LoaderLibraryPlanner
import java.nio.file.Path

/**
 * Rebuilds the relaunch command line so it boots the loader a transaction installed.
 *
 * The updater never models a launcher. A launcher's only lasting contribution to a running game is
 * the JVM argv, so that is read for the facts the relaunch needs — executable, working directory,
 * classpath — and everything else is rebuilt here:
 *
 * - the classpath is replaced with one that carries the installed loader jars in place of the old
 *   ones, keeping every non-loader entry (the game jar, user paths) where it was
 * - application arguments, JVM flags and the main class are replayed byte for byte, so launcher
 *   options like `-Xmx`, `-javaagent` or custom properties survive untouched
 *
 * When no loader was installed this is the identity function. When the transaction recorded an
 * install but the jars are gone, the relaunch is refused rather than started on a stale loader.
 */
object LoaderRelaunchStrategy {
    /**
     * Returns [command] ready to start the installed loader.
     *
     * @throws LoaderRelaunchException when the recorded loader jars are missing from disk
     */
    fun prepare(command: CurrentJvmCommand, transaction: com.github.fanziyun.updater.transaction.PreparedTransaction): CurrentJvmCommand {
        val install = loadInstall(transaction) ?: return command
        val captured = command.classpathEntries()
        val relaunch = LoaderLibraryPlanner.relaunchClasspath(captured, install)
        if (relaunch == captured) return command
        return command.withClasspath(relaunch)
    }

    /** True when [transaction] recorded a loader install, so a relaunch has to load different jars. */
    fun loaderChanged(transaction: com.github.fanziyun.updater.transaction.PreparedTransaction): Boolean =
        transaction.record.loaderInstalledVersion.isNotBlank()

    /**
     * The install a transaction recorded, re-resolved against the filesystem.
     *
     * The transaction stores the Maven coordinates it installed and the `libraries/` root it used, so
     * the relaunch recomputes jar paths from coordinates instead of trusting stored paths. Missing
     * jars are an error: a relaunch on the old loader version fails dependency resolution exactly the
     * way this update exists to prevent.
     */
    fun loadInstall(transaction: com.github.fanziyun.updater.transaction.PreparedTransaction): FabricLoaderInstall? {
        val record = transaction.record
        if (record.loaderInstalledVersion.isBlank() || record.loaderLibraries.isEmpty()) return null
        val root = Path.of(record.loaderLibrariesRoot).takeIf { it.isAbsolute }
            ?: throw LoaderRelaunchException("The updater transaction has no usable loader libraries root")
        val libraries = record.loaderLibraries.mapNotNull { coordinate ->
            runCatching { LoaderLibrary(coordinate, null, null) }.getOrNull()
        }
        if (libraries.isEmpty()) {
            throw LoaderRelaunchException("The updater transaction recorded no usable loader libraries")
        }
        val jars = libraries.map { it.jarPath(root) }
        jars.firstOrNull { !java.nio.file.Files.isRegularFile(it) }?.let { missing ->
            throw LoaderRelaunchException("Loader library is missing on disk: $missing")
        }
        Updater.LOGGER.info(
            "Relaunch will use fabric-loader {} from {}",
            record.loaderInstalledVersion,
            root,
        )
        return FabricLoaderInstall(libraries, jars, null, root)
    }
}

class LoaderRelaunchException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)