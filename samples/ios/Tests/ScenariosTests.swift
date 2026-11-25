import XCTest
@testable import StackAuditDemo

final class ScenariosTests: XCTestCase {
    func testTitlesAreUnique() {
        let titles = Scenarios.all.map(\.title)
        XCTAssertEqual(titles.count, Set(titles).count)
    }

    func testEveryClientIsCovered() {
        XCTAssertEqual(Set(Scenarios.all.map(\.client)), Set(DemoClient.allCases))
    }

    func testScenariosUseHttps() {
        XCTAssertTrue(Scenarios.all.allSatisfy { $0.url.hasPrefix("https://") || $0.url.hasPrefix("wss://") || $0.url.hasPrefix("local://") })
    }

    func testSecretsScenarioCarriesSecrets() {
        let login = Scenarios.all.first { $0.title == "POST login with secrets" }
        XCTAssertNotNil(login?.headers["Authorization"])
        XCTAssertTrue(login?.body?.contains("password") == true)
    }
}
