plugins {
    kotlin("jvm")
    id("net.fabricmc.fabric-loom-remap")
}

base.archivesName.set("${rootProject.property("archives_base_name")}-${rootProject.property("minecraft_artifact_version")}-fabric")
val common = project(":common")

sourceSets.main {
    java.srcDir(common.file("src/main/java"))
    kotlin.srcDir(common.file("src/main/kotlin"))
    rootProject.property("ui_source").toString().split(',').map(String::trim).forEach { source ->
        kotlin.srcDir(common.file("src/$source/kotlin"))
        java.srcDir(common.file("src/$source/java"))
    }
    resources.srcDir(common.file("src/main/resources"))
}

loom {
    mixin { useLegacyMixinAp.set(false) }
    runs {
        named("client") {
            client()
            ideConfigGenerated(true)
            runDir("runs/client")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${rootProject.property("minecraft_version")}")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:${rootProject.property("fabric_loader_version")}")
    modImplementation("net.fabricmc:fabric-language-kotlin:${rootProject.property("kotlin_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${rootProject.property("fabric_api_version")}")
    modImplementation("me.shedaniel.cloth:cloth-config-fabric:${rootProject.property("cloth_config_version")}") {
        exclude(group = "net.fabricmc.fabric-api")
    }
    modImplementation("${rootProject.property("modmenu_coordinate")}:${rootProject.property("modmenu_version")}")
    compileOnly("net.java.dev.jna:jna-platform:5.12.1")
    compileOnly("org.ow2.asm:asm:9.9.1")
    compileOnly("org.ow2.asm:asm-tree:9.9.1")
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "mod_id" to rootProject.property("mod_id"),
        "mod_name" to rootProject.property("mod_name"),
        "mod_author" to rootProject.property("mod_author"),
        "mod_license" to rootProject.property("mod_license"),
        "minecraft_version" to rootProject.property("minecraft_version"),
        "fabric_loader_version" to rootProject.property("fabric_loader_version"),
        "fabric_loader_version_min" to rootProject.property("fabric_loader_version_min"),
        "kotlin_loader_version" to rootProject.property("kotlin_loader_version"),
        "updater_target" to rootProject.property("updater_target"),
        "mixin_java_compatibility" to rootProject.property("mixin_java_compatibility"),
        "pack_format" to rootProject.property("pack_format"),
        "fast_restart_mixins" to rootProject.property("fast_restart_mixins"),
    )
    inputs.properties(props)
    filesMatching(listOf("fabric.mod.json", "pack.mcmeta", "updater363.mixins.json", "updater363-build.properties")) {
        expand(props)
    }
}
