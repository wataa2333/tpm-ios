import XCTest

@MainActor
final class PreviewTests: XCTestCase {
    func testCompactCardsExpandAndSearchFiltersAreContextual() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        XCTAssertFalse(app.staticTexts["搜索分类"].exists)
        XCTAssertFalse(app.staticTexts["受理组别"].exists)
        let expand = app.buttons["order-expand-FA-CNC-008"].firstMatch
        XCTAssertTrue(expand.waitForExistence(timeout: 15))
        capture(app, "Compact work orders")
        expand.tap()
        for _ in 0..<3 where !app.staticTexts["受理组别"].isHittable { app.swipeUp() }
        XCTAssertTrue(app.staticTexts["受理组别"].exists)
        XCTAssertTrue(app.staticTexts["受理部门"].exists)
        capture(app, "Expanded work order")
        app.terminate(); app.launch()
        app.searchFields.firstMatch.tap()
        XCTAssertTrue(app.staticTexts["搜索分类"].waitForExistence(timeout: 5))
        capture(app, "Active search categories")
    }

    func testHierarchyAndDirectSignIn() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "fault-type-picker"]
        app.launch()
        XCTAssertFalse(app.staticTexts["轴承磨损"].exists)
        app.buttons["机械故障"].tap()
        XCTAssertTrue(app.buttons["轴承磨损"].waitForExistence(timeout: 5))
        capture(app, "Fault type hierarchy")
        app.terminate(); app.launchArguments = ["--screen", "user-picker"]; app.launch()
        app.buttons["按班组"].tap()
        app.buttons["设备维修组"].tap()
        XCTAssertTrue(app.buttons["示例维修人"].exists)
        capture(app, "User group hierarchy")
        app.terminate(); app.launchArguments = ["--screen", "sign-in"]; app.launch()
        app.buttons["签到"].tap()
        XCTAssertTrue(app.buttons["已签到"].exists)
        XCTAssertFalse(app.buttons["二维码签到"].exists)
        capture(app, "Direct sign in")
    }

    func testCompletedTaskReturnsToItsListRepeatedly() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        for _ in 0..<2 {
            app.staticTexts["立式加工中心异常报修"].firstMatch.tap()
            XCTAssertTrue(app.buttons["领取工单"].waitForExistence(timeout: 10))
            app.buttons["领取工单"].tap()
            XCTAssertTrue(app.buttons["提交"].waitForExistence(timeout: 10))
            app.buttons["提交"].tap()
            app.sheets.buttons["提交"].tap()
            XCTAssertTrue(app.navigationBars["故障报修"].waitForExistence(timeout: 10))
            XCTAssertFalse(app.navigationBars["报修详情"].exists)
        }
        capture(app, "Returned to order list")
    }
    func testVisibleFaultSearchPreviewStates() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 20))
        XCTAssertTrue(search.isHittable)
        capture(app, "Fault top search")
        search.tap(); search.typeText("立式")
        XCTAssertTrue(app.staticTexts["立式加工中心异常报修"].exists)
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
        let reference = app.descendants(matching: .any)["erp-reference-sn"].firstMatch
        XCTAssertTrue(reference.waitForExistence(timeout: 10))
        XCTAssertEqual(reference.value as? String, "BX-DEMO-001")
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
        guard ProcessInfo.processInfo.environment["TPM_CAPTURE_SCREENSHOTS"] == "true" else { return }
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testTodoFirstAndDashboardFactoryEntry() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "todo"]
        app.launch()
        XCTAssertTrue(app.navigationBars["我的待办"].waitForExistence(timeout: 15))
        XCTAssertLessThan(app.buttons["我的待办"].firstMatch.frame.minX, app.buttons["主页"].firstMatch.frame.minX)
        app.buttons["主页"].firstMatch.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "维修工单池")).firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "精密度检查工单池")).firstMatch.exists)
        app.buttons["工作台"].firstMatch.tap()
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "资产台账")).firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "ERP 出库")).firstMatch.exists)
        app.buttons["我的"].firstMatch.tap()
        XCTAssertFalse(app.buttons["切换厂区"].exists)
    }

    func testCompactSpacingAndRepeatedExpansion() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        let expand = app.buttons["order-expand-FA-CNC-008"]
        XCTAssertTrue(expand.waitForExistence(timeout: 15))
        let title = app.staticTexts["立式加工中心异常报修"].firstMatch
        XCTAssertLessThan(expand.frame.maxY - title.frame.minY, 140)
        for _ in 0..<2 {
            expand.tap()
            XCTAssertEqual(expand.value as? String, "已展开")
            expand.tap()
            XCTAssertEqual(expand.value as? String, "已收起")
        }
        app.buttons["新建报修"].tap()
        XCTAssertTrue(app.buttons["取消"].waitForExistence(timeout: 10))
        app.buttons["取消"].tap()
        XCTAssertTrue(expand.waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["新建报修"].exists)
    }

    func testFaultRequiredValidationAndSideBySideActions() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "new-fault"]
        app.launch()
        XCTAssertTrue(app.buttons["提交"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["保存"].exists)
        XCTAssertLessThan(app.buttons["保存"].frame.midX, app.buttons["提交"].frame.midX)
        XCTAssertLessThan(abs(app.buttons["提交"].frame.midY - app.buttons["保存"].frame.midY), 3)
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
        XCTAssertEqual(app.buttons.matching(identifier: "我的").count, 1)
    }

    func testAcceptanceRejectAndReturnScreens() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "reject-accept"]
        app.launch()
        XCTAssertTrue(app.staticTexts["驳回意见"].waitForExistence(timeout: 15))
        XCTAssertFalse(app.staticTexts["维修安排"].exists)
        capture(app, "Acceptance reject")
        app.terminate()
        app.launchArguments = ["--screen", "return-order"]
        app.launch()
        XCTAssertTrue(app.staticTexts["退单原因"].waitForExistence(timeout: 15))
        capture(app, "Return order")
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
