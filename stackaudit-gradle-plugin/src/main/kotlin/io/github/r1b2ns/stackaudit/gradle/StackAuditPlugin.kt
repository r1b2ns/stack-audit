package io.github.r1b2ns.stackaudit.gradle

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

public abstract class StackAuditExtension {
    public abstract val okhttp: Property<Boolean>

    public abstract val skipBuildTypes: ListProperty<String>
}

public class StackAuditPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("stackAudit", StackAuditExtension::class.java)
        extension.okhttp.convention(true)
        extension.skipBuildTypes.convention(emptyList())
        project.plugins.withId("com.android.application") {
            val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
            components.onVariants { variant ->
                val skipped = variant.buildType in extension.skipBuildTypes.get()
                if (extension.okhttp.get() && !skipped) {
                    variant.instrumentation.transformClassesWith(
                        OkHttpVisitorFactory::class.java,
                        InstrumentationScope.ALL,
                    ) {}
                    variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
                }
            }
        }
    }
}
