package com.github.fanziyun.updater.transaction

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.data.ClientEnvironment
import com.github.fanziyun.updater.data.FileHashes
import com.github.fanziyun.updater.data.ManagedFileCache
import com.github.fanziyun.updater.data.PackageFile
import com.github.fanziyun.updater.data.PackageFileSource
import com.github.fanziyun.updater.data.TrackedVersion
import com.github.fanziyun.updater.data.VersionTracker
import com.github.fanziyun.updater.merge.FileAction
import com.github.fanziyun.updater.merge.ManagedFileRecord
import com.github.fanziyun.updater.merge.UpdatePlan
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.util.AtomicFiles
import com.github.fanziyun.updater.util.PathSafety
import com.google.gson.GsonBuilder
import java.nio.charset.StandardCharsets
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Comparator
import java.util.UUID

enum class TransactionStage {
    PREPARED,
    CHILD_STARTING,
    FIRST_FRAME,
    STABLE,
    READY,
    OLD_STOPPING,
    COMMITTING,
    COMMITTED,
    COMMIT_FAILED,
}

data class TransactionFileChange(
    val relativePath: String,
    val action: FileAction,
    val expectedCurrentHashes: FileHashes? = null,
    val targetHashes: FileHashes? = null,
)

data class TransactionRecord(
    val schemaVersion: Int = 1,
    val id: String,
    val createdAt: String,
    val stage: TransactionStage,
    val project: String,
    val minecraftVersion: String,
    val loader: String,
    val currentVersion: String,
    val targetVersion: String,
    val targetVersionId: String?,
    val managedFiles: List<ManagedFileRecord>,
    val modChanges: List<TransactionFileChange>,
    val configChanges: List<TransactionFileChange>,
    val configurationActivated: Boolean = false,
    val modsCommitted: Boolean = false,
    val helperJar: String? = null,
    val failure: String? = null,
    val startupMillis: Long = 0L,
    val peakCombinedRssBytes: Long = 0L,
)

data class PreparedTransaction(val path: Path, val record: TransactionRecord) {
    val id: String get() = record.id
    val generationMods: Path get() = PathSafety.resolveStrictRelative(path, "generation/mods")
    val helperJar: Path? get() = record.helperJar?.let { PathSafety.resolveStrictRelative(path, it) }
}

class UpdateTransactionManager(
    private val gameDir: Path = Platform.INSTANCE.gameDir,
    private val timeoutMs: Int = 30_000,
    private val selfJar: Path? = Platform.INSTANCE.selfJar,
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val cacheRoot = gameDir.resolve(".cache").resolve(Updater.MOD_ID)
    private val root = cacheRoot.resolve("transactions")
    private val contentCache = ManagedFileCache(timeoutMs, cacheRoot.resolve("objects"))

    fun prepare(plan: UpdatePlan, config: UpdaterConfig): PreparedTransaction {
        check(!plan.hasConflicts) { "Resolve managed-file conflicts before preparing the update" }
        val id = UUID.randomUUID().toString()
        val finalPath = root.resolve(id)
        val temporary = root.resolve(".$id.tmp")
        Files.createDirectories(root)
        require(!Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) { "Updater transaction already exists: $id" }
        deleteTree(temporary)
        preflight(plan, temporary)
        try {
            Files.createDirectories(temporary)
            createBackup(temporary.resolve("backup"))
            createStaging(temporary.resolve("staged"), plan)
            createGeneration(temporary.resolve("generation/mods"), plan)
            LauncherMetadata.stage(gameDir, temporary, plan)
            val helper = copyHelperJar(temporary)
            val record = TransactionRecord(
                id = id,
                createdAt = Instant.now().toString(),
                stage = TransactionStage.PREPARED,
                project = plan.project.ifBlank { config.modrinthProject.trim().ifBlank { "363fan" } },
                minecraftVersion = plan.minecraftVersion.ifBlank {
                    config.minecraftVersion.trim().ifBlank { Platform.INSTANCE.minecraftVersion }
                },
                loader = plan.loader.ifBlank { config.loader.trim().ifBlank { Platform.INSTANCE.loaderId } },
                currentVersion = plan.currentVersion,
                targetVersion = plan.targetVersion,
                targetVersionId = plan.targetVersionId.takeIf(String::isNotBlank),
                managedFiles = plan.managedFiles.toSortedMap().values.toList(),
                modChanges = plan.modFiles.filter { it.action != FileAction.UNCHANGED }.map { file ->
                    TransactionFileChange(file.relativePath, file.action, file.expectedCurrentHashes, file.targetHashes)
                },
                configChanges = plan.configFiles.filter { it.action != FileAction.UNCHANGED }.map { file ->
                    TransactionFileChange(file.relativePath, file.action)
                },
                helperJar = helper,
            )
            writeRecord(temporary, record)
            moveAtomic(temporary, finalPath)
            VersionTracker.markPending(
                record.project,
                record.minecraftVersion,
                record.loader,
                record.id,
                TrackedVersion(record.currentVersion),
                gameDir,
            )
            Updater.LOGGER.info("Prepared updater transaction {} for {}", id, plan.targetVersion)
            return PreparedTransaction(finalPath, record)
        } catch (exception: Exception) {
            runCatching { deleteTree(temporary) }
            runCatching { deleteTree(finalPath) }
            throw exception
        }
    }

    fun load(id: String): PreparedTransaction {
        require(id.matches(TRANSACTION_ID)) { "Invalid updater transaction id" }
        val path = root.resolve(id).normalize()
        require(path.parent == root.normalize() && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Updater transaction does not exist: $id"
        }
        require(!Files.isSymbolicLink(path)) { "Updater transaction is a symbolic link" }
        return PreparedTransaction(path, readRecord(path))
    }

    fun pending(): List<PreparedTransaction> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val paths = Files.list(root).use { stream ->
            stream.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && !it.fileName.toString().startsWith(".") }
                .toList()
        }
        return paths.mapNotNull { path -> runCatching { PreparedTransaction(path, readRecord(path)) }.getOrNull() }
            .filter { it.record.stage != TransactionStage.COMMITTED }
            .sortedWith(Comparator.comparing<PreparedTransaction, String> { it.record.createdAt }.reversed())
    }

    fun transition(transaction: PreparedTransaction, next: TransactionStage): PreparedTransaction {
        val current = readRecord(transaction.path)
        require(canTransition(current.stage, next)) { "Invalid updater transition ${current.stage} -> $next" }
        val updated = current.copy(stage = next, failure = null)
        writeRecord(transaction.path, updated)
        return PreparedTransaction(transaction.path, updated)
    }

    fun activateConfiguration(transaction: PreparedTransaction): PreparedTransaction {
        var record = readRecord(transaction.path)
        if (record.configurationActivated) return PreparedTransaction(transaction.path, record)
        record.configChanges.forEach { change ->
            val live = PathSafety.resolveUnder(gameDir, change.relativePath)
            val backup = PathSafety.resolveUnder(transaction.path.resolve("backup"), change.relativePath)
            validateUnchanged(live, backup, change.relativePath)
            when (change.action) {
                FileAction.WRITE -> {
                    val staged = PathSafety.resolveUnder(transaction.path.resolve("staged"), change.relativePath)
                    require(Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                        "Missing staged configuration: ${change.relativePath}"
                    }
                    AtomicFiles.write(live, Files.readAllBytes(staged))
                }
                FileAction.DELETE -> Files.deleteIfExists(live)
                FileAction.UNCHANGED -> Unit
            }
        }
        record = record.copy(configurationActivated = true)
        writeRecord(transaction.path, record)
        return PreparedTransaction(transaction.path, record)
    }

    fun rollbackBeforeReady(transaction: PreparedTransaction): PreparedTransaction {
        var record = readRecord(transaction.path)
        require(record.stage.ordinal <= TransactionStage.READY.ordinal || record.stage == TransactionStage.COMMIT_FAILED) {
            "Transaction can no longer be rolled back before handoff"
        }
        if (record.configurationActivated && !record.modsCommitted) {
            restoreChangedConfiguration(transaction.path, record.configChanges)
        }
        record = record.copy(
            stage = TransactionStage.PREPARED,
            configurationActivated = false,
            failure = null,
        )
        writeRecord(transaction.path, record)
        return PreparedTransaction(transaction.path, record)
    }

    fun commit(transaction: PreparedTransaction, config: UpdaterConfig): PreparedTransaction {
        var record = readRecord(transaction.path)
        require(
            record.stage in setOf(
                TransactionStage.PREPARED,
                TransactionStage.READY,
                TransactionStage.OLD_STOPPING,
                TransactionStage.COMMIT_FAILED,
                TransactionStage.COMMITTING,
            ),
        ) { "Transaction is not ready to commit: ${record.stage}" }
        record = record.copy(stage = TransactionStage.COMMITTING, failure = null)
        writeRecord(transaction.path, record)
        try {
            if (!record.configurationActivated) {
                record = activateConfiguration(PreparedTransaction(transaction.path, record)).record
            }
            if (!record.modsCommitted && record.modChanges.isNotEmpty()) {
                if (hasExternalCommitMarker(transaction.path)) {
                    check(targetChangesMatch(record.modChanges)) {
                        "The restart helper reported success, but the live mod generation does not match"
                    }
                } else if (targetChangesMatch(record.modChanges)) {
                    Updater.LOGGER.info("Updater transaction {} already has its target mod generation live", record.id)
                } else {
                    helperFailure(transaction.path)?.let { error("Restart helper failed: $it") }
                    validateLiveMods(record.modChanges)
                    commitMods(transaction.path, record)
                }
                record = record.copy(modsCommitted = true)
                writeRecord(transaction.path, record)
            }
            LauncherMetadata.commit(gameDir, transaction.path)
            VersionTracker.write(
                config = config,
                version = record.targetVersion,
                versionId = record.targetVersionId,
                project = record.project,
                minecraftVersion = record.minecraftVersion,
                loader = record.loader,
                managedFiles = record.managedFiles.associateBy(ManagedFileRecord::relativePath),
                pendingTransaction = null,
                metrics = com.github.fanziyun.updater.data.LaunchMetrics(
                    record.startupMillis,
                    record.peakCombinedRssBytes,
                ),
                gameDir = gameDir,
            )
            record = record.copy(stage = TransactionStage.COMMITTED, failure = null)
            writeRecord(transaction.path, record)
            Updater.LOGGER.info("Committed updater transaction {}", record.id)
            return PreparedTransaction(transaction.path, record)
        } catch (exception: Exception) {
            record = record.copy(
                stage = TransactionStage.COMMIT_FAILED,
                failure = sanitizeFailure(exception),
            )
            writeRecord(transaction.path, record)
            throw IllegalStateException("Updater transaction commit failed; the transaction was kept for retry", exception)
        }
    }

    fun recordMetrics(transaction: PreparedTransaction, startupMillis: Long, peakCombinedRssBytes: Long): PreparedTransaction {
        val current = readRecord(transaction.path)
        val updated = current.copy(
            startupMillis = startupMillis.coerceAtLeast(0L),
            peakCombinedRssBytes = peakCombinedRssBytes.coerceAtLeast(0L),
        )
        writeRecord(transaction.path, updated)
        return PreparedTransaction(transaction.path, updated)
    }

    fun hasExternalCommit(transaction: PreparedTransaction): Boolean = hasExternalCommitMarker(transaction.path)

    fun helperFailure(transaction: PreparedTransaction): String? = helperFailure(transaction.path)

    fun refreshUpdaterCopies(transaction: PreparedTransaction): PreparedTransaction {
        val current = load(transaction.id)
        require(current.record.stage in setOf(TransactionStage.PREPARED, TransactionStage.COMMIT_FAILED)) {
            "Updater copies can only be refreshed before restart"
        }
        val source = selfJar?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) } ?: return current
        require(!Files.isSymbolicLink(source) && Files.size(source) <= MAX_HELPER_JAR_BYTES) {
            "Invalid updater helper source"
        }
        val bytes = Files.readAllBytes(source)
        current.helperJar?.let { helper -> AtomicFiles.write(helper, bytes) }

        val modsRoot = gameDir.resolve("mods").toAbsolutePath().normalize()
        val normalizedSource = source.toAbsolutePath().normalize()
        if (normalizedSource.startsWith(modsRoot)) {
            val relative = "mods/" + modsRoot.relativize(normalizedSource).joinToString("/")
            val packageManagesSelf = current.record.managedFiles.any {
                it.relativePath.equals(relative, ignoreCase = true)
            } || current.record.modChanges.any {
                it.relativePath.equals(relative, ignoreCase = true)
            }
            if (!packageManagesSelf) {
                val generationSelf = PathSafety.resolveManagedPath(current.path.resolve("generation"), relative)
                require(Files.isRegularFile(generationSelf, LinkOption.NOFOLLOW_LINKS)) {
                    "The staged updater copy is missing"
                }
                AtomicFiles.write(generationSelf, bytes)
            }
        }
        return load(current.id)
    }

    private fun createBackup(destination: Path) {
        Files.createDirectories(destination)
        copyTree(gameDir.resolve("config"), destination.resolve("config"), hardLink = false)
        copyTree(gameDir.resolve("mods"), destination.resolve("mods"), hardLink = false)
        val options = gameDir.resolve("options.txt")
        require(!Files.isSymbolicLink(options)) { "Updater refuses symbolic-link path: $options" }
        if (Files.isRegularFile(options, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(options, destination.resolve("options.txt"), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun createStaging(destination: Path, plan: UpdatePlan) {
        Files.createDirectories(destination)
        copyTree(gameDir.resolve("config"), destination.resolve("config"), hardLink = false)
        val options = gameDir.resolve("options.txt")
        if (Files.isRegularFile(options, LinkOption.NOFOLLOW_LINKS)) {
            Files.copy(options, destination.resolve("options.txt"), StandardCopyOption.REPLACE_EXISTING)
        }
        plan.configFiles.filter { it.action != FileAction.UNCHANGED }.forEach { file ->
            val target = PathSafety.resolveUnder(destination, file.relativePath)
            when (file.action) {
                FileAction.WRITE -> AtomicFiles.write(target, file.bytes ?: error("Missing bytes for ${file.relativePath}"))
                FileAction.DELETE -> Files.deleteIfExists(target)
                FileAction.UNCHANGED -> Unit
            }
        }
    }

    private fun createGeneration(destination: Path, plan: UpdatePlan) {
        Files.createDirectories(destination)
        copyTree(gameDir.resolve("mods"), destination, hardLink = true)
        plan.modFiles.filter { it.action != FileAction.UNCHANGED }.forEach { file ->
            val target = PathSafety.resolveManagedPath(destination.parent, file.relativePath)
            when (file.action) {
                FileAction.WRITE -> {
                    val hashes = file.targetHashes ?: error("Missing target hashes for ${file.relativePath}")
                    val packageFile = PackageFile(
                        relativePath = file.relativePath,
                        size = file.size,
                        hashes = hashes,
                        urls = file.downloadUrls,
                        environment = if (file.optional) ClientEnvironment.OPTIONAL else ClientEnvironment.REQUIRED,
                        source = PackageFileSource.INDEX,
                        embeddedBytes = file.bytes,
                    )
                    val objectPath = contentCache.materialize(packageFile)
                    Files.createDirectories(target.parent)
                    Files.deleteIfExists(target)
                    linkOrCopy(objectPath, target)
                }
                FileAction.DELETE -> Files.deleteIfExists(target)
                FileAction.UNCHANGED -> Unit
            }
        }
        plan.managedFiles.values.forEach { managed ->
            val target = PathSafety.resolveManagedPath(destination.parent, managed.relativePath)
            require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && managed.hashes.matches(target)) {
                "Staged managed mod verification failed: ${managed.relativePath}"
            }
        }
    }

    private fun copyHelperJar(transactionPath: Path): String? {
        val source = selfJar?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) } ?: return null
        require(!Files.isSymbolicLink(source)) { "Updater helper source is a symbolic link" }
        val relative = "helper/updater-helper.jar"
        val destination = transactionPath.resolve(relative)
        Files.createDirectories(destination.parent)
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING)
        return relative
    }

    private fun commitMods(transactionPath: Path, record: TransactionRecord) {
        val generation = transactionPath.resolve("generation/mods")
        val candidate = transactionPath.resolve("commit-mods")
        val live = gameDir.resolve("mods")
        val previous = transactionPath.resolve("previous-mods")
        if (!Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS)) {
            if (targetManifestMatches(live, record.managedFiles)) return
            error("Staged mod generation is missing")
        }
        require(!Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) { "Previous live mods directory already exists" }
        deleteTree(candidate)
        copyTree(generation, candidate, hardLink = true)
        require(targetManifestMatches(candidate, record.managedFiles, relativeToModsDirectory = true)) {
            "Commit candidate mod verification failed"
        }
        if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) moveAtomic(live, previous)
        try {
            moveAtomic(candidate, live)
        } catch (exception: Exception) {
            if (!Files.exists(live, LinkOption.NOFOLLOW_LINKS) && Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                runCatching { moveAtomic(previous, live) }.onFailure(exception::addSuppressed)
            }
            throw exception
        }
    }

    private fun validateLiveMods(changes: List<TransactionFileChange>) {
        changes.forEach { change ->
            val live = PathSafety.resolveManagedPath(gameDir, change.relativePath)
            require(!Files.isSymbolicLink(live)) { "Updater refuses symbolic-link mod path: ${change.relativePath}" }
            val expected = change.expectedCurrentHashes
            val unchanged = when {
                expected == null -> !Files.exists(live, LinkOption.NOFOLLOW_LINKS)
                !Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS) -> false
                else -> expected.matches(live)
            }
            check(unchanged) { "Managed mod changed after preview: ${change.relativePath}" }
        }
    }

    private fun targetManifestMatches(
        mods: Path,
        files: List<ManagedFileRecord>,
        relativeToModsDirectory: Boolean = false,
    ): Boolean {
        if (files.isEmpty()) return false
        return files.all { file ->
            val path = if (relativeToModsDirectory) {
                require(file.relativePath.startsWith("mods/")) { "Invalid managed mod path: ${file.relativePath}" }
                PathSafety.resolveStrictRelative(mods, file.relativePath.removePrefix("mods/"))
            } else {
                PathSafety.resolveManagedPath(mods.parent, file.relativePath)
            }
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && file.hashes.matches(path)
        }
    }

    private fun targetChangesMatch(changes: List<TransactionFileChange>): Boolean = changes.all { change ->
        val live = PathSafety.resolveManagedPath(gameDir, change.relativePath)
        when (change.action) {
            FileAction.WRITE -> change.targetHashes != null &&
                Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(live) && change.targetHashes.matches(live)
            FileAction.DELETE -> !Files.exists(live, LinkOption.NOFOLLOW_LINKS)
            FileAction.UNCHANGED -> true
        }
    }

    private fun hasExternalCommitMarker(transactionPath: Path): Boolean =
        PathSafety.resolveStrictRelative(transactionPath, "helper-commit.ok").let { marker ->
            Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(marker)
        }

    private fun helperFailure(transactionPath: Path): String? {
        val marker = PathSafety.resolveStrictRelative(transactionPath, "helper-commit.failed")
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(marker)) return null
        return runCatching { Files.readString(marker, StandardCharsets.UTF_8).trim().take(500) }
            .getOrDefault("unknown helper failure")
    }

    private fun restoreChangedConfiguration(transactionPath: Path, changes: List<TransactionFileChange>) {
        changes.forEach { change ->
            val live = PathSafety.resolveUnder(gameDir, change.relativePath)
            val backup = PathSafety.resolveUnder(transactionPath.resolve("backup"), change.relativePath)
            if (Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)) {
                AtomicFiles.write(live, Files.readAllBytes(backup))
            } else {
                Files.deleteIfExists(live)
            }
        }
    }

    private fun validateUnchanged(live: Path, backup: Path, relative: String) {
        require(!Files.isSymbolicLink(live)) { "Updater refuses symbolic-link path: $relative" }
        val same = when {
            Files.isRegularFile(live, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS) ->
                Files.mismatch(live, backup) == -1L
            !Files.exists(live, LinkOption.NOFOLLOW_LINKS) && !Files.exists(backup, LinkOption.NOFOLLOW_LINKS) -> true
            else -> false
        }
        check(same) { "$relative changed after the transaction was prepared" }
    }

    private fun preflight(plan: UpdatePlan, temporary: Path) {
        require(Files.isDirectory(gameDir, LinkOption.NOFOLLOW_LINKS) && Files.isWritable(gameDir)) {
            "Minecraft game directory is not writable"
        }
        require(!Files.isSymbolicLink(gameDir.resolve("mods"))) { "Updater refuses symbolic-link mods directory" }
        require(!Files.isSymbolicLink(gameDir.resolve("config"))) { "Updater refuses symbolic-link config directory" }
        Files.createDirectories(root)
        val gameStore = Files.getFileStore(gameDir)
        val transactionStore = Files.getFileStore(root)
        require(sameStore(gameStore, transactionStore)) { "Updater cache and game directory must use the same file system" }
        val required = treeSize(gameDir.resolve("mods")) * 2 + treeSize(gameDir.resolve("config")) * 2 +
            plan.downloadBytes + 256L * 1024L * 1024L
        require(transactionStore.usableSpace >= required) {
            "Not enough disk space to stage and back up this update"
        }
        verifyAtomicRename(temporary.parent)
    }

    private fun verifyAtomicRename(parent: Path) {
        val suffix = UUID.randomUUID().toString()
        val source = parent.resolve(".atomic-$suffix-source")
        val target = parent.resolve(".atomic-$suffix-target")
        try {
            Files.createDirectory(source)
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(source)
            Files.deleteIfExists(target)
        }
    }

    private fun sameStore(first: FileStore, second: FileStore): Boolean = first == second

    private fun treeSize(path: Path): Long {
        validateTree(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return 0L
        return Files.walk(path).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.mapToLong(Files::size).sum()
        }
    }

    private fun copyTree(source: Path, destination: Path, hardLink: Boolean) {
        validateTree(source)
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(destination)
            return
        }
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                val relative = source.relativize(path)
                val target = destination.resolve(relative)
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    if (hardLink) linkOrCopy(path, target) else Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun linkOrCopy(source: Path, destination: Path) {
        runCatching { Files.createLink(destination, source) }
            .getOrElse { Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun validateTree(source: Path) {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(source)) { "Updater refuses symbolic-link path: $source" }
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                require(!Files.isSymbolicLink(path)) { "Updater refuses symbolic-link path: $path" }
                require(
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
                ) { "Updater only supports regular files and directories: $path" }
            }
        }
    }

    private fun moveAtomic(source: Path, destination: Path) {
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun readRecord(path: Path): TransactionRecord {
        val recordPath = PathSafety.resolveStrictRelative(path, "transaction.json")
        require(Files.isRegularFile(recordPath, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(recordPath)) {
            "Invalid updater transaction log"
        }
        require(Files.size(recordPath) <= MAX_TRANSACTION_LOG_BYTES) { "Updater transaction log is too large" }
        val record = gson.fromJson(Files.readString(recordPath, StandardCharsets.UTF_8), TransactionRecord::class.java)
            ?: error("Invalid updater transaction log")
        validateRecord(path, record)
        return record
    }

    private fun writeRecord(path: Path, record: TransactionRecord) {
        AtomicFiles.write(
            path.resolve("transaction.json"),
            (gson.toJson(record) + "\n").toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun validateRecord(path: Path, record: TransactionRecord) {
        require(record.schemaVersion == 1 && record.id == path.fileName.toString() && record.id.matches(TRANSACTION_ID)) {
            "Invalid updater transaction log"
        }
        runCatching { Instant.parse(requireText(record.createdAt, "createdAt")) }
            .getOrElse { throw IllegalArgumentException("Invalid updater transaction creation time", it) }
        requireNotNull(record.stage) { "Missing updater transaction stage" }
        requireText(record.project, "project")
        requireText(record.minecraftVersion, "minecraftVersion")
        requireText(record.loader, "loader")
        requireText(record.currentVersion, "currentVersion")
        requireText(record.targetVersion, "targetVersion")
        record.targetVersionId?.let { requireText(it, "targetVersionId") }
        record.failure?.let { require(it.length <= 500) { "Invalid updater transaction failure" } }
        require(record.startupMillis >= 0L && record.peakCombinedRssBytes >= 0L) {
            "Invalid updater transaction metrics"
        }
        require(record.helperJar == null || record.helperJar == "helper/updater-helper.jar") {
            "Invalid updater helper path"
        }

        val managedFiles = requireNotNull(record.managedFiles) { "Missing updater managed-file manifest" }
        val modChanges = requireNotNull(record.modChanges) { "Missing updater mod changes" }
        val configChanges = requireNotNull(record.configChanges) { "Missing updater configuration changes" }
        require(managedFiles.size <= MAX_TRANSACTION_FILES && modChanges.size <= MAX_TRANSACTION_FILES &&
            configChanges.size <= MAX_TRANSACTION_FILES) { "Updater transaction contains too many files" }

        val managedPaths = linkedSetOf<String>()
        managedFiles.forEach { file ->
            val relative = PathSafety.validateManagedRelative(requireText(file.relativePath, "managed path"))
            require(relative.startsWith("mods/")) { "Invalid updater managed-file path" }
            require(managedPaths.add(relative.lowercase())) { "Duplicate updater managed-file path" }
            require(file.size in 0..ManagedFileCache.MAX_ARTIFACT_BYTES) { "Invalid updater managed-file size" }
            validateHashes(file.hashes)
            file.sourceHost?.let { require(it.length <= 253 && '\u0000' !in it) { "Invalid updater source host" } }
        }

        val changedPaths = linkedSetOf<String>()
        modChanges.forEach { change ->
            val relative = PathSafety.validateManagedRelative(requireText(change.relativePath, "mod change path"))
            require(relative.startsWith("mods/")) { "Invalid updater mod change path" }
            require(changedPaths.add(relative.lowercase())) { "Duplicate updater transaction path" }
            val action = requireNotNull(change.action) { "Missing updater mod action" }
            change.expectedCurrentHashes?.let(::validateHashes)
            when (action) {
                FileAction.WRITE -> validateHashes(requireNotNull(change.targetHashes) { "Missing updater target hashes" })
                FileAction.DELETE -> require(change.targetHashes == null) { "Deleted updater file has target hashes" }
                FileAction.UNCHANGED -> error("Unchanged files must not be stored in updater transactions")
            }
        }
        configChanges.forEach { change ->
            val relative = PathSafety.validateConfigRelative(requireText(change.relativePath, "configuration change path"))
            require(changedPaths.add(relative.lowercase())) { "Duplicate updater transaction path" }
            require(change.action == FileAction.WRITE || change.action == FileAction.DELETE) {
                "Invalid updater configuration action"
            }
            require(change.expectedCurrentHashes == null && change.targetHashes == null) {
                "Configuration transaction unexpectedly contains artifact hashes"
            }
        }
    }

    private fun validateHashes(hashes: FileHashes) {
        FileHashes(hashes.sha1, hashes.sha512)
    }

    private fun requireText(value: String?, name: String): String {
        require(!value.isNullOrBlank() && value.length <= 1_024 && '\u0000' !in value && '\n' !in value && '\r' !in value) {
            "Invalid updater transaction $name"
        }
        return value
    }

    private fun canTransition(current: TransactionStage, next: TransactionStage): Boolean = when (current) {
        TransactionStage.PREPARED -> next == TransactionStage.CHILD_STARTING || next == TransactionStage.COMMITTING
        TransactionStage.CHILD_STARTING -> next == TransactionStage.FIRST_FRAME
        TransactionStage.FIRST_FRAME -> next == TransactionStage.STABLE
        TransactionStage.STABLE -> next == TransactionStage.READY
        TransactionStage.READY -> next == TransactionStage.OLD_STOPPING || next == TransactionStage.COMMITTING
        TransactionStage.OLD_STOPPING -> next == TransactionStage.COMMITTING
        TransactionStage.COMMITTING -> next in setOf(TransactionStage.COMMITTED, TransactionStage.COMMIT_FAILED)
        TransactionStage.COMMIT_FAILED -> next == TransactionStage.COMMITTING
        TransactionStage.COMMITTED -> false
    }

    private fun sanitizeFailure(exception: Throwable): String =
        (exception.message ?: exception.javaClass.simpleName).replace(Regex("https?://\\S+"), "<download-url>").take(500)

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(path)) {
            Files.deleteIfExists(path)
            return
        }
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    companion object {
        private val TRANSACTION_ID = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
        private const val MAX_TRANSACTION_FILES = 16_384
        private const val MAX_TRANSACTION_LOG_BYTES = 8L * 1024L * 1024L
        private const val MAX_HELPER_JAR_BYTES = 64L * 1024L * 1024L
    }
}
