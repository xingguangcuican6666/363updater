package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.merge.FileAction
import com.github.fanziyun.updater.merge.FilePlan
import com.github.fanziyun.updater.merge.UpdatePlan
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UpdateExecutorTest {
    @Test
    fun `stale plans are rejected before a backup or write is attempted`() {
        val gameDir = Files.createTempDirectory("363updater-stale-plan")
        val configDir = Files.createDirectories(gameDir.resolve("config"))
        val path = configDir.resolve("sample.json")
        Files.writeString(path, "changed after preview")
        val plan = UpdatePlan(
            currentVersion = "1.0.0",
            targetVersion = "1.1.0",
            files = listOf(
                FilePlan(
                    relativePath = "config/sample.json",
                    action = FileAction.WRITE,
                    bytes = "target".toByteArray(),
                    expectedCurrent = "previewed".toByteArray(),
                ),
            ),
        )

        val exception = assertFailsWith<IllegalStateException> {
            UpdateExecutor.validateCurrentFiles(plan, gameDir)
        }

        assertTrue(exception.message.orEmpty().contains("changed after the update preview"))
        assertTrue(Files.readString(path) == "changed after preview")
    }
}
