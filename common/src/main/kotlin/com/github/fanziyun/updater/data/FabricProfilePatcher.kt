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
 * the loader library set inside the instance profile plus the jars behind it.
 *
 * HMCL builds the classpath from the `patches[]` chain rather than the top-level `libraries`, and
 * keeps the top level as a flattened snapshot of that chain, so both have to be rewritten or the
 * launcher keeps resolving the old loader. Every profile shape in use is covered:
 *
 * - `patches[]` with a `fabric` patch (HMCL 3.x, Prism, PCL) — the authoritative source
 * - top-level `libraries` (vanilla launcher profiles, older HMCL)
 *
 * This is deliberately pure-plus-IO so each decision can be unit-tested: [patchProfile] is a JSON
 * transform with no side effects, and the caller downloads the libraries it declares first.
 */
object FabricProfilePatcher {
    private const val MAX_PROFILE_BYTES = 32L * 1024L * 1024L
    private const val LOADER_ARTIFACT = "net.fabricmc:fabric-loader"
    /** Patch ids that can own the loader libraries; anything else is left untouched. */
    private val LOADER_PATCH_IDS = setOf("fabric", "fabric-loader", "quilt", "quilt-loader")

    /**
     * A Maven coordinate from `launcherMeta.libraries`, e.g. `net.fabricmc:fabric-loader:0.19.5`.
     * A classifier is allowed: `com.mojang:jtracy:1.0.37:natives-linux`.
     */
    data class Library(
        val name: String,
        val url: String?,
        val sha1: String?,
    ) {
        private val parts: List<String> get() = name.trim().split(':').filter(String::isNotBlank)

        /** `group:artifact` without version or classifier, used to match an existing profile entry. */
        val artifact: String
            get() {
                val coordinates = parts
                require(coordinates.size >= 3) { "Invalid library coordinate: $name" }
                return "${coordinates[0]}:${coordinates[1]}"
            }

        /** Maven layout relative to a `libraries/` root, e.g. `net/fabricmc/fabric-loader/0.19.5/…jar`. */
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

        /** Absolute download URL; the meta `url` already ends with `/`. */
        fun downloadUrl(): String? {
            val base = url?.trim()?.takeIf(String::isNotEmpty) ?: return null
            val separator = if (base.endsWith('/')) "" else "/"
            return "$base$separator$relativePath"
        }
    }

    /**
     * Locate the profile that decides which loader this instance launches.
     *
     * The version id is taken from the running game's own command line when available, because an
     * isolated instance stores the profile beside the game directory rather than under `versions/`.
     * Candidates are checked in the orders launchers actually use, and the first file that parses as
     * a loader profile wins so an unrelated JSON file can never be rewritten.
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
     * Replace the loader libraries everywhere the profile declares them, keeping position so the
     * ordering rules a launcher applies still hold, and appending the ones it did not have.
     *
     * The loader version the target loader needs is read off [libraries] so the owning patch's
     * `version` field follows it — HMCL keys off that field when deciding whether the profile is
     * current. [mainClass] is applied where the profile already declares one, so a non-Fabric
     * profile is never converted.
     */
    fun patchProfile(profile: JsonObject, libraries: List<Library>, mainClass: String?): JsonObject {
        require(libraries.isNotEmpty()) { "No loader libraries to apply" }
        val byArtifact = libraries.associateBy(Library::artifact)
        val loaderVersion = libraries.firstOrNull { it.artifact == LOADER_ARTIFACT }
            ?.name?.substringAfterLast(':')

        val result = profile.deepCopy()
        val appliedTopLevel = linkedSetOf<String>()

        // HMCL/Prism/PCL: the patches chain is what the classpath is built from.
        result.getAsJsonArray("patches")?.let { patches ->
            patches.forEach { element ->
                val patch = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val patchId = patch.get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val patchLibraries = patch.getAsJsonArray("libraries")
                val ownsLoader = patchId in LOADER_PATCH_IDS ||
                    (patchLibraries != null && declaresLoader(patchLibraries))
                if (!ownsLoader) return@forEach
                if (patchLibraries != null) {
                    val (patched, applied) = patchLibraryList(patchLibraries, byArtifact)
                    patch.add("libraries", patched)
                    appliedTopLevel += applied
                }
                loaderVersion?.let { patch.addProperty("version", it) }
                mainClass?.let { if (patch.has("mainClass")) patch.addProperty("mainClass", it) }
            }
        }

        // Vanilla launcher profiles and the flattened snapshot HMCL keeps alongside patches.
        result.getAsJsonArray("libraries")?.let { existing ->
            val (patched, applied) = patchLibraryList(existing, byArtifact)
            result.add("libraries", patched)
            appliedTopLevel += applied
        }

        // Anything the profile did not declare at all goes on the top level, which every shape reads.
        val missing = libraries.filterNot { it.artifact in appliedTopLevel }
        if (missing.isNotEmpty()) {
            val topLevel = result.getAsJsonArray("libraries") ?: JsonArray().also { result.add("libraries", it) }
            missing.forEach { topLevel.add(libraryEntry(it)) }
        }
        mainClass?.let { if (result.has("mainClass")) result.addProperty("mainClass", it) }
        return result
    }

    /** Library entries stay minimal: name plus the mirror URL the loader meta supplied. */
    private fun libraryEntry(library: Library): JsonObject = JsonObject().apply {
        addProperty("name", library.name)
        library.url?.let { addProperty("url", it) }
    }

    /** Rewrite [existing] in place order, returning the new array and which artifacts it covered. */
    private fun patchLibraryList(
        existing: JsonArray,
        byArtifact: Map<String, Library>,
    ): Pair<JsonArray, Set<String>> {
        val patched = JsonArray()
        val applied = linkedSetOf<String>()
        existing.forEach { element ->
            val entry = element.takeIf { it.isJsonObject }?.asJsonObject
            val name = entry?.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            val artifact = name?.let { runCatching { Library(it, null, null).artifact }.getOrNull() }
            val replacement = artifact?.let { byArtifact[it] }
            if (replacement == null) {
                patched.add(element.deepCopy())
            } else {
                patched.add(libraryEntry(replacement))
                applied += artifact
            }
        }
        return patched to applied
    }

    private fun declaresLoader(libraries: JsonArray): Boolean = libraries.any { element ->
        val name = element.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@any false
        name.startsWith("$LOADER_ARTIFACT:")
    }

    private fun isProfile(path: Path): Boolean {
        if (Files.size(path) > MAX_PROFILE_BYTES) return false
        val root = runCatching {
            JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
        }.getOrNull() ?: return false
        val topLevel = root.getAsJsonArray("libraries")?.let { declaresLoader(it) } == true
        val inPatches = root.getAsJsonArray("patches")?.any { element ->
            val patch = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@any false
            patch.getAsJsonArray("libraries")?.let { declaresLoader(it) } == true
        } == true
        return topLevel || inPatches
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

    /**
     * True for a version id that can only name a file inside the game directory.
     *
     * Launchers allow spaces and unicode in instance names, so the id is not restricted to safe path
     * characters; it only has to be free of separators, traversal, NUL and control characters, since
     * it is joined to the game directory to form `<id>.json`.
     */
    private fun isSafeName(value: String): Boolean =
        value.indexOf('\u0000') < 0 && value.indexOf('/') < 0 && value.indexOf('\\') < 0 &&
            value != "." && value != ".." && value.none { it.isISOControl() }
}
