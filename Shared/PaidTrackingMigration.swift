import Foundation

/// Shared by iPhone and Watch. Build 50 (1.7.18) shipped with free tracking;
/// 51 (1.7.19) is the intended first subscription build. Reconfirm that boundary
/// before the first paid release, then freeze it across future version bumps.
/// This policy never reads the running app's build number to infer the cohort.
nonisolated enum PaidTrackingMigration {
    static let firstPaidTrackingBuild = 51
    static let legacyCohortKey = "tracking.cohort.legacy.v1"

    static func isLegacyOriginalVersion(_ version: String) -> Bool {
        guard !version.isEmpty,
              version.utf8.allSatisfy({ (48...57).contains($0) }),
              let build = Int(version), build > 0 else { return false }
        return build < firstPaidTrackingBuild
    }

    /// Call only with an original version from a verified AppTransaction.
    /// A fresh reinstall or a previously persisted false marker must still be
    /// repairable from purchase history; an existing legacy grant stays sticky.
    static func restoreLegacyAccess(verifiedOriginalVersion: String, defaults: UserDefaults) -> Bool {
        let legacy = defaults.bool(forKey: legacyCohortKey)
            || isLegacyOriginalVersion(verifiedOriginalVersion)
        if legacy { defaults.set(true, forKey: legacyCohortKey) }
        return legacy
    }
}
