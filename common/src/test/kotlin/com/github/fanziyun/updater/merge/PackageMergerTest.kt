package com.github.fanziyun.updater.merge

import com.github.fanziyun.updater.data.PackageSnapshot
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PackageMergerTest {
    @Test
    fun `preserves user values while applying package defaults`() {
        val gameDir = Files.createTempDirectory("363updater-test")
        val configDir = Files.createDirectories(gameDir.resolve("config"))
        Files.writeString(
            configDir.resolve("sample.json"),
            """{"A":"c","keep":"x","removed":"r","obj":{"x":9,"user":"old"}}""",
            StandardCharsets.UTF_8,
        )
        val old = PackageSnapshot(
            "1.0.0",
            mapOf("config/sample.json" to """{"A":"a","keep":"x","removed":"r","obj":{"x":1,"user":"old"}}""".toByteArray()),
        )
        val target = PackageSnapshot(
            "1.1.0",
            mapOf("config/sample.json" to """{"A":"b","keep":"y","obj":{"x":2,"user":"new","added":true}}""".toByteArray()),
        )

        assertNotNull(ConfigCodecs.parse("config/sample.json", old.files.getValue("config/sample.json")))

        val plan = PackageMerger.preview(old, target, MergeOptions(), gameDir)
        val file = plan.changedFiles.single()
        val result = file.bytes!!.toString(StandardCharsets.UTF_8)

        assertTrue(result.contains("\"A\": \"c\""), result)
        assertTrue(result.contains("\"keep\": \"y\""), result)
        assertTrue(result.contains("\"x\": 9"), result)
        assertTrue(result.contains("\"user\": \"new\""), result)
        assertTrue(result.contains("\"added\": true"), result)
        assertTrue(!result.contains("removed"), result)
        assertEquals(1, plan.removedKeys)
    }

    @Test
    fun `replaces unsupported files only after they are represented in the plan`() {
        val gameDir = Files.createTempDirectory("363updater-test")
        val configDir = Files.createDirectories(gameDir.resolve("config"))
        Files.writeString(configDir.resolve("sample.yaml"), "value: user\n")
        val old = PackageSnapshot("1.0.0", mapOf("config/sample.yaml" to "value: old\n".toByteArray()))
        val target = PackageSnapshot("1.1.0", mapOf("config/sample.yaml" to "value: new\n".toByteArray()))

        val plan = PackageMerger.preview(
            old,
            target,
            MergeOptions(allowUnknownFormatReplacement = true),
            gameDir,
        )

        assertEquals(FileAction.WRITE, plan.changedFiles.single().action)
        assertTrue(plan.changedFiles.single().warning!!.contains("not safely mergeable"))
        assertEquals("value: new\n", plan.changedFiles.single().bytes!!.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun `line files preserve user values and append target keys`() {
        val gameDir = Files.createTempDirectory("363updater-test")
        val configDir = Files.createDirectories(gameDir.resolve("config"))
        Files.writeString(
            configDir.resolve("sample.properties"),
            "a=9\nkeep = old\nremove=x\nuserOnly=y\n# keep this comment\n",
        )
        val old = PackageSnapshot(
            "1.0.0",
            mapOf("config/sample.properties" to "a=1\nkeep = old\nremove=x\n".toByteArray()),
        )
        val target = PackageSnapshot(
            "1.1.0",
            mapOf("config/sample.properties" to "a=2\nkeep = new\nadded=3\n".toByteArray()),
        )

        val result = PackageMerger.preview(old, target, MergeOptions(), gameDir)
            .changedFiles.single().bytes!!.toString(StandardCharsets.UTF_8)

        assertTrue("a=9" in result, result)
        assertTrue("keep = new" in result, result)
        assertTrue("added=3" in result, result)
        assertTrue("# keep this comment" in result, result)
        assertTrue("remove=" !in result, result)
        assertTrue("userOnly=y" in result, result)
    }

    @Test
    fun `options additions use colon delimiters`() {
        val gameDir = Files.createTempDirectory("363updater-test")
        Files.writeString(gameDir.resolve("options.txt"), "music:0.5\n")
        val old = PackageSnapshot("1.0.0", mapOf("options.txt" to "music:0.5\n".toByteArray()))
        val target = PackageSnapshot("1.1.0", mapOf("options.txt" to "music:0.7\nnewOption:true\n".toByteArray()))

        val result = PackageMerger.preview(old, target, MergeOptions(), gameDir)
            .changedFiles.single().bytes!!.toString(StandardCharsets.UTF_8)

        assertEquals("music:0.7\nnewOption:true\n", result)
    }

    @Test
    fun `preserves local keys and local deletions while applying safe target changes`() {
        val gameDir = Files.createTempDirectory("363updater-test")
        val configDir = Files.createDirectories(gameDir.resolve("config"))
        Files.writeString(
            configDir.resolve("sample.json"),
            """{"changed":9,"localOnly":true,"nested":{"keep":"local"}}""",
        )
        val old = PackageSnapshot(
            "1.0.0",
            mapOf(
                "config/sample.json" to
                    """{"changed":1,"deletedLocally":1,"removedByTarget":1,"nested":{"keep":"old"}}""".toByteArray(),
            ),
        )
        val target = PackageSnapshot(
            "1.1.0",
            mapOf(
                "config/sample.json" to
                    """{"changed":2,"deletedLocally":2,"added":3,"nested":{"keep":"target","added":true}}""".toByteArray(),
            ),
        )

        val result = PackageMerger.preview(old, target, MergeOptions(), gameDir)
            .changedFiles.single().bytes!!.toString(StandardCharsets.UTF_8)

        assertTrue("\"changed\": 9" in result, result)
        assertTrue("deletedLocally" !in result, result)
        assertTrue("removedByTarget" !in result, result)
        assertTrue("\"localOnly\": true" in result, result)
        assertTrue("\"keep\": \"local\"" in result, result)
        assertTrue("\"added\": true" in result, result)
    }

    @Test
    fun `target delete setting applies only to unmodified local values and files`() {
        val gameDir = Files.createTempDirectory("363updater-test")
        val configDir = Files.createDirectories(gameDir.resolve("config"))
        Files.writeString(configDir.resolve("sample.json"), """{"keep":1,"changed":9}""")
        Files.writeString(configDir.resolve("unchanged.txt"), "old\n")
        Files.writeString(configDir.resolve("changed.txt"), "local\n")
        val old = PackageSnapshot(
            "1.0.0",
            mapOf(
                "config/sample.json" to """{"keep":1,"changed":1}""".toByteArray(),
                "config/unchanged.txt" to "old\n".toByteArray(),
                "config/changed.txt" to "old\n".toByteArray(),
            ),
        )
        val target = PackageSnapshot("1.1.0", mapOf("config/sample.json" to "{}".toByteArray()))

        val deleting = PackageMerger.preview(old, target, MergeOptions(allowTargetDeletes = true), gameDir)
        val deletingJson = deleting.updatedFiles.single { it.relativePath == "config/sample.json" }
            .bytes!!.toString(StandardCharsets.UTF_8)
        assertTrue("keep" !in deletingJson, deletingJson)
        assertTrue("\"changed\": 9" in deletingJson, deletingJson)
        assertEquals(FileAction.DELETE, deleting.files.single { it.relativePath == "config/unchanged.txt" }.action)
        assertEquals(FileAction.UNCHANGED, deleting.files.single { it.relativePath == "config/changed.txt" }.action)

        val preserving = PackageMerger.preview(old, target, MergeOptions(allowTargetDeletes = false), gameDir)
        val preservingJson = preserving.updatedFiles.single { it.relativePath == "config/sample.json" }
            .bytes!!.toString(StandardCharsets.UTF_8)
        assertTrue("\"keep\": 1" in preservingJson, preservingJson)
        assertTrue("\"changed\": 9" in preservingJson, preservingJson)
        assertTrue(preserving.deletedFiles.isEmpty())
    }

    @Test
    fun `does not overwrite a locally created file that collides with a target addition`() {
        val gameDir = Files.createTempDirectory("363updater-test")
        val configDir = Files.createDirectories(gameDir.resolve("config"))
        Files.writeString(configDir.resolve("sample.json"), """{"local":true,"same":9}""")
        val old = PackageSnapshot("1.0.0", emptyMap())
        val target = PackageSnapshot(
            "1.1.0",
            mapOf("config/sample.json" to """{"target":true,"same":1}""".toByteArray()),
        )

        val result = PackageMerger.preview(old, target, MergeOptions(), gameDir)
            .changedFiles.single().bytes!!.toString(StandardCharsets.UTF_8)

        assertTrue("\"local\": true" in result, result)
        assertTrue("\"target\": true" in result, result)
        assertTrue("\"same\": 9" in result, result)
    }

    @Test
    fun `replaces an unchanged unknown file without enabling destructive replacement`() {
        val gameDir = Files.createTempDirectory("363updater-test")
        val configDir = Files.createDirectories(gameDir.resolve("config"))
        Files.writeString(configDir.resolve("sample.yaml"), "value: old\n")
        val old = PackageSnapshot("1.0.0", mapOf("config/sample.yaml" to "value: old\n".toByteArray()))
        val target = PackageSnapshot("1.1.0", mapOf("config/sample.yaml" to "value: new\n".toByteArray()))

        val file = PackageMerger.preview(
            old,
            target,
            MergeOptions(allowUnknownFormatReplacement = false),
            gameDir,
        ).changedFiles.single()

        assertEquals(FileAction.WRITE, file.action)
        assertEquals("value: new\n", file.bytes!!.toString(StandardCharsets.UTF_8))
        assertTrue(file.warning!!.contains("not safely mergeable"))
    }

    @Test
    fun `updates an unchanged free form txt file instead of silently treating it as empty properties`() {
        val gameDir = Files.createTempDirectory("363updater-test")
        val configDir = Files.createDirectories(gameDir.resolve("config"))
        Files.writeString(configDir.resolve("rules.txt"), "old free form text\n")
        val old = PackageSnapshot("1.0.0", mapOf("config/rules.txt" to "old free form text\n".toByteArray()))
        val target = PackageSnapshot("1.1.0", mapOf("config/rules.txt" to "new free form text\n".toByteArray()))

        val file = PackageMerger.preview(old, target, MergeOptions(), gameDir).changedFiles.single()

        assertEquals("new free form text\n", file.bytes!!.toString(StandardCharsets.UTF_8))
        assertTrue(file.warning!!.contains("not safely mergeable"))
    }

    @Test
    fun `rejects a directory where the target package expects a file`() {
        val gameDir = Files.createTempDirectory("363updater-test")
        Files.createDirectories(gameDir.resolve("config/sample.json"))
        val target = PackageSnapshot(
            "1.1.0",
            mapOf("config/sample.json" to "{}".toByteArray()),
        )

        val exception = assertFailsWith<IllegalArgumentException> {
            PackageMerger.preview(PackageSnapshot("1.0.0", emptyMap()), target, MergeOptions(), gameDir)
        }

        assertTrue(exception.message.orEmpty().contains("regular files"))
    }
}
