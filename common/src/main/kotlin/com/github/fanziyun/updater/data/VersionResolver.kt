package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.config.UpdaterConfig
import com.github.fanziyun.updater.platform.Platform
import com.github.fanziyun.updater.util.SemVer
import java.util.Locale

data class VersionSelection(
    val project: String,
    val minecraftVersion: String,
    val loader: String,
    val current: ModrinthVersion,
    val target: ModrinthVersion,
)

class VersionResolver(
    private val client: ModrinthClient,
    private val config: UpdaterConfig,
) {
    fun resolve(): VersionSelection {
        val project = config.modrinthProject.trim().ifBlank { "363fan" }
        val gameVersion = config.minecraftVersion.trim().ifBlank { Platform.INSTANCE.minecraftVersion }
        val loader = config.loader.trim().ifBlank { Platform.INSTANCE.loaderId }.lowercase(Locale.ROOT)
        val currentIdentity = VersionTracker.read(config, project, gameVersion, loader)
            ?: throw IllegalStateException(
                "Current modpack version is unavailable; set it in 363Updater or enable 363Changelog synchronization",
            )
        val allowedTypes = config.versionChannels.split(',')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it in setOf("release", "beta", "alpha") }
            .toSet()
            .ifEmpty { setOf("release") }

        val compatibleVersions = client.versions(project)
            .filter { gameVersion in it.gameVersions && loader in it.loaders }
            .filter { it.environment != "server_only" }
            .filter { it.primaryFile?.fileName?.endsWith(".mrpack", ignoreCase = true) == true }
        val current = currentIdentity.id?.let { currentId ->
            compatibleVersions.firstOrNull { it.id == currentId && it.number == currentIdentity.number }
        } ?: compatibleVersions.filter { it.number == currentIdentity.number }
            .maxWithOrNull(Comparator { left, right -> compareVersions(left, right) })
            ?: throw IllegalStateException(
                "Modrinth has no exact $loader/$gameVersion version matching current version ${currentIdentity.number}"
            )
        val targetVersions = compatibleVersions.filter { it.type in allowedTypes }
        val target = config.targetVersionOverride.trim().takeIf(String::isNotEmpty)?.let { wanted ->
            targetVersions.filter { it.number == wanted }
                .maxWithOrNull(Comparator { left, right -> compareVersions(left, right) })
                ?: throw IllegalStateException("Configured target version $wanted is unavailable")
        } ?: targetVersions.maxWithOrNull(Comparator { left, right -> compareVersions(left, right) })
            ?: throw IllegalStateException("No compatible Modrinth mrpack versions were found")

        return VersionSelection(project, gameVersion, loader, current, target)
    }

    companion object {
        fun compareVersions(left: ModrinthVersion, right: ModrinthVersion): Int =
            compareValues(left.published, right.published).takeIf { it != 0 }
                ?: SemVer.compare(left.number, right.number)

        fun isNewer(target: ModrinthVersion, current: ModrinthVersion): Boolean =
            target.id != current.id && compareVersions(target, current) > 0
    }
}
