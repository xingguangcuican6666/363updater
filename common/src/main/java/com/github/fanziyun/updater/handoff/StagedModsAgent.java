package com.github.fanziyun.updater.handoff;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.Locale;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/** Child-only agent that redirects Forge and NeoForge production mod discovery to a staged generation. */
public final class StagedModsAgent {
    public static final String PROPERTY_LOADER = "updater363.handoff.loader";
    public static final String PROPERTY_STAGED_MODS = "updater363.handoff.stagedMods";
    private static final String PROPERTY_ACTIVE = "updater363.handoff.agentActive";
    private static final String PROPERTY_LOCATOR_PATCHED = "updater363.handoff.locatorPatched";
    private static final String PROPERTY_EARLY_WINDOW_PATCHED = "updater363.handoff.earlyWindowPatched";
    private static final String HANDOFF_ENV = "UPDATER363_HANDOFF_ACTIVE";
    private static final String FORGE_LOCATOR = "net/minecraftforge/fml/loading/moddiscovery/ModsFolderLocator";
    private static final String NEOFORGE_LOCATOR = "net/neoforged/fml/loading/moddiscovery/locators/ModsFolderLocator";
    private static final String FORGE_DISPLAY = "net/minecraftforge/fml/earlydisplay/DisplayWindow";
    private static final String NEOFORGE_DISPLAY = "net/neoforged/fml/earlydisplay/DisplayWindow";
    private static final String GLFW = "org/lwjgl/glfw/GLFW";

    private StagedModsAgent() {
    }

    public static void premain(String ignored, Instrumentation instrumentation) {
        if (!"1".equals(System.getenv(HANDOFF_ENV))) return;
        String loader = normalizedLoader();
        if (!loader.equals("forge") && !loader.equals("neoforge")) return;

        Path stagedMods = stagedModsDirectory();
        if (!Files.isDirectory(stagedMods, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(stagedMods)) {
            throw new IllegalStateException("The staged mod generation is unavailable");
        }
        System.setProperty(PROPERTY_ACTIVE, "1");
        instrumentation.addTransformer(new Transformer(loader), false);
    }

    public static String validationError(String loaderId) {
        String loader = loaderId == null ? "" : loaderId.toLowerCase(Locale.ROOT);
        if (loader.equals("fabric")) return null;
        if (!loader.equals("forge") && !loader.equals("neoforge")) return "Unsupported fast-restart loader";
        if (!"1".equals(System.getProperty(PROPERTY_ACTIVE))) return "The staged-mod Java agent did not start";
        if (!"1".equals(System.getProperty(PROPERTY_LOCATOR_PATCHED))) {
            return "The loader mod-directory redirect was not applied";
        }
        return null;
    }

    static boolean earlyWindowPatched() {
        return "1".equals(System.getProperty(PROPERTY_EARLY_WINDOW_PATCHED));
    }

    private static String normalizedLoader() {
        return System.getProperty(PROPERTY_LOADER, "").toLowerCase(Locale.ROOT);
    }

    private static Path stagedModsDirectory() {
        String value = System.getProperty(PROPERTY_STAGED_MODS, "");
        if (value.isBlank() || value.indexOf('\0') >= 0) throw new IllegalStateException("Missing staged mod generation");
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static final class Transformer implements ClassFileTransformer {
        private final String loader;

        private Transformer(String loader) {
            this.loader = loader;
        }

        @Override
        public byte[] transform(
            Module module,
            ClassLoader classLoader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
        ) {
            boolean locator = className != null && className.equals(this.loader.equals("forge") ? FORGE_LOCATOR : NEOFORGE_LOCATOR);
            boolean display = className != null && className.equals(this.loader.equals("forge") ? FORGE_DISPLAY : NEOFORGE_DISPLAY);
            if (!locator && !display) return null;
            try {
                byte[] transformed = locator ? patchLocator(classfileBuffer) : patchEarlyWindow(classfileBuffer);
                System.setProperty(locator ? PROPERTY_LOCATOR_PATCHED : PROPERTY_EARLY_WINDOW_PATCHED, "1");
                return transformed;
            } catch (Throwable exception) {
                System.err.println("363Updater fast-restart agent rejected " + className + ": " + safeMessage(exception));
                return new byte[] { 0 };
            }
        }
    }

    static byte[] patchLocator(byte[] input) {
        ClassNode type = read(input);
        int replacements = 0;
        for (MethodNode method : type.methods) {
            if (!method.name.equals("<init>") || !method.desc.equals("()V")) continue;
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; ) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && field.name.equals("MODSDIR")
                    && field.owner.endsWith("/FMLPaths")) {
                    AbstractInsnNode callNode = nextOpcode(next);
                    if (callNode instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals(field.owner)
                        && call.name.equals("get")
                        && call.desc.equals("()Ljava/nio/file/Path;")) {
                        InsnList replacement = new InsnList();
                        replacement.add(new LdcInsnNode(PROPERTY_STAGED_MODS));
                        replacement.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/lang/System",
                            "getProperty",
                            "(Ljava/lang/String;)Ljava/lang/String;",
                            false
                        ));
                        replacement.add(new InsnNode(Opcodes.ICONST_0));
                        replacement.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
                        replacement.add(new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            "java/nio/file/Paths",
                            "get",
                            "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;",
                            false
                        ));
                        method.instructions.insertBefore(instruction, replacement);
                        method.instructions.remove(instruction);
                        method.instructions.remove(callNode);
                        replacements++;
                    }
                }
                instruction = next;
            }
        }
        if (replacements != 1) throw new IllegalStateException("Unexpected mods locator bytecode");
        return write(type);
    }

    static byte[] patchEarlyWindow(byte[] input) {
        ClassNode type = read(input);
        int creates = 0;
        int shows = 0;
        for (MethodNode method : type.methods) {
            for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; ) {
                AbstractInsnNode next = instruction.getNext();
                if (instruction instanceof MethodInsnNode call && call.owner.equals(GLFW)) {
                    if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.name.equals("glfwCreateWindow")
                        && call.desc.equals("(IILjava/lang/CharSequence;JJ)J")) {
                        InsnList hide = new InsnList();
                        hide.add(new LdcInsnNode(0x00020004));
                        hide.add(new InsnNode(Opcodes.ICONST_0));
                        hide.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GLFW, "glfwWindowHint", "(II)V", false));
                        method.instructions.insertBefore(call, hide);
                        creates++;
                    } else if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.name.equals("glfwShowWindow")
                        && call.desc.equals("(J)V")) {
                        method.instructions.set(call, new InsnNode(Opcodes.POP2));
                        shows++;
                    }
                }
                instruction = next;
            }
        }
        if (creates != 1 || shows != 1) throw new IllegalStateException("Unexpected early-window bytecode");
        return write(type);
    }

    private static ClassNode read(byte[] input) {
        ClassNode type = new ClassNode();
        new ClassReader(input).accept(type, 0);
        return type;
    }

    private static byte[] write(ClassNode type) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode current = instruction;
        while (current != null && current.getOpcode() < 0) current = current.getNext();
        return current;
    }

    private static String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message.replace('\n', ' ').replace('\r', ' ');
    }
}
