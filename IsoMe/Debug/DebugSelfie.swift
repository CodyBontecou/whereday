#if DEBUG && os(iOS)
import Foundation
import SelfieOverlayKit

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
#endif
