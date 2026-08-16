package com.github.fanziyun.updater.handoff;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Versioned, token-authenticated messages used only over loopback sockets. */
public final class HandoffProtocol {
    public static final int MAGIC = 0x33363355;
    public static final short VERSION = 1;
    public static final int MAX_TEXT_BYTES = 16 * 1024;

    public static final String ENV_ACTIVE = "UPDATER363_HANDOFF_ACTIVE";
    public static final String ENV_PORT = "UPDATER363_HANDOFF_PORT";
    public static final String ENV_TOKEN = "UPDATER363_HANDOFF_TOKEN";
    public static final String ENV_TRANSACTION = "UPDATER363_HANDOFF_TRANSACTION";
    public static final String ENV_OLD_PID = "UPDATER363_HANDOFF_OLD_PID";
    public static final String ENV_STARTED_EPOCH_MS = "UPDATER363_HANDOFF_STARTED_EPOCH_MS";
    public static final String ENV_RECOVERY_TRANSACTION = "UPDATER363_RECOVERY_TRANSACTION";

    private HandoffProtocol() {
    }

    public enum Type {
        HELLO_CHILD(1),
        HELLO_HELPER(2),
        HEARTBEAT(3),
        FIRST_FRAME(4),
        STABLE(5),
        READY(6),
        SHOW(7),
        VISIBLE_FRAME(8),
        ABORT(9),
        CHILD_EXIT(10),
        COMMITTING(11),
        COMMITTED(12),
        COMMIT_FAILED(13),
        SHUTDOWN(14);

        private final int id;

        Type(int id) {
            this.id = id;
        }

        public int id() {
            return this.id;
        }

        static Type fromId(int id) throws IOException {
            for (Type type : values()) {
                if (type.id == id) return type;
            }
            throw new IOException("Unknown handoff message type: " + id);
        }
    }

    public static final class Message {
        private final Type type;
        private final long first;
        private final long second;
        private final long third;
        private final long fourth;
        private final boolean flag;
        private final String text;

        public Message(Type type, long first, long second, long third, long fourth, boolean flag, String text) {
            this.type = type;
            this.first = first;
            this.second = second;
            this.third = third;
            this.fourth = fourth;
            this.flag = flag;
            this.text = text == null ? "" : text;
        }

        public static Message of(Type type) {
            return new Message(type, 0L, 0L, 0L, 0L, false, "");
        }

        public Type type() { return this.type; }
        public long first() { return this.first; }
        public long second() { return this.second; }
        public long third() { return this.third; }
        public long fourth() { return this.fourth; }
        public boolean flag() { return this.flag; }
        public String text() { return this.text; }
    }

    public static void write(OutputStream output, String token, Message message) throws IOException {
        byte[] tokenBytes = requiredToken(token);
        byte[] textBytes = message.text().getBytes(StandardCharsets.UTF_8);
        if (textBytes.length > MAX_TEXT_BYTES) throw new IOException("Handoff message text is too large");
        DataOutputStream data = output instanceof DataOutputStream
            ? (DataOutputStream) output
            : new DataOutputStream(output);
        data.writeInt(MAGIC);
        data.writeShort(VERSION);
        data.writeByte(message.type().id());
        data.writeShort(tokenBytes.length);
        data.write(tokenBytes);
        data.writeLong(message.first());
        data.writeLong(message.second());
        data.writeLong(message.third());
        data.writeLong(message.fourth());
        data.writeBoolean(message.flag());
        data.writeInt(textBytes.length);
        data.write(textBytes);
        data.flush();
    }

    public static Message read(InputStream input, String expectedToken) throws IOException {
        DataInputStream data = input instanceof DataInputStream
            ? (DataInputStream) input
            : new DataInputStream(input);
        int magic;
        try {
            magic = data.readInt();
        } catch (EOFException exception) {
            throw exception;
        }
        if (magic != MAGIC) throw new IOException("Invalid handoff protocol magic");
        short version = data.readShort();
        if (version != VERSION) throw new IOException("Unsupported handoff protocol version: " + version);
        Type type = Type.fromId(data.readUnsignedByte());
        int tokenLength = data.readUnsignedShort();
        if (tokenLength < 16 || tokenLength > 512) throw new IOException("Invalid handoff token length");
        byte[] actualToken = data.readNBytes(tokenLength);
        if (actualToken.length != tokenLength || !MessageDigest.isEqual(actualToken, requiredToken(expectedToken))) {
            throw new IOException("Invalid handoff token");
        }
        long first = data.readLong();
        long second = data.readLong();
        long third = data.readLong();
        long fourth = data.readLong();
        boolean flag = data.readBoolean();
        int textLength = data.readInt();
        if (textLength < 0 || textLength > MAX_TEXT_BYTES) throw new IOException("Invalid handoff message length");
        byte[] text = data.readNBytes(textLength);
        if (text.length != textLength) throw new EOFException("Incomplete handoff message");
        return new Message(type, first, second, third, fourth, flag, new String(text, StandardCharsets.UTF_8));
    }

    private static byte[] requiredToken(String token) throws IOException {
        if (token == null) throw new IOException("Missing handoff token");
        byte[] bytes = token.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 16 || bytes.length > 512) throw new IOException("Invalid handoff token length");
        return bytes;
    }
}
