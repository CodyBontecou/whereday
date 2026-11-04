import SwiftUI

/// How Whereday listens: recording modes and what stays on the phone, in plain words.
/// The modes are presets over the distance filter and auto-stop LocationManager already applies.
struct ListeningView: View {
    let viewModel: LocationViewModel
    @ObservedObject private var locationManager: LocationManager
    @ObservedObject private var webhookManager = WebhookManager.shared

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

    private var currentDetail: RecordingDetail {
        RecordingDetail(distanceFilter: locationManager.distanceFilter)
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
                BackPageFootnote("Distance filter controls how often points are saved while moving. Stop After auto-stops as a safety net.")

                BackPageLabel("OUTINGS FROM SAVED POINTS")
                inferredOutingsCard
                    .padding(.horizontal, 16)
                BackPageFootnote("When exact start/stop sessions don't exist, Whereday can infer outings from saved GPS points. These settings only affect auto-generated inferred outings; recorded sessions stay intact.")

                BackPageLabel("WHAT STAYS · WHAT LEAVES")
                privacyCard
                    .padding(.horizontal, 16)

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
                    Text(detail.title)
                        .font(.body.weight(.semibold))
                    Text(detail.summary)
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
            BackPagePickerRow(title: "Distance filter") {
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
                    dot(webhookManager.isEnabled ? TE.inkRust : TE.dialTick)
                        .padding(.leading, 16)
                    BackPageRowLabel(
                        title: Text("Webhook delivery"),
                        subtitle: Text("Sends points to a server you set up"),
                        value: Text(webhookManager.isEnabled ? "On" : "Off").font(TE.mono(.caption))
                    )
                    .padding(.leading, -4)
                }
            }
            .buttonStyle(.plain)
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
