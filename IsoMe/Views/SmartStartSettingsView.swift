import SwiftUI
import CoreMotion

/// UI intent boundary: reading preferences never opts in or starts a sensor.
@MainActor
final class SmartStartSettingsModel: ObservableObject {
    @Published private(set) var settings: SmartStartSettings
    @Published var requiresConfirmation = false
    @Published private(set) var errorMessage: String?
    private var enableRequested = false
    private let read: () -> SmartStartSettings
    private let save: (SmartStartSettings) throws -> Void

    init(read: @escaping () -> SmartStartSettings, save: @escaping (SmartStartSettings) throws -> Void) {
        self.read = read
        self.save = save
        settings = read()
    }

    func refresh() { settings = read() }

    func requestEnabled(_ enabled: Bool) {
        refresh()
        if enabled && !settings.isEnabled {
            enableRequested = true
            requiresConfirmation = true
        } else {
            cancelEnable()
            var next = read()
            next.isEnabled = enabled
            persist(next)
        }
    }

    func cancelEnable() {
        enableRequested = false
        requiresConfirmation = false
    }

    func confirmEnable() {
        // Alert presentation is cleared by SwiftUI before the button action runs.
        guard enableRequested else { return }
        cancelEnable()
        var next = read()
        next.isEnabled = true
        persist(next)
    }

    /// Activity/timing controls cannot bypass the explicit opt-in boundary.
    func update(_ edit: (inout SmartStartSettings) -> Void) {
        var next = read()
        let enabled = next.isEnabled
        edit(&next)
        guard next.isEnabled == enabled else { return }
        persist(next)
    }

    private func persist(_ next: SmartStartSettings) {
        do {
            try next.validate()
            try save(next)
            settings = read()
            guard settings == next else { throw SaveError.notApplied }
            errorMessage = nil
        } catch {
            settings = read()
            errorMessage = String(localized: "Smart Start settings weren’t saved. Your previous settings are still active.")
        }
    }

    private enum SaveError: Error { case notApplied }
}

struct SmartStartSettingsView: View {
    @Environment(\.layoutDirection) private var layoutDirection
    @ObservedObject private var locationManager: LocationManager
    @StateObject private var model: SmartStartSettingsModel
    @State private var motionStatus = MotionDetectionStatus.unavailable

    init(locationManager: LocationManager) {
        _locationManager = ObservedObject(wrappedValue: locationManager)
        _model = StateObject(wrappedValue: SmartStartSettingsModel(
            read: { locationManager.smartStartSettings },
            save: { try locationManager.updateSmartStartSettings($0) }))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackPageHeader(caption: "RECORDING · OPTIONAL AUTOMATION", title: "Smart Start", preservesCursiveShaping: preservesCursiveShaping)
                    .padding(.horizontal, 24)
                    .padding(.top, 8)
                sectionLabel("AUTOMATIC RECORDING")
                BackPageCard {
                    BackPageToggleRow(title: "Smart Start", subtitle: model.settings.isEnabled ? "On · requires permissions and tracking access" : "Off unless you choose to enable it",
                        isOn: Binding(get: { model.settings.isEnabled }, set: { model.requestEnabled($0) }))
                        .accessibilityIdentifier("smart-start-enabled")
                }.padding(.horizontal, 16)
                BackPageFootnote("iso.me can try to start tracking when iOS reports meaningful movement, but iOS may delay or suppress background triggers. Force-quitting the app prevents automatic relaunch until you open it again. Shortcuts automations are the most reliable way to start from CarPlay, workouts, Focus modes, Bluetooth, or NFC.")

                if let error = model.errorMessage {
                    Text(error).font(.callout).foregroundStyle(TE.inkRust)
                        .padding(.horizontal, 24).padding(.vertical, 12)
                        .accessibilityIdentifier("smart-start-save-error")
                }

                sectionLabel("PERMISSIONS")
                BackPageCard {
                    BackPageRowLabel(title: Text("Location access"), value: locationStatus, accessory: .none)
                    BackPageDivider()
                    BackPageRowLabel(title: Text("Motion activity"), value: motionStatusText, accessory: .none)
                    if !locationManager.hasAlwaysPermission || motionStatus == .denied {
                        BackPageDivider()
                        Button {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        } label: {
                            BackPageRowLabel(title: Text("Open iOS Settings"), tint: TE.inkRust, accessory: .external)
                        }.buttonStyle(.plain)
                    }
                }.padding(.horizontal, 16)
                BackPageFootnote("Always Location and Motion access are needed for automatic starts, along with tracking access in your plan. Opening this page does not request Motion access. Low-power monitoring alone does not save route points.")
                if motionStatus == .unavailable {
                    BackPageFootnote("Motion activity is unavailable on this device. Smart Start cannot start a recording here; manual tracking and Shortcuts remain separate.")
                }

                sectionLabel("ACTIVITIES")
                BackPageCard {
                    BackPageToggleRow(title: "Driving", isOn: binding(\.drivingEnabled))
                    BackPageDivider()
                    BackPageToggleRow(title: "Walking or running", isOn: binding(\.walkingRunningEnabled))
                    BackPageDivider()
                    BackPageToggleRow(title: "Cycling", isOn: binding(\.cyclingEnabled))
                }.padding(.horizontal, 16)
                if !model.settings.drivingEnabled && !model.settings.walkingRunningEnabled && !model.settings.cyclingEnabled {
                    BackPageFootnote("No activities selected. Smart Start will not begin new recordings.")
                }

                sectionLabel("CONSERVATIVE TIMING")
                BackPageCard {
                    durationPicker("Movement threshold", key: \.minimumMovementDuration, options: [60, 120, 300, 600, 900])
                    BackPageDivider()
                    durationPicker("Pause after manual Stop", key: \.manualStopCooldown, options: [0, 900, 1800, 3600])
                    BackPageDivider()
                    durationPicker("Wait before restarting", key: \.restartCooldown, options: [0, 60, 300, 900, 1800])
                    BackPageDivider()
                    BackPageToggleRow(title: "Stop when stationary", isOn: binding(\.stationaryAutoStopEnabled))
                    if model.settings.stationaryAutoStopEnabled {
                        BackPageDivider()
                        durationPicker("Stationary threshold", key: \.stationaryDuration, options: [120, 300, 600, 900])
                    }
                }.padding(.horizontal, 16)
                BackPageFootnote("Movement must be sustained, high-confidence activity, not just elapsed time. Stationary auto-stop applies only to a session owned by Smart Start; it does not stop a manual, Watch, or Shortcut session. Automatic starts can increase battery use and may mistake a passenger journey for driving.")

                sectionLabel("SHORTCUTS · THE RELIABLE ROUTE")
                BackPageCard {
                    Text("In Shortcuts, create a personal automation and choose iso.me’s Start Tracking or Stop Tracking action. Use CarPlay connect/disconnect, workout start/end, Driving Focus, Bluetooth, or NFC as the trigger. Review the automation’s confirmation and run settings in Shortcuts.")
                        .font(.body).padding(16)
                }.padding(.horizontal, 16)
                BackPageFootnote("This is not a native CarPlay app or a workout background service. iOS controls background delivery. Test your automation before relying on it, especially while locked, in Low Power Mode, or with Background App Refresh off.")
            }.padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .tint(TE.textPrimary)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .accessibilityIdentifier("smart-start-settings")
        .alert("Turn on Smart Start?", isPresented: $model.requiresConfirmation) {
            Button("Not now", role: .cancel) { model.cancelEnable() }
            Button("Turn on") { model.confirmEnable(); refreshStatus() }
        } message: {
            Text("This enables low-power monitoring when permissions and tracking access allow it. Motion permission may be requested after you opt in. iOS can delay or suppress automatic starts; force-quitting prevents automatic relaunch until you open iso.me again.")
        }
        .onAppear { refreshStatus() }
        .onDisappear { model.cancelEnable() }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in refreshStatus() }
    }

    // The custom monospace font and tracking separate Arabic letters. Other
    // pages keep their defaults; this page uses an untracked system RTL caption.
    private var preservesCursiveShaping: Bool { layoutDirection == .rightToLeft }

    private func sectionLabel(_ title: LocalizedStringKey) -> some View {
        BackPageLabel(title, preservesCursiveShaping: preservesCursiveShaping)
    }

    private func refreshStatus() {
        model.refresh()
        guard CMMotionActivityManager.isActivityAvailable() else { motionStatus = .unavailable; return }
        switch CMMotionActivityManager.authorizationStatus() {
        case .authorized: motionStatus = .authorized
        case .notDetermined: motionStatus = .notDetermined
        case .denied, .restricted: motionStatus = .denied
        @unknown default: motionStatus = .denied
        }
    }

    private var locationStatus: Text {
        switch locationManager.authorizationStatus {
        case .authorizedAlways: Text("Always")
        case .authorizedWhenInUse: Text("While using · Always needed")
        case .denied, .restricted: Text("Not allowed")
        default: Text("Not set")
        }
    }

    private var motionStatusText: Text {
        switch motionStatus {
        case .unavailable: Text("Unavailable")
        case .notDetermined: Text("Not requested")
        case .denied: Text("Not allowed")
        case .authorized: Text("Allowed")
        }
    }

    private func binding<Value>(_ key: WritableKeyPath<SmartStartSettings, Value>) -> Binding<Value> {
        Binding(get: { model.settings[keyPath: key] }, set: { value in model.update { $0[keyPath: key] = value } })
    }

    private func durationPicker(_ title: LocalizedStringKey, key: WritableKeyPath<SmartStartSettings, TimeInterval>, options: [TimeInterval]) -> some View {
        // Preserve valid custom values rather than silently clamping an existing preference.
        let values = Array(Set(options + [model.settings[keyPath: key]])).sorted()
        return BackPagePickerRow(title: title) {
            Picker(title, selection: binding(key)) {
                ForEach(values, id: \.self) { seconds in
                    if seconds == 0 { Text("None").tag(seconds) }
                    else { Text(Duration.seconds(seconds).formatted(.units(allowed: [.minutes, .seconds], width: .wide))).tag(seconds) }
                }
            }.labelsHidden().pickerStyle(.menu).frame(minHeight: 44)
        }
    }
}
