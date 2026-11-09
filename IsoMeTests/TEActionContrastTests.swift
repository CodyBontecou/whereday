import SwiftUI
import UIKit
import XCTest
@testable import IsoMe

final class TEActionContrastTests: XCTestCase {
    func testActionColorsMeetNormalTextContrastInBothAppearances() {
        for appearance in [UIUserInterfaceStyle.light, .dark] {
            let traits = UITraitCollection(userInterfaceStyle: appearance)
            let paper = components(TE.surface, traits: traits)
            let accent = components(TE.accent, traits: traits)
            let destructive = components(TE.inkRust, traits: traits)

            // Filled primary labels use paper, not white (the accent becomes light in dark mode).
            assertContrast(paper, accent, appearance: appearance)
            assertContrast(paper, blend([0, 0, 0], over: accent, opacity: 0.12), appearance: appearance)

            // Outlined actions have opaque paper plus an ink wash, including their pressed state.
            for ink in [accent, destructive] {
                let background = blend(ink, over: paper, opacity: 0.08)
                assertContrast(ink, background, appearance: appearance)
                assertContrast(ink, blend(ink, over: background, opacity: 0.04), appearance: appearance)
            }
        }
    }

    private func components(_ color: Color, traits: UITraitCollection) -> [Double] {
        let resolved = UIColor(color).resolvedColor(with: traits)
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        XCTAssertTrue(resolved.getRed(&red, green: &green, blue: &blue, alpha: &alpha))
        XCTAssertEqual(alpha, 1)
        return [Double(red), Double(green), Double(blue)]
    }

    private func blend(_ ink: [Double], over paper: [Double], opacity: Double) -> [Double] {
        zip(ink, paper).map { $0 * opacity + $1 * (1 - opacity) }
    }

    private func luminance(_ rgb: [Double]) -> Double {
        let linear = rgb.map { $0 <= 0.04045 ? $0 / 12.92 : pow(($0 + 0.055) / 1.055, 2.4) }
        return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722
    }

    private func assertContrast(_ foreground: [Double], _ background: [Double], appearance: UIUserInterfaceStyle,
                                file: StaticString = #filePath, line: UInt = #line) {
        let values = [luminance(foreground), luminance(background)].sorted()
        let ratio = (values[1] + 0.05) / (values[0] + 0.05)
        XCTAssertGreaterThanOrEqual(ratio, 4.5, "Insufficient action contrast in \(appearance): \(ratio)", file: file, line: line)
    }
}

@MainActor
final class AppAppearanceWindowOverrideTests: XCTestCase {
    func testAppearanceResetUpdatesAnExistingChildController() {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 200, height: 200))
        let root = UIViewController()
        let child = UIViewController()
        window.rootViewController = root
        root.addChild(child)
        root.view.addSubview(child.view)
        child.didMove(toParent: root)
        window.makeKeyAndVisible()
        let bridge = AppAppearanceWindowOverride.AppearanceView()
        bridge.appearance = .dark
        root.view.addSubview(bridge)
        window.updateTraitsIfNeeded()
        defer { window.isHidden = true }

        XCTAssertEqual(window.overrideUserInterfaceStyle, .dark)
        XCTAssertEqual(child.view.traitCollection.userInterfaceStyle, .dark)
        bridge.appearance = .light
        window.updateTraitsIfNeeded()
        XCTAssertEqual(child.view.traitCollection.userInterfaceStyle, .light)
        bridge.appearance = .system
        window.updateTraitsIfNeeded()
        XCTAssertEqual(window.overrideUserInterfaceStyle, .unspecified)
        XCTAssertEqual(child.view.traitCollection.userInterfaceStyle, window.traitCollection.userInterfaceStyle)
    }

    func testPreferenceAppliesWhenBridgeAttachesToAWindowLater() {
        let bridge = AppAppearanceWindowOverride.AppearanceView()
        bridge.appearance = .dark
        let window = UIWindow(frame: .zero)
        window.addSubview(bridge)
        XCTAssertEqual(window.overrideUserInterfaceStyle, .dark)
        bridge.removeFromSuperview()
        let replacement = UIWindow(frame: .zero)
        replacement.addSubview(bridge)
        XCTAssertEqual(replacement.overrideUserInterfaceStyle, .dark)
    }
}
