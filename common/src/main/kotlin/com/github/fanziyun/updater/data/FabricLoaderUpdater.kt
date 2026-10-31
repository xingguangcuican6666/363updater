package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.handoff.CurrentJvmCommand
import com.github.fanziyun.updater.progress.ProgressListener
import com.github.fanziyun.updater.progress.UpdatePhase
import com.github.fanziyun.updater.util.SemVer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

class FabricLoaderUpdateException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/**
 * Installs the Fabric loader version declared by the target mrpack over the current installation.
 *
 * Launchers such as HMCL, PCL and Prism keep the loader version pinned in the instance profile
 * (`<instance>/<id>.json`) and resolve libraries from a shared `libraries/` root. Running the Fabric
 * installer against the game directory does not change those, so it has no effect on such an
 * instance: it writes a `versions/fabric-loader-*` profile nothing reads, and the next launch still
 * uses the pinned loader. The change that takes effect everywhere is the loader library set inside
 * the profile the launcher actually loads, plus the jars behind it.
 *
 * So the update resolves the loader metadata from the Fabric meta API, downloads every library the
 * target loader needs into the resolved `libraries/` root, and rewrites the profile in place. The
 * instance keeps its name, JVM arguments and other launcher settings.
 *
 * Patching the profile only affects a launch the launcher builds afterwards. A restart that reuses
 * the captured command line still pins the old loader jars by path, so [classpathReplacements] maps
 * those paths onto the jars the patched profile declares and the relaunch boots the new loader.
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
     * The loader version a profile resolves to, or null when the profile has no loader.
     *
     * The `patches[]` chain is authoritative because that is what HMCL, Prism and PCL build the
     * classpath from; the top-level `libraries` array is a flattened snapshot they may not read. A
     * profile can carry the two out of step — a previous updater run patched only the top level, so
     * the launcher kept resolving the old loader — so this never trusts the snapshot alone.
     */
    fun profileLoaderVersion(profile: JsonObject): String? =
        loaderVersionFrom(profile.getAsJsonArray("patches")?.flatMap { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("libraries")?.mapNotNull { library ->
                library.takeIf { it.isJsonObject }?.asJsonObject?.get("name")
                    ?.takeIf { it.isJsonPrimitive }?.asString
            }.orEmpty()
        }?.toList().orEmpty())
            ?: loaderVersionFrom(
                profile.getAsJsonArray("libraries")?.mapNotNull { element ->
                    element.takeIf { it.isJsonObject }?.asJsonObject?.get("name")
                        ?.takeIf { it.isJsonPrimitive }?.asString
                }.orEmpty(),
            )

    private fun loaderVersionFrom(names: List<String>): String? =
        names.firstOrNull { it.startsWith("$LOADER_ARTIFACT:") }
            ?.substringAfterLast(':')?.takeIf(String::isNotBlank)

    /**
     * The libraries a target loader needs, in classpath order.
     *
     * `loader.maven` and `intermediary.maven` carry the loader itself and the intermediary jar; the
     * `common` group from `launcherMeta.libraries` carries asm and sponge-mixin, whose versions the
     * loader depends on. `client` and `development` groups are empty for release loaders and are only
     * included when the launcher meta actually populates them.
     */
    fun librariesFor(payload: String): List<FabricProfilePatcher.Library> {
        val root = JsonParser.parseString(payload).asJsonObject
        val loader = root.getAsJsonObject("loader")
            ?: throw FabricLoaderUpdateException("Fabric meta has no loader section")
        val loaderVersion = loader.get("version")?.asString
            ?: throw FabricLoaderUpdateException("Fabric meta has no loader version")
        val intermediary = root.getAsJsonObject("intermediary")
            ?.get("maven")?.asString
        val launcherMeta = root.getAsJsonObject("launcherMeta")
            ?: throw FabricLoaderUpdateException("Fabric meta has no launcherMeta section")

        val libraries = linkedMapOf<String, FabricProfilePatcher.Library>()
        fun add(maven: String, url: String?, sha1: String?) {
            val library = FabricProfilePatcher.Library(maven.trim(), url?.trim(), sha1?.lowercase())
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
     * An isolated instance stores its profile beside the game directory, so the version id is what
     * names it; launchers pass `--version <id>` to the game, which makes the command line the most
     * reliable source.
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
     * Resolves the loader jar paths a captured command line pins onto the jars the instance profile
     * now declares, so a relaunch of that command line boots the updated loader.
     *
     * Returns an empty plan when the profile cannot be resolved or nothing needs replacing; callers
     * treat that as "relaunch unchanged".
     */
    fun classpathPlan(
        gameDir: Path,
        arguments: List<String>,
        versionId: String? = null,
    ): LoaderLibraryRewrite.Plan {
        val classpath = classpathEntries(arguments)
        if (classpath.isEmpty()) return LoaderLibraryRewrite.Plan(emptyMap(), emptyList())
        val profilePath = FabricProfilePatcher.resolveProfile(gameDir, versionId)
            ?: return LoaderLibraryRewrite.Plan(emptyMap(), emptyList())
        val profile = runCatching {
            JsonParser.parseString(Files.readString(profilePath, Charsets.UTF_8)).asJsonObject
        }.getOrNull() ?: return LoaderLibraryRewrite.Plan(emptyMap(), emptyList())
        val libraries = declaredLibraries(profile)
            ?: return LoaderLibraryRewrite.Plan(emptyMap(), emptyList())
        val plan = LoaderLibraryRewrite.plan(
            classpath,
            FabricProfilePatcher.resolveLibrariesRoot(gameDir),
            libraries,
        )
        if (!plan.isEmpty) {
            Updater.LOGGER.info(
                "Relaunch loader jars: {} rewritten, {} unresolved",
                plan.replacements.size,
                plan.unresolved.size,
            )
        }
        return plan
    }

    /** The jar paths a `-cp` argument carries, across every classpath option the JVM accepts. */
    fun classpathEntries(arguments: List<String>): List<String> {
        val entries = mutableListOf<String>()
        // Application arguments are skipped so a game argument resembling an option cannot be read
        // as a classpath; locateMainIndex reports the boundary the JVM itself uses.
        val limit = runCatching { CurrentJvmCommand.locateMainIndex(arguments) }.getOrElse { arguments.size }
        var index = 0
        while (index < limit) {
            val argument = arguments[index]
            val option = CLASSPATH_OPTIONS.firstOrNull { argument == it || argument.startsWith("$it=") }
            if (option == null) {
                index++
                continue
            }
            val value = if (argument.length > option.length) argument.substring(option.length + 1)
            else arguments.getOrNull(++index).orEmpty()
            entries += LoaderLibraryRewrite.entries(value)
            index++
        }
        return entries
    }

    private fun declaredLibraries(profile: JsonObject): List<FabricProfilePatcher.Library>? {
        // Prefer the patches chain: that is what HMCL and friends build the classpath from.
        val fromPatches = profile.getAsJsonArray("patches")?.flatMap { element ->
            val patch = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@flatMap emptyList()
            patch.getAsJsonArray("libraries")?.mapNotNull { libraryElement(it) }.orEmpty()
        }?.takeIf(List<*>::isNotEmpty)
        if (fromPatches != null) return fromPatches
        return profile.getAsJsonArray("libraries")?.mapNotNull { libraryElement(it) }?.takeIf(List<*>::isNotEmpty)
    }

    private fun libraryElement(element: com.google.gson.JsonElement): FabricProfilePatcher.Library? {
        val name = element.takeIf { it.isJsonObject }?.asJsonObject?.get("name")
            ?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        return runCatching { FabricProfilePatcher.Library(name, null, null) }.getOrNull()
    }

    private val CLASSPATH_OPTIONS = listOf("-cp", "-classpath", "--class-path")

    /**
     * Downloads the target loader's libraries and points the instance profile at them.
     *
     * Returns the installed version, or `null` when no update is needed. Every step is reported
     * through [listener] under [UpdatePhase.LOADER]. Throws when the profile cannot be located or a
     * library cannot be fetched, so the caller can surface the reason instead of silently launching
     * the old loader again.
     */
    fun update(
        gameDir: Path,
        minecraftVersion: String,
        currentLoaderVersion: String,
        targetLoaderVersion: String,
        timeoutMs: Int,
        listener: ProgressListener = ProgressListener.NONE,
        versionId: String? = null,
    ): String? {
        if (!shouldUpdate(currentLoaderVersion, targetLoaderVersion)) return null
        listener.phase(
            UpdatePhase.LOADER,
            "fabric-loader $currentLoaderVersion -> $targetLoaderVersion",
        )

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
        if (libraries.none { it.artifact == LOADER_ARTIFACT }) {
            throw FabricLoaderUpdateException("Fabric meta declared no $LOADER_ARTIFACT library")
        }
        val librariesRoot = FabricProfilePatcher.resolveLibrariesRoot(gameDir)

        // Download first: a profile that points at missing jars is worse than the current one.
        val pending = libraries.filter { library ->
            val jar = librariesRoot.resolve(library.relativePath)
            !FabricProfilePatcher.libraryPresent(jar, library)
        }
        if (pending.isNotEmpty()) {
            listener.message("downloading ${pending.size} loader librar${if (pending.size == 1) "y" else "ies"}")
            pending.forEach { library -> downloadLibrary(librariesRoot, library, timeoutMs, listener) }
        }

        val profilePath = FabricProfilePatcher.resolveProfile(gameDir, versionId)
            ?: throw FabricLoaderUpdateException(
                "No launcher profile pinning $LOADER_ARTIFACT was found under $gameDir",
            )
        val profile = JsonParser.parseString(
            Files.readString(profilePath, Charsets.UTF_8),
        ).asJsonObject
        val patched = FabricProfilePatcher.patchProfile(profile, libraries, mainClassFor(payload))
        FabricProfilePatcher.writeProfile(profilePath, patched)

        val applied = profileLoaderVersion(patched)
        if (applied != targetLoaderVersion) {
            throw FabricLoaderUpdateException(
                "Profile ${profilePath.fileName} still resolves fabric-loader ${applied ?: "?"}",
            )
        }
        Updater.LOGGER.info(
            "Patched {} to fabric-loader {} ({} libraries downloaded) over {}",
            profilePath,
            targetLoaderVersion,
            pending.size,
            currentLoaderVersion,
        )
        return targetLoaderVersion
    }

    private fun downloadLibrary(
        librariesRoot: Path,
        library: FabricProfilePatcher.Library,
        timeoutMs: Int,
        listener: ProgressListener,
    ) {
        val url = library.downloadUrl() ?: libraryUrl(library)
        val destination = librariesRoot.resolve(library.relativePath)
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
            require(FabricProfilePatcher.libraryPresent(destination, library)) {
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
    private fun libraryUrl(library: FabricProfilePatcher.Library): String =
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
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()) {
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

/**
 * Rewrites the loader jars a captured launch command line pins by path.
 *
 * A command line built from the old profile names every library jar absolutely, so relaunching it
 * after a loader update would load the jars the running game already has open. Classpath entries are
 * matched by their Maven layout under the shared `libraries/` root: an entry whose group and artifact
 * the new profile also declares, at a different version, is replaced by the jar the profile now
 * points at. Entries outside the root, and ones already at the declared version, are left alone.
 *
 * [plan] reports both the replacements and the entries that could not be resolved, so the restart path
 * can refuse rather than relaunch with a stale jar on the classpath.
 */
object LoaderLibraryRewrite {
    /** [replacements] maps old absolute path to new; [unresolved] lists entries left stale. */
    data class Plan(val replacements: Map<String, String>, val unresolved: List<String>) {
        val isEmpty: Boolean get() = replacements.isEmpty() && unresolved.isEmpty()
    }

    /**
     * @param classpath absolute jar paths from a captured command line, in order
     * @param librariesRoot the shared `libraries/` directory the launcher resolves from
     * @param libraries the libraries the patched profile declares
     */
    fun plan(
        classpath: List<String>,
        librariesRoot: Path,
        libraries: List<FabricProfilePatcher.Library>,
    ): Plan {
        if (classpath.isEmpty() || libraries.isEmpty()) return Plan(emptyMap(), emptyList())
        val root = librariesRoot.toAbsolutePath().normalize()
        // Key on group path + artifact so a version bump is the only thing that can differ.
        val declared = libraries.mapNotNull { library ->
            val segments = library.relativePath.split('/')
            // <group dirs>/<artifact>/<version>/<file>.jar
            if (segments.size < 4) return@mapNotNull null
            val artifact = segments[segments.size - 3]
            val groupPath = segments.take(segments.size - 3).joinToString("/")
            "$groupPath/$artifact" to library.relativePath
        }.toMap()
        if (declared.isEmpty()) return Plan(emptyMap(), emptyList())

        val replacements = linkedMapOf<String, String>()
        val unresolved = mutableListOf<String>()
        classpath.forEach { entry ->
            if (entry.isBlank()) return@forEach
            val path = runCatching { Path.of(entry) }.getOrNull() ?: return@forEach
            if (!path.isAbsolute) return@forEach
            val normalized = path.normalize()
            if (!normalized.startsWith(root)) return@forEach
            val segments = root.relativize(normalized).toString().split('/', '\\').filter(String::isNotEmpty)
            if (segments.size < 4) return@forEach
            val artifact = segments[segments.size - 3]
            val groupPath = segments.take(segments.size - 3).joinToString("/")
            val replacement = declared["$groupPath/$artifact"] ?: return@forEach
            if (replacement == segments.joinToString("/")) return@forEach
            val target = root.resolve(replacement)
            if (Files.isRegularFile(target)) {
                replacements[normalized.toString()] = target.toString()
            } else {
                // The profile declares a jar that is not on disk; relaunching would keep the old one.
                unresolved += normalized.toString()
            }
        }
        return Plan(replacements, unresolved)
    }

    fun replacements(
        classpath: List<String>,
        librariesRoot: Path,
        libraries: List<FabricProfilePatcher.Library>,
    ): Map<String, String> = plan(classpath, librariesRoot, libraries).replacements

    /**
     * Splits a `-cp` value into entries. Kept here so the caller and the tests agree on the separator
     * rules, including the empty trailing entry a trailing separator produces.
     */
    fun entries(classpathValue: String): List<String> =
        classpathValue.split(java.io.File.pathSeparator).filter(String::isNotBlank)
}
