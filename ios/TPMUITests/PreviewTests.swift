import XCTest

@MainActor
final class PreviewTests: XCTestCase {
    private func capture(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testFaultRequiredValidationAndStackedActions() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "new-fault"]
        app.launch()
        XCTAssertTrue(app.buttons["提交"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["保存"].exists)
        XCTAssertLessThanOrEqual(app.buttons["提交"].frame.maxY, app.buttons["保存"].frame.minY)
        app.buttons["提交"].tap()
        XCTAssertTrue(app.alerts["请检查表单"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.alerts.staticTexts["请填写或选择报修名称"].exists)
        capture(app, "Required field validation")
        app.alerts.buttons["好"].tap()
        app.swipeUp(); app.swipeUp()
        capture(app, "Fault form lower fields")
    }

    func testNewActionIsSeparateFromTabs() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        let add = app.buttons["新建报修"]
        XCTAssertTrue(add.waitForExistence(timeout: 20))
        XCTAssertLessThanOrEqual(add.frame.maxY, app.tabBars.firstMatch.frame.minY)
    }

    func testERPFormRendersAndKeepsTwoMaterials() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "erp-form", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.navigationBars["ERP 出库"].waitForExistence(timeout: 20), "ERP form did not render")
        for _ in 0..<3 where !app.staticTexts["深沟球轴承"].isHittable { app.swipeUp() }
        XCTAssertTrue(app.staticTexts["深沟球轴承"].exists)
        XCTAssertTrue(app.staticTexts["空气滤芯"].exists)
        capture(app, "ERP multiple materials")
    }
}
