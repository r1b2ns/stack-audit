package io.github.r1b2ns.stackaudit.gradle

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

public object OkHttpRewrite {
    public const val HELPER_CLASS: String = "io.github.r1b2ns.stackaudit.okhttp.StackAuditOkHttp"
    private const val HELPER = "io/github/r1b2ns/stackaudit/okhttp/StackAuditOkHttp"
    private const val BUILDER = "okhttp3/OkHttpClient\$Builder"
    private const val BUILD_DESCRIPTOR = "()Lokhttp3/OkHttpClient;"
    private const val HELPER_DESCRIPTOR = "(Lokhttp3/OkHttpClient\$Builder;)Lokhttp3/OkHttpClient;"
    private val excludedPackages = listOf("okhttp3.", "io.github.r1b2ns.stackaudit.okhttp.")

    public fun isInstrumentable(className: String): Boolean = excludedPackages.none { className.startsWith(it) }

    public fun visitor(next: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, next) {
            override fun visitMethod(
                access: Int,
                name: String?,
                descriptor: String?,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor {
                val method = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, method) {
                    override fun visitMethodInsn(
                        opcode: Int,
                        owner: String?,
                        name: String?,
                        descriptor: String?,
                        isInterface: Boolean,
                    ) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && owner == BUILDER && name == "build" && descriptor == BUILD_DESCRIPTOR) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "build", HELPER_DESCRIPTOR, false)
                        } else {
                            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                        }
                    }
                }
            }
        }
}
