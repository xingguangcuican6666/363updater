package com.github.fanziyun.updater.handoff

import com.github.fanziyun.updater.data.LaunchMetrics
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffInfrastructureTest {
    @Test
    fun `fast restart policy covers every release artifact on Windows and Linux`() {
        val artifacts = listOf(
            "1.20.1" to "fabric",
            "1.20.1" to "forge",
            "1.21.1" to "fabric",
            "1.21.1" to "neoforge",
            "26.1.2" to "fabric",
            "26.1.2" to "neoforge",
            "26.2" to "fabric",
            "26.2" to "neoforge",
        )
        artifacts.forEach { (minecraft, loader) ->
            assertTrue(FastRestartPlatformPolicy.supportsProfile(minecraft, loader, "Windows 11"), "$minecraft $loader Windows")
            assertTrue(FastRestartPlatformPolicy.supportsProfile(minecraft, loader, "Linux"), "$minecraft $loader Linux")
            assertFalse(FastRestartPlatformPolicy.supportsProfile(minecraft, loader, "Mac OS X"), "$minecraft $loader macOS")
        }
        assertFalse(FastRestartPlatformPolicy.supportsProfile("1.20.4", "fabric", "Linux"))
        assertFalse(FastRestartPlatformPolicy.supportsProfile("1.21.1", "quilt", "Linux"))
        assertFalse(FastRestartPlatformPolicy.supportsProfile("1.20.1", "neoforge", "Linux"))
        assertFalse(FastRestartPlatformPolicy.supportsProfile("1.21.1", "forge", "Windows 11"))
    }

    @Test
    fun `Windows waits for the old process before committing locked mods`() {
        assertTrue(FastRestartPlatformPolicy.shouldWaitForOldProcessBeforeCommit("Windows 11", true))
        assertFalse(FastRestartPlatformPolicy.shouldWaitForOldProcessBeforeCommit("Windows 11", false))
        assertFalse(FastRestartPlatformPolicy.shouldWaitForOldProcessBeforeCommit("Linux", true))
    }

    @Test
    fun `protocol round trips authenticated binary messages`() {
        val token = "0123456789abcdef0123456789abcdef"
        val expected = HandoffProtocol.Message(HandoffProtocol.Type.SHOW, 1L, 2L, 3L, 4L, true, "ready")
        val output = ByteArrayOutputStream()

        HandoffProtocol.write(output, token, expected)
        val actual = HandoffProtocol.read(ByteArrayInputStream(output.toByteArray()), token)

        assertEquals(expected.type(), actual.type())
        assertEquals(expected.first(), actual.first())
        assertEquals(expected.second(), actual.second())
        assertEquals(expected.third(), actual.third())
        assertEquals(expected.fourth(), actual.fourth())
        assertEquals(expected.flag(), actual.flag())
        assertEquals(expected.text(), actual.text())
        assertFailsWith<IOException> {
            HandoffProtocol.read(ByteArrayInputStream(output.toByteArray()), "fedcba9876543210fedcba9876543210")
        }
    }

    @Test
    fun `handoff server accepts only authenticated child traffic and can reply`() {
        HandoffServer().use { server ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.port), 2_000)
                HandoffProtocol.write(
                    socket.getOutputStream(),
                    server.authenticationToken,
                    HandoffProtocol.Message(HandoffProtocol.Type.HELLO_CHILD, 123L, 0L, 0L, 0L, false, "transaction"),
                )
                val hello = assertNotNull(server.await(2_000L))
                assertTrue(hello.childConnection)
                assertEquals(HandoffProtocol.Type.HELLO_CHILD, hello.message.type())

                server.send(HandoffProtocol.Type.SHOW, 10L, 20L, 800L, 600L, true)
                val show = HandoffProtocol.read(socket.getInputStream(), server.authenticationToken)
                assertEquals(HandoffProtocol.Type.SHOW, show.type())
                assertEquals(800L, show.third())
                assertTrue(show.flag())
            }
        }

        HandoffServer().use { server ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.port), 2_000)
                HandoffProtocol.write(
                    socket.getOutputStream(),
                    "not-the-server-token-1234567890",
                    HandoffProtocol.Message.of(HandoffProtocol.Type.HELLO_CHILD),
                )
            }
            assertNull(server.await(150L))
        }
    }

    @Test
    fun `replaces only JVM properties before the launch target and redacts arguments`() {
        val workingDirectory = Files.createTempDirectory("363updater-command")
        val original = CurrentJvmCommand(
            "/runtime/bin/java",
            listOf(
                "-Xmx2g",
                "-Dfabric.modsFolder=/old/mods",
                "--class-path",
                "libraries/*",
                "example.Main",
                "--accessToken",
                "secret-value",
                "-Dfabric.modsFolder=application-argument",
            ),
            workingDirectory,
        )

        val updated = original.withSystemProperty("fabric.modsFolder", "/new/mods")

        assertEquals(4, CurrentJvmCommand.locateMainIndex(updated.arguments()))
        assertTrue("-Dfabric.modsFolder=/new/mods" in updated.arguments().take(4))
        assertFalse("-Dfabric.modsFolder=/old/mods" in updated.arguments())
        assertTrue("-Dfabric.modsFolder=application-argument" in updated.arguments().drop(4))
        assertFalse(updated.toString().contains("secret-value"))
        assertTrue(updated.toString().contains("arguments=<redacted>"))
        assertFailsWith<IllegalStateException> { CurrentJvmCommand.locateMainIndex(listOf("@launcher.args")) }
        assertFailsWith<IllegalStateException> { CurrentJvmCommand.locateMainIndex(listOf("-jar")) }

        val helper = Files.writeString(workingDirectory.resolve("updater-helper.jar"), "agent")
        val handoff = updated
            .withSystemProperty(StagedModsAgent.PROPERTY_LOADER, "neoforge")
            .withSystemProperty(StagedModsAgent.PROPERTY_STAGED_MODS, "/staged/mods")
            .withJavaAgent(helper)
        val clean = handoff.withoutUpdaterHandoffArguments()
        assertFalse(clean.arguments().any { it.startsWith("-javaagent:") })
        assertFalse(clean.arguments().any { it.startsWith("-Dfabric.modsFolder=") && it != "-Dfabric.modsFolder=application-argument" })
        assertFalse(clean.arguments().any { it.startsWith("-D${StagedModsAgent.PROPERTY_LOADER}=") })
        assertFalse(clean.arguments().any { it.startsWith("-D${StagedModsAgent.PROPERTY_STAGED_MODS}=") })
        assertTrue("-Dfabric.modsFolder=application-argument" in clean.arguments())
    }

    @Test
    fun `uses exact proc cmdline arguments when ProcessHandle omits them`() {
        val procCommandLine = listOf(
            "/runtime/bin/java",
            "-Xmx2g",
            "example.Main",
            "--accessToken",
            "token-value",
        ).joinToString("\u0000", postfix = "\u0000").toByteArray(StandardCharsets.UTF_8)

        val arguments = CurrentJvmCommand.argumentsFrom(null, procCommandLine)

        assertEquals(listOf("-Xmx2g", "example.Main", "--accessToken", "token-value"), arguments)
        assertEquals(
            listOf("example.Main"),
            CurrentJvmCommand.argumentsFrom(arrayOf("example.Main"), "ignored\u0000".toByteArray()),
        )
        assertFailsWith<IllegalStateException> {
            CurrentJvmCommand.argumentsFrom(null, "/runtime/bin/java\u0000example.Main".toByteArray())
        }
    }

    @Test
    fun `staged mods launch strategy selects the loader specific mechanism`() {
        val root = Files.createTempDirectory("363updater-launch-strategy")
        val generation = Files.createDirectories(root.resolve("generation/mods"))
        val helper = Files.writeString(root.resolve("updater-helper.jar"), "agent")
        val command = CurrentJvmCommand(
            "/runtime/bin/java",
            listOf("-Xmx2g", "example.Main", "--accessToken", "secret-value"),
            root,
        )

        val fabric = StagedModsLaunchStrategy.prepare(command, "fabric", generation, helper)
        assertTrue(fabric.arguments().any { it == "-Dfabric.modsFolder=${generation.toAbsolutePath().normalize()}" })
        assertFalse(fabric.arguments().any { it.startsWith("-javaagent:") })

        listOf("forge", "neoforge").forEach { loader ->
            val selected = StagedModsLaunchStrategy.prepare(command, loader, generation, helper)
            assertTrue(selected.arguments().any { it == "-javaagent:${helper.toAbsolutePath().normalize()}" })
            assertTrue(selected.arguments().any { it == "-D${StagedModsAgent.PROPERTY_LOADER}=$loader" })
            assertTrue(selected.arguments().any {
                it == "-D${StagedModsAgent.PROPERTY_STAGED_MODS}=${generation.toAbsolutePath().normalize()}"
            })
            assertFalse(selected.toString().contains("secret-value"))
        }
    }

    @Test
    fun `helper request round trips without writing launcher arguments to disk`() {
        val gameDir = Files.createTempDirectory("363updater-helper-request")
        val id = UUID.randomUUID().toString()
        val transaction = Files.createDirectories(gameDir.resolve(id))
        val command = CurrentJvmCommand(
            "/runtime/bin/java",
            listOf("-Xmx2g", "example.Main", "--accessToken", "secret-value"),
            gameDir,
        )
        val expected = HelperLaunchRequest(
            HelperLaunchRequest.Mode.FAST,
            123L,
            4567,
            "0123456789abcdef0123456789abcdef",
            id,
            transaction,
            gameDir,
            command,
        )
        val output = ByteArrayOutputStream()

        expected.write(output)
        val actual = HelperLaunchRequest.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(expected.mode(), actual.mode())
        assertEquals(expected.transactionId(), actual.transactionId())
        assertEquals(expected.command()!!.arguments(), actual.command()!!.arguments())
        assertFalse(Files.list(transaction).use { it.findAny().isPresent })
        assertFailsWith<IllegalArgumentException> {
            HelperLaunchRequest(
                HelperLaunchRequest.Mode.NORMAL_DEFERRED,
                123L,
                0,
                "",
                UUID.randomUUID().toString(),
                transaction,
                gameDir,
                null,
            )
        }
    }

    @Test
    fun `restart timeout follows first run and bounded historical policy`() {
        assertEquals(300_000L, RestartLaunchPolicy.timeoutMillis(LaunchMetrics()))
        assertEquals(180_000L, RestartLaunchPolicy.timeoutMillis(LaunchMetrics(lastSuccessfulStartupMillis = 1L)))
        assertEquals(200_000L, RestartLaunchPolicy.timeoutMillis(LaunchMetrics(lastSuccessfulStartupMillis = 100_000L)))
        assertEquals(600_000L, RestartLaunchPolicy.timeoutMillis(LaunchMetrics(lastSuccessfulStartupMillis = 999_000L)))
        assertEquals(600_000L, RestartLaunchPolicy.timeoutMillis(LaunchMetrics(lastSuccessfulStartupMillis = Long.MAX_VALUE)))
    }

    @Test
    fun `pure JDK helper atomically commits a deferred generation`() {
        val gameDir = Files.createTempDirectory("363updater-helper-game")
        val liveMods = Files.createDirectories(gameDir.resolve("mods"))
        Files.writeString(liveMods.resolve("old.jar"), "old")
        val id = UUID.randomUUID().toString()
        val transaction = Files.createDirectories(gameDir.resolve("transactions").resolve(id))
        val generation = Files.createDirectories(transaction.resolve("generation/mods"))
        Files.writeString(generation.resolve("new.jar"), "new")
        val backupLauncher = Files.createDirectories(transaction.resolve("backup/launcher"))
        val stagedLauncher = Files.createDirectories(transaction.resolve("staged-launcher"))
        Files.writeString(gameDir.resolve("modrinth.index.json"), "old-index")
        Files.writeString(gameDir.resolve("modpack.cfg"), "old-config")
        Files.writeString(backupLauncher.resolve("modrinth.index.json"), "old-index")
        Files.writeString(backupLauncher.resolve("modpack.cfg"), "old-config")
        Files.writeString(stagedLauncher.resolve("modrinth.index.json"), "target-index")
        Files.writeString(stagedLauncher.resolve("modpack.cfg"), "target-config")
        val request = HelperLaunchRequest(
            HelperLaunchRequest.Mode.NORMAL_DEFERRED,
            Long.MAX_VALUE,
            0,
            "",
            id,
            transaction,
            gameDir,
            null,
        )
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val helperClasses = Path.of(HandoffHelperMain::class.java.protectionDomain.codeSource.location.toURI())
        val process = ProcessBuilder(
            java,
            "-cp",
            helperClasses.toString(),
            HandoffHelperMain::class.java.name,
        ).redirectErrorStream(true).start()
        request.write(process.outputStream)
        process.outputStream.close()

        assertTrue(process.waitFor(10L, TimeUnit.SECONDS), "helper process did not exit")
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.exitValue(), output)
        assertEquals("new", Files.readString(gameDir.resolve("mods/new.jar")))
        assertEquals("old", Files.readString(transaction.resolve("previous-mods/old.jar")))
        assertTrue(Files.isRegularFile(transaction.resolve("helper-commit.ok")))
        assertEquals("new", Files.readString(transaction.resolve("generation/mods/new.jar")))
        assertEquals("target-index", Files.readString(gameDir.resolve("modrinth.index.json")))
        assertEquals("target-config", Files.readString(gameDir.resolve("modpack.cfg")))
    }
}
