plugins {
    kotlin("jvm")
    id("net.neoforged.moddev")
}

base.archivesName.set("${rootProject.property("archives_base_name")}-common")

neoForge { neoFormVersion = rootProject.property("neo_form_version") as String }

dependencies {
    compileOnly("net.fabricmc:sponge-mixin:${rootProject.property("mixin_version")}")
    compileOnly(
        "me.shedaniel.cloth:${rootProject.property("common_cloth_artifact")}:${rootProject.property("cloth_config_version")}",
    )
    testImplementation(kotlin("test"))
    testImplementation("com.google.code.gson:gson:2.13.2")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

sourceSets.main {
    rootProject.property("ui_source").toString().split(',').map(String::trim).forEach { source ->
        kotlin.srcDir("src/$source/kotlin")
    }
}

tasks.jar { enabled = false }
tasks.test {
    useJUnitPlatform()
    listOf("updater.oldPack", "updater.targetPack").forEach { name ->
        System.getProperty(name)?.let { systemProperty(name, it) }
    }
}
