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
        maven("https://maven.minecraftforge.net") { name = "MinecraftForge" }
        maven("https://thedarkcolour.github.io/KotlinForForge/") { name = "Kotlin for Forge" }
        maven("https://maven.shedaniel.me/") { name = "Cloth Config" }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "363updater-1.20.1-forge"
