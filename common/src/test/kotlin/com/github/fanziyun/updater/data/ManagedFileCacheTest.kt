package com.github.fanziyun.updater.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ManagedFileCacheTest {
    @Test
    fun `downloads validates and reuses a content addressed object`() = withServer { server, baseUrl ->
        val bytes = "managed-mod".toByteArray()
        val requests = AtomicInteger()
        server.createContext("/artifact") { exchange ->
            requests.incrementAndGet()
            exchange.respond(200, bytes)
        }
        val cache = ManagedFileCache(2_000, Files.createTempDirectory("363updater-objects"))
        val file = remoteFile("mods/example.jar", bytes, "$baseUrl/artifact", uppercaseHashes = true)

        val first = cache.materialize(file)
        val second = cache.materialize(file)

        assertEquals(first, second)
        assertContentEquals(bytes, Files.readAllBytes(first))
        assertEquals(1, requests.get())
        assertTrue(cache.contains(file))
    }

    @Test
    fun `follows bounded HTTP redirects`() = withServer { server, baseUrl ->
        val bytes = "redirected".toByteArray()
        server.createContext("/start") { exchange ->
            exchange.responseHeaders.add("Location", "/artifact")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/artifact") { exchange -> exchange.respond(200, bytes) }
        val cache = ManagedFileCache(2_000, Files.createTempDirectory("363updater-objects"))

        val path = cache.materialize(remoteFile("mods/example.jar", bytes, "$baseUrl/start"))

        assertContentEquals(bytes, Files.readAllBytes(path))
    }

    @Test
    fun `rejects bad hashes HTTP failures and interrupted bodies`() = withServer { server, baseUrl ->
        val bytes = "expected".toByteArray()
        server.createContext("/wrong") { exchange -> exchange.respond(200, "tampered".toByteArray()) }
        server.createContext("/missing") { exchange -> exchange.respond(404, "missing".toByteArray()) }
        server.createContext("/partial") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes, 0, bytes.size - 2) }
        }
        val root = Files.createTempDirectory("363updater-objects")

        assertFailsWith<ManagedFileDownloadException> {
            ManagedFileCache(2_000, root.resolve("wrong"))
                .materialize(remoteFile("mods/wrong.jar", bytes, "$baseUrl/wrong"))
        }
        assertFailsWith<ManagedFileDownloadException> {
            ManagedFileCache(2_000, root.resolve("missing"))
                .materialize(remoteFile("mods/missing.jar", bytes, "$baseUrl/missing"))
        }
        assertFailsWith<ManagedFileDownloadException> {
            ManagedFileCache(2_000, root.resolve("partial"))
                .materialize(remoteFile("mods/partial.jar", bytes, "$baseUrl/partial"))
        }
    }

    @Test
    fun `rejects symbolic links inside the object cache`() {
        val root = Files.createTempDirectory("363updater-objects")
        val outside = Files.createTempDirectory("363updater-outside")
        Files.createSymbolicLink(root.resolve("sha512"), outside)
        val bytes = "embedded".toByteArray()
        val file = embeddedFile("mods/example.jar", bytes)
        val cache = ManagedFileCache(2_000, root)

        val exception = assertFailsWith<IllegalArgumentException> { cache.materialize(file) }

        assertTrue(exception.message.orEmpty().contains("symbolic link"))
        assertFalse(Files.list(outside).use { it.findAny().isPresent })
    }

    private fun withServer(block: (HttpServer, String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            block(server, "http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun remoteFile(path: String, bytes: ByteArray, url: String, uppercaseHashes: Boolean = false): PackageFile {
        val sha1 = digest(bytes, "SHA-1").let { if (uppercaseHashes) it.uppercase() else it }
        val sha512 = digest(bytes, "SHA-512").let { if (uppercaseHashes) it.uppercase() else it }
        return PackageFile(
            relativePath = path,
            size = bytes.size.toLong(),
            hashes = FileHashes(sha1, sha512),
            urls = listOf(url),
        )
    }

    private fun embeddedFile(path: String, bytes: ByteArray): PackageFile = PackageFile(
        relativePath = path,
        size = bytes.size.toLong(),
        hashes = FileHashes(digest(bytes, "SHA-1"), digest(bytes, "SHA-512")),
        source = PackageFileSource.OVERRIDE,
        embeddedBytes = bytes,
    )

    private fun HttpExchange.respond(status: Int, body: ByteArray) {
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun digest(bytes: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString("") { "%02x".format(it) }
}
