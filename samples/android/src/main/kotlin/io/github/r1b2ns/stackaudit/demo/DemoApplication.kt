package io.github.r1b2ns.stackaudit.demo

import android.app.Application
import io.github.r1b2ns.stackaudit.Secrets
import io.github.r1b2ns.stackaudit.StackAudit
import io.github.r1b2ns.stackaudit.TrackingPlan

class DemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StackAudit.start {
            secrets = Secrets.Mask { header("X-Session") }
        }
        StackAudit.analytics.plan =
            TrackingPlan.fromJson(
                """{"events":{"purchase":{"required":["value","currency"],"types":{"value":"number"}}}}""",
            )
    }
}
