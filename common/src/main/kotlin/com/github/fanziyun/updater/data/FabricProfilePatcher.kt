package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.util.AtomicFiles
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Rewrites a launcher version profile so the next launch runs a different Fabric loader.
 *
 * Isolated launcher instances (HMCL, PCL, Prism) keep the loader version pinned in
 * `<instance>/<id>.json` and resolve libraries from a shared `libraries/` root, so running the
 * Fabric installer against the game directory has no effect on them: it writes a
 * `versions/fabric-loader-*` profile that nothing reads. The change that actually takes effect is
 * the library entry inside the instance profile plus the jars behind it.
 *
 * This is therefore what the loader update does, and it is deliberately pure-plus-IO so each decision
 * can be unit-tested: [patchProfile] is a JSON transform with no side effects, and the caller
 * downloads the libraries it declares before writing the result.
 */
object FabricProfilePatcher {
    private const val MAX_PROFILE_BYTES = 32L * 1024L * 1024L

    /** A Maven coordinate from `launcherMeta.libraries`, e.g. `net.fabricmc:fabric-loader:0.19.5`. */
    data class Library(
        val name: String,
        val url: String?,
        val sha1: String?,
    ) {
        /** `group:artifact` without the version, used to match an existing profile entry. */
        val artifact: String get() = coordinates(name).let { (group, artifactId, _) -> "$group:$artifactId" }

        /** Maven layout relative to a `libraries/` root, e.g. `net/fabricmc/fabric-loader/0.19.5/…jar`. */
        val relativePath: String
            get() {
                val (group, artifactId, version) = coordinates(name)
                val groupPath = group.replace('.', '/')
                return "$groupPath/$artifactId/$version/$artifactId-$version.jar"
            }

        /** Absolute download URL; the meta `url` already ends with `/`. */
        fun downloadUrl(): String? {
            val base = url?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val separator = if (base.endsWith('/')) "" else "/"
            return "$base$separator$relativePath"
        }

        private fun coordinates(value: String): Triple<String, String, String> {
            val parts = value.split(':')
            require(parts.size == 3 && parts.all(String::isNotBlank)) { "Invalid library coordinate: $value" }
            return Triple(parts[0], parts[1], parts[2])
        }
    }

    /**
     * True for a version id that can only name a file inside [gameDir].
     *
     * Launchers allow spaces and unicode in instance names, so the id is not restricted to safe path
     * characters; it only has to be free of separators, traversal and NUL, since it is joined to the
     * game directory to form `<id>.json`.
     */
    private fun isSafeName(value: String): Boolean =
        value.indexOf('\u0000') < 0 && value.indexOf('/') < 0 && value.indexOf('\\') < 0 &&
            value != "." && value != ".." && value.none { it.isISOControl() }

    /**
     * Locate the profile that decides which loader this instance launches.
     *
     * The version id is taken from the running game's own command line when available, because an
     * isolated instance stores the profile beside the game directory rather than under `versions/`.
     * Candidates are checked in the orders launchers actually use, and the first file that parses as
     * a profile with a `libraries` array wins so an unrelated JSON file can never be rewritten.
     */
    fun resolveProfile(gameDir: Path, versionId: String? = null): Path? {
        val id = versionId?.trim()?.takeIf { it.isNotEmpty() && isSafeName(it) }
        val named = id?.let { gameDir.fileName?.toString()?.takeIf(String::isNotBlank) }
        val candidates = buildList {
            if (id != null) {
                add(gameDir.resolve("$id.json"))
                add(gameDir.resolve("versions").resolve(id).resolve("$id.json"))
            }
            if (named != null) add(gameDir.resolve("$named.json"))
            Files.list(gameDir).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".json", ignoreCase = true) }
                    .sorted()
                    .limit(64)
                    .forEach(::add)
            }
            val versions = gameDir.resolve("versions")
            if (Files.isDirectory(versions, LinkOption.NOFOLLOW_LINKS)) {
                Files.list(versions).use { stream ->
                    stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                        .sorted()
                        .limit(64)
                        .forEach { directory ->
                            Files.list(directory).use { inner ->
                                inner.filter { it.fileName.toString().endsWith(".json", ignoreCase = true) }
                                    .sorted()
                                    .limit(16)
                                    .forEach(::add)
                            }
                        }
                }
            }
        }
        return candidates.asSequence()
            .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
            .filter { runCatching { isProfile(it) }.getOrDefault(false) }
            .firstOrNull()
    }

    /**
     * Resolve the `libraries/` root for [gameDir].
     *
     * An isolated instance has no `libraries/` of its own and shares its parent Minecraft
     * installation, so parents are walked until one holds a `libraries/` directory; the game
     * directory itself is used when nothing does, which is the standard `.minecraft` layout.
     */
    fun resolveLibrariesRoot(gameDir: Path): Path {
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
     * Replace every library that [libraries] also provides, keeping position so ordering rules the
     * launcher applies still hold, and append the ones the profile did not have. [mainClass] is only
     * applied when the profile declares one, so a non-Fabric profile is never converted.
     */
    fun patchProfile(profile: JsonObject, libraries: List<Library>, mainClass: String?): JsonObject {
        require(libraries.isNotEmpty()) { "No loader libraries to apply" }
        val byArtifact = libraries.associateBy(Library::artifact)
        val existing = profile.getAsJsonArray("libraries") ?: JsonArray()
        val patched = JsonArray()
        val applied = linkedSetOf<String>()
        existing.forEach { element ->
            val entry = element.takeIf { it.isJsonObject }?.asJsonObject
            val name = entry?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            val artifact = name?.substringBeforeLast(':', "")?.takeIf { it.isNotEmpty() }
            val replacement = artifact?.let { byArtifact[it] }
            if (replacement == null) {
                patched.add(element.deepCopy())
            } else {
                patched.add(libraryEntry(replacement))
                applied += artifact
            }
        }
        libraries.filterNot { it.artifact in applied }.forEach { patched.add(libraryEntry(it)) }

        val result = profile.deepCopy()
        result.add("libraries", patched)
        if (mainClass != null && result.has("mainClass")) result.addProperty("mainClass", mainClass)
        return result
    }

    /** Library entries stay minimal: name plus the mirror URL the loader meta supplied. */
    private fun libraryEntry(library: Library): JsonObject = JsonObject().apply {
        addProperty("name", library.name)
        library.url?.let { addProperty("url", it) }
    }

    private fun isProfile(path: Path): Boolean {
        if (Files.size(path) > MAX_PROFILE_BYTES) return false
        val root = runCatching {
            JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
        }.getOrNull() ?: return false
        return root.has("libraries") && root.get("libraries").isJsonArray &&
            root.getAsJsonArray("libraries").any { element ->
                element.isJsonObject && element.asJsonObject.get("name")?.asString
                    ?.startsWith("net.fabricmc:fabric-loader:") == true
            }
    }

    /**
     * Write [profile] over [target], keeping a `.preloader.bak` copy beside it so a mistaken rewrite
     * is recoverable by hand.
     */
    fun writeProfile(target: Path, profile: JsonObject) {
        val bytes = profile.toString().toByteArray(StandardCharsets.UTF_8)
        runCatching { Files.readAllBytes(target) }.getOrNull()?.let { current ->
            if (!current.contentEquals(bytes)) {
                runCatching { Files.write(target.resolveSibling("${target.fileName}.preloader.bak"), current) }
                    .onFailure { Updater.LOGGER.warn("Unable to back up the launcher profile {}", target, it) }
            }
        }
        AtomicFiles.write(target, bytes)
    }

    /** `true` when [jar] exists and matches [library]'s SHA-1, so it does not need downloading. */
    fun libraryPresent(jar: Path, library: Library): Boolean {
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(jar)) return false
        val expected = library.sha1?.lowercase() ?: return true
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-1")
            Files.newInputStream(jar).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) } == expected
        }.getOrDefault(false)
    }
}
