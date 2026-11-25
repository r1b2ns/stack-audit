package io.github.r1b2ns.stackaudit.okhttp

import okhttp3.OkHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

class StackAuditOkHttpTest {
    @Test
    fun addsTheInterceptorOnce() {
        val client = StackAuditOkHttp.build(OkHttpClient.Builder())
        assertEquals(1, client.interceptors.count { it is StackAuditInterceptor })
        val again = StackAuditOkHttp.build(client.newBuilder())
        assertEquals(1, again.interceptors.count { it is StackAuditInterceptor })
    }

    @Test
    fun keepsAnInterceptorTheAppAlreadyAdded() {
        val client = StackAuditOkHttp.build(OkHttpClient.Builder().audited())
        assertEquals(1, client.interceptors.count { it is StackAuditInterceptor })
    }
}
