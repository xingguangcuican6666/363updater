package com.github.fanziyun.updater.handoff;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Captures the current launcher without serialising or logging application arguments. */
public final class CurrentJvmCommand {
    private static final int MAX_ARGUMENTS = 4096;
    private static final int MAX_COMMAND_LINE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> OPTIONS_WITH_VALUE = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "-cp", "-classpath", "--class-path", "-p", "--module-path", "--upgrade-module-path",
        "--add-modules", "--enable-native-access", "--limit-modules", "--add-exports", "--add-opens",
        "--add-reads", "--patch-module", "--module-source-path", "--source"
    )));
    /** Arguments whose value is a list of paths that a library replacement has to rewrite. */
    private static final List<String> CLASSPATH_OPTIONS = Collections.unmodifiableList(Arrays.asList(
        "-cp", "-classpath", "--class-path"
    ));

    private final String executable;
    private final List<String> arguments;
    private final Path workingDirectory;

    public CurrentJvmCommand(String executable, List<String> arguments, Path workingDirectory) {
        if (executable == null || executable.isBlank() || executable.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Missing or invalid Java executable");
        }
        if (arguments == null || arguments.isEmpty()) throw new IllegalArgumentException("Missing current JVM arguments");
        if (arguments.size() > MAX_ARGUMENTS) throw new IllegalArgumentException("Too many current JVM arguments");
        if (workingDirectory == null) throw new IllegalArgumentException("Missing JVM working directory");
        for (String argument : arguments) {
            if (argument == null || argument.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid JVM argument");
        }
        this.executable = executable;
        this.arguments = List.copyOf(arguments);
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
        locateMainIndex(this.arguments);
    }

    public static CurrentJvmCommand capture() {
        ProcessHandle.Info info = ProcessHandle.current().info();
        String executable = info.command().orElseGet(CurrentJvmCommand::fallbackExecutable);
        String[] processArguments = info.arguments().orElse(null);
        List<String> arguments;
        if (processArguments != null && processArguments.length > 0) {
            arguments = List.copyOf(Arrays.asList(processArguments));
        } else if (isLinux()) {
            arguments = argumentsFrom(null, linuxCommandLine());
        } else if (isWindows()) {
            arguments = windowsCommandLine();
        } else {
            arguments = argumentsFrom(null, null);
        }
        return new CurrentJvmCommand(executable, arguments, currentWorkingDirectory());
    }

    /** The Windows JDK does not expose the current process argv through ProcessHandle.Info. */
    private static List<String> windowsCommandLine() {
        try {
            Class<?> bridge = Class.forName("com.github.fanziyun.updater.handoff.WindowsCommandLine");
            Method capture = bridge.getMethod("captureArguments");
            Object value = capture.invoke(null);
            if (!(value instanceof List<?> raw)) {
                throw new IllegalStateException("Windows command line bridge returned an invalid value");
            }
            if (raw.size() > MAX_ARGUMENTS) throw new IllegalStateException("Too many current JVM arguments");
            List<String> arguments = new ArrayList<>(raw.size());
            for (Object argument : raw) {
                if (!(argument instanceof String string) || string.indexOf('\0') >= 0) {
                    throw new IllegalStateException("Windows command line bridge returned an invalid argument");
                }
                arguments.add(string);
            }
            return List.copyOf(arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("The current Windows command line is unavailable", cause);
        } catch (ReflectiveOperationException | LinkageError exception) {
            throw new IllegalStateException("The current Windows command line is unavailable", exception);
        }
    }

    static List<String> argumentsFrom(String[] processArguments, byte[] procCommandLine) {
        if (processArguments != null && processArguments.length > 0) {
            return List.copyOf(Arrays.asList(processArguments));
        }
        if (procCommandLine == null || procCommandLine.length == 0) {
            throw new IllegalStateException("The current JVM arguments are unavailable");
        }
        if (procCommandLine.length > MAX_COMMAND_LINE_BYTES || procCommandLine[procCommandLine.length - 1] != 0) {
            throw new IllegalStateException("The process command line is incomplete or too large");
        }
        List<String> commandLine = new ArrayList<>();
        Charset charset = Charset.forName(System.getProperty("sun.jnu.encoding", Charset.defaultCharset().name()));
        int start = 0;
        for (int index = 0; index < procCommandLine.length; index++) {
            if (procCommandLine[index] != 0) continue;
            commandLine.add(new String(procCommandLine, start, index - start, charset));
            start = index + 1;
        }
        if (commandLine.size() < 2 || commandLine.get(0).isBlank()) {
            throw new IllegalStateException("The process command line does not contain a JVM launch target");
        }
        commandLine.remove(0);
        if (commandLine.size() > MAX_ARGUMENTS) throw new IllegalStateException("Too many current JVM arguments");
        return List.copyOf(commandLine);
    }

    private static byte[] linuxCommandLine() {
        Path path = Path.of("/proc/self/cmdline");
        try (InputStream input = Files.newInputStream(path)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
            byte[] buffer = new byte[8192];
            int total = 0;
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                total += count;
                if (total > MAX_COMMAND_LINE_BYTES) {
                    throw new IllegalStateException("The process command line is too large");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("The current JVM arguments are unavailable", exception);
        }
    }

    private static String fallbackExecutable() {
        if (isLinux()) {
            try {
                return Path.of("/proc/self/exe").toRealPath().toString();
            } catch (IOException ignored) {
            }
        }
        Path bin = Path.of(System.getProperty("java.home", ""), "bin");
        String[] candidates = isWindows()
            ? new String[] { "javaw.exe", "java.exe" }
            : new String[] { "java" };
        for (String candidate : candidates) {
            Path path = bin.resolve(candidate);
            if (Files.isRegularFile(path)) return path.toAbsolutePath().normalize().toString();
        }
        throw new IllegalStateException("The current Java executable is unavailable");
    }

    private static Path currentWorkingDirectory() {
        if (isLinux()) {
            try {
                return Path.of("/proc/self/cwd").toRealPath();
            } catch (IOException ignored) {
            }
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("linux");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
    }

    public String executable() { return this.executable; }
    public List<String> arguments() { return this.arguments; }
    public Path workingDirectory() { return this.workingDirectory; }

    public CurrentJvmCommand withSystemProperty(String name, String value) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Invalid system property name");
        if (value == null || value.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid system property value");
        String prefix = "-D" + name + "=";
        int originalMainIndex = locateMainIndex(this.arguments);
        List<String> next = new ArrayList<>(this.arguments.size() + 1);
        for (int index = 0; index < this.arguments.size(); index++) {
            String argument = this.arguments.get(index);
            if (index >= originalMainIndex || !argument.startsWith(prefix)) next.add(argument);
        }
        int mainIndex = locateMainIndex(next);
        next.add(mainIndex, prefix + value);
        return new CurrentJvmCommand(this.executable, next, this.workingDirectory);
    }

    public CurrentJvmCommand withJavaAgent(Path agentJar) {
        if (agentJar == null) throw new IllegalArgumentException("Missing Java agent");
        Path normalized = agentJar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || Files.isSymbolicLink(normalized)) {
            throw new IllegalArgumentException("Invalid Java agent");
        }
        String path = normalized.toString();
        if (path.indexOf('\0') >= 0 || path.indexOf('=') >= 0) {
            throw new IllegalArgumentException("The Java agent path cannot be reproduced safely");
        }

        int originalMainIndex = locateMainIndex(this.arguments);
        List<String> next = new ArrayList<>(this.arguments.size() + 1);
        for (int index = 0; index < this.arguments.size(); index++) {
            String argument = this.arguments.get(index);
            if (index >= originalMainIndex || !isUpdaterAgent(argument)) next.add(argument);
        }
        next.add(locateMainIndex(next), "-javaagent:" + path);
        return new CurrentJvmCommand(this.executable, next, this.workingDirectory);
    }

    /**
     * The classpath entries this JVM was started with, in order; empty when it had no classpath.
     *
     * A relaunch never has to understand how the launcher built that classpath: the JVM argv is the
     * one launcher-neutral source for it, and everything else about the process is equally available
     * through [executable], [workingDirectory] and [arguments].
     */
    public List<String> classpathEntries() {
        int mainIndex = locateMainIndex(this.arguments);
        for (int index = 0; index < mainIndex; index++) {
            String argument = this.arguments.get(index);
            String option = classpathOption(argument);
            if (option == null) continue;
            String value;
            if (argument.length() > option.length()) {
                value = argument.substring(option.length() + 1);
            } else {
                value = index + 1 < mainIndex ? this.arguments.get(index + 1) : "";
            }
            List<String> entries = new ArrayList<>();
            for (String entry : value.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
                if (!entry.isEmpty()) entries.add(entry);
            }
            return List.copyOf(entries);
        }
        return List.of();
    }

    /**
     * Replaces the classpath with [entries], keeping every other argument byte for byte.
     *
     * This is how a relaunch puts a different loader on the classpath: rebuild the one option the JVM
     * consumes, and replay the rest of the captured line unchanged. Application arguments after the
     * launch target are never treated as a classpath, so a game argument that looks like one cannot be
     * rewritten. When the captured line had no classpath the entries are inserted before the launch
     * target.
     */
    public CurrentJvmCommand withClasspath(List<String> entries) {
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("Missing classpath entries");
        if (entries.size() > MAX_ARGUMENTS) throw new IllegalArgumentException("Too many classpath entries");
        for (String entry : entries) {
            if (entry == null || entry.isEmpty() || entry.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Invalid classpath entry");
            }
            if (entry.indexOf(File.pathSeparatorChar) >= 0) {
                throw new IllegalArgumentException("Classpath entries must not contain the path separator");
            }
        }
        String value = String.join(File.pathSeparator, entries);

        int originalMainIndex = locateMainIndex(this.arguments);
        List<String> next = new ArrayList<>(this.arguments.size() + 2);
        boolean replaced = false;
        int mainIndex = originalMainIndex;
        for (int index = 0; index < this.arguments.size(); index++) {
            String argument = this.arguments.get(index);
            String option = index < originalMainIndex ? classpathOption(argument) : null;
            if (option == null) {
                next.add(argument);
                continue;
            }
            boolean inline = argument.length() > option.length();
            if (!inline && index + 1 < originalMainIndex) index++; // drop the value that followed
            if (replaced) continue;
            next.add(inline ? option + "=" + value : option);
            if (!inline) next.add(value);
            replaced = true;
        }
        if (!replaced) {
            mainIndex = locateMainIndex(next);
            next.add(mainIndex, "-cp");
            next.add(mainIndex + 1, value);
        }
        return new CurrentJvmCommand(this.executable, next, this.workingDirectory);
    }

    /** The classpath option an argument starts, or null when it carries no classpath. */
    private static String classpathOption(String argument) {
        for (String option : CLASSPATH_OPTIONS) {
            if (argument.equals(option)) return option;
            if (argument.startsWith(option + "=")) return option;
        }
        return null;
    }

    /** True when [arguments] pass a classpath between the JVM options and the launch target. */
    public static boolean hasClasspath(List<String> arguments) {
        if (arguments == null) return false;
        int mainIndex = locateMainIndex(arguments);
        for (int index = 0; index < mainIndex; index++) {
            if (classpathOption(arguments.get(index)) != null) return true;
        }
        return false;
    }

    public CurrentJvmCommand withoutUpdaterHandoffArguments() {
        int originalMainIndex = locateMainIndex(this.arguments);
        List<String> next = new ArrayList<>(this.arguments.size());
        for (int index = 0; index < this.arguments.size(); index++) {
            String argument = this.arguments.get(index);
            if (index < originalMainIndex && (isUpdaterAgent(argument) || isUpdaterProperty(argument))) continue;
            next.add(argument);
        }
        return new CurrentJvmCommand(this.executable, next, this.workingDirectory);
    }

    private static boolean isUpdaterAgent(String argument) {
        if (!argument.startsWith("-javaagent:")) return false;
        String value = argument.substring("-javaagent:".length());
        int options = value.indexOf('=');
        if (options >= 0) value = value.substring(0, options);
        try {
            Path fileName = Path.of(value).getFileName();
            return fileName != null && fileName.toString().equals("updater-helper.jar");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isUpdaterProperty(String argument) {
        return argument.startsWith("-Dfabric.modsFolder=")
            || argument.startsWith("-D" + StagedModsAgent.PROPERTY_LOADER + "=")
            || argument.startsWith("-D" + StagedModsAgent.PROPERTY_STAGED_MODS + "=");
    }

    public List<String> commandLine() {
        List<String> command = new ArrayList<>(this.arguments.size() + 1);
        command.add(this.executable);
        command.addAll(this.arguments);
        return command;
    }

    public static int locateMainIndex(List<String> arguments) {
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument == null || argument.indexOf('\0') >= 0) throw new IllegalArgumentException("Invalid JVM argument");
            if (argument.startsWith("@")) {
                throw new IllegalStateException("JVM argument files cannot be reproduced safely");
            }
            if (argument.equals("-jar") || argument.equals("-m") || argument.equals("--module")) {
                if (index + 1 >= arguments.size()) throw new IllegalStateException("Missing JVM launch target after " + argument);
                return index;
            }
            if (argument.startsWith("--module=")) {
                if (argument.length() == "--module=".length()) throw new IllegalStateException("Missing JVM module launch target");
                return index;
            }
            if (OPTIONS_WITH_VALUE.contains(argument)) {
                if (++index >= arguments.size()) throw new IllegalStateException("Incomplete JVM launcher option: " + argument);
                continue;
            }
            if (takesInlineValue(argument)) continue;
            if (argument.equals("--")) {
                if (index + 1 >= arguments.size()) throw new IllegalStateException("Missing JVM main class");
                return index + 1;
            }
            if (!argument.startsWith("-")) {
                if (argument.isEmpty()) throw new IllegalStateException("Missing JVM main class");
                return index;
            }
        }
        throw new IllegalStateException("Unable to locate the JVM main class");
    }

    private static boolean takesInlineValue(String argument) {
        for (String option : OPTIONS_WITH_VALUE) {
            if (argument.startsWith(option + "=")) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "CurrentJvmCommand{executable='" + Path.of(this.executable).getFileName() + "', arguments=<redacted>, workingDirectory="
            + this.workingDirectory + "}";
    }
}
