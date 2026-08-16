package com.github.fanziyun.updater.transaction

import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.data.FileHashes
import com.github.fanziyun.updater.data.VersionTracker
import com.github.fanziyun.updater.merge.FileAction
import com.github.fanziyun.updater.merge.FilePlan
import com.github.fanziyun.updater.merge.ManagedFileRecord
import com.github.fanziyun.updater.merge.UpdatePlan
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateTransactionManagerTest {
    @Test
    fun `prepares complete backups staged configuration and a full mod generation`() {
        val fixture = fixture()

        val transaction = fixture.manager.prepare(fixture.plan, fixture.config)

        assertEquals(TransactionStage.PREPARED, transaction.record.stage)
        assertContentEquals(fixture.oldConfig, Files.readAllBytes(transaction.path.resolve("backup/config/example.json")))
        assertContentEquals(fixture.oldOptions, Files.readAllBytes(transaction.path.resolve("backup/options.txt")))
        assertContentEquals(fixture.oldMod, Files.readAllBytes(transaction.path.resolve("backup/mods/managed.jar")))
        assertContentEquals(fixture.newConfig, Files.readAllBytes(transaction.path.resolve("staged/config/example.json")))
        assertContentEquals(fixture.newOptions, Files.readAllBytes(transaction.path.resolve("staged/options.txt")))
        assertContentEquals(fixture.newMod, Files.readAllBytes(transaction.generationMods.resolve("managed.jar")))
        assertEquals("local-extra", Files.readString(transaction.generationMods.resolve("local-extra.jar")))
        assertContentEquals(
            fixture.oldIndex,
            Files.readAllBytes(transaction.path.resolve("backup/launcher/modrinth.index.json")),
        )
        assertContentEquals(
            fixture.targetIndex,
            Files.readAllBytes(transaction.path.resolve("staged-launcher/modrinth.index.json")),
        )
        val pending = VersionTracker.readInstallation("test-pack", "26.1.2", "fabric", fixture.gameDir)!!
        assertEquals("1.0.0", pending.version.number)
        assertEquals(transaction.id, pending.pendingTransaction)
    }

    @Test
    fun `rolls activated configuration back before ready`() {
        val fixture = fixture()
        val transaction = fixture.manager.prepare(fixture.plan, fixture.config)

        val active = fixture.manager.activateConfiguration(transaction)
        assertContentEquals(fixture.newConfig, Files.readAllBytes(fixture.gameDir.resolve("config/example.json")))
        assertContentEquals(fixture.newOptions, Files.readAllBytes(fixture.gameDir.resolve("options.txt")))

        val rolledBack = fixture.manager.rollbackBeforeReady(active)

        assertEquals(TransactionStage.PREPARED, rolledBack.record.stage)
        assertFalse(rolledBack.record.configurationActivated)
        assertContentEquals(fixture.oldConfig, Files.readAllBytes(fixture.gameDir.resolve("config/example.json")))
        assertContentEquals(fixture.oldOptions, Files.readAllBytes(fixture.gameDir.resolve("options.txt")))
    }

    @Test
    fun `commits the staged generation before advancing tracked version state`() {
        val fixture = fixture()
        val transaction = fixture.manager.prepare(fixture.plan, fixture.config)

        val committed = fixture.manager.commit(transaction, fixture.config)

        assertEquals(TransactionStage.COMMITTED, committed.record.stage)
        assertTrue(committed.record.configurationActivated)
        assertTrue(committed.record.modsCommitted)
        assertContentEquals(fixture.newMod, Files.readAllBytes(fixture.gameDir.resolve("mods/managed.jar")))
        assertEquals("local-extra", Files.readString(fixture.gameDir.resolve("mods/local-extra.jar")))
        assertContentEquals(fixture.newMod, Files.readAllBytes(transaction.generationMods.resolve("managed.jar")))
        assertContentEquals(fixture.targetIndex, Files.readAllBytes(fixture.gameDir.resolve("modrinth.index.json")))
        val launcherConfig = JsonParser.parseString(Files.readString(fixture.gameDir.resolve("modpack.cfg"))).asJsonObject
        assertEquals(JsonParser.parseString(fixture.targetIndex.decodeToString()), launcherConfig.get("manifest"))
        assertEquals("1.1.0", launcherConfig.get("version").asString)
        assertEquals("target pack", launcherConfig.get("name").asString)
        assertEquals(1, launcherConfig.getAsJsonArray("overrides").size())
        val state = VersionTracker.readInstallation("test-pack", "26.1.2", "fabric", fixture.gameDir)!!
        assertEquals("1.1.0", state.version.number)
        assertEquals("target-id", state.version.id)
        assertEquals(null, state.pendingTransaction)
        assertEquals(setOf("mods/managed.jar"), state.managedFiles.keys)
    }

    @Test
    fun `keeps a failed commit for retry when the live mod changed after preview`() {
        val fixture = fixture()
        val transaction = fixture.manager.prepare(fixture.plan, fixture.config)
        Files.writeString(fixture.gameDir.resolve("mods/managed.jar"), "changed-after-preview")

        assertFailsWith<IllegalStateException> { fixture.manager.commit(transaction, fixture.config) }

        val failed = fixture.manager.load(transaction.id)
        assertEquals(TransactionStage.COMMIT_FAILED, failed.record.stage)
        assertTrue(Files.isDirectory(failed.generationMods))
        assertEquals("1.0.0", VersionTracker.readInstallation("test-pack", "26.1.2", "fabric", fixture.gameDir)!!.version.number)

        Files.write(fixture.gameDir.resolve("mods/managed.jar"), fixture.oldMod)
        val committed = fixture.manager.commit(failed, fixture.config)
        assertEquals(TransactionStage.COMMITTED, committed.record.stage)
        assertContentEquals(fixture.newMod, Files.readAllBytes(fixture.gameDir.resolve("mods/managed.jar")))
    }

    @Test
    fun `accepts a verified mod generation committed by the restart helper`() {
        val fixture = fixture()
        val transaction = fixture.manager.prepare(fixture.plan, fixture.config)
        val helperPrevious = transaction.path.resolve("helper-previous-mods")
        Files.move(fixture.gameDir.resolve("mods"), helperPrevious)
        Files.move(transaction.generationMods, fixture.gameDir.resolve("mods"))
        Files.writeString(transaction.path.resolve("helper-commit.ok"), "ok\n")

        val committed = fixture.manager.commit(transaction, fixture.config)

        assertEquals(TransactionStage.COMMITTED, committed.record.stage)
        assertTrue(committed.record.modsCommitted)
        assertContentEquals(fixture.newMod, Files.readAllBytes(fixture.gameDir.resolve("mods/managed.jar")))
    }

    @Test
    fun `enforces the restart transaction state machine`() {
        val fixture = fixture()
        var transaction = fixture.manager.prepare(fixture.plan, fixture.config)

        assertFailsWith<IllegalArgumentException> {
            fixture.manager.transition(transaction, TransactionStage.READY)
        }
        transaction = fixture.manager.transition(transaction, TransactionStage.CHILD_STARTING)
        transaction = fixture.manager.transition(transaction, TransactionStage.FIRST_FRAME)
        transaction = fixture.manager.transition(transaction, TransactionStage.STABLE)
        transaction = fixture.manager.transition(transaction, TransactionStage.READY)
        transaction = fixture.manager.transition(transaction, TransactionStage.OLD_STOPPING)

        assertEquals(TransactionStage.OLD_STOPPING, transaction.record.stage)
    }

    @Test
    fun `refreshes copied updater code in a pending transaction`() {
        val fixture = fixture()
        val installedUpdater = fixture.gameDir.resolve("mods/363updater.jar")
        Files.writeString(installedUpdater, "old-updater")
        val manager = UpdateTransactionManager(fixture.gameDir, timeoutMs = 2_000, selfJar = installedUpdater)
        val transaction = manager.prepare(fixture.plan, fixture.config)
        val replacement = Files.createTempFile(fixture.gameDir.resolve("mods"), "updater", ".jar")
        Files.writeString(replacement, "new-updater")
        Files.move(replacement, installedUpdater, StandardCopyOption.REPLACE_EXISTING)

        val refreshed = manager.refreshUpdaterCopies(transaction)

        assertEquals("new-updater", Files.readString(refreshed.generationMods.resolve("363updater.jar")))
        assertEquals("new-updater", Files.readString(refreshed.helperJar!!))
    }

    private fun fixture(): Fixture {
        val gameDir = Files.createTempDirectory("363updater-transaction")
        val configDir = Files.createDirectories(gameDir.resolve("config"))
        val modsDir = Files.createDirectories(gameDir.resolve("mods"))
        val oldConfig = "{\"value\":1}".toByteArray()
        val newConfig = "{\"value\":2}".toByteArray()
        val oldOptions = "music:0.5\n".toByteArray()
        val newOptions = "music:0.7\n".toByteArray()
        val oldMod = "old-managed-mod".toByteArray()
        val newMod = "new-managed-mod".toByteArray()
        Files.write(configDir.resolve("example.json"), oldConfig)
        Files.write(gameDir.resolve("options.txt"), oldOptions)
        Files.write(modsDir.resolve("managed.jar"), oldMod)
        Files.writeString(modsDir.resolve("local-extra.jar"), "local-extra")
        val oldIndex = index("1.0.0", "old pack", oldMod)
        val targetIndex = index("1.1.0", "target pack", newMod)
        Files.write(gameDir.resolve("modrinth.index.json"), oldIndex)
        Files.writeString(
            gameDir.resolve("modpack.cfg"),
            """
                {
                  "type": "Modrinth",
                  "name": "old pack",
                  "version": "1.0.0",
                  "manifest": ${oldIndex.decodeToString()},
                  "overrides": [{"path":"config/local.json","hash":"kept"}]
                }
            """.trimIndent(),
        )
        val targetHashes = hashes(newMod)
        val record = ManagedFileRecord("mods/managed.jar", newMod.size.toLong(), targetHashes)
        val plan = UpdatePlan(
            currentVersion = "1.0.0",
            targetVersion = "1.1.0",
            files = listOf(
                FilePlan(
                    relativePath = "config/example.json",
                    action = FileAction.WRITE,
                    bytes = newConfig,
                    expectedCurrent = oldConfig,
                ),
                FilePlan(
                    relativePath = "options.txt",
                    action = FileAction.WRITE,
                    bytes = newOptions,
                    expectedCurrent = oldOptions,
                ),
                FilePlan(
                    relativePath = "mods/managed.jar",
                    action = FileAction.WRITE,
                    bytes = newMod,
                    expectedCurrentHashes = hashes(oldMod),
                    targetHashes = targetHashes,
                    size = newMod.size.toLong(),
                    managedMod = true,
                ),
            ),
            project = "test-pack",
            minecraftVersion = "26.1.2",
            loader = "fabric",
            targetVersionId = "target-id",
            managedFiles = mapOf(record.relativePath to record),
            requiresRestart = true,
            codeChanges = true,
            targetIndexBytes = targetIndex,
        )
        val config = UpdaterConfig().apply {
            modrinthProject = "test-pack"
            minecraftVersion = "26.1.2"
            loader = "fabric"
            syncChangelog363Version = false
        }
        return Fixture(
            gameDir,
            config,
            UpdateTransactionManager(gameDir, timeoutMs = 2_000, selfJar = null),
            plan,
            oldConfig,
            newConfig,
            oldOptions,
            newOptions,
            oldMod,
            newMod,
            oldIndex,
            targetIndex,
        )
    }

    private fun index(version: String, name: String, mod: ByteArray): ByteArray =
        """
            {
              "formatVersion": 1,
              "game": "minecraft",
              "versionId": "$version",
              "name": "$name",
              "files": [{
                "path": "mods/managed.jar",
                "hashes": {"sha1": "${hashes(mod).sha1}", "sha512": "${hashes(mod).sha512}"},
                "downloads": ["https://example.invalid/managed.jar"],
                "fileSize": ${mod.size}
              }],
              "dependencies": {"minecraft": "26.1.2", "fabric-loader": "0.19.3"}
            }
        """.trimIndent().toByteArray()

    private fun hashes(bytes: ByteArray): FileHashes = FileHashes(
        sha1 = digest(bytes, "SHA-1"),
        sha512 = digest(bytes, "SHA-512"),
    )

    private fun digest(bytes: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val gameDir: java.nio.file.Path,
        val config: UpdaterConfig,
        val manager: UpdateTransactionManager,
        val plan: UpdatePlan,
        val oldConfig: ByteArray,
        val newConfig: ByteArray,
        val oldOptions: ByteArray,
        val newOptions: ByteArray,
        val oldMod: ByteArray,
        val newMod: ByteArray,
        val oldIndex: ByteArray,
        val targetIndex: ByteArray,
    )
}
