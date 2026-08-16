package com.github.fanziyun.updater.data

import com.github.fanziyun.updater.Updater
import com.github.fanziyun.updater.platform.Platform
import java.io.BufferedInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale

class ManagedFileDownloadException(message: String, cause: Throwable? = null) : IOException(message, cause)

class ManagedFileCache(
    private val timeoutMs: Int,
    private val root: Path = Platform.INSTANCE.gameDir.resolve(".cache").resolve(Updater.MOD_ID).resolve("objects"),
) {
    companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_ARTIFACT_BYTES = 1024L * 1024L * 1024L
        const val MAX_PREVIEW_FILE_BYTES = 64L * 1024L * 1024L
    }

    fun materializeForPreview(snapshot: PackageSnapshot): PackageSnapshot {
        val materialized = linkedMapOf<String, ByteArray>()
        snapshot.managedConfig.values.forEach { file ->
            if (file.embeddedBytes != null) return@forEach
            require(file.size <= MAX_PREVIEW_FILE_BYTES) {
                "Managed config file is too large to merge safely: ${file.relativePath}"
            }
            val objectPath = materialize(file)
            materialized[file.relativePath] = Files.readAllBytes(objectPath)
        }
        return if (materialized.isEmpty()) snapshot else snapshot.withFiles(materialized)
    }

    fun materialize(file: PackageFile): Path {
        val destination = objectPath(file.hashes)
        validateDestination(destination)
        if (Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) && valid(destination, file)) return destination
        validateCacheParents(destination.parent)
        Files.createDirectories(destination.parent)
        validateCacheParents(destination.parent)
        file.embeddedBytes?.let { bytes ->
            require(bytes.size.toLong() == file.size) { "Embedded size mismatch for ${file.relativePath}" }
            require(file.hashes.matches(bytes)) { "Embedded hash mismatch for ${file.relativePath}" }
            writeObject(destination, bytes)
            return destination
        }
        require(file.urls.isNotEmpty()) { "Managed file has no download URL: ${file.relativePath}" }
        val failures = mutableListOf<Throwable>()
        file.urls.forEach { url ->
            val result = runCatching { download(url, file, destination) }
            if (result.isSuccess) return destination
            failures += result.exceptionOrNull() ?: return@forEach
        }
        val failure = ManagedFileDownloadException("Unable to download managed file ${file.relativePath}")
        failures.forEach(failure::addSuppressed)
        throw failure
    }

    fun contains(file: PackageFile): Boolean {
        val path = objectPath(file.hashes)
        return runCatching {
            validateDestination(path)
            validateCacheParents(path.parent)
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && valid(path, file)
        }.getOrDefault(false)
    }

    internal fun objectPath(hashes: FileHashes): Path {
        val algorithm = if (hashes.sha512 != null) "sha512" else "sha1"
        val hash = hashes.preferredKey().lowercase(Locale.ROOT)
        return root.resolve(algorithm).resolve(hash.take(2)).resolve(hash)
    }

    private fun download(url: String, file: PackageFile, destination: Path) {
        val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}.", ".download")
        try {
            val connection = openFollowingRedirects(URI.create(url))
            try {
                val contentLength = connection.contentLengthLong
                if (contentLength > MAX_ARTIFACT_BYTES || file.size >= 0 && contentLength >= 0 && contentLength != file.size) {
                    throw ManagedFileDownloadException("Unexpected download size for ${file.relativePath}")
                }
                val sha1 = MessageDigest.getInstance("SHA-1")
                val sha512 = MessageDigest.getInstance("SHA-512")
                connection.inputStream.use { raw ->
                    BufferedInputStream(raw).use { input ->
                        Files.newOutputStream(temporary).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > MAX_ARTIFACT_BYTES || total > file.size) {
                                    throw ManagedFileDownloadException("Managed file is too large: ${file.relativePath}")
                                }
                                sha1.update(buffer, 0, count)
                                sha512.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                            if (total != file.size) {
                                throw ManagedFileDownloadException("Downloaded size mismatch for ${file.relativePath}")
                            }
                        }
                    }
                }
                verifyDigests(file, sha1.digest().toHex(), sha512.digest().toHex())
            } finally {
                connection.disconnect()
            }
            moveObject(temporary, destination)
        } catch (exception: Exception) {
            throw if (exception is ManagedFileDownloadException) exception else {
                ManagedFileDownloadException("Managed file download failed: ${file.relativePath}", exception)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun openFollowingRedirects(initial: URI): HttpURLConnection {
        var current = validateUri(initial)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection = current.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("User-Agent", ModrinthClient.USER_AGENT)
            val response = connection.responseCode
            if (response in 200..299) return connection
            if (response in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (redirectCount >= MAX_REDIRECTS || location.isNullOrBlank()) {
                    throw ManagedFileDownloadException("Too many or invalid managed-file redirects")
                }
                current = validateUri(current.resolve(location))
                return@repeat
            }
            connection.disconnect()
            throw ManagedFileDownloadException("Managed file server returned HTTP $response")
        }
        throw ManagedFileDownloadException("Too many managed-file redirects")
    }

    private fun validateUri(uri: URI): URI {
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "Managed file URL must be HTTP(S)"
        }
        require(uri.userInfo == null) { "Managed file URL must not contain credentials" }
        return uri
    }

    private fun valid(path: Path, file: PackageFile): Boolean =
        Files.size(path) == file.size && file.hashes.matches(path)

    private fun verifyDigests(file: PackageFile, sha1: String, sha512: String) {
        file.hashes.sha1?.let {
            require(it.equals(sha1, ignoreCase = true)) { "SHA-1 verification failed for ${file.relativePath}" }
        }
        file.hashes.sha512?.let {
            require(it.equals(sha512, ignoreCase = true)) { "SHA-512 verification failed for ${file.relativePath}" }
        }
    }

    private fun validateDestination(destination: Path) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalized = destination.toAbsolutePath().normalize()
        require(normalized.startsWith(normalizedRoot)) { "Unsafe updater object-cache path" }
        validateNoSymlinkComponents(normalizedRoot)
        require(!Files.isSymbolicLink(destination)) { "Updater cache object is a symbolic link" }
    }

    private fun validateCacheParents(destination: Path) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedDestination = destination.toAbsolutePath().normalize()
        require(normalizedDestination.startsWith(normalizedRoot)) { "Unsafe updater object-cache path" }
        validateNoSymlinkComponents(normalizedRoot)
        var current = normalizedRoot
        normalizedRoot.relativize(normalizedDestination).forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Updater cache path is a symbolic link" }
        }
    }

    private fun validateNoSymlinkComponents(path: Path) {
        var current = path.root ?: return
        path.forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current)) { "Updater object cache path is a symbolic link" }
        }
    }

    private fun writeObject(destination: Path, bytes: ByteArray) {
        val temporary = Files.createTempFile(destination.parent, ".${destination.fileName}.", ".object")
        try {
            Files.write(temporary, bytes)
            moveObject(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun moveObject(temporary: Path, destination: Path) {
        try {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
