package com.github.fanziyun.updater.handoff;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lightweight launcher/commit helper. This class intentionally has no Minecraft, Kotlin, or loader dependencies. */
public final class HandoffHelperMain {
    private HandoffHelperMain() {
    }

    public static void main(String[] ignored) {
        HelperLaunchRequest request = null;
        try {
            request = HelperLaunchRequest.read(System.in);
            switch (request.mode()) {
                case FAST -> runFast(request);
                case NORMAL_AUTOMATIC -> runNormal(request, true);
                case NORMAL_DEFERRED -> runNormal(request, false);
            }
        } catch (Throwable exception) {
            if (request != null && request.mode() == HelperLaunchRequest.Mode.FAST) {
                report(request, new HandoffProtocol.Message(
                    HandoffProtocol.Type.ABORT, 0L, 0L, 0L, 0L, false, safeMessage(exception)
                ));
            } else if (request != null) {
                writeFailureMarker(request.transactionDirectory(), exception);
            }
            System.err.println("363Updater helper failed: " + safeMessage(exception));
            System.exit(2);
        }
    }

    private static void runFast(HelperLaunchRequest request) throws Exception {
        Process child = startChild(request, true);
        report(request, new HandoffProtocol.Message(
            HandoffProtocol.Type.HELLO_HELPER, child.pid(), 0L, 0L, 0L, false, ""
        ));
        int exitCode = child.waitFor();
        report(request, new HandoffProtocol.Message(
            HandoffProtocol.Type.CHILD_EXIT, child.pid(), exitCode, 0L, 0L, false, ""
        ));
    }

    private static void runNormal(HelperLaunchRequest request, boolean relaunch) throws Exception {
        waitForOldProcess(request.oldPid());
        commitModGeneration(request);
        if (relaunch) {
            Process child = startChild(request, false);
            child.waitFor();
        }
    }

    private static Process startChild(HelperLaunchRequest request, boolean fast) throws IOException {
        CurrentJvmCommand command = request.command();
        if (command == null) throw new IOException("Missing child launch command");
        ProcessBuilder builder = new ProcessBuilder(command.commandLine());
        builder.directory(command.workingDirectory().toFile());
        builder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        Map<String, String> environment = builder.environment();
        if (fast) {
            environment.put(HandoffProtocol.ENV_ACTIVE, "1");
            environment.put(HandoffProtocol.ENV_PORT, Integer.toString(request.port()));
            environment.put(HandoffProtocol.ENV_TOKEN, request.token());
            environment.put(HandoffProtocol.ENV_TRANSACTION, request.transactionId());
            environment.put(HandoffProtocol.ENV_OLD_PID, Long.toString(request.oldPid()));
            environment.put(HandoffProtocol.ENV_STARTED_EPOCH_MS, Long.toString(System.currentTimeMillis()));
        } else {
            environment.remove(HandoffProtocol.ENV_ACTIVE);
            environment.remove(HandoffProtocol.ENV_PORT);
            environment.remove(HandoffProtocol.ENV_TOKEN);
            environment.remove(HandoffProtocol.ENV_TRANSACTION);
            environment.remove(HandoffProtocol.ENV_OLD_PID);
            environment.remove(HandoffProtocol.ENV_STARTED_EPOCH_MS);
            environment.put(HandoffProtocol.ENV_RECOVERY_TRANSACTION, request.transactionId());
        }
        return builder.start();
    }

    private static void waitForOldProcess(long pid) {
        Optional<ProcessHandle> old = ProcessHandle.of(pid);
        if (old.isPresent() && old.get().isAlive()) old.get().onExit().join();
    }

    private static void commitModGeneration(HelperLaunchRequest request) throws IOException {
        Path transaction = requireDirectory(request.transactionDirectory(), "transaction");
        Path gameDirectory = requireDirectory(request.gameDirectory(), "game");
        Path generation = transaction.resolve("generation/mods").normalize();
        Path candidate = transaction.resolve("commit-mods").normalize();
        Path live = gameDirectory.resolve("mods").normalize();
        Path previous = transaction.resolve("previous-mods").normalize();
        if (!generation.startsWith(transaction) || !candidate.startsWith(transaction) || !previous.startsWith(transaction)
            || live.getParent() == null
            || !live.getParent().equals(gameDirectory)) {
            throw new IOException("Unsafe helper commit paths");
        }
        if (Files.isSymbolicLink(generation) || Files.isSymbolicLink(candidate) || Files.isSymbolicLink(live)
            || Files.isSymbolicLink(previous)) {
            throw new IOException("Symbolic links are not allowed in helper commit paths");
        }
        requireNoSymbolicLinkSegments(transaction, generation);
        requireNoSymbolicLinkSegments(transaction, candidate);
        requireNoSymbolicLinkSegments(gameDirectory, live);
        Path success = transaction.resolve("helper-commit.ok");
        if (Files.isRegularFile(success, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Staged mod generation is missing");
        }
        validateTree(generation);
        if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) validateTree(live);
        if (Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Previous mod generation already exists");
        }
        deleteTree(candidate);
        cloneTree(generation, candidate);
        validateTree(candidate);

        boolean movedLive = false;
        boolean movedCandidate = false;
        boolean launcherMetadataCommitted = false;
        try {
            if (Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                Files.move(live, previous, StandardCopyOption.ATOMIC_MOVE);
                movedLive = true;
            }
            Files.move(candidate, live, StandardCopyOption.ATOMIC_MOVE);
            movedCandidate = true;
            launcherMetadataCommitted = commitLauncherMetadata(transaction, gameDirectory);
            writeMarker(success, "ok\n");
            Files.deleteIfExists(transaction.resolve("helper-commit.failed"));
        } catch (IOException exception) {
            if (launcherMetadataCommitted) {
                try {
                    restoreLauncherMetadata(transaction, gameDirectory);
                } catch (IOException restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            if (movedCandidate && Files.exists(live, LinkOption.NOFOLLOW_LINKS)) {
                Path failed = transaction.resolve("failed-commit-mods");
                try {
                    deleteTree(failed);
                    Files.move(live, failed, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException moveFailure) {
                    exception.addSuppressed(moveFailure);
                }
            }
            if (!Files.exists(live, LinkOption.NOFOLLOW_LINKS) && movedLive
                && Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.move(previous, live, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            throw exception;
        }
    }

    private static Path requireDirectory(Path path, String label) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)) {
            throw new IOException("Invalid " + label + " directory");
        }
        return normalized;
    }

    private static void writeFailureMarker(Path transaction, Throwable exception) {
        try {
            if (Files.isDirectory(transaction, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(transaction)) {
                writeMarker(transaction.resolve("helper-commit.failed"), safeMessage(exception) + "\n");
            }
        } catch (IOException ignored) {
        }
    }

    private static void writeMarker(Path destination, String content) throws IOException {
        Path temporary = Files.createTempFile(destination.getParent(), "." + destination.getFileName() + ".", ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void requireNoSymbolicLinkSegments(Path root, Path target) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) throw new IOException("Unsafe helper path");
        Path current = normalizedRoot;
        for (Path segment : normalizedRoot.relativize(normalizedTarget)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not allowed in helper paths");
        }
    }

    private static void validateTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("Symbolic links are not allowed in mod generations");
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unsupported file in mod generation");
                }
            }
        }
    }

    private static void cloneTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try {
                        Files.createLink(target, path);
                    } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
                        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(root)) throw new IOException("Symbolic links are not allowed in helper paths");
        validateTree(root);
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static boolean commitLauncherMetadata(Path transaction, Path gameDirectory) throws IOException {
        Path stagedIndex = transaction.resolve("staged-launcher/modrinth.index.json");
        Path stagedConfig = transaction.resolve("staged-launcher/modpack.cfg");
        boolean hasIndex = Files.exists(stagedIndex, LinkOption.NOFOLLOW_LINKS);
        boolean hasConfig = Files.exists(stagedConfig, LinkOption.NOFOLLOW_LINKS);
        if (!hasIndex && !hasConfig) return false;
        if (!regularFile(stagedIndex) || !regularFile(stagedConfig)) {
            throw new IOException("Incomplete staged launcher metadata");
        }

        Path backupIndex = transaction.resolve("backup/launcher/modrinth.index.json");
        Path backupConfig = transaction.resolve("backup/launcher/modpack.cfg");
        Path liveIndex = gameDirectory.resolve("modrinth.index.json");
        Path liveConfig = gameDirectory.resolve("modpack.cfg");
        requireNoSymbolicLinkSegments(transaction, stagedIndex);
        requireNoSymbolicLinkSegments(transaction, stagedConfig);
        requireNoSymbolicLinkSegments(transaction, backupIndex);
        requireNoSymbolicLinkSegments(transaction, backupConfig);
        requireNoSymbolicLinkSegments(gameDirectory, liveIndex);
        requireNoSymbolicLinkSegments(gameDirectory, liveConfig);
        if (!regularFile(backupIndex) || !regularFile(backupConfig)
            || !regularFile(liveIndex) || !regularFile(liveConfig)) {
            throw new IOException("Launcher metadata or its backup is missing");
        }

        byte[] oldIndex = readMetadata(backupIndex);
        byte[] oldConfig = readMetadata(backupConfig);
        byte[] targetIndex = readMetadata(stagedIndex);
        byte[] targetConfig = readMetadata(stagedConfig);
        byte[] currentIndex = readMetadata(liveIndex);
        byte[] currentConfig = readMetadata(liveConfig);
        if (!Arrays.equals(currentIndex, oldIndex) && !Arrays.equals(currentIndex, targetIndex)) {
            throw new IOException("Launcher manifest changed after update preparation");
        }
        if (!Arrays.equals(currentConfig, oldConfig) && !Arrays.equals(currentConfig, targetConfig)) {
            throw new IOException("Launcher metadata changed after update preparation");
        }

        try {
            if (!Arrays.equals(currentIndex, targetIndex)) writeBytesAtomic(liveIndex, targetIndex);
            if (!Arrays.equals(currentConfig, targetConfig)) writeBytesAtomic(liveConfig, targetConfig);
        } catch (IOException exception) {
            try {
                writeBytesAtomic(liveIndex, oldIndex);
                writeBytesAtomic(liveConfig, oldConfig);
            } catch (IOException restoreFailure) {
                exception.addSuppressed(restoreFailure);
            }
            throw exception;
        }
        return true;
    }

    private static void restoreLauncherMetadata(Path transaction, Path gameDirectory) throws IOException {
        Path backupIndex = transaction.resolve("backup/launcher/modrinth.index.json");
        Path backupConfig = transaction.resolve("backup/launcher/modpack.cfg");
        if (!regularFile(backupIndex) || !regularFile(backupConfig)) return;
        writeBytesAtomic(gameDirectory.resolve("modrinth.index.json"), readMetadata(backupIndex));
        writeBytesAtomic(gameDirectory.resolve("modpack.cfg"), readMetadata(backupConfig));
    }

    private static byte[] readMetadata(Path path) throws IOException {
        if (Files.size(path) > 32L * 1024L * 1024L) throw new IOException("Launcher metadata is too large");
        return Files.readAllBytes(path);
    }

    private static boolean regularFile(Path path) {
        return !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static void writeBytesAtomic(Path destination, byte[] content) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "." + destination.getFileName() + ".", ".tmp");
        try {
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void report(HelperLaunchRequest request, HandoffProtocol.Message message) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), request.port()), 3_000);
            HandoffProtocol.write(socket.getOutputStream(), request.token(), message);
        } catch (IOException ignored) {
        }
    }

    private static String safeMessage(Throwable exception) {
        String value = exception.getMessage();
        if (value == null || value.isBlank()) value = exception.getClass().getSimpleName();
        String sanitized = value.replaceAll("https?://\\S+", "<download-url>").replace('\n', ' ').replace('\r', ' ');
        return sanitized.substring(0, Math.min(500, sanitized.length()));
    }
}
