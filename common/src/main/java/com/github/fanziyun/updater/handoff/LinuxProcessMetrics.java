package com.github.fanziyun.updater.handoff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

public final class LinuxProcessMetrics {
    private LinuxProcessMetrics() {
    }

    public static OptionalLong rssBytes(long pid) {
        if (pid <= 0 || !isLinux()) return OptionalLong.empty();
        Path status = Path.of("/proc", Long.toString(pid), "status");
        return readKilobytes(status, "VmRSS:");
    }

    public static OptionalLong availableMemoryBytes() {
        if (!isLinux()) return OptionalLong.empty();
        return readKilobytes(Path.of("/proc/meminfo"), "MemAvailable:");
    }

    private static OptionalLong readKilobytes(Path path, String key) {
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.US_ASCII)) {
                if (!line.startsWith(key)) continue;
                String[] fields = line.substring(key.length()).trim().split("\\s+");
                if (fields.length == 0) return OptionalLong.empty();
                return OptionalLong.of(Math.multiplyExact(Long.parseLong(fields[0]), 1024L));
            }
        } catch (IOException | NumberFormatException | ArithmeticException ignored) {
            return OptionalLong.empty();
        }
        return OptionalLong.empty();
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux");
    }
}
