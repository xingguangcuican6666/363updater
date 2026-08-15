package com.github.fanziyun.updater.util

import com.github.fanziyun.updater.platform.Platform
import java.nio.file.Files
import java.nio.file.Path

object PathSafety {
    fun resolveGamePath(relative: String): Path = resolveUnder(Platform.INSTANCE.gameDir, relative)

    fun resolveUnder(rootPath: Path, relative: String): Path {
        val candidateRelative = Path.of(relative)
        require(!candidateRelative.isAbsolute) { "Updater path must be relative: $relative" }
        require(candidateRelative.none { it.toString() == "." || it.toString() == ".." }) {
            "Unsafe updater path: $relative"
        }
        val normalized = candidateRelative.joinToString("/")
        require(normalized == "options.txt" || normalized.startsWith("config/")) {
            "Updater path is outside config/options scope: $relative"
        }

        val root = rootPath.toAbsolutePath().normalize()
        val resolved = root.resolve(candidateRelative).normalize()
        require(resolved.startsWith(root)) { "Unsafe updater path: $relative" }

        var current = root
        candidateRelative.forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Updater refuses symbolic-link path: $relative" }
        }
        return resolved
    }
}
