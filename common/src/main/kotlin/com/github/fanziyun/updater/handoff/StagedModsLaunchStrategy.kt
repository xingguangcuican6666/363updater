package com.github.fanziyun.updater.handoff

import java.nio.file.Path

object StagedModsLaunchStrategy {
    fun prepare(
        command: CurrentJvmCommand,
        loaderId: String,
        generationMods: Path,
        helperJar: Path,
    ): CurrentJvmCommand {
        val clean = command.withoutUpdaterHandoffArguments()
        val staged = generationMods.toAbsolutePath().normalize().toString()
        return when (loaderId.lowercase()) {
            "fabric" -> clean.withSystemProperty("fabric.modsFolder", staged)
            "forge", "neoforge" -> clean
                .withSystemProperty(StagedModsAgent.PROPERTY_LOADER, loaderId.lowercase())
                .withSystemProperty(StagedModsAgent.PROPERTY_STAGED_MODS, staged)
                .withJavaAgent(helperJar)
            else -> error("Unsupported fast-restart loader: $loaderId")
        }
    }
}
