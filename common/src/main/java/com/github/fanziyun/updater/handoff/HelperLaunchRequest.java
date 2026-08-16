package com.github.fanziyun.updater.handoff;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Binary helper request sent through stdin so launcher/authentication arguments never touch disk. */
public final class HelperLaunchRequest {
    private static final int MAGIC = 0x33364852;
    private static final short VERSION = 1;
    private static final int MAX_ARGUMENTS = 4096;
    private static final int MAX_STRING_BYTES = 1024 * 1024;

    public enum Mode {
        FAST(1), NORMAL_AUTOMATIC(2), NORMAL_DEFERRED(3);

        private final int id;
        Mode(int id) { this.id = id; }
        int id() { return this.id; }

        static Mode fromId(int id) throws IOException {
            for (Mode mode : values()) if (mode.id == id) return mode;
            throw new IOException("Unknown helper mode: " + id);
        }
    }

    private final Mode mode;
    private final long oldPid;
    private final int port;
    private final String token;
    private final String transactionId;
    private final Path transactionDirectory;
    private final Path gameDirectory;
    private final CurrentJvmCommand command;

    public HelperLaunchRequest(
        Mode mode,
        long oldPid,
        int port,
        String token,
        String transactionId,
        Path transactionDirectory,
        Path gameDirectory,
        CurrentJvmCommand command
    ) {
        if (mode == null) throw new IllegalArgumentException("Missing helper mode");
        if (oldPid <= 0L) throw new IllegalArgumentException("Invalid old process id");
        if (transactionId == null || !transactionId.matches("[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("Invalid updater transaction id");
        }
        if (transactionDirectory == null || gameDirectory == null) {
            throw new IllegalArgumentException("Missing helper directory");
        }
        if (!transactionDirectory.getFileName().toString().equals(transactionId)) {
            throw new IllegalArgumentException("Transaction directory does not match its id");
        }
        if ((mode == Mode.FAST || mode == Mode.NORMAL_AUTOMATIC) && command == null) {
            throw new IllegalArgumentException("The selected helper mode requires a launch command");
        }
        if (mode == Mode.FAST && (port < 1 || port > 65535 || token == null || token.length() < 16)) {
            throw new IllegalArgumentException("Invalid fast handoff endpoint");
        }
        this.mode = mode;
        this.oldPid = oldPid;
        this.port = port;
        this.token = token == null ? "" : token;
        this.transactionId = transactionId;
        this.transactionDirectory = transactionDirectory.toAbsolutePath().normalize();
        this.gameDirectory = gameDirectory.toAbsolutePath().normalize();
        this.command = command;
    }

    public Mode mode() { return this.mode; }
    public long oldPid() { return this.oldPid; }
    public int port() { return this.port; }
    public String token() { return this.token; }
    public String transactionId() { return this.transactionId; }
    public Path transactionDirectory() { return this.transactionDirectory; }
    public Path gameDirectory() { return this.gameDirectory; }
    public CurrentJvmCommand command() { return this.command; }

    public void write(OutputStream output) throws IOException {
        DataOutputStream data = new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeShort(VERSION);
        data.writeByte(this.mode.id());
        data.writeLong(this.oldPid);
        data.writeInt(this.port);
        writeString(data, this.token);
        writeString(data, this.transactionId);
        writeString(data, this.transactionDirectory.toString());
        writeString(data, this.gameDirectory.toString());
        data.writeBoolean(this.command != null);
        if (this.command != null) {
            writeString(data, this.command.executable());
            writeString(data, this.command.workingDirectory().toString());
            List<String> arguments = this.command.arguments();
            if (arguments.size() > MAX_ARGUMENTS) throw new IOException("Too many JVM arguments");
            data.writeInt(arguments.size());
            for (String argument : arguments) writeString(data, argument);
        }
        data.flush();
    }

    public static HelperLaunchRequest read(InputStream input) throws IOException {
        DataInputStream data = new DataInputStream(input);
        if (data.readInt() != MAGIC) throw new IOException("Invalid helper request magic");
        if (data.readShort() != VERSION) throw new IOException("Unsupported helper request version");
        Mode mode = Mode.fromId(data.readUnsignedByte());
        long oldPid = data.readLong();
        int port = data.readInt();
        String token = readString(data);
        String transactionId = readString(data);
        Path transaction = Path.of(readString(data));
        Path gameDirectory = Path.of(readString(data));
        CurrentJvmCommand command = null;
        if (data.readBoolean()) {
            String executable = readString(data);
            Path workingDirectory = Path.of(readString(data));
            int count = data.readInt();
            if (count <= 0 || count > MAX_ARGUMENTS) throw new IOException("Invalid JVM argument count");
            List<String> arguments = new ArrayList<>(count);
            for (int index = 0; index < count; index++) arguments.add(readString(data));
            command = new CurrentJvmCommand(executable, arguments, workingDirectory);
        }
        try {
            return new HelperLaunchRequest(mode, oldPid, port, token, transactionId, transaction, gameDirectory, command);
        } catch (IllegalArgumentException exception) {
            throw new IOException(exception.getMessage(), exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) throw new IOException("Missing helper request field");
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("Helper request field is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("Invalid helper request field length");
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Incomplete helper request field");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
