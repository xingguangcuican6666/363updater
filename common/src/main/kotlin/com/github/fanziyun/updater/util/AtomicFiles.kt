package com.github.fanziyun.updater.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object AtomicFiles {
    fun write(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".${path.fileName}.", ".updater.tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
