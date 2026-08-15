pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "net.minecraftforge.gradle") {
                useModule("net.minecraftforge.gradle:ForgeGradle:${requested.version}")
            }
        }
    }
    repositories {
        gradlePluginPortal()
        mavenCentral()
        exclusiveContent {
            forRepository {
                maven("https://maven.fabricmc.net/") { name = "Fabric" }
            }
            filter { includeGroupAndSubgroups("net.fabricmc") }
        }
        maven("https://maven.neoforged.net/releases") { name = "NeoForged" }
        maven("https://maven.minecraftforge.net") { name = "MinecraftForge" }
        maven("https://thedarkcolour.github.io/KotlinForForge/") { name = "Kotlin for Forge" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "363updater"

val updaterTarget = providers.gradleProperty("updater_target").orElse("26.1.2").get()
val profileFile = file("versions/$updaterTarget.properties")
require(profileFile.isFile) {
    "Unknown updater_target '$updaterTarget'. Expected one of the property files under versions/."
}
val profile = java.util.Properties().apply { profileFile.inputStream().use(::load) }

gradle.beforeProject {
    extensions.extraProperties["updater_target"] = updaterTarget
    profile.stringPropertyNames().forEach { key ->
        extensions.extraProperties[key] = profile.getProperty(key)
    }
}

include("common")
if (updaterTarget.startsWith("1.20.1-")) {
    project(":common").buildFileName = "build-source-only.gradle.kts"
}
val loaders = profile.getProperty("loaders").split(',').map(String::trim).filter(String::isNotEmpty)
loaders.filterNot { it == "forge" }.forEach(::include)
if ("fabric" in loaders && profile.getProperty("fabric_remap").toBoolean()) {
    project(":fabric").buildFileName = "build-legacy.gradle.kts"
}
