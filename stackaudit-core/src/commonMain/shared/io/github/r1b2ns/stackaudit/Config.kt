package io.github.r1b2ns.stackaudit

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

public class SecretRules internal constructor(
    public val headers: Set<String>,
    public val fields: Set<String>,
    public val queryParams: Set<String>,
    internal val matcher: ((String) -> Boolean)?,
) {
    public class Builder internal constructor() {
        private val headers = DEFAULT_HEADERS.toMutableSet()
        private val fields = DEFAULT_FIELDS.toMutableSet()
        private val queryParams = mutableSetOf<String>()
        private var matcher: ((String) -> Boolean)? = null

        public fun header(name: String) {
            headers += normalize(name)
        }

        public fun jsonField(name: String) {
            fields += normalize(name)
        }

        public fun queryParam(name: String) {
            queryParams += normalize(name)
        }

        public fun allow(name: String) {
            val key = normalize(name)
            headers -= key
            fields -= key
            queryParams -= key
        }

        public fun matching(predicate: (name: String) -> Boolean) {
            matcher = predicate
        }

        internal fun build(): SecretRules = SecretRules(headers.toSet(), fields.toSet(), queryParams.toSet(), matcher)
    }

    internal fun isSecretHeader(name: String): Boolean = normalize(name) in headers || matcher?.invoke(name.lowercase()) == true

    internal fun isSecretField(name: String): Boolean {
        val key = normalize(name)
        return key in fields || key in queryParams || matcher?.invoke(name.lowercase()) == true
    }

    public companion object {
        public val DEFAULT_HEADERS: Set<String> =
            setOf("authorization", "proxy_authorization", "cookie", "set_cookie", "x_api_key")
        public val DEFAULT_FIELDS: Set<String> =
            setOf("password", "token", "access_token", "refresh_token", "id_token", "client_secret", "api_key")

        public val Default: SecretRules = Builder().build()

        internal fun build(configure: Builder.() -> Unit): SecretRules = Builder().apply(configure).build()

        internal fun normalize(name: String): String = name.trim().lowercase().replace('-', '_')
    }
}

public sealed class Secrets(public val rules: SecretRules) {
    public class Mask(configure: SecretRules.Builder.() -> Unit = {}) : Secrets(SecretRules.build(configure))

    public data object Show : Secrets(SecretRules.Default)

    public class ShowAfterHandshake(configure: SecretRules.Builder.() -> Unit = {}) : Secrets(SecretRules.build(configure))

    internal val masksOnWrite: Boolean get() = this !is Show
}

public sealed class Access {
    public data object Open : Access()

    public data class Handshake(
        val publicKey: String,
        val sessionTtl: Duration = 30.minutes,
    ) : Access()

    public data object Hidden : Access()
}

public class StackAuditConfig internal constructor(
    public val secrets: Secrets,
    public val access: Access,
    public val retention: Duration,
    public val maxBodySize: Long,
    public val maxStorage: Long,
    public val categories: Set<Category>,
    public val queueCapacity: Int,
) {
    public class Builder {
        public var secrets: Secrets = Secrets.Mask()
        public var access: Access = Access.Open
        public var retention: Duration = 7.days
        public var maxBodySize: Long = 1024L * 1024L
        public var maxStorage: Long = 100L * 1024L * 1024L
        public var categories: Set<Category> = Category.entries.toSet()
        public var queueCapacity: Int = 1000

        public fun build(): StackAuditConfig {
            require(secrets !is Secrets.ShowAfterHandshake || access is Access.Handshake) {
                "StackAudit: Secrets.ShowAfterHandshake requires access = Access.Handshake(publicKey)."
            }
            require(retention.isPositive()) { "StackAudit: retention must be positive." }
            require(maxBodySize >= 0) { "StackAudit: maxBodySize must not be negative." }
            require(maxStorage > 0) { "StackAudit: maxStorage must be positive." }
            require(queueCapacity > 0) { "StackAudit: queueCapacity must be positive." }
            return StackAuditConfig(secrets, access, retention, maxBodySize, maxStorage, categories.toSet(), queueCapacity)
        }
    }

    public companion object {
        public fun build(configure: Builder.() -> Unit = {}): StackAuditConfig = Builder().apply(configure).build()
    }
}
