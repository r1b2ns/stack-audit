import StackAudit
import SwiftUI

struct ContentView: View {
    @State private var results: [String: String] = [:]
    @State private var running = false
    private let runner = DemoRunner()

    var body: some View {
        NavigationView {
            List {
                Section {
                    Button("Open StackAudit") { StackAudit.show() }
                        .font(.headline)
                    Button(running ? "Running…" : "Run all scenarios") { runAll() }
                        .disabled(running)
                } footer: {
                    Text("Shake the device (⌃⌘Z in the simulator) to open the log.")
                }
                Section("Scenarios") {
                    ForEach(Scenarios.all) { scenario in
                        Button {
                            Task { results[scenario.title] = await runner.run(scenario) }
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(scenario.title).foregroundColor(.primary)
                                Text(results[scenario.title] ?? "\(scenario.client.rawValue) · \(scenario.method)")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("StackAudit Demo")
        }
        .navigationViewStyle(.stack)
    }

    private func runAll() {
        running = true
        Task {
            for scenario in Scenarios.all {
                results[scenario.title] = await runner.run(scenario)
            }
            running = false
        }
    }
}
