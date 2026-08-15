package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.platform.Platform
import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

data class BackupInfo(val id: String, val path: Path)

object BackupManager {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val root: Path
        get() = Platform.INSTANCE.gameDir.resolve(".cache").resolve(Updater.MOD_ID).resolve("backups")

    fun create(retain: Int): BackupInfo {
        val createdAt = Instant.now()
        val id = createdAt.toString().replace(":", "-").replace(".", "-")
        val path = root.resolve(id)
        val temporary = root.resolve(".$id.tmp")
        Files.createDirectories(root)
        try {
            deleteTree(temporary)
            val payload = temporary.resolve("payload")
            Files.createDirectories(payload)
            copyTree(Platform.INSTANCE.gameDir.resolve("config"), payload.resolve("config"))
            val options = Platform.INSTANCE.gameDir.resolve("options.txt")
            require(!Files.isSymbolicLink(options)) { "Updater refuses symbolic-link path: $options" }
            if (Files.isRegularFile(options)) {
                Files.copy(options, payload.resolve("options.txt"), StandardCopyOption.REPLACE_EXISTING)
            }
            Files.writeString(
                temporary.resolve("metadata.json"),
                gson.toJson(mapOf("id" to id, "createdAt" to createdAt.toString())),
            )
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path)
            }
        } catch (exception: Exception) {
            runCatching { deleteTree(temporary) }
            throw exception
        }
        prune(retain)
        Updater.LOGGER.info("Created updater backup {}", id)
        return BackupInfo(id, path)
    }

    fun latest(): BackupInfo? = list().firstOrNull()

    fun list(): List<BackupInfo> = if (!Files.isDirectory(root)) emptyList() else Files.list(root).use { stream ->
        stream.filter(Files::isDirectory)
            .filter { !it.fileName.toString().startsWith(".") }
            .map { BackupInfo(it.fileName.toString(), it) }
            .sorted(Comparator.comparing<BackupInfo, String> { it.id }.reversed())
            .toList()
    }

    fun restore(backup: BackupInfo) {
        val backupPath = backup.path.toAbsolutePath().normalize()
        val backupRoot = root.toAbsolutePath().normalize()
        require(backupPath.parent == backupRoot && !Files.isSymbolicLink(backupPath)) {
            "Invalid updater backup path: ${backup.path}"
        }
        val payload = backupPath.resolve("payload")
        require(Files.isDirectory(payload, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(payload)) {
            "Invalid updater backup: ${backup.id}"
        }
        val backupConfig = payload.resolve("config")
        val options = payload.resolve("options.txt")
        validateTree(backupConfig)
        require(!Files.isSymbolicLink(options)) { "Invalid updater backup: symbolic-link options.txt" }

        val gameDir = Platform.INSTANCE.gameDir
        val configTarget = gameDir.resolve("config")
        val optionsTarget = gameDir.resolve("options.txt")
        require(!Files.isSymbolicLink(configTarget)) { "Updater refuses symbolic-link path: $configTarget" }
        require(!Files.isSymbolicLink(optionsTarget)) { "Updater refuses symbolic-link path: $optionsTarget" }

        deleteTree(configTarget)
        deleteIfRegular(optionsTarget)
        copyTree(backupConfig, configTarget)
        if (Files.isRegularFile(options)) Files.copy(options, gameDir.resolve("options.txt"), StandardCopyOption.REPLACE_EXISTING)
        Updater.LOGGER.info("Restored updater backup {}", backup.id)
    }

    private fun prune(retain: Int) {
        list().drop(retain.coerceAtLeast(1)).forEach { deleteTree(it.path) }
    }

    private fun copyTree(source: Path, destination: Path) {
        validateTree(source)
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val relative = source.relativize(path)
                val target = destination.resolve(relative)
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(target)
                else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun validateTree(source: Path) {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(source)) { "Updater refuses symbolic-link path: $source" }
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Updater refuses symbolic-link path: $path" }
            }
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { entry ->
                Files.deleteIfExists(entry)
            }
        }
    }

    private fun deleteIfRegular(path: Path) {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) Files.deleteIfExists(path)
    }
}
