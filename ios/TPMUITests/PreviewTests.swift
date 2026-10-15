import XCTest

@MainActor
final class PreviewTests: XCTestCase {
    func testERPFormRendersAndKeepsTwoMaterials() {
        let app = XCUIApplication()
        app.launchArguments = ["--screen", "erp-form", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(app.navigationBars["ERP 出库"].waitForExistence(timeout: 20), "ERP form did not render")
        for _ in 0..<3 where !app.staticTexts["深沟球轴承"].isHittable { app.swipeUp() }
        XCTAssertTrue(app.staticTexts["深沟球轴承"].exists)
        XCTAssertTrue(app.staticTexts["空气滤芯"].exists)
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "ERP multiple materials"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
