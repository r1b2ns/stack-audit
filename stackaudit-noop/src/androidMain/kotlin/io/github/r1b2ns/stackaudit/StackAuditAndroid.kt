package io.github.r1b2ns.stackaudit

import android.content.Context

public object StackAuditAndroid {
    public fun attach(context: Context) {
        context.hashCode()
    }
}
