import StackAudit
import SwiftUI

@main
struct DemoApp: App {
    init() {
        StackAudit.start {
            $0.secrets = .mask(extraHeaders: ["X-Session"])
        }
        try? StackAuditAnalytics.setTrackingPlan(json: #"{"events":{"purchase":{"required":["value","currency"],"types":{"value":"number"}}}}"#)
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
