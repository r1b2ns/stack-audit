package io.github.r1b2ns.stackaudit.channel

import io.github.r1b2ns.stackaudit.Access
import io.github.r1b2ns.stackaudit.FlagProvider
import io.github.r1b2ns.stackaudit.FlagValue
import io.github.r1b2ns.stackaudit.HandshakeResult
import io.github.r1b2ns.stackaudit.Header
import io.github.r1b2ns.stackaudit.HttpExchange
import io.github.r1b2ns.stackaudit.HttpRecorder
import io.github.r1b2ns.stackaudit.SecretRules
import io.github.r1b2ns.stackaudit.Secrets
import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.StackAuditConfig
import io.github.r1b2ns.stackaudit.StorageChange
import io.github.r1b2ns.stackaudit.StorageOperation
import io.github.r1b2ns.stackaudit.StorageRecorder
import io.github.r1b2ns.stackaudit.TrackingPlan
import kotlin.time.Duration.Companion.seconds

public class StackAuditChannel(
    private val http: HttpRecorder = StackAudit,
    private val storage: StorageRecorder = StackAudit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    public fun handle(
        method: String,
        args: Map<String, Any?>,
    ): Any? =
        when (method) {
            "start" -> StackAudit.start(config(args)).let { null }
            "isCapturing" -> http.isCapturing
            "maxBodySize" -> http.maxBodySize
            "recordHttp" -> http.record(exchange(args)).let { null }
            "recordStorage" -> storage.record(storageChange(args)).let { null }
            "recordAnalytics" ->
                StackAudit.analytics
                    .record(text(args, "provider"), text(args, "name"), map(args["params"]))
                    .let { null }
            "setTrackingPlan" -> setTrackingPlan(args["json"] as String?).let { null }
            "flag" -> flag(args)
            "handshake" -> handshake(text(args, "token"))
            "handleLink" -> StackAudit.handleLink(text(args, "url"))
            "deviceId" -> StackAudit.deviceId
            "show" -> StackAudit.show()
            else -> throw UnsupportedOperationException(method)
        }

    public fun exchange(args: Map<String, Any?>): HttpExchange {
        val requestHeaders = headers(args["requestHeaders"])
        val responseHeaders = headers(args["responseHeaders"])
        return HttpExchange(
            source = args["source"] as String? ?: "flutter",
            method = text(args, "method"),
            url = text(args, "url"),
            requestHeaders = requestHeaders,
            requestBody = args["requestBody"] as ByteArray?,
            requestContentType = contentType(requestHeaders),
            status = (args["status"] as Number?)?.toInt(),
            responseHeaders = responseHeaders,
            responseBody = args["responseBody"] as ByteArray?,
            responseContentType = contentType(responseHeaders),
            startedAt = (args["startedAt"] as Number?)?.toLong() ?: now(),
            durationMs = (args["durationMs"] as Number?)?.toLong() ?: 0,
            error = args["error"] as String?,
            requestBodySize = (args["requestBodySize"] as Number?)?.toLong(),
            responseBodySize = (args["responseBodySize"] as Number?)?.toLong(),
        )
    }

    public fun storageChange(args: Map<String, Any?>): StorageChange {
        val operation = text(args, "operation")
        return StorageChange(
            source = args["source"] as String? ?: "flutter",
            store = text(args, "store"),
            key = args["key"] as String?,
            operation =
                StorageOperation.entries.firstOrNull { it.name.equals(operation, ignoreCase = true) }
                    ?: throw IllegalArgumentException("unknown storage operation $operation"),
            timestamp = (args["timestamp"] as Number?)?.toLong() ?: now(),
            oldValue = args["oldValue"] as String?,
            newValue = args["newValue"] as String?,
            valueType = args["valueType"] as String?,
        )
    }

    public companion object {
        public const val NAME: String = "io.github.r1b2ns.stackaudit"

        public fun config(args: Map<String, Any?>): StackAuditConfig =
            StackAuditConfig.build {
                val rules: SecretRules.Builder.() -> Unit = {
                    list(args["extraHeaders"]).forEach(::header)
                    list(args["extraFields"]).forEach(::jsonField)
                    list(args["allow"]).forEach(::allow)
                }
                secrets =
                    when (args["secrets"] ?: "mask") {
                        "mask" -> Secrets.Mask(rules)
                        "show" -> Secrets.Show
                        "showAfterHandshake" -> Secrets.ShowAfterHandshake(rules)
                        else -> throw IllegalArgumentException("unknown secrets mode ${args["secrets"]}")
                    }
                access =
                    when (args["access"] ?: "open") {
                        "open" -> Access.Open
                        "hidden" -> Access.Hidden
                        "handshake" ->
                            Access.Handshake(
                                requireNotNull(args["publicKey"] as String?) { "access handshake requires publicKey" },
                                ((args["sessionTtlSeconds"] as Number?)?.toLong() ?: 1800).seconds,
                                args["appId"] as String?,
                            )
                        else -> throw IllegalArgumentException("unknown access mode ${args["access"]}")
                    }
                (args["retentionSeconds"] as Number?)?.let { retention = it.toLong().seconds }
                (args["maxBodySize"] as Number?)?.let { maxBodySize = it.toLong() }
                (args["maxStorage"] as Number?)?.let { maxStorage = it.toLong() }
            }

        public fun flagValue(
            kind: String,
            value: Any?,
        ): FlagValue? =
            when {
                value == null -> null
                kind == "bool" -> (value as? Boolean)?.let { FlagValue.Bool(it) }
                kind == "string" -> (value as? String)?.let { FlagValue.Text(it) }
                kind == "number" -> (value as? Number)?.let { FlagValue.Number(it.toDouble()) }
                else -> throw IllegalArgumentException("unknown flag kind $kind")
            }

        private fun flag(args: Map<String, Any?>): Any {
            val key = text(args, "key")
            val kind = text(args, "kind")
            val remote = flagValue(kind, args["value"])
            val flags = StackAudit.flags.wrap(FlagProvider { remote }, args["provider"] as String? ?: "app")
            val fallback = args["default"]
            return when (kind) {
                "bool" -> flags.bool(key, fallback as Boolean)
                "string" -> flags.string(key, fallback as String)
                "number" -> flags.number(key, (fallback as Number).toDouble())
                else -> throw IllegalArgumentException("unknown flag kind $kind")
            }
        }

        private fun setTrackingPlan(json: String?) {
            StackAudit.analytics.plan =
                json?.let {
                    try {
                        TrackingPlan.fromJson(it)
                    } catch (error: Exception) {
                        throw IllegalArgumentException("invalid tracking plan: ${error.message}")
                    }
                }
        }

        private fun handshake(token: String): String? =
            when (val result = StackAudit.handshake(token)) {
                is HandshakeResult.Accepted -> null
                is HandshakeResult.Rejected -> result.reason
            }

        private fun text(
            args: Map<String, Any?>,
            name: String,
        ): String = requireNotNull(args[name] as String?) { "$name is required" }

        private fun list(value: Any?): List<String> = (value as List<*>?)?.filterIsInstance<String>().orEmpty()

        private fun map(value: Any?): Map<String, Any?> =
            (value as Map<*, *>?)?.entries?.associate { it.key.toString() to it.value }.orEmpty()

        private fun headers(value: Any?): List<Header> =
            (value as Map<*, *>?)?.entries?.map { Header(it.key.toString(), it.value.toString()) }.orEmpty()

        private fun contentType(headers: List<Header>): String? =
            headers.firstOrNull { it.name.equals("Content-Type", ignoreCase = true) }?.value
    }
}
