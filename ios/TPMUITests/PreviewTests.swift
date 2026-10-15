import XCTest

@MainActor
final class PreviewTests: XCTestCase {
    func testVisibleFaultSearchPreviewStates() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 20))
        XCTAssertTrue(search.isHittable)
        capture(app, "Fault top search")
        search.tap(); search.typeText("立式")
        XCTAssertTrue(app.staticTexts["立式加工中心"].exists)
        capture(app, "Fault search results")
        search.tap()
        if let text = search.value as? String { search.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: text.count)) }
        search.typeText("不存在的设备")
        XCTAssertTrue(app.staticTexts["暂无相关报修"].waitForExistence(timeout: 5))
        capture(app, "Fault search empty")
    }

    func testRepairExpandedMaterialPreview() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "processing"]
        app.launch()
        let material = app.staticTexts["深沟球轴承"].firstMatch
        XCTAssertTrue(material.waitForExistence(timeout: 20))
        for _ in 0..<4 where !material.isHittable { app.swipeUp() }
        material.tap()
        app.swipeUp()
        capture(app, "Repair expanded material")
    }
    func testRepairOpensLinkedERPWithMultipleMaterials() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "processing"]
        app.launch()
        XCTAssertTrue(app.buttons["新建出库单"].waitForExistence(timeout: 20))
        capture(app, "Repair form top")
        for _ in 0..<3 where !app.buttons["新建出库单"].isHittable { app.swipeUp() }
        capture(app, "Repair ERP entry")
        app.buttons["新建出库单"].tap()
        XCTAssertTrue(app.navigationBars["ERP 出库"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["BX-DEMO-001"].exists)
        capture(app, "Repair linked ERP form")
        for _ in 0..<4 where !app.staticTexts["空气滤芯"].isHittable { app.swipeUp() }
        XCTAssertTrue(app.staticTexts["申请数量：2 个"].exists)
        XCTAssertTrue(app.staticTexts["申请数量：1 件"].exists)
        capture(app, "Repair linked ERP materials")
    }
    func testFactorySelectionRequiresDifferentFactory() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "factories"]
        app.launch()
        XCTAssertTrue(app.buttons["示例一厂"].waitForExistence(timeout: 20))
        XCTAssertFalse(app.buttons["切换"].isEnabled)
        app.buttons["示例二厂"].tap()
        XCTAssertTrue(app.buttons["切换"].isEnabled)
        XCTAssertEqual(app.buttons["示例二厂"].value as? String, "已选择")
        capture(app, "Factory selection")
    }
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
        let mine = app.buttons["我的"].firstMatch
        XCTAssertLessThan(abs(add.frame.midY - mine.frame.midY), 10)
        XCTAssertGreaterThan(add.frame.minX, mine.frame.maxX)
    }

    func testERPFormRendersAndKeepsTwoMaterials() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "erp-form", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.navigationBars["ERP 出库"].waitForExistence(timeout: 20), "ERP form did not render")
        for _ in 0..<3 where !app.staticTexts["深沟球轴承"].isHittable { app.swipeUp() }
        XCTAssertTrue(app.staticTexts["深沟球轴承"].exists)
        for _ in 0..<3 where !app.staticTexts["空气滤芯"].isHittable { app.swipeUp() }
        XCTAssertTrue(app.staticTexts["空气滤芯"].exists)
        capture(app, "ERP multiple materials")
        let material = app.staticTexts["深沟球轴承"].firstMatch
        if material.isHittable { material.tap(); app.swipeUp(); capture(app, "ERP expanded material") }
    }
}
