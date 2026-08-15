import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    base
    kotlin("jvm") version "2.3.21" apply false
    id("net.fabricmc.fabric-loom") version "1.17.17" apply false
    id("net.neoforged.moddev") version "2.0.141" apply false
}

if (property("updater_target") == "1.20.1-forge") {
    val prepareForgeWrapper = tasks.register<Copy>("prepareForgeWrapper") {
        from("gradlew", "gradlew.bat")
        from("gradle/wrapper/gradle-wrapper.jar") { into("gradle/wrapper") }
        into("forge")
    }
    val nestedForgeBuild = tasks.register<Exec>("buildForge120") {
        group = "build"
        description = "Builds the Forge 1.20.1 artifact with its Gradle 8 toolchain"
        dependsOn(prepareForgeWrapper)
        workingDir("forge")
        val forwardedProperties = listOf(
            "https.proxyHost",
            "https.proxyPort",
            "http.proxyHost",
            "http.proxyPort",
            "org.gradle.internal.http.connectionTimeout",
            "org.gradle.internal.http.socketTimeout",
        ).mapNotNull { key -> System.getProperty(key)?.let { "-D$key=$it" } }
        val forgeJavaHome = listOfNotNull(
            System.getenv("UPDATER_FORGE_JAVA_HOME"),
            System.getenv("JAVA17_HOME"),
            "/usr/lib/jvm/java-17-openjdk".takeIf { file(it).isDirectory },
            "/usr/lib/jvm/java-17-openjdk-amd64".takeIf { file(it).isDirectory },
        ).firstOrNull()
        if (forgeJavaHome != null) {
            environment("JAVA_HOME", forgeJavaHome)
            environment("PATH", "$forgeJavaHome/bin${File.pathSeparator}${System.getenv("PATH")}")
        }
        commandLine(listOf("./gradlew", "build") + forwardedProperties)
    }
    tasks.named("build") { dependsOn(nestedForgeBuild) }
}

val javaVersion = (property("java_version") as String).toInt()

subprojects {
    group = rootProject.property("maven_group") as String
    version = rootProject.property("mod_version") as String

    repositories {
        mavenCentral()
        maven("https://maven.shedaniel.me/") { name = "Cloth Config" }
        maven("https://maven.terraformersmc.com/releases/") { name = "Mod Menu" }
        maven("https://api.modrinth.com/maven") { name = "Modrinth" }
        maven("https://thedarkcolour.github.io/KotlinForForge/") { name = "Kotlin for Forge" }
        maven("https://maven.minecraftforge.net") { name = "MinecraftForge" }
    }

    plugins.withId("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(javaVersion)
    }

    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
    }

    tasks.withType<ProcessResources>().configureEach { filteringCharset = "UTF-8" }

    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) { rename { "${it}_${rootProject.property("archives_base_name")}" } }
    }
}
