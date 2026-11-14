import SwiftUI
import SwiftData
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Bindable var viewModel: LocationViewModel
    @ObservedObject private var locationManager: LocationManager
    @ObservedObject private var storeManager = StoreManager.shared
    @State private var showingMyselfClaim = false
    @State private var showingPaywall = false
    @State private var showingClearConfirmation = false
    @State private var showingImportPicker = false
    @State private var importFileSelection: ImportFileSelection?
    @State private var importErrorMessage: String?
    @State private var showingImportError = false

    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false
    @AppStorage("appAppearance") private var appAppearance = AppAppearance.system.rawValue
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    @AppStorage("allowNetworkGeocoding") private var allowNetworkGeocoding = true
    @AppStorage("showOutliers") private var showOutliers = false
    @AppStorage(LocationViewModel.showVisitSuggestionsKey) private var showVisitSuggestions = false
    @AppStorage(RecordingSessionInferenceConfiguration.includesInferredSessionsKey)
    private var includesInferredSessions = RecordingSessionInferenceConfiguration.defaultIncludesInferredSessions
    @AppStorage(RecordingSessionInferenceConfiguration.gapPresetKey)
    private var inferenceGapPresetRawValue = RecordingSessionInferenceConfiguration.defaultGapPreset.rawValue
    @AppStorage(RecordingSessionInferenceConfiguration.minimumDurationPresetKey)
    private var inferenceMinimumDurationPresetRawValue = RecordingSessionInferenceConfiguration.defaultMinimumDurationPreset.rawValue
    @AppStorage(RecordingSessionInferenceConfiguration.minimumPointCountKey)
    private var inferenceMinimumPointCount = RecordingSessionInferenceConfiguration.defaultMinimumPointCountPreset.rawValue

    init(viewModel: LocationViewModel) {
        _viewModel = Bindable(wrappedValue: viewModel)
        _locationManager = ObservedObject(initialValue: viewModel.locationManager)
    }

    var body: some View {
        NavigationStack {
            ZStack {
                FieldNotesBackground()
                    .overlay(TE.surface.opacity(0.45))
                    .allowsHitTesting(false)

                ScrollView {
                    VStack(spacing: 0) {
                        purchaseSection
                        myselfClaimSection
                        appearanceSection
                        trackingSection
                        inferredOutingsSection
                        liveActivitySection
                        unitsSection
                        mapDisplaySection
                        savedLocationsSection
                        importSection
                        dataSection
                        onboardingSection
                        supportSection
                        aboutSection
                    }
                    .padding(.bottom, dynamicTypeSize.isAccessibilitySize ? 170 : 96)
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .alert("Clear All Data?", isPresented: $showingClearConfirmation) {
                Button("Cancel", role: .cancel) {}
                Button("Clear", role: .destructive) { viewModel.clearAllData() }
            } message: {
                Text("This will permanently delete all visits, location points, and manual country stays. This action cannot be undone.")
            }
            .alert("Import Failed", isPresented: $showingImportError) {
                Button("OK", role: .cancel) {}
            } message: {
                if let message = importErrorMessage {
                    Text(message)
                }
            }
            .fileImporter(
                isPresented: $showingImportPicker,
                allowedContentTypes: [.json, .commaSeparatedText, UTType(filenameExtension: "md") ?? .plainText],
                allowsMultipleSelection: false
            ) { result in
                handleImportResult(result)
            }
            .onChange(of: usesMetricDistanceUnits) { _, _ in
                locationManager.refreshDistanceUnitPreference()
            }
            .sheet(item: $importFileSelection) { selection in
                ImportPreviewView(url: selection.url, viewModel: viewModel)
            }
            .sheet(isPresented: $showingMyselfClaim) { MyselfClaimView(app: "iso.me") }
            .sheet(isPresented: $showingPaywall) {
                PaywallView(storeManager: storeManager, context: .settings)
            }
        }
    }

    // MARK: - Purchase Section

    private var purchaseSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "DATA EXPORT")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    if storeManager.isPurchased {
                        TERow {
                            settingsButton(
                                "PURCHASES & FAMILY",
                                icon: storeManager.isFamilyUnlocked ? "person.3.fill" : "checkmark.seal.fill"
                            ) {
                                showingPaywall = true
                            }
                        }

                        TERow(showDivider: false) {
                            settingsValueRow(
                                title: "CURRENT PLAN",
                                value: currentPurchasePlanName,
                                valueColor: TE.success
                            )
                        }
                    } else {
                        TERow {
                            settingsButton(storeManager.hasLegacyTrackingAccess ? "UNLOCK EXPORT" : "CHOOSE A PLAN", icon: "lock.open.fill") {
                                showingPaywall = true
                            }
                        }

                        TERow(showDivider: false) {
                            settingsButton("RESTORE PURCHASE", icon: "arrow.clockwise") {
                                Task { await storeManager.restorePurchases() }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            if storeManager.isPurchased {
                TESectionFooter(text: "Manage your plan or Apple Family Sharing here.")
            } else {
                TESectionFooter(text: storeManager.hasLegacyTrackingAccess
                    ? "Your existing free tracking access is preserved. Choose a plan to unlock export."
                    : "Choose a plan to track. Your existing records remain available to view and export.")
            }
        }
    }

    private var myselfClaimSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "MYSELF.MD")
            TECard(paperOpacity: 0.97) {
                TERow(showDivider: false) {
                    settingsButton("CLAIM MYSELF.MD ACCESS", icon: "gift.fill") { showingMyselfClaim = true }
                }
            }
            .padding(.horizontal, 16)
            TESectionFooter(text: "Existing purchasers receive complimentary lifetime access. Verify your purchase, then sign in to myself.md.")
        }
    }

    private var currentPurchasePlanName: String {
        (storeManager.activePurchaseOption?.title ?? String(localized: "No active plan")).uppercased()
    }

    private var appearanceSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "APPEARANCE")
            TECard(paperOpacity: 0.97) {
                TERow(showDivider: false) {
                    settingsPickerRow(title: "THEME") {
                        Picker("Theme", selection: $appAppearance) {
                            ForEach(AppAppearance.allCases) { appearance in
                                Text(appearance.title).tag(appearance.rawValue)
                            }
                        }
                        .labelsHidden()
                        .tint(TE.accent)
                    }
                }
            }
            .padding(.horizontal, 16)
            TESectionFooter(text: "System follows your device’s appearance.")
        }
    }

    // MARK: - Tracking Section

    private var trackingSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "LOCATION TRACKING")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow {
                        settingsToggle(
                            "TRACKING",
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
                    }

                    TERow {
                        settingsValueRow(
                            title: "PERMISSION",
                            value: permissionStatusText.uppercased(),
                            valueColor: permissionStatusColor
                        )
                    }

                    if !locationManager.hasAlwaysPermission {
                        TERow {
                            settingsButton("OPEN SETTINGS", icon: "arrow.up.right") {
                                if let url = URL(string: UIApplication.openSettingsURLString) {
                                    UIApplication.shared.open(url)
                                }
                            }
                        }
                    }

                    TERow {
                        settingsPickerRow(title: "DISTANCE FILTER") {
                            Picker("", selection: Binding(
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
                            .tint(TE.accent)
                        }
                    }

                    TERow {
                        settingsPickerRow(title: "STOP AFTER") {
                            Picker("", selection: Binding(
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
                            .tint(TE.accent)
                        }
                    }

                    TERow(showDivider: false) {
                        settingsToggle("LOCATION NAMES", isOn: $allowNetworkGeocoding)
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Tracking records visits, significant changes, and a continuous high-accuracy path. Distance filter controls how often points are saved while moving. Stop After auto-stops as a safety net.")
        }
    }

    // MARK: - Inferred Outings Section

    private var inferredOutingsSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "INFERRED OUTINGS")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow(showDivider: includesInferredSessions) {
                        settingsToggle("AUTO-GENERATE", isOn: $includesInferredSessions)
                    }

                    if includesInferredSessions {
                        TERow {
                            settingsPickerRow(title: "SPLIT AFTER") {
                                Picker("", selection: $inferenceGapPresetRawValue) {
                                    ForEach(RecordingSessionGapPreset.allCases) { option in
                                        Text(option.settingsLabel).tag(option.rawValue)
                                    }
                                }
                                .labelsHidden()
                                .tint(TE.accent)
                            }
                        }

                        TERow {
                            settingsPickerRow(title: "IGNORE SHORTER THAN") {
                                Picker("", selection: $inferenceMinimumDurationPresetRawValue) {
                                    ForEach(RecordingSessionMinimumDurationPreset.allCases) { option in
                                        Text(option.label).tag(option.rawValue)
                                    }
                                }
                                .labelsHidden()
                                .tint(TE.accent)
                            }
                        }

                        TERow(showDivider: false) {
                            settingsPickerRow(title: "MIN GPS POINTS") {
                                Picker("", selection: $inferenceMinimumPointCount) {
                                    ForEach(RecordingSessionMinimumPointCountPreset.allCases) { option in
                                        Text(option.label).tag(option.rawValue)
                                    }
                                }
                                .labelsHidden()
                                .tint(TE.accent)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "When exact start/stop sessions don't exist, Whereday can infer outings from saved GPS points. These settings only affect auto-generated inferred outings; recorded sessions stay intact.")
        }
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
        case .authorizedAlways: return TE.accent
        case .authorizedWhenInUse: return TE.accent.opacity(0.7)
        case .denied, .restricted: return TE.danger
        default: return TE.textMuted
        }
    }

    // MARK: - Live Activity Section

    private var liveActivitySection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "LIVE ACTIVITY MONITOR")

            TECard(paperOpacity: 0.97) {
                TERow(showDivider: false) {
                    settingsToggle(
                        "ENABLED",
                        isOn: Binding(
                            get: { locationManager.isLiveActivityEnabled },
                            set: { locationManager.setLiveActivityEnabled($0) }
                        )
                    )
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Show tracking progress on the Lock Screen and Dynamic Island. Your choice is saved for future tracking sessions.")
        }
    }

    // MARK: - Units Section

    private var unitsSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "UNITS")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    if dynamicTypeSize.isAccessibilitySize {
                        unitButton("METRIC", isSelected: usesMetricDistanceUnits) {
                            usesMetricDistanceUnits = true
                        }
                        Rectangle()
                            .fill(TE.border)
                            .frame(height: 1)
                        unitButton("US STANDARD", isSelected: !usesMetricDistanceUnits) {
                            usesMetricDistanceUnits = false
                        }
                    } else {
                        HStack(spacing: 0) {
                            unitButton("METRIC", isSelected: usesMetricDistanceUnits) {
                                usesMetricDistanceUnits = true
                            }
                            Rectangle()
                                .fill(TE.border)
                                .frame(width: 1)
                            unitButton("US STANDARD", isSelected: !usesMetricDistanceUnits) {
                                usesMetricDistanceUnits = false
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Distance values shown throughout the app, widgets, and Live Activity.")
        }
    }

    private func unitButton(_ title: LocalizedStringKey, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(TE.mono(.caption2, weight: isSelected ? .bold : .medium))
                .tracking(dynamicTypeSize.isAccessibilitySize ? 0.5 : 1.5)
                .foregroundStyle(isSelected ? TE.accent : TE.textMuted)
                .multilineTextAlignment(.center)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 8)
                .padding(.vertical, dynamicTypeSize.isAccessibilitySize ? 14 : 0)
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(isSelected ? TE.accent.opacity(0.08) : Color.clear)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Map Display Section

    private var mapDisplaySection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "MAP DISPLAY")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow {
                        settingsToggle("VISIT SUGGESTIONS", isOn: $showVisitSuggestions)
                    }

                    TERow(showDivider: false) {
                        settingsToggle("SHOW GPS GLITCHES", isOn: $showOutliers)
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Visit suggestions show a compact Save Place prompt on the map when Whereday finds a nearby place. GPS glitches are hidden by default; turn on to inspect raw data.")
        }
    }

    // MARK: - Import Section

    private var savedLocationsSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "SAVED LOCATIONS")
            TECard(paperOpacity: 0.97) {
                TERow(showDivider: false) {
                    NavigationLink {
                        SavedLocationsView(viewModel: viewModel)
                    } label: {
                        HStack {
                            Image(systemName: "mappin.and.ellipse").foregroundStyle(TE.accent)
                            Text("MANAGE LOCATIONS")
                                .font(TE.mono(.subheadline, weight: .semibold))
                                .foregroundStyle(TE.textPrimary)
                            Spacer(minLength: 8)
                            Image(systemName: "chevron.right").foregroundStyle(TE.textMuted)
                        }
                        .frame(minHeight: 44)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Manage Saved Locations")
                }
            }
            .padding(.horizontal, 16)
            TESectionFooter(text: "Add, edit, or import reusable places for future visit suggestions.")
        }
    }

    private var importSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "DATA IMPORT")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow(showDivider: false) {
                        settingsButton("IMPORT FILE", icon: "square.and.arrow.down") {
                            showingImportPicker = true
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Preview and import one Google Timeline JSON or Whereday JSON, CSV, or Markdown file. Duplicate visits and points are skipped.")
        }
    }

    // MARK: - Data Section

    private var dataSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "DATA")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow {
                        HStack {
                            Text("VISITS")
                                .font(TE.mono(.caption, weight: .medium))
                                .tracking(1)
                                .foregroundStyle(TE.textPrimary)
                            Spacer()
                            Text("\(viewModel.allVisits.count)")
                                .font(TE.mono(.caption2, weight: .medium))
                                .foregroundStyle(TE.textMuted)
                        }
                    }

                    TERow {
                        HStack {
                            Text("POINTS")
                                .font(TE.mono(.caption, weight: .medium))
                                .tracking(1)
                                .foregroundStyle(TE.textPrimary)
                            Spacer()
                            Text("\(viewModel.totalLocationPointCount)")
                                .font(TE.mono(.caption2, weight: .medium))
                                .foregroundStyle(TE.textMuted)
                        }
                    }

                    TERow(showDivider: false) {
                        settingsButton("CLEAR ALL DATA", icon: "trash", color: TE.danger) {
                            showingClearConfirmation = true
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    // MARK: - Onboarding Section

    private var onboardingSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "ONBOARDING")

            TECard(paperOpacity: 0.97) {
                TERow(showDivider: false) {
                    settingsButton("REPLAY ONBOARDING", icon: "sparkles") {
                        hasCompletedOnboarding = false
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    // MARK: - Support Section

    private var supportSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "SUPPORT")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow {
                        NavigationLink {
                            LogViewerView()
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "doc.text.magnifyingglass")
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(TE.accent)
                                Text("VIEW LOGS")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.accent)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(TE.accent.opacity(0.5))
                            }
                        }
                    }

                    if let discordInviteURL = IsoMeURLs.discordInvite {
                        TERow(showDivider: false) {
                            Link(destination: discordInviteURL) {
                                HStack(spacing: 8) {
                                    Image(systemName: "bubble.left.and.bubble.right.fill")
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(TE.accent)
                                    Text("JOIN DISCORD")
                                        .font(TE.mono(.caption, weight: .medium))
                                        .tracking(1)
                                        .foregroundStyle(TE.accent)
                                    Spacer()
                                    Image(systemName: "arrow.up.right")
                                        .font(.caption2.weight(.bold))
                                        .foregroundStyle(TE.accent.opacity(0.5))
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "View app activity logs or chat with the community.")
        }
    }

    // MARK: - About Section

    private var aboutSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "ABOUT")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow {
                        HStack {
                            Text("VERSION")
                                .font(TE.mono(.caption, weight: .medium))
                                .tracking(1)
                                .foregroundStyle(TE.textPrimary)
                            Spacer()
                            Text(AppInfo.versionDisplay)
                                .font(TE.mono(.caption2, weight: .medium))
                                .foregroundStyle(TE.textMuted)
                        }
                    }

                    TERow {
                        HStack {
                            Text("STORAGE")
                                .font(TE.mono(.caption, weight: .medium))
                                .tracking(1)
                                .foregroundStyle(TE.textPrimary)
                            Spacer()
                            Text("ON-DEVICE ONLY")
                                .font(TE.mono(.caption2, weight: .medium))
                                .tracking(1)
                                .foregroundStyle(TE.textMuted)
                        }
                    }

                    TERow {
                        settingsButton("SEND FEEDBACK", icon: "envelope") {
                            sendFeedbackEmail()
                        }
                    }

                    if let privacyPolicyURL = IsoMeURLs.privacyPolicy {
                        TERow {
                            Link(destination: privacyPolicyURL) {
                                HStack(spacing: 8) {
                                    Image(systemName: "hand.raised")
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(TE.accent)
                                    Text("PRIVACY POLICY")
                                        .font(TE.mono(.caption, weight: .medium))
                                        .tracking(1)
                                        .foregroundStyle(TE.accent)
                                    Spacer()
                                    Image(systemName: "arrow.up.right")
                                        .font(.caption2.weight(.bold))
                                        .foregroundStyle(TE.accent.opacity(0.5))
                                }
                            }
                        }
                    }

                    if let termsOfServiceURL = IsoMeURLs.termsOfService {
                        TERow(showDivider: false) {
                            Link(destination: termsOfServiceURL) {
                                HStack(spacing: 8) {
                                    Image(systemName: "doc.text")
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(TE.accent)
                                    Text("TERMS OF SERVICE")
                                        .font(TE.mono(.caption, weight: .medium))
                                        .tracking(1)
                                        .foregroundStyle(TE.accent)
                                    Spacer()
                                    Image(systemName: "arrow.up.right")
                                        .font(.caption2.weight(.bold))
                                        .foregroundStyle(TE.accent.opacity(0.5))
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "All data stored locally. Never uploaded to any server.")
        }
    }

    // MARK: - Feedback Email

    private func sendFeedbackEmail() {
        let subject = String(localized: "Whereday Feedback")
        let footer = """

        ---
        App: Whereday \(AppInfo.versionDisplay)
        Platform: \(AppInfo.platformDisplay)
        Device: \(AppInfo.deviceModel)
        """
        let encodedSubject = subject.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? subject
        let encodedBody = footer.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? footer
        if let url = URL(string: "mailto:cody@isolated.tech?subject=\(encodedSubject)&body=\(encodedBody)") {
            UIApplication.shared.open(url)
        }
    }

    // MARK: - Reusable Row Components

    private func settingsToggle(_ label: LocalizedStringKey, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            Text(label)
                .font(TE.mono(.caption, weight: .medium))
                .tracking(dynamicTypeSize.isAccessibilitySize ? 0.5 : 1)
                .foregroundStyle(TE.textPrimary)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
        }
        .toggleStyle(TEToggleStyle())
    }

    private func settingsButton(_ label: LocalizedStringKey, icon: String, color: Color = TE.accent, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Image(systemName: icon)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(color)
                Text(label)
                    .font(TE.mono(.caption, weight: .medium))
                    .tracking(dynamicTypeSize.isAccessibilitySize ? 0.5 : 1)
                    .foregroundStyle(color)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 12)
                Image(systemName: "arrow.right")
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(color.opacity(0.5))
            }
        }
        .buttonStyle(.plain)
    }

    private func settingsValueRow(title: LocalizedStringKey, value: String, valueColor: Color) -> some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 8) {
                    settingsRowLabel(title)
                    Text(verbatim: value)
                        .font(TE.mono(.caption2, weight: .medium))
                        .tracking(0.5)
                        .foregroundStyle(valueColor)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack {
                    settingsRowLabel(title)
                    Spacer()
                    Text(verbatim: value)
                        .font(TE.mono(.caption2, weight: .medium))
                        .tracking(1)
                        .foregroundStyle(valueColor)
                }
            }
        }
    }

    private func settingsPickerRow<PickerContent: View>(
        title: LocalizedStringKey,
        @ViewBuilder picker: () -> PickerContent
    ) -> some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 8) {
                    settingsRowLabel(title)
                    picker()
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            } else {
                HStack {
                    settingsRowLabel(title)
                    Spacer()
                    picker()
                }
            }
        }
    }

    private func settingsRowLabel(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .font(TE.mono(.caption, weight: .medium))
            .tracking(dynamicTypeSize.isAccessibilitySize ? 0.5 : 1)
            .foregroundStyle(TE.textPrimary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func handleImportResult(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard let url = urls.first else { return }
            importFileSelection = ImportFileSelection(url: url)
        case .failure(let error):
            importErrorMessage = error.localizedDescription
            showingImportError = true
        }
    }

}

#Preview {
    SettingsView(viewModel: LocationViewModel(
        modelContext: try! ModelContainer(for: Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self).mainContext,
        locationManager: LocationManager()
    ))
}

// MARK: - Folder Picker

struct FolderPicker: UIViewControllerRepresentable {
    let onFolderSelected: (URL?) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onFolderSelected: onFolderSelected)
    }

    class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onFolderSelected: (URL?) -> Void

        init(onFolderSelected: @escaping (URL?) -> Void) {
            self.onFolderSelected = onFolderSelected
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onFolderSelected(urls.first)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onFolderSelected(nil)
        }
    }
}
