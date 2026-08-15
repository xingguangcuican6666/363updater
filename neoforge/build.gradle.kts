plugins {
    kotlin("jvm")
    id("net.neoforged.moddev")
}

base.archivesName.set("${rootProject.property("archives_base_name")}-${rootProject.property("minecraft_artifact_version")}-neoforge")
val common = project(":common")
val modId = rootProject.property("mod_id") as String

sourceSets.main {
    kotlin.srcDir(common.file("src/main/kotlin"))
    rootProject.property("ui_source").toString().split(',').map(String::trim).forEach { source ->
        kotlin.srcDir(common.file("src/$source/kotlin"))
    }
    resources.srcDir(common.file("src/main/resources"))
}

neoForge {
    version = rootProject.property("neoforge_version") as String
    mods { register(modId) { sourceSet(sourceSets.main.get()) } }
    runs {
        register("client") {
            client()
            gameDirectory.set(file("runs/client"))
        }
    }
}

dependencies {
    implementation("thedarkcolour:kotlinforforge-neoforge:${rootProject.property("kotlin_for_forge_version")}")
    implementation("me.shedaniel.cloth:cloth-config-neoforge:${rootProject.property("cloth_config_version")}")
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "mod_id" to modId,
        "mod_name" to rootProject.property("mod_name"),
        "mod_author" to rootProject.property("mod_author"),
        "mod_license" to rootProject.property("mod_license"),
        "minecraft_version" to rootProject.property("minecraft_version"),
        "minecraft_version_range" to rootProject.property("minecraft_version_range"),
        "neoforge_loader_version_range" to rootProject.property("neoforge_loader_version_range"),
        "neoforge_version" to rootProject.property("neoforge_version"),
        "cloth_config_version" to rootProject.property("cloth_config_version"),
        "kotlin_for_forge_version_range" to rootProject.property("kotlin_for_forge_version_range"),
        "updater_target" to rootProject.property("updater_target"),
        "mixin_java_compatibility" to rootProject.property("mixin_java_compatibility"),
        "pack_format" to rootProject.property("pack_format"),
    )
    inputs.properties(props)
    filesMatching(
        listOf(
            "META-INF/neoforge.mods.toml",
            "pack.mcmeta",
            "updater363.mixins.json",
            "updater363-build.properties",
        ),
    ) { expand(props) }
}
