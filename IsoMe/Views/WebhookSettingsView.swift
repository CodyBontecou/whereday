import SwiftUI

/// Configuration UI for the HTTP webhook delivery feature.
/// Pushed from Settings and from the Archive destination options.
struct WebhookSettingsView: View {
    @ObservedObject private var storeManager = StoreManager.shared
    @ObservedObject private var webhook = WebhookManager.shared
    @State private var showingPaywall = false
    @State private var testResult: String?
    @State private var testError: String?
    @State private var isTesting = false
    @State private var confirmingClearQueue = false
    @AppStorage("webhook.privacyWarningDismissed") private var privacyWarningDismissed = false
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var usesAccessibilityLayout: Bool {
        dynamicTypeSize.isAccessibilitySize
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header

                if storeManager.canExport {
                    if !privacyWarningDismissed {
                        privacyWarning
                    }
                    enableSection
                    if webhook.isEnabled {
                        endpointSection
                        authSection
                        sendModeSection
                        statusSection
                    }
                } else {
                    lockedState
                }
            }
            .padding(.bottom, usesAccessibilityLayout ? 200 : 96)
        }
        .scrollIndicators(.hidden)
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingPaywall) {
            PaywallView(storeManager: storeManager, context: .webhook)
        }
        .confirmationDialog(
            clearQueueTitle,
            isPresented: $confirmingClearQueue,
            titleVisibility: .visible
        ) {
            Button("Clear queue", role: .destructive) { webhook.clearQueue() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Waiting points won't be sent. Your saved location history stays on this iPhone.")
        }
    }

    private var clearQueueTitle: String {
        webhook.queuedPointCount == 1
            ? String(localized: "Discard 1 waiting point?")
            : String(localized: "Discard \(webhook.queuedPointCount.formatted()) waiting points?")
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("SEND TO YOUR OWN SERVER")
                .font(TE.mono(.caption2))
                .tracking(1.5)
                .foregroundStyle(TE.textMuted)
            Text("Webhook")
                .font(TE.editorial(.largeTitle))
                .accessibilityAddTraits(.isHeader)
        }
        .padding(.horizontal, 24)
        .padding(.top, 8)
    }

    // MARK: - Locked

    private var lockedState: some View {
        VStack(spacing: 12) {
            Image(systemName: "lock")
                .font(.largeTitle.weight(.light))
                .accessibilityHidden(true)
            Text("Webhook is part of a plan")
                .font(TE.editorial(.title))
                .multilineTextAlignment(.center)
            Text("Choose a Whereday plan to unlock export features.")
                .font(.subheadline)
                .foregroundStyle(TE.textMuted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                showingPaywall = true
            } label: {
                Text("See plans")
            }
            .buttonStyle(ArchiveCapsuleButtonStyle(filled: true))
            .frame(maxWidth: 240)
            .padding(.top, 8)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 40)
        .padding(.top, 80)
    }

    // MARK: - Privacy

    private var privacyWarning: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.title3)
                .foregroundStyle(TE.placeInks[1])
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                Text("DATA LEAVES YOUR DEVICE")
                    .font(TE.mono(.caption2))
                    .tracking(usesAccessibilityLayout ? 0.5 : 1.2)
                    .foregroundStyle(TE.inkRust)
                    .fixedSize(horizontal: false, vertical: true)
                Text("Delivery sends your location data to a server you configure. Whereday has no access to that server or what it receives.")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button {
                privacyWarningDismissed = true
            } label: {
                Image(systemName: "xmark")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(TE.textMuted)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.top, -12)
            .padding(.trailing, -12)
            .accessibilityLabel("Dismiss warning")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.placeInks[1], lineWidth: 1.5))
        .padding(.horizontal, 16)
        .padding(.top, 18)
    }

    // MARK: - Enable

    private var enableSection: some View {
        Toggle(isOn: $webhook.isEnabled) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Webhook delivery")
                    .font(.subheadline)
                Text(enableSubtitle)
                    .font(.caption)
                    .foregroundStyle(TE.textMuted)
            }
        }
        .toggleStyle(TEToggleStyle())
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(minHeight: 56)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .padding(.horizontal, 16)
        .padding(.top, 16)
    }

    private var enableSubtitle: LocalizedStringKey {
        guard webhook.isEnabled else { return "Send location data to an external API or self-hosted server." }
        return webhook.sendMode == .manual
            ? "Sends only when you tap Send now"
            : "Sends new points as they're kept"
    }

    // MARK: - Endpoint

    private static let formatChoices: [ExportFormat] = [
        .json, .geojson, .csv, .gpx, .kml, .markdown, .owntracks, .overland
    ]

    private var endpointSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            sectionHeading("WHERE")
            card {
                fieldLabel("URL")
                TextField("https://example.com/api/location", text: $webhook.urlString)
                    .font(TE.mono(.subheadline))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled(true)
                    .keyboardType(.URL)
                    .modifier(WebhookFieldStyle())

                fieldLabel("FORMAT")
                    .padding(.top, 6)
                WebhookFlowLayout(spacing: 6) {
                    ForEach(Self.formatChoices, id: \.self) { format in
                        chip(format.displayName, isSelected: webhook.format == format) {
                            webhook.format = format
                        }
                    }
                }
            }
            sectionFooter("OwnTracks format works with Dawarich (api/v1/owntracks/points), OwnTracks Recorder (/pub), and Traccar.")
        }
    }

    // MARK: - Auth

    private var authSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            sectionHeading("SIGN-IN")
            card {
                segmented(
                    WebhookManager.AuthType.allCases.map { ($0, shortLabel(for: $0), $0.label) },
                    selection: $webhook.authType,
                    groupLabel: "Authentication"
                )

                switch webhook.authType {
                case .none:
                    EmptyView()
                case .apiKeyQuery:
                    textField("PARAM NAME", prompt: "api_key", text: $webhook.authKey)
                    secureField("API KEY", text: $webhook.authValue)
                case .bearer:
                    secureField("TOKEN", text: $webhook.authValue)
                case .basic:
                    textField("USERNAME", prompt: "user", text: $webhook.authUsername)
                    secureField("PASSWORD", text: $webhook.authValue)
                case .customHeader:
                    textField("HEADER NAME", prompt: "X-API-Key", text: $webhook.authKey)
                    secureField("HEADER VALUE", text: $webhook.authValue)
                }
            }
            sectionFooter(webhook.authType == .apiKeyQuery
                ? "The key and value are appended as a query string (?key=value)."
                : "Credentials are sent as HTTP headers and stored in the device keychain.")
        }
    }

    private func shortLabel(for type: WebhookManager.AuthType) -> String {
        switch type {
        case .none: return String(localized: "None")
        case .apiKeyQuery: return String(localized: "Query")
        case .bearer: return String(localized: "Token")
        case .basic: return String(localized: "Basic")
        case .customHeader: return String(localized: "Header")
        }
    }

    // MARK: - Send mode

    /// One chip per way of sending: live, an interval, when a batch fills, or only on request.
    private enum Cadence: Hashable {
        case live, every(Int), whenFull, manual
    }

    private static let intervalChoices: [(Int, String)] = [
        (1, String(localized: "1 min")),
        (5, String(localized: "5 min")),
        (15, String(localized: "15 min")),
        (30, String(localized: "30 min")),
        (60, String(localized: "1 hour"))
    ]

    private static let batchSizeChoices = [5, 10, 25, 50, 100]

    private var cadenceChoices: [(Cadence, String)] {
        [(Cadence.live, String(localized: "Live"))]
            + Self.intervalChoices.map { (Cadence.every($0.0), $0.1) }
            + [(Cadence.whenFull, String(localized: "When full")), (Cadence.manual, String(localized: "Manual"))]
    }

    private var currentCadence: Cadence {
        switch webhook.sendMode {
        case .realtime: .live
        case .batchTime: .every(webhook.batchTimeMinutes)
        case .batchCount: .whenFull
        case .manual: .manual
        }
    }

    private func select(_ cadence: Cadence) {
        switch cadence {
        case .live: webhook.sendMode = .realtime
        case .every(let minutes):
            webhook.batchTimeMinutes = minutes
            webhook.sendMode = .batchTime
        case .whenFull: webhook.sendMode = .batchCount
        case .manual: webhook.sendMode = .manual
        }
    }

    private var usesBatchSize: Bool {
        webhook.sendMode == .batchTime || webhook.sendMode == .batchCount
    }

    private var sendModeSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            sectionHeading("HOW OFTEN")
            card {
                WebhookFlowLayout(spacing: 6) {
                    ForEach(cadenceChoices, id: \.0) { choice in
                        chip(choice.1, isSelected: currentCadence == choice.0) {
                            select(choice.0)
                        }
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityLabel("How often")

                hairline
                HStack(spacing: 12) {
                    Text("Points per batch")
                        .font(.subheadline)
                        .foregroundStyle(usesBatchSize ? TE.textPrimary : TE.textMuted)
                    Spacer(minLength: 8)
                    Menu {
                        Picker("Points per batch", selection: $webhook.batchCount) {
                            ForEach(Self.batchSizeChoices, id: \.self) { size in
                                Text(verbatim: size.formatted()).tag(size)
                            }
                        }
                    } label: {
                        HStack(spacing: 4) {
                            Text(verbatim: webhook.batchCount.formatted())
                                .font(TE.mono(.subheadline, weight: .medium))
                            Image(systemName: "chevron.up.chevron.down")
                                .font(.caption2)
                                .accessibilityHidden(true)
                        }
                        .foregroundStyle(usesBatchSize ? TE.textPrimary : TE.textMuted)
                        .frame(minWidth: 44, minHeight: 44, alignment: .trailing)
                        .contentShape(Rectangle())
                    }
                    .disabled(!usesBatchSize)
                    .accessibilityLabel("Points per batch")
                    .accessibilityValue(Text(verbatim: webhook.batchCount.formatted()))
                }
            }
            sectionFooter(sendModeFooter)
        }
    }

    private var hairline: some View {
        Rectangle()
            .fill(TE.dialTrack)
            .frame(height: 1)
            .accessibilityHidden(true)
    }

    private var sendModeFooter: LocalizedStringKey {
        switch webhook.sendMode {
        case .realtime:
            return "Each new GPS fix is POSTed immediately."
        case .batchCount:
            return "Points are queued in memory and sent when the batch is full. Unsent points are lost if the app is terminated."
        case .batchTime:
            return "Points are queued and sent on the interval, or sooner when a batch fills. Unsent points are lost if the app is terminated."
        case .manual:
            return "Data is only sent when you tap \"Send Now\" below."
        }
    }

    // MARK: - Status and actions

    private var statusSection: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 12) {
                let titleLayout = usesAccessibilityLayout
                    ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
                    : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 8))
                titleLayout {
                    Text(queueTitle)
                        .font(TE.editorial(.title2))
                    if !usesAccessibilityLayout { Spacer(minLength: 0) }
                    Text(verbatim: lastSentText)
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.onInkCardMuted)
                }
                .accessibilityElement(children: .combine)

                if let error = webhook.lastError {
                    statusMessage(error, systemImage: "xmark.circle")
                }

                let buttonLayout = usesAccessibilityLayout
                    ? AnyLayout(VStackLayout(spacing: 8))
                    : AnyLayout(HStackLayout(spacing: 8))
                // Primary row: Send now + Clear queue (artboard). With nothing queued,
                // Send now posts the saved history and Test connection takes the second slot.
                buttonLayout {
                    Button {
                        Task {
                            if hasQueuedPoints {
                                await webhook.flushBatch()
                            } else {
                                await webhook.sendNow()
                            }
                        }
                    } label: {
                        Text("Send now")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(TE.inkCard)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .background(Capsule().fill(TE.onInkCard))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .disabled(webhook.isSending)

                    if hasQueuedPoints {
                        inkOutlineButton("Clear queue") {
                            confirmingClearQueue = true
                        }
                    } else {
                        testConnectionButton
                    }
                }

                // Secondary actions stay out of the primary row.
                if hasQueuedPoints {
                    let secondaryLayout = usesAccessibilityLayout
                        ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
                        : AnyLayout(HStackLayout(spacing: 16))
                    secondaryLayout {
                        inkTextButton(isTesting ? "Testing…" : "Test connection") {
                            runConnectionTest()
                        }
                        .disabled(isTesting)
                        inkTextButton("Send all history") {
                            Task { await webhook.sendNow() }
                        }
                        .disabled(webhook.isSending)
                    }
                }

                if let result = testResult {
                    statusMessage(result, systemImage: "checkmark.circle")
                }
                if let error = testError {
                    statusMessage(error, systemImage: "xmark.circle")
                }
            }
            .foregroundStyle(TE.onInkCard)
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 18).fill(TE.inkCard))
            .padding(.horizontal, 16)
            .padding(.top, 20)

            sectionFooter(hasQueuedPoints
                ? "Send now posts the waiting points. Clear queue discards them without sending. Send all history posts all saved location data to the endpoint."
                : "Use Test Connection to verify the endpoint URL and credentials without sending real data. Send Now posts all saved location data to the endpoint.")
        }
    }

    private var testConnectionButton: some View {
        inkOutlineButton(isTesting ? "Testing…" : "Test connection") {
            runConnectionTest()
        }
        .disabled(isTesting)
    }

    private func runConnectionTest() {
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
    }

    private func inkTextButton(_ title: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(TE.mono(.caption, weight: .medium))
                .underline()
                .foregroundStyle(TE.onInkCardMuted)
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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

    private func inkOutlineButton(_ title: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(TE.onInkCard)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 10)
                .frame(maxWidth: .infinity, minHeight: 44)
                .overlay(Capsule().strokeBorder(TE.onInkCard.opacity(0.35), lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private func statusMessage(_ message: String, systemImage: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: systemImage)
                .font(.footnote)
                .accessibilityHidden(true)
            Text(message)
                .font(TE.mono(.caption))
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(TE.onInkCardMuted)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Building blocks

    private func sectionHeading(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .font(TE.mono(.caption2))
            .tracking(1.5)
            .foregroundStyle(TE.textMuted)
            .padding(.horizontal, 24)
            .padding(.top, 24)
            .padding(.bottom, 8)
            .accessibilityAddTraits(.isHeader)
    }

    private func sectionFooter(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(TE.textMuted)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 24)
            .padding(.top, 8)
    }

    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            content()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .padding(.horizontal, 16)
    }

    private func fieldLabel(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .font(TE.mono(.caption2))
            .tracking(1)
            .foregroundStyle(TE.textMuted)
    }

    private func textField(_ title: LocalizedStringKey, prompt: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            fieldLabel(title)
            TextField(prompt, text: text)
                .font(TE.mono(.subheadline))
                .autocorrectionDisabled(true)
                .textInputAutocapitalization(.never)
                .modifier(WebhookFieldStyle())
        }
    }

    private func secureField(_ title: LocalizedStringKey, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            fieldLabel(title)
            SecureField("••••••••", text: text)
                .font(TE.mono(.subheadline))
                .modifier(WebhookFieldStyle())
        }
    }

    private func chip(_ title: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(verbatim: title)
                .font(TE.mono(.caption, weight: .medium))
                .foregroundStyle(isSelected ? TE.surface : TE.textPrimary)
                .padding(.horizontal, 12)
                .frame(minHeight: 36)
                .background(Capsule().fill(isSelected ? TE.accent : Color.clear))
                .overlay(Capsule().strokeBorder(isSelected ? TE.accent : TE.border, lineWidth: 1))
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    /// A capsule segmented control; stacks vertically at accessibility sizes so labels never truncate.
    @ViewBuilder
    private func segmented<Value: Hashable>(
        _ items: [(Value, String, String)],
        selection: Binding<Value>,
        groupLabel: LocalizedStringKey
    ) -> some View {
        let layout = usesAccessibilityLayout
            ? AnyLayout(VStackLayout(spacing: 0))
            : AnyLayout(HStackLayout(spacing: 0))
        layout {
            ForEach(Array(items.enumerated()), id: \.offset) { _, item in
                let isSelected = selection.wrappedValue == item.0
                Button {
                    selection.wrappedValue = item.0
                } label: {
                    Text(verbatim: item.1)
                        .font(.caption.weight(.medium))
                        .lineLimit(usesAccessibilityLayout ? nil : 1)
                        .minimumScaleFactor(0.8)
                        .foregroundStyle(isSelected ? TE.surface : TE.textPrimary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(isSelected ? TE.accent : Color.clear)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(verbatim: item.2))
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 22))
        .overlay(RoundedRectangle(cornerRadius: 22).strokeBorder(TE.border, lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(groupLabel)
    }
}

private struct WebhookFieldStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .foregroundStyle(TE.textPrimary)
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(RoundedRectangle(cornerRadius: 10).fill(TE.surface))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(TE.border, lineWidth: 1))
    }
}

/// Wraps chips onto as many rows as the width needs.
private struct WebhookFlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let height = rows.map(\.height).reduce(0, +) + spacing * CGFloat(max(rows.count - 1, 0))
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width ?? width, height: height)
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
            let proposedWidth = current.indices.isEmpty ? itemWidth : current.width + spacing + itemWidth
            if proposedWidth > width, !current.indices.isEmpty {
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

#Preview {
    NavigationStack {
        WebhookSettingsView()
    }
}
