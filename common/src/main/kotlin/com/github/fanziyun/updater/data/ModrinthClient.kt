package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

data class ModrinthFile(
    val url: String,
    val fileName: String,
    val sha1: String?,
    val sha512: String?,
    val size: Long,
    val primary: Boolean,
)

data class ModrinthVersion(
    val id: String,
    val number: String,
    val type: String,
    val published: Instant?,
    val environment: String,
    val gameVersions: Set<String>,
    val loaders: Set<String>,
    val files: List<ModrinthFile>,
) {
    val primaryFile: ModrinthFile?
        get() = files.firstOrNull { it.primary && it.fileName.endsWith(".mrpack", ignoreCase = true) }
            ?: files.firstOrNull { it.fileName.endsWith(".mrpack", ignoreCase = true) }
            ?: files.firstOrNull()
}

class ModrinthException(message: String, cause: Throwable? = null) : IOException(message, cause)

class ModrinthClient(private val timeoutMs: Int) {
    private companion object {
        const val API_ROOT = "https://api.modrinth.com/v2"
        const val MAX_METADATA_BYTES = 8L * 1024L * 1024L
        const val MAX_PACKAGE_BYTES = 128L * 1024L * 1024L
        const val USER_AGENT = "363Updater/0.1.0"
    }

    fun versions(project: String): List<ModrinthVersion> {
        val encoded = URLEncoder.encode(project.trim(), StandardCharsets.UTF_8)
        val body = request("$API_ROOT/project/$encoded/version", MAX_METADATA_BYTES)
        val array = JsonParser.parseString(body).asJsonArray
        return array.map { parseVersion(it.asJsonObject) }
    }

    fun download(version: ModrinthVersion, destination: Path): Path {
        val file = version.primaryFile ?: throw ModrinthException("Modrinth version ${version.number} has no downloadable file")
        if (!file.fileName.endsWith(".mrpack", ignoreCase = true)) {
            throw ModrinthException("Modrinth version ${version.number} has no mrpack file")
        }
        Files.createDirectories(destination.parent)
        val temporary = destination.resolveSibling(".${destination.fileName}.download")
        try {
            val connection = open(file.url)
            connection.inputStream.use { input ->
                BufferedInputStream(input).use { buffered ->
                    Files.newOutputStream(temporary).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = buffered.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_PACKAGE_BYTES) throw ModrinthException("mrpack is too large")
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            verify(temporary, file)
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            return destination
        } catch (exception: Exception) {
            Files.deleteIfExists(temporary)
            if (exception is ModrinthException) throw exception
            throw ModrinthException("Unable to download ${file.fileName}", exception)
        }
    }

    fun cachedFileValid(version: ModrinthVersion, path: Path): Boolean {
        val file = version.primaryFile ?: return false
        if (!Files.isRegularFile(path)) return false
        if (file.size >= 0 && runCatching { Files.size(path) }.getOrDefault(-1L) != file.size) return false
        return runCatching {
            when {
                file.sha512 != null -> digest(path, "SHA-512") == file.sha512.lowercase(Locale.ROOT)
                file.sha1 != null -> digest(path, "SHA-1") == file.sha1.lowercase(Locale.ROOT)
                else -> true
            }
        }.getOrDefault(false)
    }

    private fun parseVersion(json: JsonObject): ModrinthVersion = ModrinthVersion(
        id = json.get("id").asString,
        number = json.get("version_number").asString,
        type = json.get("version_type").asString.lowercase(Locale.ROOT),
        published = json.get("date_published")?.asString?.let { runCatching { Instant.parse(it) }.getOrNull() },
        environment = json.get("environment")?.asString?.lowercase(Locale.ROOT).orEmpty(),
        gameVersions = strings(json.getAsJsonArray("game_versions")),
        loaders = strings(json.getAsJsonArray("loaders")).map { it.lowercase(Locale.ROOT) }.toSet(),
        files = json.getAsJsonArray("files").map { file ->
            val objectFile = file.asJsonObject
            val hashes = objectFile.getAsJsonObject("hashes")
            ModrinthFile(
                url = objectFile.get("url").asString,
                fileName = objectFile.get("filename").asString,
                sha1 = hashes?.get("sha1")?.asString,
                sha512 = hashes?.get("sha512")?.asString,
                size = objectFile.get("size")?.asLong ?: -1L,
                primary = objectFile.get("primary")?.asBoolean == true,
            )
        },
    )

    private fun strings(array: JsonArray?): Set<String> = array?.map { it.asString }?.toSet().orEmpty()

    private fun request(url: String, maxBytes: Long): String {
        val connection = open(url)
        return connection.inputStream.use { input ->
            val bytes = input.readNBytes((maxBytes + 1).toInt())
            if (bytes.size.toLong() > maxBytes) throw ModrinthException("Modrinth response is too large")
            bytes.toString(StandardCharsets.UTF_8)
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.setRequestProperty("Accept", "application/json, application/octet-stream")
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.instanceFollowRedirects = true
        val response = connection.responseCode
        if (response !in 200..299) {
            connection.disconnect()
            throw ModrinthException("Modrinth returned HTTP $response")
        }
        return connection
    }

    private fun verify(path: Path, file: ModrinthFile) {
        file.sha512?.let { expected ->
            if (digest(path, "SHA-512") != expected.lowercase(Locale.ROOT)) {
                throw ModrinthException("SHA-512 verification failed for ${file.fileName}")
            }
            return
        }
        file.sha1?.let { expected ->
            if (digest(path, "SHA-1") != expected.lowercase(Locale.ROOT)) {
                throw ModrinthException("SHA-1 verification failed for ${file.fileName}")
            }
        }
    }

    private fun digest(path: Path, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
