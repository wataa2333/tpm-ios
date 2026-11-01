import XCTest

@MainActor
final class MotionTests: XCTestCase {
    func testSearchKeyboardRepeatedDismissalKeepsCardExpanded() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "todo"]
        app.launch()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 10))
        let disclosure = app.buttons.matching(NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@", "展开详情", "收起详情")).firstMatch
        XCTAssertTrue(disclosure.waitForExistence(timeout: 5))
        disclosure.tap()
        let open = app.buttons["order-open-FA-CNC-008"].firstMatch
        XCTAssertTrue(open.waitForExistence(timeout: 5))
        for _ in 0..<3 {
            search.tap()
            let done = app.buttons["keyboard-dismiss"]
            XCTAssertTrue(done.waitForExistence(timeout: 5))
            XCTAssertTrue(done.isHittable)
            XCTAssertEqual(app.buttons.matching(identifier: "keyboard-dismiss").count, 1)
            XCTAssertFalse(app.buttons["新建报修"].isHittable, "搜索时新建附件应退出")
            done.tap()
            let closed = expectation(for: NSPredicate { _, _ in app.keyboards.count == 0 }, evaluatedWith: nil)
            wait(for: [closed], timeout: 5)
            XCTAssertTrue(disclosure.label.contains("收起详情"), "收起键盘不应重建卡片或清空展开状态")
            XCTAssertTrue(open.exists)
        }
        let cancel = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "取消", "Cancel")).firstMatch
        XCTAssertTrue(cancel.waitForExistence(timeout: 5))
        cancel.tap()
        XCTAssertTrue(app.buttons["新建报修"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.tabBars.firstMatch.buttons.count, 4)
    }

    func testPhoneKeyboardCanDismissAfterSwitchingFields() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "new-fault", "--skip-biometrics"]
        app.launch()
        let name = app.textFields["请输入报修名称"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap()
        name.typeText("Keyboard regression")
        app.buttons["keyboard-dismiss"].tap()
        let closed = expectation(for: NSPredicate { _, _ in app.keyboards.count == 0 }, evaluatedWith: nil)
        wait(for: [closed], timeout: 5)
        let phone = app.textFields["请输入联系电话"]
        for _ in 0..<6 where !phone.isHittable { app.swipeUp() }
        XCTAssertTrue(phone.isHittable)
        for _ in 0..<3 {
            phone.tap()
            let done = app.buttons["keyboard-dismiss"]
            XCTAssertTrue(done.waitForExistence(timeout: 5))
            XCTAssertTrue(done.isHittable)
            done.tap()
            let closed = expectation(for: NSPredicate { _, _ in app.keyboards.count == 0 }, evaluatedWith: nil)
            wait(for: [closed], timeout: 5)
            XCTAssertTrue(app.buttons["保存"].isHittable)
            XCTAssertTrue(app.buttons["提交"].isHittable)
        }
    }

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
