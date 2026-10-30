package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.progress.ProgressListener
import com.github.fanziyun.updater.progress.UpdatePhase
import com.github.fanziyun.updater.util.AtomicFiles
import com.github.fanziyun.updater.util.SemVer
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

class FabricLoaderUpdateException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/**
 * Installs the Fabric loader version declared by the target mrpack over the current installation.
 *
 * The flow mirrors what a launcher does: resolve the loader and a stable installer from the Fabric
 * meta API, download the installer JAR into the updater cache (reported through the shared progress
 * listener), then run it in `client -dir <gameDir>` mode so the loader profile and its libraries land
 * next to the game directory and the next launch overwrites the current loader version.
 *
 * Network and process steps are thin; the decision logic and command shape live in pure functions so
 * they stay unit-testable.
 */
object FabricLoaderUpdater {
    const val META_ROOT = "https://meta.fabricmc.net/v2"
    const val MAX_INSTALLER_BYTES = 64L * 1024L * 1024L
    private const val MAX_METADATA_BYTES = 8L * 1024L * 1024L

    fun loaderMetaUrl(minecraftVersion: String, loaderVersion: String): String =
        "$META_ROOT/versions/loader/${encode(minecraftVersion)}/${encode(loaderVersion)}"

    fun installerListUrl(): String = "$META_ROOT/versions/installer"

    fun installerJarUrl(installerVersion: String): String =
        "$META_ROOT/versions/installer/${encode(installerVersion)}/installer/jar"

    /** True when [target] is a non-blank strict upgrade over [current] (blank current always upgrades). */
    fun shouldUpdate(current: String, target: String): Boolean {
        if (target.isBlank()) return false
        if (current.isBlank()) return true
        return SemVer.compare(target, current) > 0
    }

    /** Pick the newest stable installer version from the meta `versions/installer` payload. */
    fun selectInstallerVersion(payload: String): String {
        val array = JsonParser.parseString(payload).asJsonArray
        val stable = array.map { it.asJsonObject }
            .filter { it.get("stable")?.asBoolean == true }
            .map { it.get("version").asString }
            .filter(String::isNotBlank)
        return (stable.maxWithOrNull(Comparator { a, b -> SemVer.compare(a, b) })
            ?: throw FabricLoaderUpdateException("Fabric meta returned no stable installer"))
    }

    fun installCommand(java: Path, installerJar: Path, gameDir: Path, minecraftVersion: String, loaderVersion: String): List<String> =
        listOf(
            java.toAbsolutePath().normalize().toString(),
            "-jar",
            installerJar.toAbsolutePath().normalize().toString(),
            "client",
            "-dir",
            gameDir.toAbsolutePath().normalize().toString(),
            "-mcversion",
            minecraftVersion,
            "-loader",
            loaderVersion,
        )

    /**
     * Downloads and installs [targetLoaderVersion]. Returns the installed version, or `null` when no
     * update is needed. Reports every step through [listener] under [UpdatePhase.LOADER].
     */
    fun update(
        gameDir: Path,
        minecraftVersion: String,
        currentLoaderVersion: String,
        targetLoaderVersion: String,
        timeoutMs: Int,
        listener: ProgressListener = ProgressListener.NONE,
        javaHome: Path = Path.of(System.getProperty("java.home")),
    ): String? {
        if (!shouldUpdate(currentLoaderVersion, targetLoaderVersion)) return null
        listener.phase(UpdatePhase.LOADER, "fabric-loader $currentLoaderVersion -> $targetLoaderVersion")

        val loaderMeta = request(loaderMetaUrl(minecraftVersion, targetLoaderVersion), timeoutMs)
            ?: throw FabricLoaderUpdateException(
                "Fabric meta has no loader $targetLoaderVersion for Minecraft $minecraftVersion",
            )
        JsonParser.parseString(loaderMeta).asJsonObject.get("version")?.asString
            ?.takeIf { it.equals(targetLoaderVersion, ignoreCase = true) }
            ?: throw FabricLoaderUpdateException("Fabric meta returned an unexpected loader version")

        val installerPayload = request(installerListUrl(), timeoutMs)
            ?: throw FabricLoaderUpdateException("Fabric meta installer list is unavailable")
        val installerVersion = selectInstallerVersion(installerPayload)
        val cacheDir = gameDir.resolve(".cache").resolve(Updater.MOD_ID).resolve("loader")
        Files.createDirectories(cacheDir)
        val installerJar = cacheDir.resolve("fabric-installer-$installerVersion.jar")

        listener.startFile(installerJar.fileName.toString(), -1L)
        download(installerJarUrl(installerVersion), installerJar, timeoutMs) { done -> listener.fileBytes(done) }
        listener.finishFile(installerJar.fileName.toString(), runCatching { Files.size(installerJar) }.getOrDefault(0L))

        val java = javaHome.resolve("bin").resolve(if (isWindows()) "java.exe" else "java")
        require(Files.isRegularFile(java, LinkOption.NOFOLLOW_LINKS)) { "Java executable is unavailable: $java" }
        val command = installCommand(java, installerJar, gameDir, minecraftVersion, targetLoaderVersion)
        Updater.LOGGER.info("Installing Fabric loader: {}", command.joinToString(" "))
        listener.message("fabric-installer $installerVersion")
        val process = ProcessBuilder(command)
            .directory(gameDir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.use { input ->
            input.readNBytes(1024 * 1024).toString(Charsets.UTF_8)
        }
        val exit = process.waitFor()
        if (exit != 0) {
            throw FabricLoaderUpdateException(
                "Fabric installer exited with $exit: ${output.lineSequence().lastOrNull { it.isNotBlank() } ?: ""}",
            )
        }
        val profileDir = gameDir.resolve("versions").resolve("fabric-loader-$minecraftVersion-$targetLoaderVersion")
        if (!Files.isDirectory(profileDir, LinkOption.NOFOLLOW_LINKS)) {
            Updater.LOGGER.warn("Fabric loader profile directory was not created: {}", profileDir)
        }
        Updater.LOGGER.info("Installed Fabric loader {} over {}", targetLoaderVersion, currentLoaderVersion)
        return targetLoaderVersion
    }

    private fun download(url: String, destination: Path, timeoutMs: Int, onBytes: (Long) -> Unit) {
        val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}.", ".download")
        try {
            val connection = open(url, timeoutMs)
            try {
                connection.inputStream.use { raw ->
                    java.io.BufferedInputStream(raw).use { input ->
                        Files.newOutputStream(temporary).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_INSTALLER_BYTES) {
                                    throw FabricLoaderUpdateException("Fabric installer is too large")
                                }
                                output.write(buffer, 0, count)
                                onBytes(total)
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (exception: Exception) {
            runCatching { Files.deleteIfExists(temporary) }
            if (exception is FabricLoaderUpdateException) throw exception
            throw FabricLoaderUpdateException("Unable to download the Fabric installer", exception)
        }
    }

    private fun request(url: String, timeoutMs: Int): String? {
        val connection = open(url, timeoutMs)
        return try {
            connection.inputStream.use { input ->
                val bytes = input.readNBytes((MAX_METADATA_BYTES + 1).toInt())
                if (bytes.size.toLong() > MAX_METADATA_BYTES) {
                    throw FabricLoaderUpdateException("Fabric meta response is too large")
                }
                bytes.toString(Charsets.UTF_8)
            }
        } catch (exception: java.io.IOException) {
            Updater.LOGGER.warn("Fabric meta request failed: {}", url, exception)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        val uri = URI.create(url)
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "Fabric meta URL must be HTTP(S)"
        }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json, application/octet-stream")
        connection.setRequestProperty("User-Agent", ModrinthClient.USER_AGENT)
        val response = connection.responseCode
        if (response !in 200..299) {
            connection.disconnect()
            throw FabricLoaderUpdateException("Fabric meta returned HTTP $response for $url")
        }
        return connection
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value.trim(), Charsets.UTF_8).replace("+", "%20")

    private fun isWindows(): Boolean =
        System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
}
