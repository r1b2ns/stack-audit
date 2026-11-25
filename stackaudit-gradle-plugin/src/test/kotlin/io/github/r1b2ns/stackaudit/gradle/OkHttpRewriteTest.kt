package io.github.r1b2ns.stackaudit.gradle

import okhttp3.OkHttpClient
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Sample {
    fun client(): OkHttpClient = OkHttpClient.Builder().build()

    fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
}

class OkHttpRewriteTest {
    private fun calls(bytes: ByteArray): List<String> {
        val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
        return node.methods.flatMap { method ->
            method.instructions.toArray().filterIsInstance<MethodInsnNode>().map {
                "${opcode(it.opcode)} ${it.owner}.${it.name}${it.desc}"
            }
        }
    }

    private fun opcode(value: Int): String = if (value == Opcodes.INVOKESTATIC) "static" else "virtual"

    private fun original(): ByteArray = javaClass.classLoader.getResourceAsStream("io/github/r1b2ns/stackaudit/gradle/Sample.class")!!.readBytes()

    private fun rewritten(): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(original()).accept(OkHttpRewrite.visitor(writer), 0)
        return writer.toByteArray()
    }

    @Test
    fun replacesBuilderBuildWithTheHelper() {
        val before = calls(original())
        val after = calls(rewritten())
        assertTrue("virtual okhttp3/OkHttpClient\$Builder.build()Lokhttp3/OkHttpClient;" in before)
        assertFalse(after.any { it.endsWith("OkHttpClient\$Builder.build()Lokhttp3/OkHttpClient;") })
        assertEquals(
            1,
            after.count {
                it == "static io/github/r1b2ns/stackaudit/okhttp/StackAuditOkHttp.build(Lokhttp3/OkHttpClient\$Builder;)Lokhttp3/OkHttpClient;"
            },
        )
        assertEquals(before.size, after.size)
    }

    @Test
    fun skipsOkHttpAndStackAuditClasses() {
        assertFalse(OkHttpRewrite.isInstrumentable("okhttp3.OkHttpClient\$Builder"))
        assertFalse(OkHttpRewrite.isInstrumentable("io.github.r1b2ns.stackaudit.okhttp.StackAuditOkHttp"))
        assertTrue(OkHttpRewrite.isInstrumentable("com.example.Api"))
        assertTrue(OkHttpRewrite.isInstrumentable("io.github.r1b2ns.stackaudit.demo.DemoRunner"))
    }

    @Test
    fun rewrittenClassStillLoads() {
        val bytes = rewritten()
        val loader =
            object : ClassLoader(javaClass.classLoader) {
                fun define(): Class<*> = defineClass("io.github.r1b2ns.stackaudit.gradle.Sample", bytes, 0, bytes.size)
            }
        val type = loader.define()
        assertEquals("builder", type.getMethod("builder").name)
    }
}
