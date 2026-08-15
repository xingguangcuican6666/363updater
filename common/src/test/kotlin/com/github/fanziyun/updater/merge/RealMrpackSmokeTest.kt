package com.github.fanziyun.updater.merge

import com.github.fanziyun.updater.data.MrpackReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class RealMrpackSmokeTest {
    @Test
    fun `previews real mrpacks when paths are supplied`() {
        val oldPath = System.getProperty("updater.oldPack")?.takeIf(String::isNotBlank)?.let(Path::of) ?: return
        val targetPath = System.getProperty("updater.targetPack")?.takeIf(String::isNotBlank)?.let(Path::of) ?: return
        val old = MrpackReader.read(oldPath, "old")
        val target = MrpackReader.read(targetPath, "target")
        val gameDir = Files.createTempDirectory("363updater-real-pack")
        old.files.forEach { (relative, bytes) ->
            val destination = gameDir.resolve(relative)
            Files.createDirectories(destination.parent)
            Files.write(destination, bytes)
        }

        val plan = PackageMerger.preview(old, target, MergeOptions(), gameDir)

        assertTrue(old.files.size > 50)
        assertTrue(target.files.size > 50)
        assertTrue(plan.changedFiles.isNotEmpty())
        assertTrue(plan.files.all { it.relativePath == "options.txt" || it.relativePath.startsWith("config/") })
    }
}
