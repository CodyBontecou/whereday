import Foundation
import Combine
import UIKit

@MainActor
protocol SmartStartTracking: AnyObject {
    var hasAlwaysPermission: Bool { get }
    var isTrackingEnabled: Bool { get }
    var trackingStartSource: TrackingStartSource? { get }
    func startAmbientMonitoring()
    func stopAmbientMonitoring()
    func startTracking(source: TrackingStartSource, allowsPermissionPrompt: Bool)
    func stopTrackingAutomatically()
}

/// One owner per LocationManager. The target is weak; callbacks carry a generation
/// so neither cancellation nor an async completion can resurrect disabled tracking.
@MainActor
final class AutoStartCoordinator {
    private weak var tracking: (any SmartStartTracking)?
    private let motion: any ActivityDetecting
    private let state: SmartStartStateStore
    private let canTrack: @MainActor () -> Bool
    private let now: () -> Date
    private let log: @MainActor (AutoStartPolicy.Decision) -> Void
    private var generation = UUID()
    private var running = false
    private var isShutdown = false
    private var lastEvidenceAt: Date?
    private var lastEvaluationAt: Date?
    private var lifecycleObserver: AnyCancellable?

    init(tracking: any SmartStartTracking, motion: any ActivityDetecting,
         state: SmartStartStateStore, canTrack: @escaping @MainActor () -> Bool,
         now: @escaping () -> Date = Date.init, observeLifecycle: Bool = true,
         log: (@MainActor (AutoStartPolicy.Decision) -> Void)? = nil) {
        self.tracking = tracking; self.motion = motion; self.state = state
        self.canTrack = canTrack; self.now = now
        self.log = log ?? { decision in
            LogManager.shared.info("Smart Start · \(decision)", category: .location)
        }
        if observeLifecycle {
            lifecycleObserver = NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)
                .sink { [weak self] _ in
                    Task { @MainActor [weak self] in self?.refresh() }
                }
        }
    }

    func updateSettings(_ settings: SmartStartSettings) {
        guard !isShutdown else { return }
        state.settings = settings
        // A changed mode/threshold must not apply an older in-flight query.
        cancelMotion()
        refresh()
    }

    /// Call on opt-in, foreground, entitlement and location authorization changes.
    /// This is also checked again at every completion, not cached as permission.
    func refresh() {
        guard !isShutdown else { return }
        guard let tracking else { shutdown(); return }
        guard state.settings.isEnabled, tracking.hasAlwaysPermission, canTrack() else {
            tracking.stopAmbientMonitoring()
            cancelMotion()
            return
        }
        tracking.startAmbientMonitoring()
        guard motion.status == .authorized || motion.status == .notDetermined else {
            cancelMotion()
            log(.suppress(.missingMotionPermission))
            return
        }
        guard !running else { return }
        running = true
        let token = generation
        motion.start { [weak self] evidence in self?.receive(evidence, generation: token) }
    }

    /// A location event is only a reason to ask for recent motion evidence.
    func locationWake() {
        refresh()
        if running { motion.queryRecentActivity() }
    }

    func shutdown() {
        isShutdown = true
        tracking?.stopAmbientMonitoring()
        cancelMotion()
        lifecycleObserver = nil
    }

    private func cancelMotion() {
        generation = UUID()
        if running { motion.stop() }
        running = false
        lastEvidenceAt = nil
        lastEvaluationAt = nil
    }

    private func receive(_ evidence: ActivityEvidence?, generation token: UUID) {
        guard running, token == generation, let tracking else { return }
        // Re-check even if no permission-change notification was delivered yet.
        guard state.settings.isEnabled, tracking.hasAlwaysPermission, canTrack(),
              motion.status == .authorized else {
            refresh()
            return
        }
        let date = now()
        guard let evidence, let signal = evidence.signal(at: date),
              lastEvidenceAt.map({ evidence.queriedAt > $0 }) ?? true,
              lastEvaluationAt.map({ date >= $0 }) ?? true else {
            log(.suppress(.invalidSignal))
            return
        }
        lastEvidenceAt = evidence.queriedAt
        lastEvaluationAt = date
        let context = AutoStartPolicy.Context(
            settings: state.settings, hasAlwaysLocation: tracking.hasAlwaysPermission,
            hasMotionPermission: motion.status == .authorized, canTrack: canTrack(),
            isTracking: tracking.isTrackingEnabled, source: tracking.trackingStartSource,
            lastManualStopAt: state.lastManualStopAt, lastAutoStartAt: state.lastAutoStartAt, now: date)
        let decision = AutoStartPolicy().evaluate(signal, context: context)
        log(decision)
        switch decision {
        case .start(let source): tracking.startTracking(source: source, allowsPermissionPrompt: false)
        case .stop: tracking.stopTrackingAutomatically()
        case .suppress, .noop: break
        }
    }
}
