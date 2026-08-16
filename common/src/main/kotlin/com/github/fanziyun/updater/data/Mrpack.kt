package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

enum class ClientEnvironment {
    REQUIRED,
    OPTIONAL,
    UNSUPPORTED,
}

enum class PackageFileSource {
    INDEX,
    OVERRIDE,
    CLIENT_OVERRIDE,
}

data class FileHashes(
    val sha1: String?,
    val sha512: String?,
) {
    init {
        require(sha1 == null || sha1.matches(HEX_40)) { "Invalid SHA-1 hash" }
        require(sha512 == null || sha512.matches(HEX_128)) { "Invalid SHA-512 hash" }
        require(sha1 != null || sha512 != null) { "A managed file must have a SHA-1 or SHA-512 hash" }
    }

    fun matches(bytes: ByteArray): Boolean =
        (sha512 == null || digest(bytes, "SHA-512").equals(sha512, ignoreCase = true)) &&
            (sha1 == null || digest(bytes, "SHA-1").equals(sha1, ignoreCase = true))

    fun matches(path: Path): Boolean =
        (sha512 == null || digest(path, "SHA-512").equals(sha512, ignoreCase = true)) &&
            (sha1 == null || digest(path, "SHA-1").equals(sha1, ignoreCase = true))

    fun preferredKey(): String = sha512 ?: sha1 ?: error("Missing file hash")

    fun preferredAlgorithm(): String = if (sha512 != null) "SHA-512" else "SHA-1"

    companion object {
        private val HEX_40 = Regex("[0-9a-fA-F]{40}")
        private val HEX_128 = Regex("[0-9a-fA-F]{128}")

        fun from(hashes: JsonObject?): FileHashes {
            val sha1 = hashes.hash("sha1")
            val sha512 = hashes.hash("sha512")
            return FileHashes(sha1, sha512)
        }

        private fun JsonObject?.hash(name: String): String? = this?.get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotEmpty)

        private fun digest(bytes: ByteArray, algorithm: String): String =
            MessageDigest.getInstance(algorithm).digest(bytes).toHex()

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
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

data class PackageFile(
    val relativePath: String,
    val size: Long,
    val hashes: FileHashes,
    val urls: List<String> = emptyList(),
    val environment: ClientEnvironment = ClientEnvironment.REQUIRED,
    val source: PackageFileSource = PackageFileSource.INDEX,
    val embeddedBytes: ByteArray? = null,
) {
    val isMod: Boolean get() = relativePath.startsWith("mods/")
    val isConfig: Boolean get() = relativePath == "options.txt" || relativePath.startsWith("config/")
    val sourceHost: String?
        get() = urls.asSequence().mapNotNull { runCatching { URI.create(it).host }.getOrNull() }.firstOrNull()

    init {
        require(relativePath == "options.txt" || relativePath.startsWith("config/") || relativePath.startsWith("mods/")) {
            "Unsupported managed mrpack path: $relativePath"
        }
        require(size >= 0) { "Negative size for $relativePath" }
        embeddedBytes?.let { require(it.size.toLong() == size) { "Embedded size mismatch for $relativePath" } }
        urls.forEach(::validateUrl)
    }

    fun hasContent(): Boolean = embeddedBytes != null

    private fun validateUrl(value: String) {
        val uri = runCatching { URI.create(value) }.getOrElse {
            throw IllegalArgumentException("Invalid download URL for $relativePath", it)
        }
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "Managed file URL must be HTTP(S): $relativePath"
        }
        require(uri.userInfo == null) { "Managed file URL must not contain credentials: $relativePath" }
    }
}

data class PackageSnapshot(
    val version: String,
    val files: Map<String, ByteArray>,
    val managedFiles: Map<String, PackageFile> = embeddedManagedFiles(files),
    val indexBytes: ByteArray? = null,
) {
    val managedMods: Map<String, PackageFile> get() = managedFiles.filterValues(PackageFile::isMod)

    val managedConfig: Map<String, PackageFile> get() = managedFiles.filterValues(PackageFile::isConfig)

    fun withFiles(replacements: Map<String, ByteArray>): PackageSnapshot {
        val nextFiles = files.toMutableMap().apply { putAll(replacements) }
        val nextManaged = managedFiles.mapValues { (path, entry) ->
            val bytes = nextFiles[path]
            if (bytes == null || entry.embeddedBytes?.contentEquals(bytes) == true) entry else entry.copy(
                size = bytes.size.toLong(),
                hashes = FileHashes(
                    sha1 = sha1(bytes),
                    sha512 = sha512(bytes),
                ),
                embeddedBytes = bytes,
            )
        }
        return copy(files = nextFiles, managedFiles = nextManaged)
    }

    companion object {
        private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).toHex()
        private fun sha512(bytes: ByteArray): String = MessageDigest.getInstance("SHA-512").digest(bytes).toHex()
        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}

private fun embeddedManagedFiles(files: Map<String, ByteArray>): Map<String, PackageFile> = files.mapValues { (path, bytes) ->
    PackageFile(
        relativePath = path,
        size = bytes.size.toLong(),
        hashes = FileHashes(
            sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).toHex(),
            sha512 = MessageDigest.getInstance("SHA-512").digest(bytes).toHex(),
        ),
        source = PackageFileSource.OVERRIDE,
        embeddedBytes = bytes,
    )
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

object MrpackReader {
    private const val MAX_FILE_BYTES = 1024L * 1024L * 1024L
    private const val MAX_INDEX_BYTES = 16L * 1024L * 1024L
    private const val MAX_FILES = 16_384

    fun read(path: Path, version: String): PackageSnapshot {
        if (!Files.isRegularFile(path)) error("mrpack does not exist: $path")
        val indexFiles = linkedMapOf<String, PackageFile>()
        val overrides = linkedMapOf<String, PackageFile>()
        val clientOverrides = linkedMapOf<String, PackageFile>()
        var hasIndex = false
        var indexBytes: ByteArray? = null
        ZipFile(path.toFile()).use { zip ->
            var count = 0
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++count > MAX_FILES) error("mrpack contains too many files")
                if (entry.isDirectory) continue
                val rawName = entry.name.replace('\\', '/')
                val scopedEntry = rawName == "modrinth.index.json" || rawName.startsWith("overrides/") ||
                    rawName.startsWith("client-overrides/")
                val name = normalize(rawName) ?: if (scopedEntry) {
                    throw IllegalArgumentException("Unsafe mrpack archive path: ${entry.name}")
                } else {
                    continue
                }
                if (name == "modrinth.index.json") {
                    indexBytes = zip.getInputStream(entry).use { input ->
                        readBounded(input, MAX_INDEX_BYTES, "mrpack index")
                    }
                    readIndex(indexBytes, indexFiles)
                    hasIndex = true
                    continue
                }
                val layer = when {
                    name.startsWith("overrides/") -> overrides
                    name.startsWith("client-overrides/") -> clientOverrides
                    else -> null
                } ?: continue
                val prefix = if (layer === clientOverrides) "client-overrides/" else "overrides/"
                val relative = normalize(name.removePrefix(prefix)) ?: continue
                if (!isManagedPath(relative)) continue
                if (relative == "config/updater363.json" || relative == "config/updater363-state.json") continue
                require(!layer.containsKey(relative)) { "Duplicate mrpack override path: $relative" }
                val bytes = zip.getInputStream(entry).use { input -> readBounded(input, MAX_FILE_BYTES, relative) }
                layer[relative] = embedded(relative, bytes, if (layer === clientOverrides) {
                    PackageFileSource.CLIENT_OVERRIDE
                } else {
                    PackageFileSource.OVERRIDE
                })
            }
        }
        require(hasIndex) { "mrpack index is missing" }

        // Modrinth applies overrides after downloaded index files. A client override
        // is the final layer, which is also the behavior of the existing updater.
        val effective = linkedMapOf<String, PackageFile>().apply {
            putAll(indexFiles)
            putAll(overrides)
            putAll(clientOverrides)
        }.filterValues { it.environment != ClientEnvironment.UNSUPPORTED }
        val files = effective.mapNotNull { (pathName, file) -> file.embeddedBytes?.let { pathName to it } }.toMap()
        Updater.LOGGER.info("Read mrpack {} with {} managed files ({} embedded)", version, effective.size, files.size)
        return PackageSnapshot(version, files, effective, indexBytes)
    }

    private fun readIndex(bytes: ByteArray, destination: MutableMap<String, PackageFile>) {
        val root = JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8)).asJsonObject
        require(root.get("formatVersion")?.asInt == 1) { "Unsupported mrpack formatVersion" }
        val files = root.getAsJsonArray("files") ?: JsonArray()
        require(files.size() <= MAX_FILES) { "mrpack index contains too many files" }
        files.forEach { element ->
            val objectFile = element.asJsonObject
            val pathName = normalize(objectFile.get("path")?.asString.orEmpty())
                ?: throw IllegalArgumentException("Unsafe mrpack index path")
            if (!isManagedPath(pathName)) return@forEach
            if (pathName == "config/updater363.json" || pathName == "config/updater363-state.json") return@forEach
            require(!destination.containsKey(pathName)) { "Duplicate mrpack index path: $pathName" }
            val hashes = FileHashes.from(objectFile.getAsJsonObject("hashes"))
            val size = objectFile.get("fileSize")?.asLong ?: -1L
            require(size in 0..MAX_FILE_BYTES) { "Invalid mrpack file size for $pathName" }
            val environment = parseEnvironment(objectFile.getAsJsonObject("env"))
            val urls = objectFile.getAsJsonArray("downloads")?.map { it.asString } ?: emptyList()
            destination[pathName] = PackageFile(
                relativePath = pathName,
                size = size,
                hashes = hashes,
                urls = urls,
                environment = environment,
                source = PackageFileSource.INDEX,
            )
        }
    }

    private fun parseEnvironment(environment: JsonObject?): ClientEnvironment {
        val client = environment?.get("client")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.lowercase(Locale.ROOT)
        return when (client) {
            "unsupported" -> ClientEnvironment.UNSUPPORTED
            "optional" -> ClientEnvironment.OPTIONAL
            else -> ClientEnvironment.REQUIRED
        }
    }

    private fun embedded(pathName: String, bytes: ByteArray, source: PackageFileSource): PackageFile = PackageFile(
        relativePath = pathName,
        size = bytes.size.toLong(),
        hashes = FileHashes(
            sha1 = MessageDigest.getInstance("SHA-1").digest(bytes).toHex(),
            sha512 = MessageDigest.getInstance("SHA-512").digest(bytes).toHex(),
        ),
        source = source,
        embeddedBytes = bytes,
    )

    private fun readBounded(input: java.io.InputStream, maxBytes: Long, description: String): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 1024L * 1024L).toInt())
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) error("$description is too large")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun isManagedPath(pathName: String): Boolean =
        pathName == "options.txt" || pathName.startsWith("config/") || pathName.startsWith("mods/")

    private fun normalize(raw: String): String? {
        if (raw.indexOf('\u0000') >= 0) return null
        val name = raw.replace('\\', '/').removePrefix("./")
        if (name.startsWith('/') || name.isBlank() || name.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            return null
        }
        return name
    }
}
