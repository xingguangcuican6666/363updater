package com.github.fanziyun.updater.handoff

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StagedModsAgentTest {
    @Test
    fun `locator patch replaces the production mods directory lookup`() {
        val patched = read(StagedModsAgent.patchLocator(locatorClass()))
        val instructions = patched.methods.single { it.name == "<init>" }.instructions.toArray()

        assertFalse(instructions.filterIsInstance<FieldInsnNode>().any { it.name == "MODSDIR" })
        assertTrue(instructions.filterIsInstance<MethodInsnNode>().any {
            it.owner == "java/lang/System" && it.name == "getProperty"
        })
        assertTrue(instructions.filterIsInstance<MethodInsnNode>().any {
            it.owner == "java/nio/file/Paths" && it.name == "get"
        })
    }

    @Test
    fun `early window patch keeps the loader window hidden`() {
        val patched = read(StagedModsAgent.patchEarlyWindow(earlyWindowClass()))
        val calls = patched.methods.flatMap { method -> method.instructions.toArray().filterIsInstance<MethodInsnNode>() }

        assertEquals(1, calls.count { it.owner == GLFW && it.name == "glfwCreateWindow" })
        assertEquals(1, calls.count { it.owner == GLFW && it.name == "glfwWindowHint" })
        assertFalse(calls.any { it.owner == GLFW && it.name == "glfwShowWindow" })
    }

    private fun locatorClass(): ByteArray {
        val owner = "net/neoforged/fml/loading/FMLPaths"
        return ClassWriter(0).apply {
            visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/ModsFolderLocator", null, "java/lang/Object", null)
            visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                visitCode()
                visitVarInsn(Opcodes.ALOAD, 0)
                visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
                visitFieldInsn(Opcodes.GETSTATIC, owner, "MODSDIR", "L$owner;")
                visitMethodInsn(Opcodes.INVOKEVIRTUAL, owner, "get", "()Ljava/nio/file/Path;", false)
                visitInsn(Opcodes.POP)
                visitInsn(Opcodes.RETURN)
                visitMaxs(1, 1)
                visitEnd()
            }
            visitEnd()
        }.toByteArray()
    }

    private fun earlyWindowClass(): ByteArray = ClassWriter(0).apply {
        visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/DisplayWindow", null, "java/lang/Object", null)
        visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "create", "()V", null, null).apply {
            visitCode()
            visitInsn(Opcodes.ICONST_1)
            visitInsn(Opcodes.ICONST_1)
            visitLdcInsn("Loading")
            visitInsn(Opcodes.LCONST_0)
            visitInsn(Opcodes.LCONST_0)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                GLFW,
                "glfwCreateWindow",
                "(IILjava/lang/CharSequence;JJ)J",
                false,
            )
            visitVarInsn(Opcodes.LSTORE, 0)
            visitVarInsn(Opcodes.LLOAD, 0)
            visitMethodInsn(Opcodes.INVOKESTATIC, GLFW, "glfwShowWindow", "(J)V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(7, 2)
            visitEnd()
        }
        visitEnd()
    }.toByteArray()

    private fun read(bytes: ByteArray): ClassNode = ClassNode().also { ClassReader(bytes).accept(it, 0) }

    private companion object {
        const val GLFW = "org/lwjgl/glfw/GLFW"
    }
}
