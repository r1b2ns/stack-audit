package io.github.r1b2ns.stackaudit.flutter

import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.github.r1b2ns.stackaudit.channel.StackAuditChannel
import io.github.r1b2ns.stackaudit.ui.StackAuditUi

class StackAuditPlugin :
    FlutterPlugin,
    MethodChannel.MethodCallHandler {
    private var channel: MethodChannel? = null
    private val handler = StackAuditChannel()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        StackAuditUi.install(binding.applicationContext)
        channel = MethodChannel(binding.binaryMessenger, StackAuditChannel.NAME).also { it.setMethodCallHandler(this) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
    }

    override fun onMethodCall(
        call: MethodCall,
        result: MethodChannel.Result,
    ) {
        val args = (call.arguments as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value }.orEmpty()
        try {
            result.success(handler.handle(call.method, args))
        } catch (error: UnsupportedOperationException) {
            result.notImplemented()
        } catch (error: IllegalArgumentException) {
            result.error("invalid_arguments", error.message, null)
        } catch (error: Exception) {
            result.error("stackaudit_error", error.message, null)
        }
    }
}
