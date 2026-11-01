package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.util.AtomicFiles
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Points an instance's launcher profile at the loader libraries that were installed.
 *
 * Rebuilding the relaunch argv is enough for the process the updater starts itself, but the *next*
 * launch is built by the launcher from the instance profile, so that file has to move too — otherwise
 * the game restarts fine and then fails again the moment the player launches it normally.
 *
 * The rewrite is structural rather than launcher-specific. A profile is a JSON document that names
 * library coordinates in objects with a `name` field, wherever they sit: a vanilla `libraries` array,
 * a launcher's patch chain, or any other shape a launcher keeps. So instead of teaching this code
 * about `patches` or `inheritsFrom`, it walks the whole document and does exactly one thing:
 *
 * - an object whose `name` is `group:artifact:version[:classifier]` for an artifact the install
 *   provides gets that name replaced, keeping its position and every sibling key
 * - a container that ended up holding a replaced library, and whose `version` still reads the
 *   previous loader version, has that version bumped
 *
 * Nothing else is touched, and this works for a launcher format that does not exist yet.
 */
object LoaderProfileRewriter {
    private const val MAX_PROFILE_BYTES = 32L * 1024L * 1024L

    data class Result(val edits: Int, val profileVersion: String?) {
        val changed: Boolean get() = edits > 0
    }

    /**
     * Locate the profile the launcher reads for the instance named [versionId].
     *
     * Only the standard `--version <id>` naming is used: `<gameDir>/<id>.json` for an isolated
     * instance and `<gameDir>/versions/<id>/<id>.json` for a shared one. Both are the Minecraft
     * launcher layout, so no launcher is named. Candidates are validated before being returned, so an
     * unrelated JSON file can never be rewritten.
     */
    fun findProfile(gameDir: Path, versionId: String?): Path? {
        val id = versionId?.trim()?.takeIf { it.isNotEmpty() && isSafeName(it) } ?: return null
        val candidates = listOf(
            gameDir.resolve("$id.json"),
            gameDir.resolve("versions").resolve(id).resolve("$id.json"),
            gameDir.fileName?.toString()?.takeIf(String::isNotBlank)?.let { gameDir.resolve("$it.json") },
        ).filterNotNull()
        return candidates.firstOrNull { path ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                runCatching { namesLoader(read(path)) }.getOrDefault(false)
        }
    }

    /**
     * Replace every coordinate the install provides and bump a container's loader version.
     *
     * Returns how many fields changed so the caller can tell "already current" from "rewritten".
     */
    fun rewrite(profile: JsonObject, install: FabricLoaderInstall, previousVersion: String): Result {
        val byArtifact = install.libraries.associateBy { it.artifact }
        if (byArtifact.isEmpty()) return Result(0, null)
        val replacedArtifacts = linkedSetOf<String>()
        var edits = 0

        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> {
                    val name = element.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                    val library = name?.let { coordinate -> byArtifact[resolveArtifact(coordinate)] }
                    if (library != null) {
                        // Only fields that actually change are written, so an already-current profile
                        // is detected as such instead of being rewritten on every check.
                        if (name != library.name) {
                            element.addProperty("name", library.name)
                            // A replaced entry takes the mirror the loader meta publishes; an entry
                            // that was already current keeps whatever url it had.
                            library.url?.let { element.addProperty("url", it) }
                            edits++
                        }
                        replacedArtifacts += library.artifact
                    }
                    element.entrySet().forEach { (_, value) -> walk(value) }
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(profile)

        // A container that now holds a replaced library is the launcher's loader block; if it also
        // records the loader version, that value has to follow or the launcher keeps treating the
        // profile as stale.
        fun bump(element: JsonElement, installedVersion: String) {
            when (element) {
                is JsonObject -> {
                    val holdsReplaced = element.getAsJsonArray("libraries")?.any { entry ->
                        entry.takeIf { it.isJsonObject }?.asJsonObject?.get("name")
                            ?.takeIf { it.isJsonPrimitive }?.asString
                            ?.let { resolveArtifact(it) } in replacedArtifacts
                    } == true
                    val version = element.get("version")?.takeIf { it.isJsonPrimitive }?.asString
                    if (holdsReplaced && version == previousVersion) {
                        element.addProperty("version", installedVersion)
                        edits++
                    }
                    element.entrySet().forEach { (_, value) -> bump(value, installedVersion) }
                }
                is JsonArray -> element.forEach { bump(it, installedVersion) }
                else -> Unit
            }
        }
        install.resolveLoaderVersion()?.let { installed -> bump(profile, installed) }

        return Result(edits, install.resolveLoaderVersion())
    }

    /**
     * Write [profile] over [path], keeping a `.preloader.bak` copy so a mistaken rewrite is
     * recoverable by hand.
     */
    fun write(path: Path, profile: JsonObject) {
        val bytes = profile.toString().toByteArray(StandardCharsets.UTF_8)
        runCatching { Files.readAllBytes(path) }.getOrNull()?.let { current ->
            if (!current.contentEquals(bytes)) {
                runCatching { Files.write(path.resolveSibling("${path.fileName}.preloader.bak"), current) }
                    .onFailure { Updater.LOGGER.warn("Unable to back up the launcher profile {}", path, it) }
            }
        }
        AtomicFiles.write(path, bytes)
    }

    /** The loader version a document resolves to, read from whichever coordinate names it. */
    fun loaderVersion(profile: JsonObject): String? {
        var found: String? = null
        fun walk(element: JsonElement) {
            if (found != null) return
            when (element) {
                is JsonObject -> {
                    val name = element.get("name")?.takeIf { it.isJsonPrimitive }?.asString
                    if (name != null && resolveArtifact(name) == LOADER_ARTIFACT) {
                        found = name.split(':').getOrNull(2)
                        return
                    }
                    element.entrySet().forEach { (_, value) -> walk(value) }
                }
                is JsonArray -> element.forEach(::walk)
                else -> Unit
            }
        }
        walk(profile)
        return found
    }

    private fun namesLoader(profile: JsonObject): Boolean = loaderVersion(profile) != null

    /**
     * `group:artifact` of a Maven coordinate. Only the group and artifact matter for matching, so the
     * version and any classifier are dropped; a string that is not coordinate-shaped yields the input
     * itself, which can never equal a real artifact key.
     */
    private fun resolveArtifact(coordinate: String): String {
        val parts = coordinate.trim().split(':')
        return if (parts.size >= 3) "${parts[0]}:${parts[1]}" else coordinate
    }

    private fun read(path: Path): JsonObject {
        require(Files.size(path) <= MAX_PROFILE_BYTES) { "Launcher profile is too large: $path" }
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).asJsonObject
    }

    private fun isSafeName(value: String): Boolean =
        value.indexOf('\u0000') < 0 && value.indexOf('/') < 0 && value.indexOf('\\') < 0 &&
            value != "." && value != ".." && value.none { it.isISOControl() }

    private const val LOADER_ARTIFACT = "net.fabricmc:fabric-loader"
}