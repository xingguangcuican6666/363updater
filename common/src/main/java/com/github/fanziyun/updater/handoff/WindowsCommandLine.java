package com.github.fanziyun.updater.handoff;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.W32APIOptions;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Reads the exact Windows argv with APIs available on every supported Java version. */
public final class WindowsCommandLine {
    private static final int MAX_ARGUMENTS = 4096;
    private static final int MAX_COMMAND_LINE_BYTES = 2 * 1024 * 1024;

    private interface KernelCommandLine extends Library {
        KernelCommandLine INSTANCE = Native.load("kernel32", KernelCommandLine.class, W32APIOptions.DEFAULT_OPTIONS);
        Pointer GetCommandLineW();
    }

    private WindowsCommandLine() {
    }

    /** Returns argv without argv[0], matching ProcessHandle.Info.arguments(). */
    public static List<String> captureArguments() {
        Pointer commandLinePointer = KernelCommandLine.INSTANCE.GetCommandLineW();
        if (commandLinePointer == null || Pointer.nativeValue(commandLinePointer) == 0L) {
            throw new IllegalStateException("GetCommandLineW failed");
        }
        String commandLine = commandLinePointer.getWideString(0L);
        if (commandLine.getBytes(StandardCharsets.UTF_8).length > MAX_COMMAND_LINE_BYTES) {
            throw new IllegalStateException("The Windows command line is too large");
        }

        IntByReference countReference = new IntByReference();
        Pointer argv = Shell32.INSTANCE.CommandLineToArgvW(new WString(commandLine), countReference);
        if (argv == null || Pointer.nativeValue(argv) == 0L) {
            throw new IllegalStateException("CommandLineToArgvW failed");
        }
        try {
            int count = countReference.getValue();
            if (count < 2) {
                throw new IllegalStateException("The process command line does not contain a JVM launch target");
            }
            if (count > MAX_ARGUMENTS + 1) throw new IllegalStateException("Too many current JVM arguments");

            List<String> arguments = new ArrayList<>(count - 1);
            for (int index = 0; index < count; index++) {
                Pointer valuePointer = argv.getPointer((long) index * Native.POINTER_SIZE);
                if (valuePointer == null || Pointer.nativeValue(valuePointer) == 0L) {
                    throw new IllegalStateException("Invalid Windows JVM argument");
                }
                String value = valuePointer.getWideString(0L);
                if (value.indexOf('\0') >= 0 || (index == 0 && value.isBlank())) {
                    throw new IllegalStateException("Invalid Windows JVM argument");
                }
                if (index > 0) arguments.add(value);
            }
            return List.copyOf(arguments);
        } finally {
            Kernel32.INSTANCE.LocalFree(argv);
        }
    }
}
