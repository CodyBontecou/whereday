#if DEBUG && os(iOS)
import Foundation
import SelfieOverlayKit
import UIKit

/// Debug-only selfie overlay bootstrap.
///
/// Entire file is `#if DEBUG && os(iOS)`-guarded: Release builds compile this
/// to nothing, and the SelfieOverlayKit `debug-only` branch itself compiles to
/// an empty library in Release — so no overlay code links into production.
///
/// Usage: summon the selfie bubble with a **3-tap, 2-finger gesture** anywhere
/// in the app. Tap again to dismiss. The bubble itself offers recording
/// controls and customization (long-press).
enum DebugSelfie {
    static func install() {
        let overlay = SelfieOverlayKit.shared

        // Global gesture: 3 taps with 2 fingers toggles the bubble from anywhere.
        overlay.enableSummonGesture(taps: 3, touches: 2)

        overlay.onRawExportComplete = { bundle in
            print("[DebugSelfie] raw export complete")
            print("[DebugSelfie]   screen:", bundle.screenURL.lastPathComponent)
            print("[DebugSelfie]   camera:", bundle.cameraURL.lastPathComponent)
            print("[DebugSelfie]   audio: ", bundle.audioURL?.lastPathComponent ?? "embedded/none")
        }
        overlay.onRawExportFailed = { error in
            print("[DebugSelfie] raw export failed:", error.localizedDescription)
        }

        print("[DebugSelfie] installed — 3-tap 2-finger gesture summons the selfie bubble")
    }
}

/// Temporary, opt-in local diagnostics. Never records labels, text, locations, or network data.
@MainActor
private enum ScrollDiagnosticStorage {
    static var timer: Timer?
    static var lastState = ""
    static var tick = 0
    static var handle: FileHandle?
}

@MainActor
enum DebugScrollDiagnostics {
    static func installIfRequested() {
        guard ProcessInfo.processInfo.arguments.contains("--scroll-diagnostics") else { return }
        let url = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("scroll-diagnostics.txt")
        FileManager.default.createFile(atPath: url.path, contents: Data())
        ScrollDiagnosticStorage.handle = try? FileHandle(forWritingTo: url)
        let timer = Timer(timeInterval: 0.2, repeats: true) { _ in
            MainActor.assumeIsolated { sample() }
        }
        ScrollDiagnosticStorage.timer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private static func sample() {
        ScrollDiagnosticStorage.tick += 1
        // Bound the diagnostic session to ten minutes and roughly 3,000 samples.
        guard ScrollDiagnosticStorage.tick <= 3_000 else {
            ScrollDiagnosticStorage.timer?.invalidate()
            try? ScrollDiagnosticStorage.handle?.close()
            return
        }
        let windows = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap(\.windows)
        var lines = ["applicationState=\(UIApplication.shared.applicationState.rawValue)"]
        for window in windows where !window.isHidden {
            lines.append("WINDOW \(kind(window)) level=\(window.windowLevel.rawValue) key=\(window.isKeyWindow)")
            let point = CGPoint(x: window.bounds.width * 0.5, y: window.bounds.height * 0.7)
            if let hit = window.hitTest(point, with: nil) {
                lines.append("HIT \(kind(hit))")
                for view in sequence(first: hit, next: { $0.superview }) {
                    lines.append("ANCESTOR \(kind(view)) interactive=\(view.isUserInteractionEnabled) \(gestures(view))")
                }
            }
            var queue: [UIView] = [window]
            var index = 0
            while index < queue.count {
                let view = queue[index]
                index += 1
                queue.append(contentsOf: view.subviews)
                guard let scroll = view as? UIScrollView, !scroll.isHidden else { continue }
                lines.append("SCROLL \(kind(scroll)) bounds=\(scroll.bounds) size=\(scroll.contentSize) offset=\(scroll.contentOffset) enabled=\(scroll.isScrollEnabled) panEnabled=\(scroll.panGestureRecognizer.isEnabled) panState=\(scroll.panGestureRecognizer.state.rawValue) tracking=\(scroll.isTracking) dragging=\(scroll.isDragging) decelerating=\(scroll.isDecelerating) \(gestures(scroll))")
            }
        }
        let state = lines.joined(separator: "\n")
        guard state != ScrollDiagnosticStorage.lastState || ScrollDiagnosticStorage.tick % 25 == 0 else { return }
        ScrollDiagnosticStorage.lastState = state
        let entry = "[SCROLL-PROBE] tick=\(ScrollDiagnosticStorage.tick)\n\(state)\n\n"
        try? ScrollDiagnosticStorage.handle?.write(contentsOf: Data(entry.utf8))
    }

    private static func kind(_ object: AnyObject) -> String {
        "\(type(of: object))@\(Unmanaged.passUnretained(object).toOpaque())"
    }

    private static func gestures(_ view: UIView) -> String {
        (view.gestureRecognizers ?? []).map {
            "\(kind($0)){state=\($0.state.rawValue),enabled=\($0.isEnabled),cancel=\($0.cancelsTouchesInView),delayBegin=\($0.delaysTouchesBegan),delayEnd=\($0.delaysTouchesEnded)}"
        }.joined(separator: ";")
    }
}
#endif
