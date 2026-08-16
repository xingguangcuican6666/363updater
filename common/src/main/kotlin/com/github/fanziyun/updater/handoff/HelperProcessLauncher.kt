package com.github.fanziyun.updater.handoff

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

object HelperProcessLauncher {
    fun start(helperJar: Path, request: HelperLaunchRequest): Process {
        require(Files.isRegularFile(helperJar, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(helperJar)) {
            "The copied updater helper JAR is unavailable"
        }
        val java = request.command()?.executable() ?: CurrentJvmCommand.capture().executable()
        val process = ProcessBuilder(
            java,
            "-Xms16m",
            "-Xmx64m",
            "-cp",
            helperJar.toAbsolutePath().normalize().toString(),
            HandoffHelperMain::class.java.name,
        ).apply {
            directory((request.command()?.workingDirectory() ?: request.gameDirectory()).toFile())
            redirectOutput(ProcessBuilder.Redirect.INHERIT)
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }.start()
        try {
            request.write(process.outputStream)
            process.outputStream.close()
        } catch (exception: Exception) {
            process.destroyForcibly()
            throw IllegalStateException("Unable to send the updater helper request", exception)
        }
        return process
    }
}
