import XCTest

/// App Store の紹介動画用に、本物のアプリをいつもの流れで操作する。
/// CI でこのテストを動かしながらシミュレータの画面を録画する（.github/workflows/preview-video.yml）。
/// 写真とマスクは、スクリーンショットと同じ撮影モード（-demoScene）でアプリの Documents から読む。
final class PreviewVideoUITests: XCTestCase {
    func testAppPreviewFlow() throws {
        let app = XCUIApplication()
        app.launchArguments = ["-demoScene", "preview", "-demoPremium"]
        app.launch()

        // 1. 撮った写真 → 次へ
        let next = app.buttons["次へ"]
        XCTAssertTrue(next.waitForExistence(timeout: 15))
        pause(2.5)
        next.tap()

        // 2. 自動で切り抜き → 次へ
        XCTAssertTrue(app.staticTexts["作品を切り抜きました"].waitForExistence(timeout: 15))
        pause(2.5)
        app.buttons["次へ"].tap()

        // 3. 縁の調整 → 次へ
        XCTAssertTrue(app.navigationBars["縁の調整"].waitForExistence(timeout: 15))
        pause(1.2)
        app.buttons["次へ"].tap()

        // 4. 背景を次々に選ぶ
        XCTAssertTrue(app.navigationBars["背景"].waitForExistence(timeout: 30))
        pause(1.5)
        // 動画では机置きの背景だけを使う（壁の背景は作品が大きく見えすぎるため）
        for (category, background) in [
            ("カフェ", "ラテのある木のテーブル"),
            ("布", "ドライフラワーとリネン"),
            ("季節の背景", "ジンジャーブレッドとキャンディケーン"),
            ("木", "毛糸と編み針"),
        ] {
            tapIfExists(app.buttons[category])
            pause(0.6)
            tapIfExists(app.buttons[background])
            pause(1.6)
        }

        // 5. 背景だけを調整・接地影
        tapIfExists(app.buttons["調整"])
        pause(0.8)
        let sliders = app.sliders
        if sliders.count >= 3 {
            sliders.element(boundBy: 0).adjust(toNormalizedSliderPosition: 0.62)
            pause(0.8)
            sliders.element(boundBy: 1).adjust(toNormalizedSliderPosition: 0.6)
            pause(1.0)
        }
        tapIfExists(app.buttons["影"])
        pause(0.8)
        if app.sliders.count >= 1 {
            app.sliders.element(boundBy: 0).adjust(toNormalizedSliderPosition: 0.6)
            pause(1.2)
        }

        // 6. 書き出し：比率を変える
        app.buttons["次へ"].tap()
        XCTAssertTrue(app.navigationBars["書き出し"].waitForExistence(timeout: 30))
        pause(3)
        tapIfExists(app.buttons["4:5"])
        pause(2.5)
        tapIfExists(app.buttons["元の比率"])
        pause(3)
    }

    private func pause(_ seconds: TimeInterval) {
        Thread.sleep(forTimeInterval: seconds)
    }

    private func tapIfExists(_ element: XCUIElement) {
        if element.waitForExistence(timeout: 3) && element.isHittable {
            element.tap()
        }
    }
}
