package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.progress.ProgressListener
import com.github.fanziyun.updater.progress.UpdatePhase
import com.github.fanziyun.updater.util.SemVer
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Installs the loader libraries a mrpack declares, in a launcher-agnostic way.
 *
 * The updater does not model launchers. It resolves the Maven coordinates the Fabric meta API
 * publishes for the target loader, puts those jars in the one `libraries/` root the running JVM
 * resolves from, and records the resulting library set so the relaunch can be rebuilt from the
 * JVM's own argv instead of from any launcher's profile format.
 *
 * What this replaces: running the Fabric installer against the game directory only writes a
 * `versions/fabric-loader-*` profile, and isolated instances resolve their classpath from a shared
 * `libraries/` root plus whatever the launcher captured at start, so the installer has no effect on
 * them. Downloading the exact jars and rebuilding the relaunch classpath does, for every launcher.
 *
 * Profile files are still consulted when present, but only as an optional hint for [resolveLibrariesRoot]
 * and for verifying the jars landed; no launcher-specific JSON shape is rewritten or required.
 */
object FabricLoaderUpdater {
    const val META_ROOT = "https://meta.fabricmc.net/v2"
    const val MAX_LIBRARY_BYTES = 64L * 1024L * 1024L
    private const val MAX_METADATA_BYTES = 8L * 1024L * 1024L
    private const val LOADER_ARTIFACT = "net.fabricmc:fabric-loader"

    fun loaderMetaUrl(minecraftVersion: String, loaderVersion: String): String =
        "$META_ROOT/versions/loader/${encode(minecraftVersion)}/${encode(loaderVersion)}"

    /** True when [target] is a non-blank strict upgrade over [current] (blank current always upgrades). */
    fun shouldUpdate(current: String, target: String): Boolean {
        if (target.isBlank()) return false
        if (current.isBlank()) return true
        return SemVer.compare(target, current) > 0
    }

    /**
     * The libraries a target loader needs, in classpath order.
     *
     * `loader.maven` and `intermediary.maven` carry the loader itself and the intermediary jar; the
     * `common` group from `launcherMeta.libraries` carries asm and sponge-mixin, whose versions the
     * loader depends on. `client` and `development` groups are empty for release loaders and are only
     * included when the launcher meta actually populates them.
     */
    fun librariesFor(payload: String): List<LoaderLibrary> {
        val root = JsonParser.parseString(payload).asJsonObject
        val loader = root.getAsJsonObject("loader")
            ?: throw FabricLoaderUpdateException("Fabric meta has no loader section")
        val loaderVersion = loader.get("version")?.asString
            ?: throw FabricLoaderUpdateException("Fabric meta has no loader version")
        val intermediary = root.getAsJsonObject("intermediary")
            ?.get("maven")?.asString
        val launcherMeta = root.getAsJsonObject("launcherMeta")
            ?: throw FabricLoaderUpdateException("Fabric meta has no launcherMeta section")

        val libraries = linkedMapOf<String, LoaderLibrary>()
        fun add(maven: String, url: String?, sha1: String?) {
            val library = LoaderLibrary(maven.trim(), url?.trim(), sha1?.lowercase())
            libraries[library.artifact] = library
        }

        add(loader.get("maven")?.asString ?: "$LOADER_ARTIFACT:$loaderVersion", null, null)
        intermediary?.let { add(it, null, null) }
        launcherMeta.getAsJsonObject("libraries")?.entrySet()?.forEach { (group, element) ->
            if (group == "development" || group == "server") return@forEach
            element.takeIf { it.isJsonArray }?.asJsonArray?.forEach { entry ->
                val objectEntry = entry.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val name = objectEntry.get("name")?.asString ?: return@forEach
                add(name, objectEntry.get("url")?.asString, objectEntry.get("sha1")?.asString)
            }
        }
        return libraries.values.toList()
    }

    /** The main class a target loader uses, or null when the meta does not declare one. */
    fun mainClassFor(payload: String): String? =
        JsonParser.parseString(payload).asJsonObject
            .getAsJsonObject("launcherMeta")
            ?.getAsJsonObject("mainClass")
            ?.get("client")?.takeIf { it.isJsonPrimitive }?.asString

    /**
     * Reads the `--version` value from a launcher command line.
     *
     * Kept because it names the instance for logging and for locating the profile hint; nothing in
     * the install path depends on it.
     */
    fun versionIdFromArguments(arguments: List<String>): String? {
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            if (argument == "--version" || argument == "--gameVersion") {
                return arguments.getOrNull(index + 1)?.takeIf(String::isNotBlank)
            }
            if (argument.startsWith("--version=")) return argument.substringAfter('=').takeIf(String::isNotBlank)
            index++
        }
        return null
    }

    /**
     * Resolve the `libraries/` root the running JVM reads from.
     *
     * The definitive source is the captured classpath: whichever directory the loader jars already
     * came from is the one the next launch will read. [gameDir] and its parents are only a fallback
     * for a launch that had no classpath at all.
     */
    fun resolveLibrariesRoot(gameDir: Path, classpath: List<String> = emptyList()): Path {
        LoaderLibraryPlanner.librariesRootFromClasspath(classpath)?.let { return it }
        val root = gameDir.toAbsolutePath().normalize()
        var candidate: Path? = root
        var depth = 0
        while (candidate != null && depth < 4) {
            val libraries = candidate.resolve("libraries")
            if (Files.isDirectory(libraries, LinkOption.NOFOLLOW_LINKS)) return libraries
            candidate = candidate.parent
            depth++
        }
        return root.resolve("libraries")
    }

    /**
     * Downloads the target loader's libraries and returns their absolute paths in classpath order.
     *
     * Returns `null` when no update is needed. Every step is reported through [listener] under
     * [UpdatePhase.LOADER]. Throws when a library cannot be fetched or does not match the SHA-1 the
     * meta published, so the caller never relaunches with a half-installed loader.
     */
    fun install(
        minecraftVersion: String,
        currentLoaderVersion: String,
        targetLoaderVersion: String,
        librariesRoot: Path,
        timeoutMs: Int,
        listener: ProgressListener = ProgressListener.NONE,
    ): FabricLoaderInstall? {
        if (!shouldUpdate(currentLoaderVersion, targetLoaderVersion)) return null
        listener.phase(UpdatePhase.LOADER, "fabric-loader $currentLoaderVersion -> $targetLoaderVersion")

        val payload = request(loaderMetaUrl(minecraftVersion, targetLoaderVersion), timeoutMs)
            ?: throw FabricLoaderUpdateException(
                "Fabric meta has no loader $targetLoaderVersion for Minecraft $minecraftVersion",
            )
        val declared = JsonParser.parseString(payload).asJsonObject
            .getAsJsonObject("loader")?.get("version")?.asString
        if (declared == null || !declared.equals(targetLoaderVersion, ignoreCase = true)) {
            throw FabricLoaderUpdateException(
                "Fabric meta returned loader ${declared ?: "?"} instead of $targetLoaderVersion",
            )
        }

        val libraries = librariesFor(payload)
        val loaderLibrary = libraries.firstOrNull { it.artifact == LOADER_ARTIFACT }
            ?: throw FabricLoaderUpdateException("Fabric meta declared no $LOADER_ARTIFACT library")
        val pending = libraries.filter { library ->
            !library.presentIn(librariesRoot)
        }
        if (pending.isNotEmpty()) {
            listener.message("downloading ${pending.size} loader librar${if (pending.size == 1) "y" else "ies"}")
            pending.forEach { library -> downloadLibrary(librariesRoot, library, timeoutMs, listener) }
        }

        val jars = libraries.map { it.jarPath(librariesRoot) }
        jars.forEach { jar ->
            require(Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS)) {
                "Loader library is missing after download: $jar"
            }
        }
        require(FabricLoaderInstall(libraries, jars, mainClassFor(payload), librariesRoot)
            .resolveLoaderVersion() == targetLoaderVersion) {
            "Installed libraries do not resolve fabric-loader $targetLoaderVersion"
        }
        Updater.LOGGER.info(
            "Installed fabric-loader {} ({} of {} jars downloaded) over {} into {}",
            targetLoaderVersion,
            pending.size,
            jars.size,
            currentLoaderVersion,
            librariesRoot,
        )
        return FabricLoaderInstall(libraries, jars, mainClassFor(payload), librariesRoot)
    }

    private fun downloadLibrary(
        librariesRoot: Path,
        library: LoaderLibrary,
        timeoutMs: Int,
        listener: ProgressListener,
    ) {
        val url = library.downloadUrl() ?: libraryUrl(library)
        val destination = library.jarPath(librariesRoot)
        val label = destination.fileName.toString()
        listener.startFile(label, -1L)
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".$label.", ".download")
        try {
            val connection = open(url, timeoutMs)
            var total = 0L
            try {
                connection.inputStream.use { raw ->
                    java.io.BufferedInputStream(raw).use { input ->
                        Files.newOutputStream(temporary).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_LIBRARY_BYTES) {
                                    throw FabricLoaderUpdateException("Loader library $label is too large")
                                }
                                output.write(buffer, 0, count)
                                listener.fileBytes(total)
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            require(library.presentIn(librariesRoot)) {
                "Downloaded $label does not match the SHA-1 from Fabric meta"
            }
            listener.finishFile(label, total)
        } catch (exception: Exception) {
            runCatching { Files.deleteIfExists(temporary) }
            if (exception is FabricLoaderUpdateException) throw exception
            throw FabricLoaderUpdateException("Unable to download $label from $url", exception)
        }
    }

    /** Fallback URL for coordinates the meta did not carry a mirror for. */
    private fun libraryUrl(library: LoaderLibrary): String =
        "https://maven.fabricmc.net/${library.relativePath}"

    private fun request(url: String, timeoutMs: Int): String? {
        val connection = open(url, timeoutMs)
        return try {
            connection.inputStream.use { input ->
                val bytes = input.readNBytes((MAX_METADATA_BYTES + 1).toInt())
                if (bytes.size.toLong() > MAX_METADATA_BYTES) {
                    throw FabricLoaderUpdateException("Fabric meta response is too large")
                }
                bytes.toString(Charsets.UTF_8)
            }
        } catch (exception: java.io.IOException) {
            Updater.LOGGER.warn("Fabric meta request failed: {}", url, exception)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val uri = URI.create(url)
        require(uri.scheme?.lowercase(java.util.Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "Fabric meta URL must be HTTP(S)"
        }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json, application/octet-stream")
        connection.setRequestProperty("User-Agent", ModrinthClient.USER_AGENT)
        val response = connection.responseCode
        if (response !in 200..299) {
            connection.disconnect()
            throw FabricLoaderUpdateException("Fabric meta returned HTTP $response for $url")
        }
        return connection
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value.trim(), Charsets.UTF_8).replace("+", "%20")
}

/** A Maven coordinate from `launcherMeta.libraries`; a classifier is allowed. */
data class LoaderLibrary(
    val name: String,
    val url: String?,
    val sha1: String?,
) {
    private val parts: List<String> get() = name.trim().split(':').filter(String::isNotBlank)

    /** `group:artifact`, used to match an existing classpath entry. */
    val artifact: String
        get() {
            val coordinates = parts
            require(coordinates.size >= 3) { "Invalid library coordinate: $name" }
            return "${coordinates[0]}:${coordinates[1]}"
        }

    val version: String
        get() = parts[2]

    /**
     * The `libraries/`-relative path, derived from the coordinate rather than from any launcher
     * layout, so the same rule works for every launcher that resolves Maven libraries.
     */
    val relativePath: String
        get() {
            val coordinates = parts
            require(coordinates.size >= 3) { "Invalid library coordinate: $name" }
            val group = coordinates[0].replace('.', '/')
            val artifactId = coordinates[1]
            val version = coordinates[2]
            val classifier = coordinates.drop(3).joinToString("-")
            val fileName = if (classifier.isEmpty()) "$artifactId-$version.jar"
            else "$artifactId-$version-$classifier.jar"
            return "$group/$artifactId/$version/$fileName"
        }

    fun downloadUrl(): String? {
        val base = url?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val separator = if (base.endsWith('/')) "" else "/"
        return "$base$separator$relativePath"
    }

    fun jarPath(librariesRoot: Path): Path = librariesRoot.resolve(relativePath).normalize()

    /** True when the jar is on disk with a matching SHA-1 (or no published hash to check). */
    fun presentIn(librariesRoot: Path): Boolean {
        val jar = jarPath(librariesRoot)
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jar)) return false
        val expected = sha1?.lowercase() ?: return true
        return runCatching { sha1Of(jar) == expected }.getOrDefault(false)
    }

    companion object {
        /** The coordinate a jar under [librariesRoot] stands for, or null when it is not Maven-shaped. */
        fun fromJarPath(jar: Path, librariesRoot: Path): LoaderLibrary? {
            val root = librariesRoot.toAbsolutePath().normalize()
            val normalized = jar.toAbsolutePath().normalize()
            if (!normalized.startsWith(root)) return null
            val segments = root.relativize(normalized).toString().split('/', '\\').filter(String::isNotEmpty)
            if (segments.size < 4) return null
            val fileName = segments.last()
            if (!fileName.endsWith(".jar")) return null
            val version = segments[segments.size - 2]
            val artifactId = segments[segments.size - 3]
            val group = segments.take(segments.size - 3).joinToString(".")
            val base = fileName.removeSuffix(".jar")
            val expectedBase = "$artifactId-$version"
            val classifier = when {
                base == expectedBase -> ""
                base.startsWith("$expectedBase-") -> base.removePrefix("$expectedBase-")
                else -> return null
            }
            val name = if (classifier.isEmpty()) "$group:$artifactId:$version"
            else "$group:$artifactId:$version:$classifier"
            return runCatching { LoaderLibrary(name, null, null) }.getOrNull()
        }

        internal fun sha1Of(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-1")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * The loader libraries a transaction installed, and the classpath they stand for.
 *
 * This is what the relaunch needs: absolute jar paths in the order the loader meta declares, plus the
 * main class. Nothing here depends on a launcher's profile format.
 */
data class FabricLoaderInstall(
    val libraries: List<LoaderLibrary>,
    val jars: List<Path>,
    val mainClass: String?,
    val librariesRoot: Path,
) {
    fun resolveLoaderVersion(): String? = libraries.firstOrNull { it.artifact == "net.fabricmc:fabric-loader" }?.version
}

/**
 * Rebuilds a classpath from the loader libraries a relaunch has to use.
 *
 * The captured JVM argv is the only launcher-neutral source of the classpath, so the planner works on
 * plain path lists: keep every entry the JVM was started with, drop the entries that belong to a
 * loader library (anything Maven-shaped under the libraries root), then insert the installed loader
 * jars where the first dropped one was. Entries outside the libraries root — the game jar, user-added
 * paths — are never reordered or dropped.
 */
object LoaderLibraryPlanner {
    /** The jar paths a relaunch classpath should carry, given the captured one. */
    fun relaunchClasspath(captured: List<String>, install: FabricLoaderInstall): List<String> {
        val root = install.librariesRoot.toAbsolutePath().normalize()
        val installed = install.jars.map { it.toAbsolutePath().normalize().toString() }
        if (captured.isEmpty()) return installed

        val result = mutableListOf<String>()
        var inserted = false
        captured.forEach { entry ->
            if (entry.isBlank()) return@forEach
            val path = runCatching { Path.of(entry) }.getOrNull()
            val isLoaderLibrary = path != null && path.isAbsolute &&
                path.toAbsolutePath().normalize().startsWith(root) &&
                LoaderLibrary.fromJarPath(path, root) != null
            when {
                isLoaderLibrary && !inserted -> {
                    result += installed
                    inserted = true
                }
                isLoaderLibrary -> Unit
                else -> result += entry
            }
        }
        if (!inserted) result += installed
        return result
    }

    /** The `libraries/` root the captured classpath resolves from, or null when it has none. */
    fun librariesRootFromClasspath(captured: List<String>): Path? {
        val counts = mutableMapOf<Path, Int>()
        captured.forEach { entry ->
            val path = runCatching { Path.of(entry) }.getOrNull() ?: return@forEach
            if (!path.isAbsolute) return@forEach
            val library = findLibrariesRoot(path) ?: return@forEach
            counts[library] = (counts[library] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }

    /**
     * Walk up from [jar] until a directory named `libraries` is found, requiring the Maven layout
     * under it so an unrelated path that happens to contain a `libraries` segment cannot match.
     */
    fun findLibrariesRoot(jar: Path): Path? {
        var candidate = jar.parent ?: return null
        var depth = 0
        while (depth < 8) {
            if (candidate.fileName?.toString() == "libraries") {
                if (LoaderLibrary.fromJarPath(jar, candidate) != null) return candidate
            }
            candidate = candidate.parent ?: return null
            depth++
        }
        return null
    }
}

class FabricLoaderUpdateException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)