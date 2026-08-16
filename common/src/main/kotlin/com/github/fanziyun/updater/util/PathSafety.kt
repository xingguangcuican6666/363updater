package com.github.fanziyun.updater.util

import com.github.fanziyun.updater.platform.Platform
import java.nio.file.Files
import java.nio.file.Path

object PathSafety {
    fun resolveGamePath(relative: String): Path = resolveUnder(Platform.INSTANCE.gameDir, relative)

    fun resolveUnder(rootPath: Path, relative: String): Path = resolveScoped(rootPath, relative, allowMods = false)

    fun resolveManagedPath(rootPath: Path, relative: String): Path = resolveScoped(rootPath, relative, allowMods = true)

    fun resolveStrictRelative(rootPath: Path, relative: String): Path {
        val candidateRelative = validatedRelative(relative)
        val root = rootPath.toAbsolutePath().normalize()
        validateNoSymbolicLinkComponents(root)
        val resolved = root.resolve(candidateRelative).normalize()
        require(resolved.startsWith(root)) { "Unsafe updater path: $relative" }

        var current = root
        candidateRelative.forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Updater refuses symbolic-link path: $relative" }
        }
        return resolved
    }

    fun validateConfigRelative(relative: String): String = validateScope(relative, allowMods = false)

    fun validateManagedRelative(relative: String): String = validateScope(relative, allowMods = true)

    private fun resolveScoped(rootPath: Path, relative: String, allowMods: Boolean): Path {
        validateScope(relative, allowMods)
        return resolveStrictRelative(rootPath, relative)
    }

    private fun validateScope(relative: String, allowMods: Boolean): String {
        val candidateRelative = validatedRelative(relative)
        val normalized = candidateRelative.joinToString("/")
        require(
            normalized == "options.txt" || normalized.startsWith("config/") ||
                allowMods && normalized.startsWith("mods/"),
        ) {
            "Updater path is outside config/options scope: $relative"
        }
        return normalized
    }

    private fun validatedRelative(relative: String): Path {
        require(relative.isNotBlank() && relative.indexOf('\u0000') < 0 && relative.indexOf('\\') < 0) {
            "Unsafe updater path: $relative"
        }
        val candidateRelative = Path.of(relative)
        require(!candidateRelative.isAbsolute) { "Updater path must be relative: $relative" }
        require(candidateRelative.none { it.toString() == "." || it.toString() == ".." }) {
            "Unsafe updater path: $relative"
        }
        require(candidateRelative.joinToString("/") == relative) { "Updater path is not canonical: $relative" }
        return candidateRelative
    }

    private fun validateNoSymbolicLinkComponents(path: Path) {
        var current = path.root ?: return
        path.forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Updater refuses symbolic-link path: $path" }
        }
    }
}
