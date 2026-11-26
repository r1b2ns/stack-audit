import StackAuditKit
import UIKit
import XCTest
@testable import StackAudit

@MainActor
final class ViewerHostingTests: XCTestCase {
    override func setUp() async throws {
        TestSupport.startOnce()
    }

    func testHostBundleIsMissingTheProMotionPlistKey() {
        XCTAssertNil(Bundle.main.object(forInfoDictionaryKey: "CADisableMinimumFrameDurationOnPhone"))
    }

    func testViewerAppearsInAHostWithoutTheProMotionPlistKey() async throws {
        let closed = expectation(description: "onClose not called")
        closed.isInverted = true
        let controller = StackAuditUi.shared.viewController { closed.fulfill() }
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = controller
        window.makeKeyAndVisible()

        await fulfillment(of: [closed], timeout: 2)

        XCTAssertTrue(controller.isViewLoaded)
        XCTAssertNotNil(controller.view.window)
        window.isHidden = true
    }
}
