import SwiftUI
import WatchConnectivity

/// When the Apple Watch last handed recorded points or stays to this iPhone.
/// ContentView stamps it whenever a watch payload is imported.
enum WatchSyncStatus {
    static let lastImportDateKey = "watchSync.lastImportDate"
}

/// How Whereday listens: recording modes and what stays on the phone, in plain words.
/// The modes are presets over the distance filter and auto-stop LocationManager already applies.
struct ListeningView: View {
    let viewModel: LocationViewModel
    @ObservedObject private var locationManager: LocationManager
    @ObservedObject private var webhookManager = WebhookManager.shared
    @ObservedObject private var dailyScheduler = DailyExportScheduler.shared
    @Environment(\.colorScheme) private var colorScheme
    @AppStorage("appAppearance") private var appAppearance = AppAppearance.system.rawValue
    @AppStorage(WatchSyncStatus.lastImportDateKey) private var lastWatchImport: Double = 0

    @AppStorage("allowNetworkGeocoding") private var allowNetworkGeocoding = true
    @AppStorage(RecordingSessionInferenceConfiguration.includesInferredSessionsKey)
    private var includesInferredSessions = RecordingSessionInferenceConfiguration.defaultIncludesInferredSessions
    @AppStorage(RecordingSessionInferenceConfiguration.gapPresetKey)
    private var inferenceGapPresetRawValue = RecordingSessionInferenceConfiguration.defaultGapPreset.rawValue
    @AppStorage(RecordingSessionInferenceConfiguration.minimumDurationPresetKey)
    private var inferenceMinimumDurationPresetRawValue = RecordingSessionInferenceConfiguration.defaultMinimumDurationPreset.rawValue
    @AppStorage(RecordingSessionInferenceConfiguration.minimumPointCountKey)
    private var inferenceMinimumPointCount = RecordingSessionInferenceConfiguration.defaultMinimumPointCountPreset.rawValue

    init(viewModel: LocationViewModel) {
        self.viewModel = viewModel
        _locationManager = ObservedObject(initialValue: viewModel.locationManager)
    }

    /// nil while recording by time: none of the distance presets describes that.
    private var currentDetail: RecordingDetail? {
        guard locationManager.routeSamplingMode == .distance else { return nil }
        return RecordingDetail(distanceFilter: locationManager.distanceFilter)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackPageHeader(caption: "RECORDING · WHAT STAYS ON THIS IPHONE", title: "How Whereday listens")
                    .padding(.horizontal, 24)
                    .padding(.top, 8)

                BackPageLabel("RECORDING")
                recordingCard
                    .padding(.horizontal, 16)

                VStack(spacing: 8) {
                    ForEach(RecordingDetail.allCases) { detail in
                        modeCard(detail)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                BackPageFootnote("Every mode also notes the places you stop and large moves, even when Whereday is closed.")

                BackPageLabel("FINE-TUNE")
                fineTuneCard
                    .padding(.horizontal, 16)
                BackPageFootnote("Record points sets how often your route gets a point. At 1 min or more, GPS rests between points. Stop After auto-stops as a safety net.")

                BackPageLabel("OUTINGS FROM SAVED POINTS")
                inferredOutingsCard
                    .padding(.horizontal, 16)
                BackPageFootnote("When exact start/stop sessions don't exist, Whereday can infer outings from saved GPS points. These settings only affect auto-generated inferred outings; recorded sessions stay intact.")

                BackPageLabel("WHAT STAYS · WHAT LEAVES")
                privacyCard
                    .padding(.horizontal, 16)

                deviceCard
                    .padding(.horizontal, 16)
                    .padding(.top, 16)

                closingNote
                    .padding(.horizontal, 24)
                    .padding(.top, 28)
            }
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
    }

    // MARK: - Recording

    private var recordingCard: some View {
        BackPageCard {
            BackPageToggleRow(
                title: "Record my days",
                subtitle: locationManager.isTrackingEnabled ? "Recording now" : "Paused",
                isOn: Binding(
                    get: { locationManager.isTrackingEnabled },
                    set: { newValue in
                        if newValue {
                            viewModel.startTracking()
                        } else {
                            viewModel.stopTracking()
                        }
                    }
                )
            )
            BackPageDivider()
            BackPageRowLabel(
                title: Text("Location access"),
                value: Text(permissionStatusText),
                valueColor: permissionStatusColor,
                accessory: .none
            )
            .accessibilityElement(children: .combine)

            if !locationManager.hasAlwaysPermission {
                BackPageDivider()
                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    BackPageRowLabel(
                        title: Text("Open iOS Settings"),
                        subtitle: Text("Choose Always so Whereday can keep notes in the background."),
                        tint: TE.inkRust,
                        accessory: .external
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }

    private func modeCard(_ detail: RecordingDetail) -> some View {
        let isSelected = detail == currentDetail
        return Button {
            detail.apply(to: locationManager)
        } label: {
            HStack(alignment: .top, spacing: 14) {
                Image(systemName: isSelected ? "largecircle.fill.circle" : "circle")
                    .font(.title3)
                    .foregroundStyle(isSelected ? TE.placeInks[1] : TE.textMuted)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(detail.listeningTitle)
                        .font(.body.weight(.semibold))
                    Text(detail.listeningSummary)
                        .font(.footnote)
                        .foregroundStyle(TE.textMuted)
                }
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .strokeBorder(isSelected ? TE.textPrimary : TE.border, lineWidth: isSelected ? 2 : 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var permissionStatusText: String {
        switch locationManager.authorizationStatus {
        case .notDetermined: return String(localized: "Not Set")
        case .restricted: return String(localized: "Restricted")
        case .denied: return String(localized: "Denied")
        case .authorizedWhenInUse: return String(localized: "When In Use")
        case .authorizedAlways: return String(localized: "Always")
        @unknown default: return String(localized: "Unknown")
        }
    }

    private var permissionStatusColor: Color {
        switch locationManager.authorizationStatus {
        case .authorizedAlways: return TE.textPrimary
        case .denied, .restricted: return TE.inkRust
        default: return TE.textMuted
        }
    }

    // MARK: - Fine-tune

    private var fineTuneCard: some View {
        BackPageCard {
            BackPagePickerRow(title: "Record points") {
                Picker("Record points", selection: Binding(
                    get: { locationManager.routeSamplingMode },
                    set: { locationManager.setRouteSamplingMode($0) }
                )) {
                    ForEach(RouteSamplingMode.allCases) { mode in
                        Text(mode.title).tag(mode)
                    }
                }
                .labelsHidden()
                // Keep "Distance + time" on one line instead of letting the row squeeze it.
                .fixedSize()
            }
            if locationManager.routeSamplingMode.usesTimeInterval {
                BackPageDivider()
                BackPagePickerRow(title: "Every") {
                    Picker("Every", selection: Binding(
                        get: { locationManager.routeSampleInterval },
                        set: { locationManager.setRouteSampleInterval($0) }
                    )) {
                        ForEach(intervalOptions, id: \.self) { seconds in
                            Text(RouteSamplingPolicy.intervalLabel(seconds)).tag(seconds)
                        }
                    }
                    .labelsHidden()
                }
            }
            if locationManager.routeSamplingMode.usesDistanceFilter {
                BackPageDivider()
                BackPagePickerRow(title: locationManager.routeSamplingMode == .distanceAndTime ? "Once moved" : "Distance filter") {
                    Picker("Distance filter", selection: Binding(
                        get: { locationManager.distanceFilter },
                        set: { locationManager.setDistanceFilter($0) }
                    )) {
                        Text("5m").tag(5.0)
                        Text("10m").tag(10.0)
                        Text("25m").tag(25.0)
                        Text("50m").tag(50.0)
                        Text("100m").tag(100.0)
                        Text("200m").tag(200.0)
                    }
                    .labelsHidden()
                }
            }
            BackPageDivider()
            BackPagePickerRow(title: "Stop after") {
                Picker("Stop after", selection: Binding(
                    get: { locationManager.stopAfterHours },
                    set: { locationManager.setStopAfterHours($0) }
                )) {
                    Text("Never").tag(0.0)
                    Text("1h").tag(1.0)
                    Text("2h").tag(2.0)
                    Text("4h").tag(4.0)
                    Text("8h").tag(8.0)
                    Text("12h").tag(12.0)
                }
                .labelsHidden()
            }
            BackPageDivider()
            BackPageToggleRow(
                title: "Live Activity",
                subtitle: "Tracking progress on the Lock Screen and Dynamic Island",
                isOn: Binding(
                    get: { locationManager.isLiveActivityEnabled },
                    set: { locationManager.setLiveActivityEnabled($0) }
                )
            )
        }
    }

    /// The standard intervals, plus a stored one that isn't among them so the picker never goes blank.
    private var intervalOptions: [TimeInterval] {
        let options = RouteSamplingPolicy.intervalOptions
        let current = locationManager.routeSampleInterval
        return options.contains(current) ? options : (options + [current]).sorted()
    }

    // MARK: - Inferred outings

    private var inferredOutingsCard: some View {
        BackPageCard {
            BackPageToggleRow(title: "Auto-generate outings", isOn: $includesInferredSessions)

            if includesInferredSessions {
                BackPageDivider()
                BackPagePickerRow(title: "Split after") {
                    Picker("Split after", selection: $inferenceGapPresetRawValue) {
                        ForEach(RecordingSessionGapPreset.allCases) { option in
                            Text(option.settingsLabel).tag(option.rawValue)
                        }
                    }
                    .labelsHidden()
                }
                BackPageDivider()
                BackPagePickerRow(title: "Ignore shorter than") {
                    Picker("Ignore shorter than", selection: $inferenceMinimumDurationPresetRawValue) {
                        ForEach(RecordingSessionMinimumDurationPreset.allCases) { option in
                            Text(option.label).tag(option.rawValue)
                        }
                    }
                    .labelsHidden()
                }
                BackPageDivider()
                BackPagePickerRow(title: "Minimum GPS points") {
                    Picker("Minimum GPS points", selection: $inferenceMinimumPointCount) {
                        ForEach(RecordingSessionMinimumPointCountPreset.allCases) { option in
                            Text(option.label).tag(option.rawValue)
                        }
                    }
                    .labelsHidden()
                }
            }
        }
    }

    // MARK: - What stays, what leaves

    private var privacyCard: some View {
        BackPageCard {
            HStack(spacing: 12) {
                dot(TE.placeInks[3])
                Text("Your journal")
                    .font(.subheadline)
                Spacer(minLength: 8)
                Text("This iPhone only")
                    .font(TE.mono(.caption))
                    .foregroundStyle(TE.textMuted)
            }
            .padding(.horizontal, 16)
            .frame(minHeight: 52)
            .accessibilityElement(children: .combine)

            BackPageDivider()
            HStack(spacing: 12) {
                dot(TE.placeInks[2])
                BackPageToggleRow(title: "Place names", subtitle: "Asks Apple Maps", isOn: $allowNetworkGeocoding)
                    .padding(.leading, -16)
            }
            .padding(.leading, 16)

            BackPageDivider()
            NavigationLink {
                WebhookSettingsView()
            } label: {
                HStack(spacing: 0) {
                    dot(sendsAnything ? TE.inkRust : TE.dialTick)
                        .padding(.leading, 16)
                    BackPageRowLabel(
                        title: Text("Webhooks & scheduled sends"),
                        subtitle: Text(sendsSummary),
                        value: Text(sendsAnything ? "On" : "Off").font(TE.mono(.caption))
                    )
                    .padding(.leading, -4)
                }
            }
            .buttonStyle(.plain)
        }
    }

    private var sendsAnything: Bool {
        webhookManager.isEnabled || dailyScheduler.isEnabled
    }

    /// Webhook delivery plus the Archive's scheduled file exports, in one line.
    private var sendsSummary: String {
        let webhook = webhookManager.isEnabled
            ? String(localized: "Webhook on")
            : String(localized: "Webhook off")
        let schedule: String
        if !dailyScheduler.isEnabled {
            schedule = String(localized: "no scheduled files")
        } else if dailyScheduler.isIntervalSchedule {
            schedule = String(localized: "files every \(dailyScheduler.intervalHours) h")
        } else {
            var components = DateComponents()
            components.hour = dailyScheduler.hour
            components.minute = dailyScheduler.minute
            let time = Calendar.current.date(from: components)?.formatted(date: .omitted, time: .shortened) ?? ""
            schedule = String(localized: "a daily file at \(time)")
        }
        var parts = [webhook, schedule]
        if dailyScheduler.isEnabled {
            if let error = dailyScheduler.lastError, !error.isEmpty {
                parts.append(String(localized: "last run failed"))
            } else if let lastRun = dailyScheduler.lastRun {
                parts.append(String(localized: "last \(lastRun.formatted(.relative(presentation: .named)))"))
            }
        }
        return parts.joined(separator: " · ")
    }

    // MARK: - Watch and appearance

    private var deviceCard: some View {
        BackPageCard {
            NavigationLink {
                WatchSyncDetailView()
            } label: {
                TimelineView(.everyMinute) { context in
                    HStack(spacing: 12) {
                        Image(systemName: "applewatch")
                            .font(.subheadline)
                            .foregroundStyle(TE.textMuted)
                            .frame(width: 20)
                            .accessibilityHidden(true)
                        BackPageRowLabel(
                            title: Text("Apple Watch"),
                            value: Text(WatchSyncSnapshot.current(lastImport: lastWatchImport).summary(now: context.date))
                                .font(TE.mono(.caption))
                        )
                        .padding(.leading, -16)
                    }
                    .padding(.leading, 16)
                }
            }
            .buttonStyle(.plain)
            .accessibilityElement(children: .combine)

            BackPageDivider()
            Menu {
                Picker("Appearance", selection: $appAppearance) {
                    ForEach(AppAppearance.allCases) { appearance in
                        Text(appearanceName(appearance)).tag(appearance.rawValue)
                    }
                }
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "circle.lefthalf.filled")
                        .font(.subheadline)
                        .foregroundStyle(TE.textMuted)
                        .frame(width: 20)
                        .accessibilityHidden(true)
                    BackPageRowLabel(
                        title: Text("Appearance"),
                        value: Text(appearanceSummary).font(TE.mono(.caption))
                    )
                    .padding(.leading, -16)
                }
                .padding(.leading, 16)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Appearance")
            .accessibilityValue(appearanceSummary)
        }
    }

    private func appearanceName(_ appearance: AppAppearance) -> String {
        switch appearance {
        case .system: String(localized: "Auto")
        case .light: String(localized: "Paper")
        case .dark: String(localized: "Night")
        }
    }

    /// "Paper · auto" while following the system, otherwise the chosen look.
    private var appearanceSummary: String {
        switch AppAppearance(rawValue: appAppearance) ?? .system {
        case .system:
            let current = colorScheme == .dark ? String(localized: "Night") : String(localized: "Paper")
            return String(localized: "\(current) · auto")
        case .light: return String(localized: "Paper")
        case .dark: return String(localized: "Night")
        }
    }

    private func dot(_ color: Color) -> some View {
        Circle()
            .fill(color)
            .frame(width: 8, height: 8)
            .accessibilityHidden(true)
    }

    private var closingNote: some View {
        VStack(spacing: 4) {
            Text("No account. No server copy of where you've been.")
            if let source = URL(string: "https://github.com/CodyBontecou/whereday") {
                Link("Open source — read every line on GitHub.", destination: source)
                    .underline()
                    .frame(minHeight: 44)
            }
        }
        .font(TE.editorial(.subheadline).italic())
        .foregroundStyle(TE.textMuted)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Apple Watch

/// What the iPhone can see of the watch: the WatchConnectivity session and the last payload it imported.
/// Points still queued on the watch aren't visible from the phone, and the phone can't ask the watch to send.
struct WatchSyncSnapshot {
    enum Pairing {
        case unsupported, connecting, notPaired, notInstalled, ready
    }

    let pairing: Pairing
    let isReachable: Bool
    let lastSync: Date?

    static func current(lastImport: Double) -> WatchSyncSnapshot {
        let lastSync = lastImport > 0 ? Date(timeIntervalSince1970: lastImport) : nil
        guard WCSession.isSupported() else {
            return WatchSyncSnapshot(pairing: .unsupported, isReachable: false, lastSync: lastSync)
        }
        let session = WCSession.default
        let pairing: Pairing
        if session.activationState != .activated {
            pairing = .connecting
        } else if !session.isPaired {
            pairing = .notPaired
        } else if !session.isWatchAppInstalled {
            pairing = .notInstalled
        } else {
            pairing = .ready
        }
        return WatchSyncSnapshot(
            pairing: pairing,
            isReachable: session.activationState == .activated && session.isReachable,
            lastSync: lastSync
        )
    }

    /// The Listening row's trailing value: "Synced 2 min ago".
    func summary(now: Date) -> String {
        switch pairing {
        case .unsupported: return String(localized: "Not available")
        case .notPaired: return String(localized: "No watch paired")
        case .notInstalled: return String(localized: "Whereday not installed")
        case .connecting, .ready: return lastSyncText(now: now)
        }
    }

    func lastSyncText(now: Date) -> String {
        guard let lastSync else { return String(localized: "Not synced yet") }
        if now.timeIntervalSince(lastSync) < 60 { return String(localized: "Synced just now") }
        let ago = lastSync.formatted(.relative(presentation: .numeric, unitsStyle: .abbreviated))
        return String(localized: "Synced \(ago)")
    }

    var pairedText: String {
        switch pairing {
        case .unsupported: String(localized: "Not available on this iPhone")
        case .connecting: String(localized: "Checking…")
        case .notPaired: String(localized: "No")
        case .notInstalled, .ready: String(localized: "Yes")
        }
    }

    var installedText: String {
        switch pairing {
        case .ready: String(localized: "Yes")
        case .notInstalled: String(localized: "No")
        default: "—"
        }
    }

    var reachableText: String {
        guard pairing == .ready else { return "—" }
        return isReachable ? String(localized: "Yes") : String(localized: "No")
    }
}

/// Watch route transfers and acknowledged, bidirectional recording control.
struct WatchSyncDetailView: View {
    @ObservedObject private var recordingControl = SharedRecordingControl.shared
    @AppStorage(WatchSyncStatus.lastImportDateKey) private var lastWatchImport: Double = 0
    @State private var waitingPointCount: Int?
    @State private var isSyncing = false
    @State private var syncMessage: String?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 15)) { context in
            let snapshot = WatchSyncSnapshot.current(lastImport: lastWatchImport)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    BackPageHeader(caption: "SYNC · FROM YOUR WRIST", title: "Apple Watch")
                        .padding(.horizontal, 24)
                        .padding(.top, 8)

                    BackPageLabel("STATUS")
                    BackPageCard {
                        statusRow("Paired", value: snapshot.pairedText)
                        BackPageDivider()
                        statusRow("Whereday installed", value: snapshot.installedText,
                                  valueColor: snapshot.pairing == .notInstalled ? TE.inkRust : TE.textMuted)
                        BackPageDivider()
                        statusRow("In range now", value: snapshot.reachableText)
                        BackPageDivider()
                        statusRow("Recording controls", value: recordingControl.statusDescription)
                    }
                    .padding(.horizontal, 16)

                    BackPageLabel("LAST SYNC")
                    BackPageCard {
                        BackPageRowLabel(
                            title: Text("Last points received"),
                            subtitle: snapshot.lastSync == nil ? nil : Text(snapshot.lastSyncText(now: context.date)),
                            value: Text(snapshot.lastSync.map { $0.formatted(date: .abbreviated, time: .shortened) }
                                        ?? String(localized: "Not synced yet"))
                                .font(TE.mono(.caption)),
                            accessory: .none
                        )
                        .accessibilityElement(children: .combine)
                        BackPageDivider()
                        statusRow(
                            "Points waiting on the watch",
                            value: waitingPointCount.map { $0.formatted() } ?? String(localized: "—")
                        )
                    }
                    .padding(.horizontal, 16)

                    if snapshot.pairing == .ready || snapshot.pairing == .connecting {
                        Button(action: syncNow) {
                            HStack(spacing: 8) {
                                if isSyncing { ProgressView().controlSize(.small) }
                                Text(isSyncing ? "Asking the watch…" : "Sync now")
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(TEActionButtonStyle())
                        .disabled(isSyncing)
                        .padding(.horizontal, 16)
                        .padding(.top, 16)

                        if let syncMessage {
                            Text(syncMessage)
                                .font(TE.mono(.caption))
                                .foregroundStyle(TE.textMuted)
                                .padding(.horizontal, 24)
                                .padding(.top, 8)
                        }
                    }

                    BackPageFootnote(footnote(for: snapshot))
                    BackPageFootnote("Start or stop on either device to update both. Offline changes wait for a connection; location permission and recording access are required on each device. Both devices can collect points for the shared outing. GPS recording uses extra battery; battery duration has not been measured.")
                }
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
        }
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
    }

    private func syncNow() {
        recordingControl.flush()
        isSyncing = true
        syncMessage = nil
        WatchLocationSyncReceiver.requestWatchSync { count in
            isSyncing = false
            waitingPointCount = count
            if let count {
                syncMessage = count == 0
                    ? String(localized: "Nothing was waiting.")
                    : String(localized: "Sending \(count) points from the watch.")
            } else {
                syncMessage = String(localized: "The watch isn't in range. Open Whereday on it and try again.")
            }
        }
    }

    private func footnote(for snapshot: WatchSyncSnapshot) -> LocalizedStringKey {
        switch snapshot.pairing {
        case .notInstalled:
            "Install Whereday from the Watch app on this iPhone to record from your wrist."
        case .notPaired, .unsupported:
            "Pair an Apple Watch to record outings from your wrist."
        case .connecting, .ready:
            "The watch sends what it recorded on its own whenever it's near this iPhone. Sync now asks it to send right away; the watch has to be in range."
        }
    }

    private func statusRow(_ title: LocalizedStringKey, value: String, valueColor: Color = TE.textMuted) -> some View {
        BackPageRowLabel(
            title: Text(title),
            value: Text(value).font(TE.mono(.caption)),
            valueColor: valueColor,
            accessory: .none
        )
        .accessibilityElement(children: .combine)
    }
}
