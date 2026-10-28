import SwiftUI

/// Configuration UI for the HTTP webhook delivery feature.
/// Embedded in SettingsView via a NavigationLink.
struct WebhookSettingsView: View {
    @ObservedObject private var storeManager = StoreManager.shared
    @ObservedObject private var webhook = WebhookManager.shared
    @State private var showingPaywall = false
    @State private var testResult: String?
    @State private var testError: String?
    @State private var isTesting = false
    @AppStorage("webhook.privacyWarningDismissed") private var privacyWarningDismissed = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var usesAccessibilityLayout: Bool {
        dynamicTypeSize.isAccessibilitySize
    }

    var body: some View {
        ZStack {
            TE.surface.ignoresSafeArea()

            if storeManager.isPurchased {
                ScrollView {
                    VStack(spacing: 0) {
                        if !privacyWarningDismissed {
                            privacyWarning
                        }
                        enableSection
                        if webhook.isEnabled {
                            endpointSection
                            authSection
                            sendModeSection
                            statusSection
                            actionsSection
                        }
                        Spacer().frame(height: usesAccessibilityLayout ? 200 : 96)
                    }
                }
            } else {
                lockedState
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text("WEBHOOK")
                    .font(TE.mono(.caption, weight: .bold))
                    .tracking(3)
                    .foregroundStyle(TE.textMuted)
            }
        }
        .sheet(isPresented: $showingPaywall) {
            PaywallView(storeManager: storeManager, context: .webhook)
        }
    }

    // MARK: - Locked

    private var lockedState: some View {
        VStack(spacing: 18) {
            Image(systemName: "lock.fill")
                .font(.title.weight(.light))
                .foregroundStyle(TE.textMuted)
            Text("WEBHOOK LOCKED")
                .font(TE.mono(.caption, weight: .bold))
                .tracking(2)
                .foregroundStyle(TE.textPrimary)
            Text("Export features require a one-time purchase.")
                .font(TE.mono(.caption2, weight: .medium))
                .foregroundStyle(TE.textMuted)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.horizontal, 32)
            Button {
                showingPaywall = true
            } label: {
                Text("UNLOCK EXPORT")
                    .font(TE.mono(.caption, weight: .bold))
                    .tracking(2)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 28)
                    .frame(height: 44)
                    .background(TE.accent)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
            }
            .buttonStyle(.plain)
        }
        .padding()
    }

    // MARK: - Privacy

    private var privacyWarning: some View {
        VStack(spacing: 0) {
            TECard {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.callout)
                            .foregroundStyle(TE.warning)
                            .accessibilityHidden(true)

                        Text("DATA LEAVES YOUR DEVICE")
                            .font(TE.mono(.caption2, weight: .bold))
                            .tracking(usesAccessibilityLayout ? 0.5 : 1.5)
                            .foregroundStyle(TE.warning)
                            .fixedSize(horizontal: false, vertical: true)

                        Spacer(minLength: 0)

                        Button {
                            privacyWarningDismissed = true
                        } label: {
                            Image(systemName: "xmark")
                                .font(.caption.weight(.bold))
                                .foregroundStyle(TE.textMuted)
                                .frame(width: 32, height: 32)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Dismiss warning")
                    }

                    Text("Enabling webhook delivery sends your location data to an external server you configure. Whereday does not have access to this server or the transmitted data.")
                        .font(TE.mono(.caption2, weight: .regular))
                        .foregroundStyle(TE.textMuted)
                        .lineSpacing(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(12)
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
        }
    }

    // MARK: - Enable

    private var enableSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "ENABLE")

            TECard {
                TERow(showDivider: false) {
                    Toggle(isOn: $webhook.isEnabled) {
                        Text("WEBHOOK DELIVERY")
                            .font(TE.mono(.caption, weight: .medium))
                            .tracking(1)
                            .foregroundStyle(TE.textPrimary)
                    }
                    .toggleStyle(TEToggleStyle())
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: webhook.isEnabled
                ? "Location data will be POSTed to the configured endpoint."
                : "Send location data to an external API or self-hosted server.")
        }
    }

    // MARK: - Endpoint

    private var endpointSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "ENDPOINT")

            TECard {
                TERow {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("URL")
                            .font(TE.mono(.caption2, weight: .semibold))
                            .tracking(1)
                            .foregroundStyle(TE.textMuted)
                        TextField("https://example.com/api/location", text: $webhook.urlString)
                            .font(TE.mono(.caption, weight: .medium))
                            .foregroundStyle(TE.textPrimary)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled(true)
                            .keyboardType(.URL)
                    }
                }

                webhookPickerRow("FORMAT", selection: $webhook.format, showDivider: false) {
                    Text("OWNTRACKS").tag(ExportFormat.owntracks)
                    Text("OVERLAND").tag(ExportFormat.overland)
                    Text("JSON").tag(ExportFormat.json)
                    Text("CSV").tag(ExportFormat.csv)
                    Text("GPX").tag(ExportFormat.gpx)
                    Text("KML").tag(ExportFormat.kml)
                    Text("GEOJSON").tag(ExportFormat.geojson)
                    Text("MARKDOWN").tag(ExportFormat.markdown)
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "OwnTracks format works with Dawarich (api/v1/owntracks/points), OwnTracks Recorder (/pub), and Traccar.")
        }
    }

    // MARK: - Auth

    private var authSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "AUTH")

            TECard {
                webhookPickerRow("TYPE", selection: $webhook.authType, mutedTitle: true) {
                    ForEach(WebhookManager.AuthType.allCases) { type in
                        Text(verbatim: type.label).tag(type)
                    }
                }

                switch webhook.authType {
                case .none:
                    EmptyView()

                case .apiKeyQuery:
                    TERow {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("PARAM NAME")
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1)
                                .foregroundStyle(TE.textMuted)
                            TextField("api_key", text: $webhook.authKey)
                                .font(TE.mono(.caption, weight: .medium))
                                .foregroundStyle(TE.textPrimary)
                                .autocorrectionDisabled(true)
                                .textInputAutocapitalization(.never)
                        }
                    }
                    TERow(showDivider: false) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("API KEY")
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1)
                                .foregroundStyle(TE.textMuted)
                            SecureField("••••••••", text: $webhook.authValue)
                                .font(TE.mono(.caption, weight: .medium))
                                .foregroundStyle(TE.textPrimary)
                        }
                    }

                case .bearer:
                    TERow(showDivider: false) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("TOKEN")
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1)
                                .foregroundStyle(TE.textMuted)
                            SecureField("••••••••", text: $webhook.authValue)
                                .font(TE.mono(.caption, weight: .medium))
                                .foregroundStyle(TE.textPrimary)
                        }
                    }

                case .basic:
                    TERow {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("USERNAME")
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1)
                                .foregroundStyle(TE.textMuted)
                            TextField("user", text: $webhook.authUsername)
                                .font(TE.mono(.caption, weight: .medium))
                                .foregroundStyle(TE.textPrimary)
                                .autocorrectionDisabled(true)
                                .textInputAutocapitalization(.never)
                        }
                    }
                    TERow(showDivider: false) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("PASSWORD")
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1)
                                .foregroundStyle(TE.textMuted)
                            SecureField("••••••••", text: $webhook.authValue)
                                .font(TE.mono(.caption, weight: .medium))
                                .foregroundStyle(TE.textPrimary)
                        }
                    }

                case .customHeader:
                    TERow {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("HEADER NAME")
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1)
                                .foregroundStyle(TE.textMuted)
                            TextField("X-API-Key", text: $webhook.authKey)
                                .font(TE.mono(.caption, weight: .medium))
                                .foregroundStyle(TE.textPrimary)
                                .autocorrectionDisabled(true)
                                .textInputAutocapitalization(.never)
                        }
                    }
                    TERow(showDivider: false) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("HEADER VALUE")
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1)
                                .foregroundStyle(TE.textMuted)
                            SecureField("••••••••", text: $webhook.authValue)
                                .font(TE.mono(.caption, weight: .medium))
                                .foregroundStyle(TE.textPrimary)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: webhook.authType == .apiKeyQuery
                ? "The key and value are appended as a query string (?key=value)."
                : "Credentials are sent as HTTP headers and stored in the device keychain.")
        }
    }

    // MARK: - Send mode

    private var sendModeSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "SEND MODE")

            TECard {
                webhookPickerRow(
                    "MODE",
                    selection: $webhook.sendMode,
                    showDivider: webhook.sendMode == .batchCount || webhook.sendMode == .batchTime
                ) {
                    ForEach(WebhookManager.SendMode.allCases) { mode in
                        Text(verbatim: mode.label).tag(mode)
                    }
                }

                if webhook.sendMode == .batchCount {
                    webhookPickerRow("COUNT", selection: $webhook.batchCount, showDivider: false) {
                        Text("5").tag(5)
                        Text("10").tag(10)
                        Text("25").tag(25)
                        Text("50").tag(50)
                        Text("100").tag(100)
                    }
                } else if webhook.sendMode == .batchTime {
                    webhookPickerRow("INTERVAL", selection: $webhook.batchTimeMinutes, showDivider: false) {
                        Text("1 min").tag(1)
                        Text("5 min").tag(5)
                        Text("15 min").tag(15)
                        Text("30 min").tag(30)
                        Text("1 hour").tag(60)
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: sendModeFooter)
        }
    }

    private var sendModeFooter: LocalizedStringKey {
        switch webhook.sendMode {
        case .realtime:
            return "Each new GPS fix is POSTed immediately."
        case .batchCount:
            return "Points are queued in memory and sent when the count is reached. Unsent points are lost if the app is terminated."
        case .batchTime:
            return "Points are queued and flushed every N minutes. Unsent points are lost if the app is terminated."
        case .manual:
            return "Data is only sent when you tap \"Send Now\" below."
        }
    }

    // MARK: - Status

    private var statusSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "STATUS")

            TECard {
                webhookValueRow(
                    "LAST SENT",
                    value: lastSentText,
                    showDivider: hasQueuedPoints || webhook.lastError != nil
                )

                if hasQueuedPoints {
                    webhookValueRow(
                        "QUEUED",
                        value: "\(webhook.queuedPointCount) POINTS",
                        valueColor: TE.accent
                    )

                    TERow(showDivider: webhook.lastError != nil) {
                        Button {
                            Task { await webhook.flushBatch() }
                        } label: {
                            webhookButtonLabel("FLUSH QUEUE", systemImage: "paperplane.fill", showsTrailingArrow: false)
                        }
                        .buttonStyle(.plain)
                    }
                }

                if let error = webhook.lastError {
                    webhookMessageRow(error, systemImage: "xmark.circle.fill", color: TE.danger, showDivider: false)
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private var hasQueuedPoints: Bool {
        webhook.sendMode != .manual && webhook.queuedPointCount > 0
    }

    private var lastSentText: String {
        guard let last = webhook.lastSentAt else { return String(localized: "NEVER") }
        let fmt = DateFormatter()
        fmt.dateStyle = .short
        fmt.timeStyle = .short
        return fmt.string(from: last)
    }

    // MARK: - Actions

    private var actionsSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "ACTIONS")

            TECard {
                TERow {
                    Button {
                        isTesting = true
                        testResult = nil
                        testError = nil
                        Task {
                            do {
                                testResult = try await webhook.testConnection()
                            } catch {
                                testError = error.localizedDescription
                            }
                            isTesting = false
                        }
                    } label: {
                        webhookButtonLabel(
                            isTesting ? "TESTING…" : "TEST CONNECTION",
                            systemImage: "antenna.radiowaves.left.and.right",
                            isLoading: isTesting
                        )
                    }
                    .buttonStyle(.plain)
                    .disabled(isTesting)
                }

                if let result = testResult {
                    webhookMessageRow(result, systemImage: "checkmark.circle.fill", color: TE.success)
                }

                if let error = testError {
                    webhookMessageRow(error, systemImage: "xmark.circle.fill", color: TE.danger)
                }

                TERow(showDivider: false) {
                    Button {
                        Task { await webhook.sendNow() }
                    } label: {
                        webhookButtonLabel("SEND NOW", systemImage: "paperplane.fill")
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Use Test Connection to verify the endpoint URL and credentials without sending real data. Send Now posts all saved location data to the endpoint.")
        }
    }

    // MARK: - Responsive Rows

    private func rowTitle(_ title: LocalizedStringKey, muted: Bool = false) -> some View {
        Text(title)
            .font(TE.mono(muted ? .caption2 : .caption, weight: muted ? .semibold : .medium))
            .tracking(usesAccessibilityLayout ? 0.5 : 1)
            .foregroundStyle(muted ? TE.textMuted : TE.textPrimary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func webhookPickerRow<SelectionValue: Hashable, Content: View>(
        _ title: LocalizedStringKey,
        selection: Binding<SelectionValue>,
        showDivider: Bool = true,
        mutedTitle: Bool = false,
        @ViewBuilder options: () -> Content
    ) -> some View {
        TERow(showDivider: showDivider) {
            if usesAccessibilityLayout {
                VStack(alignment: .leading, spacing: 10) {
                    rowTitle(title, muted: mutedTitle)

                    Picker(title, selection: selection) {
                        options()
                    }
                    .pickerStyle(.menu)
                    .tint(TE.accent)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else {
                HStack(alignment: .center, spacing: 12) {
                    rowTitle(title, muted: mutedTitle)
                    Spacer(minLength: 12)
                    Picker(title, selection: selection) {
                        options()
                    }
                    .labelsHidden()
                    .tint(TE.accent)
                }
            }
        }
    }

    private func webhookValueRow(
        _ title: LocalizedStringKey,
        value: String,
        valueColor: Color = TE.textMuted,
        showDivider: Bool = true
    ) -> some View {
        TERow(showDivider: showDivider) {
            if usesAccessibilityLayout {
                VStack(alignment: .leading, spacing: 8) {
                    rowTitle(title)
                    Text(value)
                        .font(TE.mono(.caption2, weight: .medium))
                        .foregroundStyle(valueColor)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
            } else {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    rowTitle(title)
                    Spacer(minLength: 12)
                    Text(value)
                        .font(TE.mono(.caption2, weight: .medium))
                        .foregroundStyle(valueColor)
                        .fixedSize(horizontal: false, vertical: true)
                        .multilineTextAlignment(.trailing)
                }
                .accessibilityElement(children: .combine)
            }
        }
    }

    private func webhookMessageRow(
        _ message: String,
        systemImage: String,
        color: Color,
        showDivider: Bool = true
    ) -> some View {
        TERow(showDivider: showDivider) {
            HStack(alignment: .top, spacing: usesAccessibilityLayout ? 10 : 6) {
                Image(systemName: systemImage)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(color)
                    .accessibilityHidden(true)

                Text(message)
                    .font(TE.mono(.caption2, weight: .medium))
                    .foregroundStyle(color)
                    .lineSpacing(usesAccessibilityLayout ? 4 : 2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)
        }
    }

    @ViewBuilder
    private func webhookButtonLabel(
        _ title: LocalizedStringKey,
        systemImage: String,
        showsTrailingArrow: Bool = true,
        isLoading: Bool = false
    ) -> some View {
        if usesAccessibilityLayout {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Image(systemName: systemImage)
                        .font(.caption.weight(.medium))
                        .foregroundStyle(TE.accent)
                        .accessibilityHidden(true)

                    Text(title)
                        .font(TE.mono(.caption, weight: .medium))
                        .tracking(0.5)
                        .foregroundStyle(TE.accent)
                        .fixedSize(horizontal: false, vertical: true)

                    if isLoading {
                        ProgressView()
                            .tint(TE.accent)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                    .font(.caption.weight(.medium))
                    .foregroundStyle(TE.accent)
                    .accessibilityHidden(true)

                Text(title)
                    .font(TE.mono(.caption, weight: .medium))
                    .tracking(1)
                    .foregroundStyle(TE.accent)

                Spacer()

                if isLoading {
                    ProgressView()
                        .scaleEffect(0.6)
                        .tint(TE.accent)
                } else if showsTrailingArrow {
                    Image(systemName: "arrow.right")
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(TE.accent.opacity(0.5))
                        .accessibilityHidden(true)
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        WebhookSettingsView()
    }
}
