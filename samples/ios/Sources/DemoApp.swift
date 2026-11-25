import StackAudit
import SwiftUI

@main
struct DemoApp: App {
    init() {
        StackAudit.start {
            $0.secrets = .mask(extraHeaders: ["X-Session"])
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
