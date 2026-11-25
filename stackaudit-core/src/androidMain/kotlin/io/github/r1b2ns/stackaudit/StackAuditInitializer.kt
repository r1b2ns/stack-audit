package io.github.r1b2ns.stackaudit

import android.content.Context
import androidx.startup.Initializer

public class StackAuditInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        StackAuditAndroid.attach(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
