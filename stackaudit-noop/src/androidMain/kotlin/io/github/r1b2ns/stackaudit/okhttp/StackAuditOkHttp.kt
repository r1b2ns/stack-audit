package io.github.r1b2ns.stackaudit.okhttp

import okhttp3.OkHttpClient

public object StackAuditOkHttp {
    @JvmStatic
    public fun build(builder: OkHttpClient.Builder): OkHttpClient = builder.build()
}
