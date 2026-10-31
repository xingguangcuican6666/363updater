package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
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

    /** The loader version the profile currently pins, or null when the profile has no loader. */
    fun profileLoaderVersion(profile: JsonObject): String? =
        profile.getAsJsonArray("libraries")?.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
        }?.firstOrNull { it.startsWith("$LOADER_ARTIFACT:") }
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
        val loaderLibrary = libraries.firstOrNull { it.artifact == LOADER_ARTIFACT }
            ?: throw FabricLoaderUpdateException("Fabric meta declared no $LOADER_ARTIFACT library")
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
