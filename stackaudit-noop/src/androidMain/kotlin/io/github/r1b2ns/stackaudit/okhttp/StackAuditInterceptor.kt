package io.github.r1b2ns.stackaudit.okhttp

import io.github.r1b2ns.stackaudit.HttpRecorder
import io.github.r1b2ns.stackaudit.StackAudit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

public class StackAuditInterceptor
    @JvmOverloads
    constructor(
        recorder: HttpRecorder = StackAudit,
        source: String = "okhttp",
    ) : Interceptor {
        init {
            recorder.hashCode()
            source.hashCode()
        }

        override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
    }

public fun OkHttpClient.Builder.audited(recorder: HttpRecorder = StackAudit): OkHttpClient.Builder = this.also { recorder.hashCode() }
