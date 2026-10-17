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

    func testDraftBoxOpensFromTheNewFormAndKeyboardButtonSitsInTheActionBar() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "new-fault", "--skip-biometrics"]
        app.launch()
        XCTAssertTrue(app.buttons["draft-box-button"].waitForExistence(timeout: 10))
        app.buttons["draft-box-button"].tap()
        XCTAssertTrue(app.navigationBars["草稿箱"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["草稿箱是空的"].exists)
        app.buttons["完成"].tap()
        let name = app.textFields["请输入报修名称"]
        XCTAssertTrue(name.waitForExistence(timeout: 10))
        name.tap()
        let dismiss = app.buttons["keyboard-dismiss"]
        XCTAssertTrue(dismiss.waitForExistence(timeout: 10))
        let save = app.buttons["保存"], submit = app.buttons["提交"]
        XCTAssertTrue(dismiss.frame.minY >= submit.frame.maxY - 2 && !(dismiss.frame.minX > save.frame.maxX && dismiss.frame.maxX < submit.frame.minX), "收起键盘应贴着键盘，不在保存和提交这一行")
        dismiss.tap()
        XCTAssertFalse(app.buttons["keyboard-dismiss"].waitForExistence(timeout: 2))
    }

    func testSwipeMenuPinsAnOptionToTheTopOfThePicker() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "user-picker"]
        app.launch()
        let row = app.buttons["示例申请人"]
        XCTAssertTrue(row.waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts["置顶 · 1/10"].exists)
        row.swipeLeft()
        XCTAssertTrue(app.buttons["置顶"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["设为默认"].exists)
        app.buttons["置顶"].tap()
        XCTAssertTrue(app.staticTexts["置顶 · 1/10"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["全部"].exists)
        // Long press offers the same choices.
        app.buttons.matching(identifier: "示例申请人").firstMatch.press(forDuration: 1.0)
        XCTAssertTrue(app.buttons["取消置顶"].waitForExistence(timeout: 5))
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
            XCTAssertTrue(app.buttons["抢单"].waitForExistence(timeout: 10))
            app.buttons["抢单"].tap()
            XCTAssertTrue(app.buttons["提交"].waitForExistence(timeout: 10))
            app.buttons["提交"].tap()
            // No confirmation sheet: submitting is the confirmation.
            XCTAssertFalse(app.buttons["dialog-primary"].waitForExistence(timeout: 1))
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
    func testFactoryMenuSwitchesImmediatelyWithoutChooserOrConfirmation() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "home"]
        app.launch()
        let menu = app.descendants(matching: .any)["切换厂区，当前示例一厂"].firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 20))
        menu.tap()
        XCTAssertTrue(app.buttons["示例二厂"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["示例一厂"].isEnabled, "当前厂区不能重复选择")
        app.buttons["示例二厂"].tap()
        // No full-screen chooser, no confirmation: the toolbar simply shows the new factory.
        XCTAssertTrue(app.descendants(matching: .any)["切换厂区，当前示例二厂"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertFalse(app.navigationBars["切换厂区"].exists)
        XCTAssertFalse(app.buttons["dialog-primary"].exists)
        XCTAssertFalse(app.navigationBars["切换厂区"].exists)
    }
    func testSigningInKeepsTheOrderOpenOnItsNextStepAndBackReturnsOnce() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "signed"]
        app.launch()
        app.buttons["order-title-FA-CNC-008"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["报修详情"].waitForExistence(timeout: 10))
        app.buttons["签到"].firstMatch.tap()
        XCTAssertTrue(app.navigationBars["任务签到"].waitForExistence(timeout: 10))
        // The detail's own 签到 button stays behind the sheet; tap the one that is reachable.
        let signNow = app.buttons.matching(NSPredicate(format: "label == %@ AND hittable == true", "签到")).firstMatch
        XCTAssertTrue(signNow.waitForExistence(timeout: 5))
        signNow.tap()
        // The next step is shown on the same order, not the stale 待签到 page.
        XCTAssertTrue(app.buttons["完成任务"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.navigationBars["报修详情"].isHittable)
        app.navigationBars.buttons.firstMatch.tap()
        let listReturned = expectation(for: NSPredicate(format: "hittable == true"), evaluatedWith: app.navigationBars["故障报修"])
        wait(for: [listReturned], timeout: 10)
        XCTAssertFalse(app.navigationBars["报修详情"].isHittable, "返回一次就应回到列表")
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
        XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", "所有工单")).firstMatch.waitForExistence(timeout: 10))
        for title in ["待受理", "待抢单", "待签到", "待完成", "待确认", "已完结"] {
            XCTAssertTrue(app.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS %@", title)).firstMatch.exists, title)
        }
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
        XCTAssertTrue(app.staticTexts["提示"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["请填写或选择报修名称"].exists)
        capture(app, "Required field validation")
        app.buttons["dialog-primary"].tap()
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
        // One round button at the bottom-right corner, above the tab bar and not one of its tabs.
        XCTAssertLessThanOrEqual(add.frame.maxY, mine.frame.minY + 2)
        XCTAssertGreaterThan(add.frame.midX, app.frame.midX)
        XCTAssertTrue(add.isHittable)
        XCTAssertEqual(app.buttons.matching(identifier: "我的").count, 1)
        XCTAssertEqual(app.tabBars.firstMatch.buttons.count, 4)
        // It opens the form as a sheet and the page stays where it was.
        add.tap()
        XCTAssertTrue(app.buttons["取消"].waitForExistence(timeout: 10))
        app.buttons["取消"].tap()
        XCTAssertTrue(app.buttons["新建报修"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.navigationBars["故障报修"].exists)
    }

    func testQuickRepairQueryCardsOpenTheOrderList() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "workspace"]
        app.launch()
        let machineCard = app.buttons["quick-query-按机台号查询"]
        for _ in 0..<4 where !machineCard.exists { app.swipeUp() }
        XCTAssertTrue(machineCard.waitForExistence(timeout: 10))
        // Empty text: the button is disabled and nothing opens.
        XCTAssertFalse(machineCard.isEnabled)
        let field = app.textFields["按机台号查询"]
        field.tap(); field.typeText("D-E59")
        XCTAssertTrue(machineCard.isEnabled)
        machineCard.tap()
        XCTAssertTrue(app.navigationBars["故障报修"].waitForExistence(timeout: 10))
        // Back on the workspace the box is empty again: nothing of the last query is left in it.
        app.navigationBars.buttons.firstMatch.tap()
        XCTAssertTrue(machineCard.waitForExistence(timeout: 10))
        XCTAssertFalse(machineCard.isEnabled)
        XCTAssertNotEqual(app.textFields["按机台号查询"].value as? String, "D-E59")
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

    func testRepairCompletionRequiresNextNodeApproverBeforeConfirming() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "repair-commit"]
        app.launch()
        let approver = app.buttons["assignee-demoRepairConfirmUser"]
        XCTAssertTrue(approver.waitForExistence(timeout: 15), app.debugDescription)
        let submit = app.buttons["提交"]
        let enabled = expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: submit)
        wait(for: [enabled], timeout: 10)
        // Nothing chosen: submitting is refused and names the next node; no confirmation sheet opens.
        submit.tap()
        XCTAssertTrue(app.staticTexts["提交未完成"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "审批人")).firstMatch.exists)
        XCTAssertFalse(app.staticTexts["提交当前流程节点？"].exists)
        app.buttons["dialog-primary"].tap()
        // Choose the approver: submitting then goes straight through, with no confirmation sheet.
        approver.tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "示例审批人")).firstMatch.tap()
        XCTAssertTrue(approver.waitForExistence(timeout: 5))
        submit.tap()
        XCTAssertFalse(app.staticTexts["提交当前流程节点？"].exists)
        XCTAssertFalse(app.staticTexts["提交未完成"].exists)
    }

    func testLongTextBoxIsCompactAndCopyable() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        let expand = expansionControl(app)
        XCTAssertTrue(expand.waitForExistence(timeout: 15)); expand.tap()
        let fact = factElement(app, "故障描述")
        for _ in 0..<6 where !fact.isHittable { scrollUpSmall(app) }
        fact.tap()
        XCTAssertTrue(app.buttons["copy-button"].waitForExistence(timeout: 5))
        // Short text: the box is nowhere near half of the screen.
        let sheetBar = app.navigationBars["故障描述"]
        XCTAssertTrue(sheetBar.waitForExistence(timeout: 5))
        XCTAssertGreaterThan(sheetBar.frame.minY, app.frame.height * 0.45)
        app.buttons["copy-button"].tap()
        XCTAssertTrue(app.buttons["已复制"].waitForExistence(timeout: 3))
    }

    func testSearchDoesNotFollowNewSheetAndNativeDateControlsFit() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "faults"]
        app.launch()
        XCTAssertTrue(app.searchFields.firstMatch.waitForExistence(timeout: 15))
        app.searchFields.firstMatch.tap()
        let cancelSearch = app.buttons.matching(NSPredicate(format: "label == %@ OR label == %@", "取消", "Cancel")).firstMatch
        XCTAssertTrue(cancelSearch.waitForExistence(timeout: 5))
        cancelSearch.tap()
        app.buttons["新建报修"].tap()
        XCTAssertTrue(app.buttons["取消"].waitForExistence(timeout: 10))
        // The list's search field stays behind the sheet; it must not be reachable from the form.
        XCTAssertFalse(app.searchFields.allElementsBoundByIndex.contains { $0.isHittable })
        let date = app.datePickers["date-value-故障时间"]
        for _ in 0..<4 where !date.isHittable { app.swipeUp() }
        let time = app.datePickers["time-value-故障时间"]
        XCTAssertTrue(date.isHittable)
        XCTAssertTrue(time.isHittable)
        XCTAssertLessThan(date.frame.maxX, time.frame.minX + 1)
        XCTAssertLessThanOrEqual(time.frame.maxX, app.frame.maxX - 16)
        XCTAssertFalse(app.searchFields.allElementsBoundByIndex.contains { $0.isHittable })
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
