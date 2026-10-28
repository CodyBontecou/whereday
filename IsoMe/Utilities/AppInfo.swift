import UIKit

enum AppInfo {
    static var version: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }

    static var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
    }

    static var versionDisplay: String {
        "\(version) (\(buildNumber))"
    }

    static var platformDisplay: String {
        String(localized: "iOS \(UIDevice.current.systemVersion)")
    }

    static var deviceModel: String {
        UIDevice.current.model
    }

    static var fullDeviceInfo: String {
        String(localized: "App: Whereday \(versionDisplay)\nPlatform: \(platformDisplay)\nDevice: \(deviceModel)")
    }
}
