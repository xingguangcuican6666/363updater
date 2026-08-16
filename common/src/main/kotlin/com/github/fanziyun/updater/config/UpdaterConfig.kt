package com.github.fanziyun.updater.config

import me.shedaniel.autoconfig.ConfigData
import me.shedaniel.autoconfig.annotation.Config
import me.shedaniel.autoconfig.annotation.ConfigEntry
import me.shedaniel.cloth.clothconfig.shadowed.blue.endless.jankson.Comment

@Config(name = "updater363")
class UpdaterConfig : ConfigData {
    @Comment("Modrinth project slug, for example 363fan")
    @ConfigEntry.Category("basic")
    var modrinthProject: String = "363fan"

    @Comment("Override the Minecraft version used for Modrinth filtering; blank uses the running game")
    @ConfigEntry.Category("basic")
    var minecraftVersion: String = ""

    @Comment("Override the loader used for Modrinth filtering; blank uses the running game")
    @ConfigEntry.Category("basic")
    var loader: String = ""

    @Comment("Allowed Modrinth channels: release, beta, alpha")
    @ConfigEntry.Category("basic")
    var versionChannels: String = "release,beta"

    @Comment("Check for updates after launch")
    @ConfigEntry.Category("basic")
    var autoCheck: Boolean = true

    @Comment("Minutes between automatic checks")
    @ConfigEntry.BoundedDiscrete(min = 5, max = 1440)
    @ConfigEntry.Category("basic")
    var checkIntervalMinutes: Int = 60

    @Comment("Network timeout in seconds")
    @ConfigEntry.BoundedDiscrete(min = 5, max = 120)
    @ConfigEntry.Category("advanced")
    var networkTimeoutSeconds: Int = 30

    @Comment("Modrinth-compatible API root; change only for mirrors or local testing")
    @ConfigEntry.Category("advanced")
    var modrinthApiRoot: String = "https://api.modrinth.com/v2"

    @Comment("Keep the last three backups by default")
    @ConfigEntry.BoundedDiscrete(min = 1, max = 10)
    @ConfigEntry.Category("advanced")
    var backupCount: Int = 3

    @Comment("Keep downloaded mrpack files in the updater cache")
    @ConfigEntry.Category("advanced")
    var cachePackages: Boolean = true

    @Comment("Allow deleting keys and files removed by the target package")
    @ConfigEntry.Category("advanced")
    var allowTargetDeletes: Boolean = true

    @Comment("Replace files whose format is not supported by the structured merger")
    @ConfigEntry.Category("advanced")
    var allowUnknownFormatReplacement: Boolean = false

    @Comment("Download and update mods managed by the selected mrpack; code executes only in a new JVM")
    @ConfigEntry.Category("restart")
    var updateManagedMods: Boolean = false

    @Comment("Experimental Linux/Windows two-process restart handoff for supported build profiles")
    @ConfigEntry.Category("restart")
    var experimentalFastRestart: Boolean = false

    @Comment("Reduce temporary work in the old process while a replacement starts; this cannot unload mods or render resources")
    @ConfigEntry.Category("restart")
    var trimOldProcessDuringRestart: Boolean = true

    @Comment("Legacy config-only runtime reload; managed mod changes always require a new JVM")
    @ConfigEntry.Category("advanced")
    var experimentalHotReload: Boolean = false

    @Comment("Current Modrinth version override; blank uses changelog363.json or updater state")
    @ConfigEntry.Category("advanced")
    var currentVersionOverride: String = ""

    @Comment("Read and update config/changelog363.json for the default 363fan project")
    @ConfigEntry.Category("advanced")
    var syncChangelog363Version: Boolean = true

    @Comment("Target Modrinth version override; blank selects the newest configured channel")
    @ConfigEntry.Category("advanced")
    var targetVersionOverride: String = ""
}
