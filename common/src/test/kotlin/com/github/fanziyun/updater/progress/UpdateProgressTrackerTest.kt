package com.github.fanziyun.updater.progress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateProgressTrackerTest {
    @Test
    fun `file and overall meters advance independently`() {
        UpdateProgressTracker.reset(totalItems = 2, totalBytes = 200L)
        UpdateProgressTracker.phase(UpdatePhase.DOWNLOAD_PACKAGE)
        UpdateProgressTracker.listener.startFile("mods/a.jar", 100L)
        UpdateProgressTracker.listener.fileBytes(50L)

        var progress = UpdateProgressTracker.progress
        assertEquals(0.5, progress.fileFraction, 1e-9)
        assertEquals(0.0, progress.totalFraction, 1e-9)
        assertEquals("mods/a.jar", progress.fileLabel)
        assertTrue(UpdateProgressTracker.active)

        UpdateProgressTracker.listener.finishFile("mods/a.jar", 100L)
        progress = UpdateProgressTracker.progress
        assertEquals(1, progress.doneItems)
        assertEquals(100L, progress.doneBytes)
        assertEquals(0.5, progress.totalFraction, 1e-9)

        UpdateProgressTracker.listener.startFile("mods/b.jar", 100L)
        UpdateProgressTracker.listener.finishFile("mods/b.jar", 100L)
        progress = UpdateProgressTracker.progress
        assertEquals(2, progress.doneItems)
        assertEquals(1.0, progress.totalFraction, 1e-9)
        // The phase still governs `active`; finishing items alone does not end the run.
        assertTrue(UpdateProgressTracker.active)
        UpdateProgressTracker.done()
        assertFalse(UpdateProgressTracker.active)
    }

    @Test
    fun `done clamps counters to totals and failure clears the file meter`() {
        UpdateProgressTracker.reset(totalItems = 1, totalBytes = 10L)
        UpdateProgressTracker.listener.startFile("config/x.yaml", 10L)
        UpdateProgressTracker.listener.fileBytes(4L)
        UpdateProgressTracker.done()
        assertEquals(UpdatePhase.DONE, UpdateProgressTracker.progress.phase)
        assertEquals(1, UpdateProgressTracker.progress.doneItems)
        assertFalse(UpdateProgressTracker.active)

        UpdateProgressTracker.reset(totalItems = 3, totalBytes = 30L)
        UpdateProgressTracker.listener.startFile("mods/y.jar", 10L)
        UpdateProgressTracker.failed("boom")
        val failed = UpdateProgressTracker.progress
        assertEquals(UpdatePhase.FAILED, failed.phase)
        assertEquals("", failed.fileLabel)
        assertEquals(0L, failed.fileDoneBytes)
        assertEquals("boom", failed.message)
    }

    @Test
    fun `unknown totals never divide by zero`() {
        UpdateProgressTracker.reset()
        UpdateProgressTracker.listener.startFile("mystery", -1L)
        UpdateProgressTracker.listener.fileBytes(12345L)
        val progress = UpdateProgressTracker.progress
        assertEquals(0.0, progress.fileFraction, 1e-9)
        assertEquals(0.0, progress.totalFraction, 1e-9)
    }

    @Test
    fun `view model picks file headline and formats captions`() {
        UpdateProgressTracker.reset(totalItems = 4, totalBytes = 2_000_000L)
        UpdateProgressTracker.listener.startFile("mods/sodium.jar", 1_500_000L)
        UpdateProgressTracker.listener.fileBytes(750_000L)
        val model = ProgressViewModel.of(UpdateProgressTracker.progress)
        assertEquals("screen.updater363.progress.file", model.headlineKey)
        assertEquals("mods/sodium.jar", model.headlineArg)
        assertEquals("732.4 KiB / 1.4 MiB", model.fileCaption)
        assertEquals("0 / 4  0 B / 1.9 MiB", model.totalCaption)
        assertEquals(0.5, model.fileFraction, 1e-9)
        assertFalse(model.failed)
        assertFalse(model.finished)
    }

    @Test
    fun `view model falls back to phase headline without a file`() {
        UpdateProgressTracker.reset()
        UpdateProgressTracker.phase(UpdatePhase.BACKUP, "tx-id")
        val model = ProgressViewModel.of(UpdateProgressTracker.progress)
        assertEquals("screen.updater363.progress.backup", model.headlineKey)
        assertEquals("tx-id", model.headlineArg)
        assertEquals("screen.updater363.progress.unknown", model.filePlaceholderKey)
    }

    @Test
    fun `a completed file keeps its meter full until the next one starts`() {
        UpdateProgressTracker.reset(totalItems = 2, totalBytes = 200L)
        UpdateProgressTracker.listener.startFile("mods/a.jar", 100L)
        UpdateProgressTracker.listener.fileBytes(100L)
        UpdateProgressTracker.listener.finishFile("mods/a.jar", 100L)

        val progress = UpdateProgressTracker.progress
        assertEquals("mods/a.jar", progress.fileLabel)
        assertEquals(1.0, progress.fileFraction, 1e-9)
        assertEquals(1, progress.doneItems)

        UpdateProgressTracker.listener.startFile("mods/b.jar", 100L)
        assertEquals("mods/b.jar", UpdateProgressTracker.progress.fileLabel)
        assertEquals(0.0, UpdateProgressTracker.progress.fileFraction, 1e-9)
    }

    @Test
    fun `a finished run reads both bars full and names phase-specific placeholders`() {
        UpdateProgressTracker.reset(totalItems = 2, totalBytes = 200L)
        UpdateProgressTracker.phase(UpdatePhase.LOADER, "fabric-loader 0.18.6")
        UpdateProgressTracker.done("fabric-loader 0.18.6 installed")

        val model = ProgressViewModel.of(UpdateProgressTracker.progress)
        assertTrue(model.finished)
        assertEquals(1.0, model.fileFraction, 1e-9)
        assertEquals(1.0, model.totalFraction, 1e-9)
        assertEquals("screen.updater363.progress.file_done", model.filePlaceholderKey)
        assertEquals("screen.updater363.progress.status", model.statusKey)
        assertEquals("fabric-loader 0.18.6 installed", model.statusArg)
    }

    @Test
    fun `a failed run reports the error through the shared error key`() {
        UpdateProgressTracker.reset(totalItems = 2, totalBytes = 200L)
        UpdateProgressTracker.failed("Managed file server returned HTTP 404")
        val model = ProgressViewModel.of(UpdateProgressTracker.progress)
        assertTrue(model.failed)
        assertEquals("screen.updater363.error", model.statusKey)
        assertEquals("Managed file server returned HTTP 404", model.statusArg)
        assertEquals("screen.updater363.progress.file_stopped", model.filePlaceholderKey)
    }
}
