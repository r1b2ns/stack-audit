package io.github.r1b2ns.stackaudit.demo

import android.app.Application
import io.github.r1b2ns.stackaudit.Secrets
import io.github.r1b2ns.stackaudit.StackAudit

class DemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StackAudit.start {
            secrets = Secrets.Mask { header("X-Session") }
        }
    }
}
