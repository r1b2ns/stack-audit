package io.github.r1b2ns.stackaudit.gradle

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor

public abstract class OkHttpVisitorFactory : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor {
        val available = classContext.loadClassData(OkHttpRewrite.HELPER_CLASS) != null
        return if (available) OkHttpRewrite.visitor(nextClassVisitor) else nextClassVisitor
    }

    override fun isInstrumentable(classData: ClassData): Boolean = OkHttpRewrite.isInstrumentable(classData.className)
}
