import XCTest

@MainActor
final class MotionTests: XCTestCase {
    func testBatchBarRestoresItsHeightAfterRepeatedDeselection() {
        checkBatchBar(reduced: false)
    }

    func testBatchBarRemainsUsableWithReducedMotion() {
        checkBatchBar(reduced: true)
    }

    private func checkBatchBar(reduced: Bool) {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "motion-regression"] + (reduced ? ["--motion-reduced"] : [])
        app.launch()
        let toggle = app.buttons["motion-toggle-order"]
        let bar = app.otherElements["motion-batch-bar"]
        let run = app.buttons["batch-run"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10))
        XCTAssertTrue(bar.waitForExistence(timeout: 5))
        let emptyHeight = bar.frame.height
        XCTAssertGreaterThan(emptyHeight, 0)
        XCTAssertFalse(run.exists)
        for _ in 0..<3 {
            toggle.tap()
            XCTAssertTrue(run.waitForExistence(timeout: 5))
            XCTAssertTrue(run.isHittable)
            XCTAssertGreaterThan(bar.frame.height, emptyHeight + 20)
            toggle.tap()
            let removed = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: run)
            wait(for: [removed], timeout: 5)
            XCTAssertEqual(bar.frame.height, emptyHeight, accuracy: 2, "取消最后一单后不应保留操作行空白")
        }
        app.terminate()
    }
}
