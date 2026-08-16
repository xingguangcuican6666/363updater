import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.21"
    id("net.minecraftforge.gradle") version "6.0.36"
}

group = property("maven_group") as String
version = property("mod_version") as String
base.archivesName.set(property("archives_base_name") as String)

repositories {
    mavenCentral()
    maven("https://thedarkcolour.github.io/KotlinForForge/")
    maven("https://maven.shedaniel.me/")
    maven("https://maven.minecraftforge.net/")
    maven("https://libraries.minecraft.net/")
}

val targetJavaVersion = (property("java_version") as String).toInt()
java.toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))

val handoffJava = sourceSets.create("handoffJava") {
    java.setSrcDirs(listOf("../common/src/main/java"))
}

sourceSets.main {
    java.setSrcDirs(listOf("src/main/java", "../common/src/mc120/java"))
    kotlin.setSrcDirs(
        listOf(
            "src/main/kotlin",
            "../common/src/main/kotlin",
            "../common/src/legacy/kotlin",
            "../common/src/mc120/kotlin",
        ),
    )
    resources.setSrcDirs(listOf("src/main/resources", "../common/src/main/resources"))
}

minecraft {
    mappings("official", property("minecraft_version") as String)
    runs {
        create("client") {
            workingDirectory(project.file("runs/client"))
            property("forge.logging.markers", "REGISTRIES")
            property("forge.logging.console.level", "debug")
            mods {
                create(property("mod_id") as String) { source(sourceSets.main.get()) }
            }
        }
    }
}

dependencies {
    minecraft("net.minecraftforge:forge:${property("forge_version")}")
    implementation("thedarkcolour:kotlinforforge:${property("kotlin_for_forge_version")}")
    implementation("me.shedaniel.cloth:cloth-config-forge:${property("cloth_config_version")}")
    compileOnly("net.java.dev.jna:jna-platform:5.12.1")
    compileOnly("org.ow2.asm:asm:9.9.1")
    compileOnly("org.ow2.asm:asm-tree:9.9.1")
}

handoffJava.compileClasspath += sourceSets.main.get().compileClasspath

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "mod_id" to project.property("mod_id"),
        "mod_name" to project.property("mod_name"),
        "mod_author" to project.property("mod_author"),
        "mod_license" to project.property("mod_license"),
        "minecraft_version" to project.property("minecraft_version"),
        "minecraft_version_range" to project.property("minecraft_version_range"),
        "forge_loader_version_range" to project.property("forge_loader_version_range"),
        "forge_version_range" to project.property("forge_version_range"),
        "cloth_config_version" to project.property("cloth_config_version"),
        "kotlin_for_forge_version_range" to project.property("kotlin_for_forge_version_range"),
        "updater_target" to project.property("updater_target"),
        "mixin_java_compatibility" to project.property("mixin_java_compatibility"),
        "pack_format" to project.property("pack_format"),
        "fast_restart_mixins" to project.property("fast_restart_mixins"),
    )
    inputs.properties(props)
    filesMatching(
        listOf("META-INF/mods.toml", "pack.mcmeta", "updater363.mixins.json", "updater363-build.properties"),
    ) { expand(props) }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.named<KotlinCompile>("compileKotlin") {
    dependsOn(tasks.named(handoffJava.compileJavaTaskName))
    libraries.from(handoffJava.output.classesDirs)
}

tasks.named<JavaCompile>("compileJava") {
    classpath += handoffJava.output
}

tasks.named<Jar>("jar") {
    dependsOn(tasks.named(handoffJava.classesTaskName))
    from(handoffJava.output)
    from("../LICENSE") { rename { "${it}_${project.property("archives_base_name")}" } }
    exclude("META-INF/MANIFEST.MF")
    manifest {
        attributes(
            "MixinConfigs" to "updater363.mixins.json",
            "Specification-Title" to project.property("mod_id"),
            "Specification-Vendor" to project.property("mod_author"),
            "Implementation-Title" to project.name,
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to project.property("mod_author"),
            "Premain-Class" to "com.github.fanziyun.updater.handoff.StagedModsAgent",
            "Can-Redefine-Classes" to "false",
            "Can-Retransform-Classes" to "false",
        )
    }
    finalizedBy("reobfJar")
}
