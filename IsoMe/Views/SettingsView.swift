import SwiftUI
import SwiftData
import UniformTypeIdentifiers

/// The back pages: plan, recording, look, data and help. Presented modally from the Archive tab.
struct SettingsView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dismiss) private var dismiss
    /// True when Settings is a tab of the iPad rail rather than a sheet over Archive: no back capsule then.
    @Environment(\.isPresentedAsRailTab) private var isPresentedAsRailTab
    @Bindable var viewModel: LocationViewModel
    @ObservedObject private var locationManager: LocationManager
    @ObservedObject private var storeManager = StoreManager.shared
    @ObservedObject private var webhookManager = WebhookManager.shared
    @State private var showingMyselfClaim = false
    @State private var showingPaywall = false
    @State private var showingClearConfirmation = false
    @State private var exportAfterClearSheet = false
    @State private var showingReleaseNotes = false
    @State private var showingImportPicker = false
    @State private var importFileSelection: ImportFileSelection?
    @State private var importErrorMessage: String?
    @State private var showingImportError = false
    @State private var reopenImportPicker = false
    @State private var exportErrorMessage: String?
    @State private var selectedPane: SettingsPane = .plan

    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false
    @AppStorage("discordPromoDismissed") private var discordPromoDismissed = false
    @AppStorage("appAppearance") private var appAppearance = AppAppearance.system.rawValue
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    @AppStorage("allowNetworkGeocoding") private var allowNetworkGeocoding = true
    @AppStorage("showOutliers") private var showOutliers = false
    @AppStorage(LocationViewModel.showVisitSuggestionsKey) private var showVisitSuggestions = false

    init(viewModel: LocationViewModel) {
        _viewModel = Bindable(wrappedValue: viewModel)
        _locationManager = ObservedObject(initialValue: viewModel.locationManager)
    }

    private var usesTwoPanes: Bool {
        horizontalSizeClass == .regular && !dynamicTypeSize.isAccessibilitySize
    }

    var body: some View {
        Group {
            if usesTwoPanes {
                twoPaneLayout
            } else {
                NavigationStack {
                    singleColumnLayout
                        .navigationTitle("Settings")
                        .toolbar(.hidden, for: .navigationBar)
                }
            }
        }
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .tint(TE.textPrimary)
        .alert("Import Failed", isPresented: $showingImportError) {
            Button("OK", role: .cancel) {}
        } message: {
            if let message = importErrorMessage {
                Text(message)
            }
        }
        .alert("Export Failed", isPresented: Binding(
            get: { exportErrorMessage != nil },
            set: { if !$0 { exportErrorMessage = nil } }
        )) {
            Button("OK", role: .cancel) {}
        } message: {
            if let exportErrorMessage {
                Text(exportErrorMessage)
            }
        }
        .fileImporter(
            isPresented: $showingImportPicker,
            allowedContentTypes: [.json, .commaSeparatedText, UTType(filenameExtension: "md") ?? .plainText, .folder],
            allowsMultipleSelection: true
        ) { result in
            handleImportResult(result)
        }
        .onChange(of: usesMetricDistanceUnits) { _, _ in
            locationManager.refreshDistanceUnitPreference()
        }
        .sheet(item: $importFileSelection, onDismiss: {
            if reopenImportPicker {
                reopenImportPicker = false
                showingImportPicker = true
            }
        }) { selection in
            ImportPreviewView(urls: selection.urls, viewModel: viewModel) {
                reopenImportPicker = true
            }
        }
        .sheet(isPresented: $showingMyselfClaim) { MyselfClaimView(app: "iso.me") }
        .sheet(isPresented: $showingPaywall) {
            PaywallView(storeManager: storeManager, context: .settings)
        }
        .sheet(isPresented: $showingReleaseNotes) {
            if let latest = IsoMeReleaseNotes.notes.first {
                WhatsNewSheet(release: latest)
            }
        }
        .sheet(isPresented: $showingClearConfirmation, onDismiss: {
            if exportAfterClearSheet {
                exportAfterClearSheet = false
                exportEverything()
            }
        }) {
            ClearDataSheet(
                visitCount: viewModel.allVisits.count,
                pointCount: viewModel.totalLocationPointCount,
                earliestDate: viewModel.allVisits.map(\.arrivedAt).min(),
                onExportFirst: { exportAfterClearSheet = true },
                onErase: { viewModel.clearAllData() }
            )
        }
    }

    /// The pre-erase backup includes every model Clear All Data removes. Archive
    /// exports intentionally remain independent of this lossless restore format.
    private func exportEverything() {
        guard storeManager.canExport else {
            showingPaywall = true
            return
        }
        do {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
            let files = try viewModel.exportBackup(to: directory)
            let activity = UIActivityViewController(activityItems: files, applicationActivities: nil)
            activity.completionWithItemsHandler = { _, completed, _, _ in
                Task { @MainActor in
                    try? FileManager.default.removeItem(at: directory)
                    guard completed else { return }
                    AppReviewPromptCoordinator.shared.recordSuccessfulFileExport()
                    LogManager.shared.info("Exported complete backup before clearing", category: .export)
                    showingClearConfirmation = true
                }
            }
            ExportService.presentShareSheet(activity, from: nil)
        } catch {
            LogManager.shared.error("Backup failed · \(error.localizedDescription)", category: .export)
            exportErrorMessage = error.localizedDescription
        }
    }

    // MARK: - Layouts

    private var singleColumnLayout: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                communityBanner
                ForEach(SettingsSection.allCases) { section in
                    BackPageLabel(section.label)
                    sectionCard(section)
                        .padding(.horizontal, 16)
                    sectionFooter(section)
                }
                legalFooter
            }
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
    }

    /// On a wide display the seven sections stay on the left and the chosen one opens on the right.
    /// The right pane has its own navigation, so deeper pages push there instead of over both panes.
    private var twoPaneLayout: some View {
        HStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    communityBanner
                    VStack(spacing: 2) {
                        ForEach(SettingsPane.allCases) { pane in
                            paneNavigationRow(pane)
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 18)
                    legalFooter
                }
                .padding(.bottom, 32)
            }
            .scrollIndicators(.hidden)
            .frame(maxWidth: .infinity)

            Rectangle()
                .fill(TE.dialTrack)
                .frame(width: 1)
                .ignoresSafeArea()

            NavigationStack {
                paneDetail(selectedPane)
                    .toolbar(.hidden, for: .navigationBar)
            }
            .id(selectedPane)
            .frame(maxWidth: .infinity)
        }
    }

    private func paneNavigationRow(_ pane: SettingsPane) -> some View {
        let isSelected = pane == selectedPane
        return Button { selectedPane = pane } label: {
            HStack(spacing: 12) {
                Text(pane.title)
                    .font(.subheadline)
                Spacer(minLength: 8)
                paneSummary(pane)
                    .font(TE.mono(.caption))
                    .foregroundStyle(TE.textMuted)
                    .lineLimit(1)
            }
            .padding(.horizontal, 14)
            .frame(minHeight: 50)
            .background(RoundedRectangle(cornerRadius: 14).fill(isSelected ? TE.card : Color.clear))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .strokeBorder(isSelected ? TE.placeInks[1] : Color.clear, lineWidth: 1.5)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    @ViewBuilder
    private func paneSummary(_ pane: SettingsPane) -> some View {
        switch pane {
        case .plan:
            Text(verbatim: storeManager.activePurchaseOption?.title ?? String(localized: "No plan"))
        case .recording:
            listeningSummary
        case .look:
            Text(currentAppearance.shortTitle)
        case .importFile:
            Text(verbatim: viewModel.totalLocationPointCount.formatted())
        case .webhook:
            Text(webhookManager.isEnabled ? "On" : "Off")
        case .shortcuts:
            EmptyView()
        case .help:
            Text(verbatim: AppInfo.version)
        }
    }

    @ViewBuilder
    private func paneDetail(_ pane: SettingsPane) -> some View {
        switch pane {
        case .webhook:
            WebhookSummaryPane()
        case .shortcuts:
            ShortcutsGuideView()
        default:
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text(pane.label)
                        .font(TE.mono(.caption2))
                        .tracking(1.5)
                        .foregroundStyle(TE.textMuted)
                    Text(pane.title)
                        .font(TE.editorial(.title))
                        .padding(.top, 2)
                        .padding(.bottom, 12)
                        .accessibilityAddTraits(.isHeader)
                    switch pane {
                    case .plan:
                        planCard
                        sectionFooter(.plan)
                    case .recording:
                        recordingCard
                    case .look:
                        lookCard
                    case .importFile:
                        importCard
                    default:
                        helpCard
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 26)
                .padding(.bottom, 32)
            }
            .scrollIndicators(.hidden)
            .background(TE.surface.ignoresSafeArea())
        }
    }

    /// The wide layout's "Import a file" page: what's on this iPhone, the importer, and clearing it.
    private var importCard: some View {
        BackPageCard {
            BackPageCountGrid(counts: [
                (viewModel.allVisits.count, "VISITS"),
                (viewModel.totalLocationPointCount, "POINTS")
            ])
            .padding(16)

            BackPageDivider()
            importRow

            BackPageDivider()
            clearDataRow
        }
    }

    private var importRow: some View {
        Button { showingImportPicker = true } label: {
            BackPageRowLabel(
                title: Text("Import files or a folder"),
                subtitle: Text("Select import files, or one unzipped Google folder. Make the folder available offline in Files first. ZIP files are not supported."),
                minHeight: 64
            )
        }
        .buttonStyle(.plain)
    }

    private var clearDataRow: some View {
        Button { showingClearConfirmation = true } label: {
            BackPageRowLabel(title: Text("Clear all data…"), tint: TE.inkRust, accessory: .none)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 14) {
            if !isPresentedAsRailTab {
                BackPageBackCapsule(title: "Archive") { dismiss() }
            }
            BackPageHeader(caption: "THE BACK PAGES", title: "Settings")
                .padding(.horizontal, 4)
        }
        .padding(.horizontal, 20)
        .padding(.top, 12)
    }

    @ViewBuilder
    private var communityBanner: some View {
        if !discordPromoDismissed, IsoMeURLs.discordInvite != nil {
            DiscordPromoBanner { discordPromoDismissed = true }
                .padding(.horizontal, 16)
                .padding(.top, 18)
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private func sectionCard(_ section: SettingsSection) -> some View {
        switch section {
        case .plan: planCard
        case .recording: recordingCard
        case .look: lookCard
        case .data: dataCard
        case .help: helpCard
        }
    }

    @ViewBuilder
    private func sectionFooter(_ section: SettingsSection) -> some View {
        switch section {
        case .plan where !storeManager.isPurchased:
            BackPageFootnote(storeManager.hasLegacyTrackingAccess
                ? "Your existing free tracking access is preserved. Choose a plan to unlock export."
                : "Choose a plan to track. Your existing records remain available to view and export.")
        case .plan where MyselfClaimFeature.isEnabled:
            BackPageFootnote("Existing purchasers receive complimentary lifetime access. Verify your purchase, then sign in to myself.md.")
        default:
            EmptyView()
        }
    }

    private var planCard: some View {
        BackPageCard {
            if storeManager.isPurchased {
                Button { showingPaywall = true } label: {
                    BackPageRowLabel(
                        title: Text(verbatim: storeManager.activePurchaseOption?.title ?? String(localized: "Active purchase")),
                        subtitle: Text(verbatim: planStatus).font(TE.mono(.caption2)),
                        value: Text("Manage"),
                        valueColor: TE.inkRust,
                        accessory: .none,
                        minHeight: 56
                    )
                }
                .buttonStyle(.plain)
                .accessibilityHint("Opens purchases and Family Sharing")
            } else {
                Button { showingPaywall = true } label: {
                    BackPageRowLabel(
                        title: Text(storeManager.hasLegacyTrackingAccess ? "Unlock export" : "Choose a plan"),
                        minHeight: 56
                    )
                }
                .buttonStyle(.plain)
            }

            if MyselfClaimFeature.isEnabled {
                BackPageDivider()
                Button { showingMyselfClaim = true } label: {
                    BackPageRowLabel(title: Text("Claim myself.md access"))
                }
                .buttonStyle(.plain)
            }

            BackPageDivider()
            Button {
                Task { await storeManager.restorePurchases() }
            } label: {
                BackPageRowLabel(title: Text("Restore purchase"), accessory: .none)
            }
            .buttonStyle(.plain)
            .disabled(storeManager.isLoading)
        }
    }

    /// Subscriptions that will auto-renew show the renewal date; ones set to lapse show when access ends.
    private var planStatus: String {
        guard let option = storeManager.activePurchaseOption else { return "" }
        var parts: [String] = []
        if option.isSubscription, let expiration = storeManager.trackingExpiration {
            let date = expiration.formatted(date: .abbreviated, time: .omitted)
            parts.append(storeManager.willAutoRenew == true
                ? String(localized: "Renews \(date)")
                : String(localized: "Through \(date)"))
        } else if !option.isSubscription {
            parts.append(String(localized: "Lifetime"))
        }
        if option.isFamilyPlan {
            parts.append(String(localized: "Family Sharing"))
        }
        return parts.joined(separator: " · ").uppercased()
    }

    private var recordingCard: some View {
        BackPageCard {
            NavigationLink {
                ListeningView(viewModel: viewModel)
            } label: {
                BackPageRowLabel(title: Text("How Whereday listens"), value: listeningSummary)
            }
            .buttonStyle(.plain)

            BackPageDivider()
            NavigationLink {
                SavedLocationsView(viewModel: viewModel)
            } label: {
                BackPageRowLabel(title: Text("Manage saved places"))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Manage Saved Locations")

            BackPageDivider()
            BackPageToggleRow(
                title: "Name places automatically",
                subtitle: "Looks up street and business names",
                isOn: $allowNetworkGeocoding
            )
            BackPageDivider()
            BackPageToggleRow(
                title: "Visit suggestions",
                subtitle: "A compact Save Place prompt on the map near a likely place",
                isOn: $showVisitSuggestions
            )
            BackPageDivider()
            BackPageToggleRow(
                title: "Show GPS glitches",
                subtitle: "Hidden by default; turn on to inspect raw data",
                isOn: $showOutliers
            )
        }
    }

    /// e.g. "Always · Places + routes", or "Off" while not recording.
    private var listeningSummary: Text {
        guard locationManager.isTrackingEnabled else { return Text("Off") }
        let permission: Text
        switch locationManager.authorizationStatus {
        case .authorizedAlways: permission = Text("Always")
        case .authorizedWhenInUse: permission = Text("While using")
        case .denied, .restricted: permission = Text("Not allowed")
        default: permission = Text("Not set")
        }
        guard locationManager.routeSamplingMode == .distance else {
            return permission + Text(verbatim: " · ") + Text(locationManager.routeSamplingPolicy.summary)
        }
        return permission + Text(verbatim: " · ") + Text(RecordingDetail(distanceFilter: locationManager.distanceFilter).title)
    }

    private var currentAppearance: AppAppearance {
        AppAppearance(rawValue: appAppearance) ?? .system
    }

    private var lookCard: some View {
        BackPageCard {
            VStack(alignment: .leading, spacing: 14) {
                lookRow("Units") {
                    BackPageSegmented(
                        label: "Units",
                        options: [(true, "Metric"), (false, "US")],
                        selection: $usesMetricDistanceUnits
                    )
                }
                lookRow("Theme") {
                    BackPageSegmented(
                        label: "Theme",
                        options: AppAppearance.allCases.map { ($0.rawValue, $0.shortTitle) },
                        selection: $appAppearance
                    )
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
        }
    }

    @ViewBuilder
    private func lookRow<Control: View>(_ title: LocalizedStringKey, @ViewBuilder control: () -> Control) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 8) {
                Text(title).font(.subheadline)
                control()
            }
        } else {
            HStack(spacing: 12) {
                Text(title).font(.subheadline)
                Spacer(minLength: 8)
                control()
            }
        }
    }

    private var dataCard: some View {
        BackPageCard {
            BackPageCountGrid(counts: [
                (viewModel.allVisits.count, "VISITS"),
                (viewModel.totalLocationPointCount, "POINTS")
            ])
            .padding(16)

            BackPageDivider()
            importRow

            BackPageDivider()
            NavigationLink {
                WebhookSettingsView()
            } label: {
                BackPageRowLabel(title: Text("Webhook delivery"), value: Text(webhookManager.isEnabled ? "On" : "Off"))
            }
            .buttonStyle(.plain)

            BackPageDivider()
            NavigationLink {
                ShortcutsGuideView()
            } label: {
                BackPageRowLabel(title: Text("Shortcuts & Siri"))
            }
            .buttonStyle(.plain)

            BackPageDivider()
            clearDataRow
        }
    }

    private var helpCard: some View {
        BackPageCard {
            if let latest = IsoMeReleaseNotes.notes.first {
                Button { showingReleaseNotes = true } label: {
                    BackPageRowLabel(
                        title: Text("What's new"),
                        value: Text(verbatim: latest.version).font(TE.mono(.caption)),
                        accessory: .none
                    )
                }
                .buttonStyle(.plain)
                BackPageDivider()
            }

            NavigationLink {
                LogViewerView()
            } label: {
                BackPageRowLabel(title: Text("Diagnostics & logs"))
            }
            .buttonStyle(.plain)

            BackPageDivider()
            Button { sendFeedbackEmail() } label: {
                BackPageRowLabel(title: Text("Send feedback"), accessory: .external)
            }
            .buttonStyle(.plain)

            if discordPromoDismissed, let discordInviteURL = IsoMeURLs.discordInvite {
                BackPageDivider()
                Link(destination: discordInviteURL) {
                    BackPageRowLabel(title: Text("Chat with us on Discord"), accessory: .external)
                }
                .buttonStyle(.plain)
            }

            BackPageDivider()
            Button { hasCompletedOnboarding = false } label: {
                BackPageRowLabel(title: Text("Replay the welcome"), accessory: .none)
            }
            .buttonStyle(.plain)
        }
    }

    private var legalFooter: some View {
        HStack(spacing: 16) {
            if let privacyPolicyURL = IsoMeURLs.privacyPolicy {
                Link("Privacy policy", destination: privacyPolicyURL)
                    .frame(minHeight: 44)
            }
            if let termsOfServiceURL = IsoMeURLs.termsOfService {
                Link("Terms of service", destination: termsOfServiceURL)
                    .frame(minHeight: 44)
            }
            Text(verbatim: "WHEREDAY \(AppInfo.versionDisplay)")
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
        }
        .font(.footnote)
        .foregroundStyle(TE.textPrimary)
        .modifier(BackPageWrapIfNeeded())
        .padding(.horizontal, 24)
        .padding(.top, 26)
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

    private func handleImportResult(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls):
            guard !urls.isEmpty else { return }
            importFileSelection = ImportFileSelection(urls: urls)
        case .failure(let error):
            importErrorMessage = error.localizedDescription
            showingImportError = true
        }
    }
}

/// The wide layout's seven top-level pages.

/// The wide layout's Webhook pane: a short summary (privacy note, URL and format, what's waiting,
/// Send now) with a link to the full WebhookSettingsView for sign-in, interval and batch size.
private struct WebhookSummaryPane: View {
    @ObservedObject private var storeManager = StoreManager.shared
    @ObservedObject private var webhook = WebhookManager.shared
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private static let commonFormats: [ExportFormat] = [.json, .geojson, .gpx, .owntracks, .overland]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("YOUR DATA · ON-DEVICE ONLY")
                        .font(TE.mono(.caption2))
                        .tracking(1.5)
                        .foregroundStyle(TE.textMuted)
                    Text("Webhook")
                        .font(TE.editorial(.title))
                        .accessibilityAddTraits(.isHeader)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text("DATA LEAVES YOUR DEVICE")
                        .font(TE.mono(.caption2))
                        .tracking(1.2)
                        .foregroundStyle(TE.inkRust)
                    Text("Delivery sends your location data to a server you configure.")
                        .font(.footnote)
                        .foregroundStyle(TE.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.placeInks[1], lineWidth: 1.5))
                .accessibilityElement(children: .combine)

                if storeManager.canExport {
                    BackPageCard {
                        BackPageToggleRow(title: "Webhook delivery", isOn: $webhook.isEnabled)
                    }
                    if webhook.isEnabled {
                        endpointCard
                        statusCard
                    }
                } else {
                    Text("Webhook is part of a plan. Choose a Whereday plan to unlock export features.")
                        .font(.footnote)
                        .foregroundStyle(TE.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }

                NavigationLink {
                    WebhookSettingsView()
                } label: {
                    HStack(spacing: 4) {
                        Text(storeManager.canExport ? "Sign-in, interval and batch size" : "See plans")
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .accessibilityHidden(true)
                    }
                    .font(.footnote)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 24)
            .padding(.top, 26)
            .padding(.bottom, 32)
        }
        .scrollIndicators(.hidden)
        .background(TE.surface.ignoresSafeArea())
    }

    private var endpointCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            VStack(alignment: .leading, spacing: 6) {
                Text("URL")
                    .font(TE.mono(.caption2))
                    .tracking(1)
                    .foregroundStyle(TE.textMuted)
                TextField("https://example.com/api/location", text: $webhook.urlString)
                    .font(TE.mono(.subheadline))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled(true)
                    .keyboardType(.URL)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 44)
                    .background(RoundedRectangle(cornerRadius: 10).fill(TE.surface))
                    .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(TE.border, lineWidth: 1))
            }
            BackPageWrapLayout(spacing: 6) {
                ForEach(formatChoices, id: \.self) { format in
                    formatChip(format)
                }
                NavigationLink {
                    WebhookSettingsView()
                } label: {
                    chipLabel(String(localized: "More…"), isSelected: false)
                }
                .buttonStyle(.plain)
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Format")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
    }

    /// The five common formats, plus the chosen one when it lives behind "More…".
    private var formatChoices: [ExportFormat] {
        Self.commonFormats.contains(webhook.format) ? Self.commonFormats : Self.commonFormats + [webhook.format]
    }

    private func formatChip(_ format: ExportFormat) -> some View {
        let isSelected = webhook.format == format
        return Button { webhook.format = format } label: {
            chipLabel(format.displayName, isSelected: isSelected)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private func chipLabel(_ title: String, isSelected: Bool) -> some View {
        Text(verbatim: title)
            .font(TE.mono(.caption, weight: .medium))
            .foregroundStyle(isSelected ? TE.surface : TE.textPrimary)
            .padding(.horizontal, 10)
            .frame(minHeight: 34)
            .background(Capsule().fill(isSelected ? TE.accent : Color.clear))
            .overlay(Capsule().strokeBorder(isSelected ? TE.accent : TE.border, lineWidth: 1))
            .frame(minHeight: 44)
            .contentShape(Rectangle())
    }

    private var statusCard: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
            : AnyLayout(HStackLayout(spacing: 12))
        return VStack(alignment: .leading, spacing: 8) {
            layout {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: queueTitle)
                        .font(TE.editorial(.title3))
                    Text(verbatim: lastSentText)
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.onInkCardMuted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)

                // Only sends what's waiting; sending all history lives on the full webhook page.
                Button {
                    Task { await webhook.flushBatch() }
                } label: {
                    Text("Send now")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(TE.inkCard)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 44)
                        .background(Capsule().fill(TE.onInkCard))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(webhook.isSending || !hasQueuedPoints)
                .opacity(hasQueuedPoints ? 1 : 0.5)
            }
            if let error = webhook.lastError {
                Text(error)
                    .font(TE.mono(.caption))
                    .foregroundStyle(TE.onInkCardMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .foregroundStyle(TE.onInkCard)
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.inkCard))
    }

    private var hasQueuedPoints: Bool {
        webhook.sendMode != .manual && webhook.queuedPointCount > 0
    }

    private var queueTitle: String {
        guard hasQueuedPoints else { return String(localized: "Nothing waiting") }
        return webhook.queuedPointCount == 1
            ? String(localized: "1 point waiting")
            : String(localized: "\(webhook.queuedPointCount.formatted()) points waiting")
    }

    /// "LAST SENT 14:05 · 200 OK"; older sends include the date.
    private var lastSentText: String {
        guard let last = webhook.lastSentAt else { return String(localized: "LAST SENT NEVER") }
        let time = Calendar.current.isDateInToday(last)
            ? last.formatted(date: .omitted, time: .shortened)
            : last.formatted(date: .abbreviated, time: .shortened)
        var text = String(localized: "LAST SENT \(time)")
        if let code = webhook.lastStatusCode {
            text += " · " + WebhookManager.statusText(code)
        }
        return text.uppercased()
    }
}

/// Wraps chips onto as many rows as the width needs.
private struct BackPageWrapLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let height = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0))
        return CGSize(width: proposal.width ?? (rows.map(\.width).max() ?? 0), height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for row in arrange(width: bounds.width, subviews: subviews) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                let width = min(size.width, bounds.width)
                subviews[index].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(width: width, height: size.height))
                x += width + spacing
            }
            y += row.height + spacing
        }
    }

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var current = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let itemWidth = min(size.width, width)
            if !current.indices.isEmpty, current.width + spacing + itemWidth > width {
                rows.append(current)
                current = Row()
            }
            current.width = current.indices.isEmpty ? itemWidth : current.width + spacing + itemWidth
            current.height = max(current.height, size.height)
            current.indices.append(index)
        }
        if !current.indices.isEmpty { rows.append(current) }
        return rows
    }
}

private enum SettingsPane: String, CaseIterable, Identifiable {
    case plan, recording, look, importFile, webhook, shortcuts, help
    var id: String { rawValue }

    var label: LocalizedStringKey {
        switch self {
        case .plan: "PLAN"
        case .recording: "RECORDING & PLACES"
        case .look: "LOOK"
        case .importFile, .webhook, .shortcuts: "YOUR DATA · ON-DEVICE ONLY"
        case .help: "HELP"
        }
    }

    var title: LocalizedStringKey {
        switch self {
        case .plan: "Plan"
        case .recording: "Recording & places"
        case .look: "Look"
        case .importFile: "Import a file"
        case .webhook: "Webhook"
        case .shortcuts: "Shortcuts & Siri"
        case .help: "Help & diagnostics"
        }
    }
}

private enum SettingsSection: String, CaseIterable, Identifiable {
    case plan, recording, look, data, help
    var id: String { rawValue }

    var label: LocalizedStringKey {
        switch self {
        case .plan: "PLAN"
        case .recording: "RECORDING & PLACES"
        case .look: "LOOK"
        case .data: "YOUR DATA · ON-DEVICE ONLY"
        case .help: "HELP"
        }
    }

    var title: LocalizedStringKey {
        switch self {
        case .plan: "Plan"
        case .recording: "Recording & places"
        case .look: "Look"
        case .data: "Your data"
        case .help: "Help & diagnostics"
        }
    }
}

private extension AppAppearance {
    /// The journal's own names for the light and dark appearances.
    var shortTitle: LocalizedStringKey {
        switch self {
        case .system: "System"
        case .light: "Paper"
        case .dark: "Night"
        }
    }
}

// MARK: - Clear all data

/// Confirms erasing everything, with the real counts that will be deleted.
struct ClearDataSheet: View {
    let visitCount: Int
    let pointCount: Int
    let earliestDate: Date?
    let onExportFirst: () -> Void
    let onErase: () -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.modelContext) private var modelContext
    @State private var manualStayCount = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("CLEAR ALL DATA")
                    .font(TE.mono(.caption2))
                    .tracking(1.5)
                    .foregroundStyle(TE.inkRust)
                    .padding(.horizontal, 4)
                Text(headline)
                    .font(TE.editorial(.title))
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)
                    .accessibilityAddTraits(.isHeader)

                BackPageCard {
                    BackPageCountGrid(counts: [
                        (visitCount, "VISITS"),
                        (pointCount, "POINTS"),
                        (manualStayCount, "MANUAL STAYS")
                    ], valueFont: TE.editorial(.title2))
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                }

                Text("This permanently deletes all visits, location points, outings, saved places, photo matches and manual country stays. It can't be undone.")
                    .font(.subheadline)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 4)

                VStack(spacing: 10) {
                    Button("Export everything first") {
                        onExportFirst()
                        dismiss()
                    }
                    .buttonStyle(BackPagePillButtonStyle(kind: .outlined))

                    Button("Erase all data") {
                        onErase()
                        dismiss()
                    }
                    .buttonStyle(BackPagePillButtonStyle(kind: .destructive))

                    Button("Cancel") { dismiss() }
                        .font(.subheadline)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 24)
            .padding(.bottom, 30)
        }
        .foregroundStyle(TE.textPrimary)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(TE.surface)
        .task {
            manualStayCount = (try? modelContext.fetchCount(FetchDescriptor<CountryStay>())) ?? 0
        }
    }

    private var headline: String {
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .full
        formatter.allowedUnits = [.year, .month, .day]
        formatter.maximumUnitCount = 1
        if let earliestDate, Date().timeIntervalSince(earliestDate) >= 86_400,
           let span = formatter.string(from: earliestDate, to: Date()) {
            return String(localized: "Erase \(span) of days from this iPhone?")
        }
        return String(localized: "Erase everything Whereday has kept on this iPhone?")
    }
}

// MARK: - Back-page components

/// Shared by the settings pages: paper cards, hairline rows and capsule controls.
struct BackPageCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
    }
}

struct BackPageDivider: View {
    var body: some View {
        Rectangle()
            .fill(TE.dialTrack)
            .frame(height: 1)
            .accessibilityHidden(true)
    }
}

/// Mono section label above a card.
struct BackPageLabel: View {
    let text: LocalizedStringKey

    init(_ text: LocalizedStringKey) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(TE.mono(.caption2))
            .tracking(1.5)
            .foregroundStyle(TE.textMuted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 24)
            .padding(.top, 26)
            .padding(.bottom, 8)
            .accessibilityAddTraits(.isHeader)
    }
}

struct BackPageFootnote: View {
    let text: LocalizedStringKey

    init(_ text: LocalizedStringKey) {
        self.text = text
    }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(TE.textMuted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 24)
            .padding(.top, 8)
    }
}

/// Mono caption over a serif page title.
struct BackPageHeader: View {
    var caption: LocalizedStringKey?
    let title: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let caption {
                Text(caption)
                    .font(TE.mono(.caption2))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Text(title)
                .font(TE.editorial(.largeTitle))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A row's contents: title, optional subtitle, trailing value and accessory.
struct BackPageRowLabel: View {
    enum Accessory { case chevron, external, none }

    let title: Text
    var subtitle: Text?
    var value: Text?
    var valueColor: Color = TE.textMuted
    var tint: Color = TE.textPrimary
    var accessory: Accessory = .chevron
    var minHeight: CGFloat = 52

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 4) {
                    titles
                    HStack(spacing: 6) {
                        trailing
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(spacing: 12) {
                    titles
                    Spacer(minLength: 8)
                    trailing
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(minHeight: minHeight)
        .contentShape(Rectangle())
    }

    private var titles: some View {
        VStack(alignment: .leading, spacing: 2) {
            title
                .font(.subheadline)
                .foregroundStyle(tint)
            if let subtitle {
                subtitle
                    .font(.caption)
                    .foregroundStyle(TE.textMuted)
            }
        }
        .multilineTextAlignment(.leading)
        .fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder
    private var trailing: some View {
        if let value {
            value
                .font(.footnote)
                .foregroundStyle(valueColor)
                .multilineTextAlignment(.trailing)
        }
        switch accessory {
        case .chevron:
            Image(systemName: "chevron.right")
                .font(.footnote.weight(.medium))
                .foregroundStyle(TE.textMuted)
                .accessibilityHidden(true)
        case .external:
            Image(systemName: "arrow.up.right")
                .font(.footnote.weight(.medium))
                .foregroundStyle(TE.textMuted)
                .accessibilityHidden(true)
        case .none:
            EmptyView()
        }
    }
}

struct BackPageToggleRow: View {
    let title: LocalizedStringKey
    var subtitle: LocalizedStringKey?
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline)
                    .foregroundStyle(TE.textPrimary)
                if let subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(TE.textMuted)
                }
            }
        }
        .toggleStyle(TEToggleStyle())
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .frame(minHeight: 52)
    }
}

/// A row with a menu picker on the trailing edge.
struct BackPagePickerRow<Picker: View>: View {
    let title: LocalizedStringKey
    @ViewBuilder let picker: Picker
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.subheadline)
                    picker
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(spacing: 12) {
                    Text(title).font(.subheadline)
                    Spacer(minLength: 8)
                    picker
                }
            }
        }
        .tint(TE.textPrimary)
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .frame(minHeight: 52)
    }
}

/// A capsule of options with the chosen one filled in ink.
struct BackPageSegmented<Value: Hashable>: View {
    let label: LocalizedStringKey
    let options: [(Value, LocalizedStringKey)]
    @Binding var selection: Value

    var body: some View {
        HStack(spacing: 0) {
            ForEach(options.indices, id: \.self) { index in
                let option = options[index]
                let isSelected = option.0 == selection
                Button { selection = option.0 } label: {
                    Text(option.1)
                        .font(.footnote.weight(.medium))
                        .lineLimit(1)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 44)
                        .foregroundStyle(isSelected ? TE.surface : TE.textPrimary)
                        .background(isSelected ? TE.textPrimary : Color.clear)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .background(TE.card)
        .clipShape(Capsule())
        .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(label)
    }
}

/// Big serif counts over mono labels, side by side.
struct BackPageCountGrid: View {
    let counts: [(Int, LocalizedStringKey)]
    var valueFont: Font = TE.editorial(.title)
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 12))
        layout {
            ForEach(counts.indices, id: \.self) { index in
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: counts[index].0.formatted())
                        .font(valueFont)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Text(counts[index].1)
                        .font(TE.mono(.caption2))
                        .tracking(1)
                        .foregroundStyle(TE.textMuted)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
            }
        }
    }
}

/// 52pt capsule actions: filled ink, outlined, or rust for destructive.
struct BackPagePillButtonStyle: ButtonStyle {
    enum Kind { case filled, outlined, destructive }

    var kind: Kind = .filled
    var font: Font = .subheadline.weight(.semibold)
    var minHeight: CGFloat = 52
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(font)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity, minHeight: minHeight)
            .foregroundStyle(foreground)
            .background(Capsule().fill(background))
            .overlay(Capsule().strokeBorder(kind == .outlined ? TE.textPrimary : Color.clear, lineWidth: 1))
            .contentShape(Capsule())
            .opacity(configuration.isPressed ? 0.75 : (isEnabled ? 1 : 0.5))
    }

    private var foreground: Color {
        switch kind {
        case .filled: TE.surface
        case .outlined: TE.textPrimary
        case .destructive: TE.card
        }
    }

    private var background: Color {
        switch kind {
        case .filled: TE.textPrimary
        case .outlined: Color.clear
        case .destructive: TE.inkRust
        }
    }
}

/// The "‹ Archive" / "‹ Settings" capsule at the top of a back page.
struct BackPageBackCapsule: View {
    let title: LocalizedStringKey
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Image(systemName: "chevron.left")
                    .font(.subheadline.weight(.medium))
                    .accessibilityHidden(true)
                Text(title)
                    .font(.subheadline.weight(.medium))
            }
            .padding(.leading, 10)
            .padding(.trailing, 14)
            .frame(minHeight: 44)
            .background(Capsule().fill(TE.card))
            .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .foregroundStyle(TE.textPrimary)
        .accessibilityLabel(Text("Back to \(Text(title))"))
    }
}

/// A 44pt paper circle holding an icon, such as a sheet's close button.
struct BackPageCircleButton: View {
    let systemImage: String
    let label: LocalizedStringKey
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.subheadline.weight(.medium))
                .frame(width: 44, height: 44)
                .background(Circle().fill(TE.card))
                .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(TE.textPrimary)
        .accessibilityLabel(label)
    }
}

/// Lays a short row of links out vertically when the text is too large to sit side by side.
struct BackPageWrapIfNeeded: ViewModifier {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    func body(content: Content) -> some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 4) {
                content
            }
        } else {
            content
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
