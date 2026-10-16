import XCTest

@MainActor
final class PreviewTests: XCTestCase {
    func testAssetERPAndInventoryDetailsRestoreTabsOnlyOnTheirLists() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "workspace"]
        app.launch()
        let ledger = app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "资产台账")).firstMatch
        XCTAssertTrue(ledger.waitForExistence(timeout: 10)); ledger.tap()
        app.staticTexts["立式加工中心"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["资产详情"].waitForExistence(timeout: 10))
        assertTabBarHidden(app)
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.buttons["我的待办"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "ERP 出库")).firstMatch.tap()
        app.staticTexts["深沟球轴承领料"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["出库详情"].waitForExistence(timeout: 10))
        assertTabBarHidden(app)
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.buttons["我的待办"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.firstMatch.tap()
        app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "ERP 库存")).firstMatch.tap()
        app.staticTexts["深沟球轴承"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["库存详情"].waitForExistence(timeout: 10))
        assertTabBarHidden(app)
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(app.buttons["我的待办"].waitForExistence(timeout: 5))
    }

    func testNestedDetailsNeverRestoreTabsOverTaskActions() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        for _ in 0..<3 {
            app.buttons["order-title-FA-CNC-008"].firstMatch.tap()
            XCTAssertTrue(app.navigationBars["报修详情"].waitForExistence(timeout: 10))
            assertTabBarHidden(app)
            XCTAssertFalse(app.buttons["新建报修"].isHittable)
            let flow = app.buttons["处理流程"]
            for _ in 0..<6 where !flow.isHittable { app.swipeUp() }
            flow.tap()
            XCTAssertTrue(app.navigationBars["处理流程"].waitForExistence(timeout: 10))
            assertTabBarHidden(app)
            app.navigationBars.buttons.firstMatch.tap()
            XCTAssertTrue(app.navigationBars["报修详情"].waitForExistence(timeout: 10))
            assertTabBarHidden(app)
            app.navigationBars.buttons.firstMatch.tap()
            let listReturned = expectation(for: NSPredicate(format: "hittable == true"), evaluatedWith: app.navigationBars["故障报修"])
            wait(for: [listReturned], timeout: 10)
            XCTAssertFalse(app.navigationBars["报修详情"].isHittable, "一次返回必须离开工单详情")
            let restored = expectation(for: NSPredicate(format: "hittable == true"), evaluatedWith: app.buttons["新建报修"])
            wait(for: [restored], timeout: 10)
            XCTAssertTrue(app.buttons["我的待办"].exists)
        }
    }

    func testReferenceSelectionsSurviveReturningToForm() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "new-fault"]
        app.launch()
        let department = app.buttons["reference-受理部门"]
        for _ in 0..<4 where !department.isHittable { app.swipeUp() }
        XCTAssertTrue(department.isHittable)
        department.tap()
        app.buttons["示例厂区"].tap()
        app.buttons["示例生产部"].tap()
        XCTAssertTrue(app.staticTexts["示例生产部"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["示例默认受理人"].exists)
        XCTAssertEqual(app.searchFields.count, 0)
        let user = app.buttons["reference-维修受理人"]
        for _ in 0..<3 where !user.isHittable { app.swipeUp() }
        user.tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "示例维修人")).firstMatch.tap()
        XCTAssertTrue(app.staticTexts["示例维修人"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["示例生产部"].exists)
        user.tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "示例申请人")).firstMatch.tap()
        XCTAssertTrue(app.staticTexts["示例申请人"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["示例生产部"].exists)
    }

    func testCompactCardsExpandAndSearchFiltersAreContextual() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        XCTAssertFalse(app.staticTexts["搜索分类"].exists)
        XCTAssertFalse(factElement(app, "受理组别").exists)
        let expand = expansionControl(app)
        XCTAssertTrue(expand.waitForExistence(timeout: 15))
        XCTAssertNotNil(app.buttons["待抢单"].value as? String)
        XCTAssertTrue((app.buttons["待抢单"].value as? String)?.contains("单") == true)
        capture(app, "Compact work orders")
        expand.tap()
        for _ in 0..<6 where !factElement(app, "受理组别").isHittable { scrollUpSmall(app) }
        XCTAssertTrue(factElement(app, "受理组别").exists)
        XCTAssertTrue(factElement(app, "受理部门").exists)
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
            app.buttons["order-title-FA-CNC-008"].firstMatch.tap()
            XCTAssertTrue(app.buttons["领取工单"].waitForExistence(timeout: 10))
            app.buttons["领取工单"].tap()
            XCTAssertTrue(app.buttons["提交"].waitForExistence(timeout: 10))
            app.buttons["提交"].tap()
            app.sheets.buttons["提交"].tap()
            let returned = expectation(for: NSPredicate(format: "hittable == true"), evaluatedWith: app.navigationBars["故障报修"])
            wait(for: [returned], timeout: 10)
            XCTAssertFalse(app.navigationBars["报修详情"].isHittable)
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

    func testHomeFirstAndDashboardFactoryEntry() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "todo"]
        app.launch()
        XCTAssertTrue(app.navigationBars["我的待办"].waitForExistence(timeout: 15))
        XCTAssertLessThan(app.buttons["主页"].firstMatch.frame.minX, app.buttons["我的待办"].firstMatch.frame.minX)
        app.buttons["主页"].firstMatch.tap()
        XCTAssertTrue(app.buttons["新建报修"].exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "维修工单池")).firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "精密度检查工单池")).firstMatch.exists)
        app.buttons["工作台"].firstMatch.tap()
        XCTAssertTrue(app.buttons["新建报修"].exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "资产台账")).firstMatch.waitForExistence(timeout: 10))
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "ERP 出库")).firstMatch.exists)
        app.buttons["我的"].firstMatch.tap()
        XCTAssertTrue(app.buttons["新建报修"].exists)
        XCTAssertFalse(app.buttons["切换厂区"].exists)
    }

    func testCompactSpacingAndRepeatedExpansion() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        let expand = expansionControl(app)
        XCTAssertTrue(expand.waitForExistence(timeout: 15))
        let title = app.staticTexts["立式加工中心异常报修"].firstMatch
        XCTAssertLessThan(expand.frame.maxY - title.frame.minY, 140)
        let reference = app.descendants(matching: .any)["order-reference-FA-CNC-008"].firstMatch
        let time = app.descendants(matching: .any)["order-time-FA-CNC-008"].firstMatch
        XCTAssertLessThanOrEqual(time.frame.minY - reference.frame.maxY, 14)
        for _ in 0..<2 {
            expand.tap()
            XCTAssertTrue(expand.label.contains("收起详情"), app.debugDescription)
            expand.tap()
            XCTAssertTrue(expand.label.contains("展开详情"), app.debugDescription)
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
        // A real TabView places actions in its system accessory above the bar.
        XCTAssertLessThanOrEqual(add.frame.maxY, mine.frame.minY + 2)
        XCTAssertGreaterThan(add.frame.midX, app.frame.midX)
        XCTAssertTrue(add.isHittable)
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

    func testExpandedOpenOrderCanScrollAboveBottomBar() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        let expand = expansionControl(app)
        XCTAssertTrue(expand.waitForExistence(timeout: 15)); expand.tap()
        let open = app.buttons["order-open-FA-CNC-008"].firstMatch
        let bar = app.buttons["主页"].firstMatch
        for _ in 0..<8 {
            if open.exists && open.isHittable && open.frame.maxY < bar.frame.minY { break }
            scrollUpSmall(app)
        }
        XCTAssertTrue(open.isHittable)
        XCTAssertLessThan(open.frame.maxY, bar.frame.minY)
        open.tap()
        XCTAssertTrue(app.navigationBars["报修详情"].waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertEqual(app.searchFields.count, 0)
        assertTabBarHidden(app)
        app.navigationBars.buttons.firstMatch.tap()
        let listReturned = expectation(for: NSPredicate(format: "hittable == true"), evaluatedWith: app.navigationBars["故障报修"])
        wait(for: [listReturned], timeout: 10)
        XCTAssertFalse(app.navigationBars["报修详情"].isHittable, "打开工单后只需返回一次")
    }

    func testSearchDoesNotFollowNewSheetAndNativeDateControlsFit() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        XCTAssertTrue(app.searchFields.firstMatch.waitForExistence(timeout: 15))
        app.searchFields.firstMatch.tap()
        XCTAssertTrue(app.buttons["取消搜索"].waitForExistence(timeout: 5))
        app.buttons["取消搜索"].tap()
        app.buttons["新建报修"].tap()
        XCTAssertTrue(app.buttons["取消"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.searchFields.count, 0)
        let date = app.datePickers["date-value-故障时间"]
        for _ in 0..<4 where !date.isHittable { app.swipeUp() }
        let time = app.datePickers["time-value-故障时间"]
        XCTAssertTrue(date.isHittable)
        XCTAssertTrue(time.isHittable)
        XCTAssertLessThan(date.frame.maxX, time.frame.minX + 1)
        XCTAssertLessThanOrEqual(time.frame.maxX, app.frame.maxX - 16)
        XCTAssertEqual(app.searchFields.count, 0)
    }

    private func expansionControl(_ app: XCUIApplication) -> XCUIElement {
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@ OR label CONTAINS %@", "展开详情", "收起详情")).firstMatch
    }

    private func factElement(_ app: XCUIApplication, _ title: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", title)).firstMatch
    }

    private func scrollUpSmall(_ app: XCUIApplication) {
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.72))
        let end = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.52))
        start.press(forDuration: 0.05, thenDragTo: end)
    }

    private func assertTabBarHidden(_ app: XCUIApplication) {
        let hidden = expectation(for: NSPredicate(format: "hittable == false"), evaluatedWith: app.tabBars.firstMatch)
        wait(for: [hidden], timeout: 5)
        XCTAssertFalse(app.tabBars.firstMatch.isHittable)
    }
}
