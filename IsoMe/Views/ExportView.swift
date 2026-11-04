import SwiftUI
import SwiftData
import ExportKit

/// The Archive tab: an export "recipe" written as a sentence whose underlined choices open the
/// full set of export options, with a sample of the files it will produce.
struct ExportView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Bindable var viewModel: LocationViewModel
    @StateObject private var exportFolderManager = ExportFolderManager.shared
    @StateObject private var dailyScheduler = DailyExportScheduler.shared
    @ObservedObject private var storeManager = StoreManager.shared
    @ObservedObject private var obsidianVault = ObsidianVaultStore.shared
    @ObservedObject private var webhookManager = WebhookManager.shared

    @State private var options = ExportPreferencesStore.load().options
    @State private var selectedFormats: Set<ExportFormat> = ExportPreferencesStore.load().selectedFormats
    @State private var showingPaywall = false
    @State private var showingPreview = false
    @State private var showingFolderPicker = false
    @State private var showingClearFolderConfirmation = false
    @State private var dailyTimeBinding = Date()
    @State private var cachedOutingSummaries: [RecordingSessionSummary] = []
    @State private var showingSettings = false
    @State private var editingToken: RecipeToken?
    @State private var showingSaveRecipe = false
    @State private var newRecipeName = ""
    @State private var livePreview: RecipeLivePreview = .idle
    @State private var showingVaultPicker = false
    @State private var isSendingToServer = false

    @AppStorage("useDefaultExportFolder") private var useDefaultExportFolder = true
    @AppStorage("exportFilenamePattern") private var filenamePattern = FilenameTemplate.defaultPattern
    @AppStorage("exportSavedRecipes") private var savedRecipesData = Data()
    @AppStorage("exportSeededExampleRecipes") private var seededExampleRecipes = false
    @AppStorage(ObsidianVaultStore.destinationDefaultsKey) private var destinationRaw = ArchiveDestination.files.rawValue

    private var destination: ArchiveDestination {
        ArchiveDestination(rawValue: destinationRaw) ?? .files
    }

    /// Above this many records the inline sample is skipped; the full preview sheet still works.
    private static let livePreviewItemLimit = 20_000

    private var selectedFormatsList: [ExportFormat] {
        let selected = ExportFormat.allCases.filter { selectedFormats.contains($0) }
        return selected.isEmpty ? [.json] : selected
    }

    private var primarySelectedFormat: ExportFormat {
        selectedFormatsList.first ?? .json
    }

    private var selectedFormatTokensKey: String {
        selectedFormatsList.map(\.token).joined(separator: ",")
    }

    private func effectiveDataKind(for format: ExportFormat) -> ExportOptions.DataKind {
        if format.isPointsOnly, options.dataKind != .outings {
            return .points
        }
        return options.dataKind
    }

    private var effectiveDataKinds: Set<ExportOptions.DataKind> {
        Set(selectedFormatsList.map { effectiveDataKind(for: $0) })
    }

    private func effectiveOptions(for format: ExportFormat) -> ExportOptions {
        var copy = options
        copy.format = format
        copy.dataKind = effectiveDataKind(for: format)
        return copy
    }

    private var filteredVisits: [Visit] {
        options.filterVisits(viewModel.allVisits)
    }

    private var filteredPoints: [LocationPoint] {
        options.filterPoints(viewModel.locationPoints)
    }

    private var filteredOutings: [RecordingSessionSummary] {
        options.filterOutings(cachedOutingSummaries)
    }

    private var totalCount: Int {
        if effectiveDataKinds.contains(.outings) {
            return filteredOutings.count
        }
        if effectiveDataKinds.contains(.all) {
            return filteredVisits.count + filteredPoints.count
        }

        var count = 0
        if effectiveDataKinds.contains(.visits) { count += filteredVisits.count }
        if effectiveDataKinds.contains(.points) { count += filteredPoints.count }
        return count
    }

    private var splitFileCount: Int {
        guard totalCount > 0 else { return 0 }
        guard options.splitByDay else { return selectedFormatsList.count }
        if showsOutingExport {
            return filteredOutings.count * selectedFormatsList.count
        }

        return selectedFormatsList.reduce(0) { partial, format in
            let formatOptions = effectiveOptions(for: format)
            return partial + formatOptions.groupByDay(visits: filteredVisits, points: filteredPoints).count
        }
    }

    private var showsVisitFields: Bool {
        effectiveDataKinds.contains(.visits) || effectiveDataKinds.contains(.all)
    }

    private var showsPointFields: Bool {
        effectiveDataKinds.contains(.points) || effectiveDataKinds.contains(.all)
    }

    private var showsOutingExport: Bool {
        effectiveDataKinds.contains(.outings)
    }

    var body: some View {
        stack
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if pinsFooterOutsideStack {
                    exportFooter
                }
            }
    }

    private var stack: some View {
        NavigationStack {
            Group {
                if usesDuoLayout {
                    duoLayout
                } else {
                    compactLayout
                }
            }
            .background(TE.surface.ignoresSafeArea())
            .foregroundStyle(TE.textPrimary)
            .toolbar(.hidden, for: .navigationBar)
            .modifier(FolderPresentations(
                isPickingFolder: presentation($showingFolderPicker, inOptionsSheet: false),
                isConfirmingRemoval: presentation($showingClearFolderConfirmation, inOptionsSheet: false),
                folderManager: exportFolderManager
            ))
            .sheet(isPresented: $showingPaywall) {
                PaywallView(storeManager: storeManager, context: .export)
            }
            .modifier(VaultPresentation(isPicking: presentation($showingVaultPicker, inOptionsSheet: false), vault: obsidianVault))
            .sheet(isPresented: $showingSettings) {
                SettingsView(viewModel: viewModel)
            }
            .sheet(item: $editingToken) { token in
                optionsSheet(for: token)
            }
            .sheet(isPresented: $showingPreview) {
                IsoMeExportPreviewView(
                    visits: viewModel.allVisits,
                    points: viewModel.locationPoints,
                    recordingSessions: viewModel.allRecordingSessions,
                    activeTrackingStart: viewModel.locationManager.trackingStartTime,
                    options: options,
                    selectedFormats: selectedFormatsList,
                    filenamePattern: filenamePattern,
                    destinationLabel: previewDestinationLabel,
                    destinationRootName: previewDestinationRootName,
                    totalItemCount: totalCount,
                    totalFileCount: splitFileCount
                )
            }
            .alert("Save Recipe", isPresented: $showingSaveRecipe) {
                TextField("Name", text: $newRecipeName)
                Button("Save") { saveCurrentRecipe() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Saved recipes appear above the sentence. Tap one to load its choices.")
            }
            .task(id: livePreviewKey) {
                // Let rapid changes settle before rendering sample files.
                try? await Task.sleep(nanoseconds: 350_000_000)
                guard !Task.isCancelled else { return }
                await buildLivePreview()
            }
            .onAppear {
                ensurePointDataIfNeeded()
                seedExampleRecipesIfNeeded()
                #if DEBUG
                if ProcessInfo.processInfo.arguments.contains("--demo-export-filters") {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                        editingToken = .range
                    }
                }
                #endif
            }
            .onChange(of: storeManager.canExport) { _, _ in ensurePointDataIfNeeded() }
            .onChange(of: options) { _, _ in persistExportPreferences() }
            .onChange(of: options.dataKind.rawValue) { _, _ in ensurePointDataIfNeeded() }
            .onChange(of: selectedFormatTokensKey) { _, _ in
                persistExportPreferences()
                ensurePointDataIfNeeded()
            }
            .onChange(of: viewModel.allRecordingSessions.count) { _, _ in refreshOutingSummariesIfNeeded() }
            .onChange(of: viewModel.totalLocationPointCount) { _, _ in refreshOutingSummariesIfNeeded() }
        }
    }

    /// Folder sheets are owned by whichever layer is frontmost, so the options sheet can present them too.
    private func presentation(_ flag: Binding<Bool>, inOptionsSheet: Bool) -> Binding<Bool> {
        Binding(
            get: { flag.wrappedValue && (editingToken != nil) == inOptionsSheet },
            set: { flag.wrappedValue = $0 }
        )
    }

    private var usesDuoLayout: Bool {
        horizontalSizeClass == .regular && !dynamicTypeSize.isAccessibilitySize
    }

    // MARK: - Layouts

    @ViewBuilder
    private var compactLayout: some View {
        if dynamicTypeSize.isAccessibilitySize {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    recipeComposer
                    recipeOutcome
                    listeningLink
                        .padding(.horizontal, 16)
                        .padding(.top, 12)
                    exportFooter
                        .padding(.top, 20)
                }
                .padding(.bottom, 170)
            }
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    recipeComposer
                    recipeOutcome
                    listeningLink
                        .padding(.horizontal, 16)
                        .padding(.top, 12)
                }
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
        }
    }

    /// The compact footer is pinned outside the NavigationStack so it stacks on top of the
    /// floating tab bar's inset; pinned inside, the tab bar's inset never reaches it.
    private var pinsFooterOutsideStack: Bool {
        !usesDuoLayout && !dynamicTypeSize.isAccessibilitySize
    }

    private var duoLayout: some View {
        HStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    recipeComposer
                    listeningLink
                        .padding(.horizontal, 24)
                        .padding(.top, 24)
                }
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .frame(maxWidth: .infinity)

            Rectangle()
                .fill(TE.dialTrack)
                .frame(width: 2)
                .ignoresSafeArea()
                .accessibilityHidden(true)

            VStack(spacing: 12) {
                livePreviewPane
                scheduleCard
                exportFooter
            }
            .padding(.top, 26)
            .padding(.horizontal, 8)
            .frame(maxWidth: .infinity)
        }
    }

    private var recipeComposer: some View {
        VStack(alignment: .leading, spacing: 0) {
            recipeHeader
            recipeChips
                .padding(.top, 14)
            recipeSentence
                .padding(.horizontal, 24)
                .padding(.top, 22)
            recipeHint
                .padding(.horizontal, 24)
                .padding(.top, 8)
        }
    }

    private var recipeOutcome: some View {
        VStack(spacing: 12) {
            outcomeCard
            scheduleCard
        }
        .padding(.horizontal, 16)
        .padding(.top, 18)
    }

    /// "How Whereday listens": what gets recorded, so the archive's contents are never a surprise.
    private var listeningLink: some View {
        NavigationLink {
            ListeningView(viewModel: viewModel)
        } label: {
            HStack(spacing: 12) {
                Image(systemName: "ear")
                    .font(.body)
                    .foregroundStyle(TE.textMuted)
                    .frame(width: 24)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("How Whereday listens")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(TE.textPrimary)
                    Text("WHAT GETS RECORDED · WHAT STAYS ON THIS IPHONE")
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(TE.textMuted)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
    }

    // MARK: - Header

    private var recipeHeader: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 2) {
                Text("ARCHIVE · YOUR DATA, YOUR FILES")
                    .font(TE.mono(.caption2))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
                Group {
                    if let recipe = matchingRecipe {
                        Text(verbatim: recipe.name)
                    } else {
                        Text("New recipe")
                    }
                }
                .font(TE.editorial(.largeTitle))
                .lineLimit(2)
                .accessibilityAddTraits(.isHeader)
            }
            Spacer(minLength: 12)
            // Regular width shows the navigation rail (MainTabView's `usesRail`), which carries
            // Settings itself; the gear only stands in for it beside the closed tab bar.
            if horizontalSizeClass != .regular {
                Button {
                    showingSettings = true
                } label: {
                    Image(systemName: "gearshape")
                        .font(.system(size: 18, weight: .light))
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(TE.card))
                        .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Settings")
            }
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
    }

    // MARK: - Saved recipes

    private var recipeChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(savedRecipes) { recipe in
                    let isCurrent = recipe.id == matchingRecipe?.id
                    Button {
                        apply(recipe)
                    } label: {
                        Text(verbatim: recipe.name)
                            .font(.caption.weight(.medium))
                            .foregroundStyle(TE.textPrimary)
                            .lineLimit(1)
                            .padding(.horizontal, 12)
                            .frame(minHeight: 36)
                            .background(Capsule().fill(TE.card))
                            .overlay(Capsule().strokeBorder(isCurrent ? TE.inkRust : TE.border, lineWidth: 1))
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityValue(isCurrent ? Text("Loaded") : Text(""))
                    .accessibilityHint("Loads this saved recipe")
                    .contextMenu {
                        Button(role: .destructive) {
                            deleteRecipe(recipe)
                        } label: {
                            Label("Delete Recipe", systemImage: "trash")
                        }
                    }
                    .accessibilityAction(named: "Delete Recipe") { deleteRecipe(recipe) }
                }

                if matchingRecipe == nil {
                    Button {
                        newRecipeName = suggestedRecipeName
                        showingSaveRecipe = true
                    } label: {
                        Text("+ Save this")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(TE.textMuted)
                            .lineLimit(1)
                            .padding(.horizontal, 12)
                            .frame(minHeight: 36)
                            .overlay(
                                Capsule().strokeBorder(TE.dialTick, style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
                            )
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Save this recipe")
                }
            }
            .padding(.horizontal, 24)
        }
    }

    // MARK: - Sentence

    private var recipeSentence: some View {
        Text(sentenceText)
            .font(TE.editorial(.title))
            .lineSpacing(6)
            .foregroundStyle(TE.textPrimary)
            .tint(TE.inkRust)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .environment(\.openURL, OpenURLAction { url in
                if let token = RecipeToken(url: url) {
                    handle(token)
                }
                return .handled
            })
            .accessibilityAction(named: "Change data") { handle(.what) }
            .accessibilityAction(named: "Change date range") { handle(.range) }
            .accessibilityAction(named: "Change format") { handle(.format) }
            .accessibilityAction(named: "Change file layout") { handle(.layout) }
            .accessibilityAction(named: "Change destination") { handle(.destination) }
    }

    /// Builds the recipe sentence from one localized template so translations can reorder the choices.
    private var sentenceText: AttributedString {
        let markers = (0..<5).map { "\u{E000}\($0)\u{E001}" }
        let template = String(
            localized: "Export my \(markers[0]) from \(markers[1]) as \(markers[2]), \(markers[3]), into \(markers[4]).",
            comment: "Archive recipe sentence. Each placeholder is a tappable choice: data, date range, format, file layout, destination."
        )
        let tokens: [(RecipeToken, String)] = [
            (.what, whatPhrase),
            (.range, rangePhrase),
            (.format, formatPhrase),
            (.layout, layoutPhrase),
            (.destination, destinationPhrase)
        ]

        var result = AttributedString()
        var remaining = template[...]
        while let open = remaining.firstIndex(of: "\u{E000}") {
            result += AttributedString(String(remaining[..<open]))
            let afterOpen = remaining.index(after: open)
            guard let close = remaining[afterOpen...].firstIndex(of: "\u{E001}"),
                  let index = Int(remaining[afterOpen..<close]),
                  tokens.indices.contains(index) else {
                remaining = remaining[afterOpen...]
                continue
            }
            let (token, phrase) = tokens[index]
            var choice = AttributedString(phrase)
            choice.link = token.url
            choice.swiftUI.foregroundColor = TE.inkRust
            choice.swiftUI.underlineStyle = Text.LineStyle(pattern: .solid, color: TE.placeInks[1])
            result += choice
            remaining = remaining[remaining.index(after: close)...]
        }
        result += AttributedString(String(remaining))
        return result
    }

    private var recipeHint: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 12))
        return layout {
            Text("TAP ANY UNDERLINED WORD TO CHANGE IT")
                .font(TE.mono(.caption2))
                .tracking(1)
                .foregroundStyle(TE.textMuted)
                .fixedSize(horizontal: false, vertical: true)
            if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 0) }
            Button {
                editingToken = .all
            } label: {
                Text("ALL OPTIONS")
                    .font(TE.mono(.caption2, weight: .semibold))
                    .tracking(1)
                    .foregroundStyle(TE.inkRust)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Shows filters, fields, file path and every other export option")
        }
    }

    private var whatPhrase: String {
        switch options.dataKind {
        case .visits: return String(localized: "visits")
        case .points: return String(localized: "routes")
        case .outings: return String(localized: "outings")
        case .all: return String(localized: "visits & routes")
        }
    }

    private var rangePhrase: String {
        switch options.datePreset {
        case .allTime: return String(localized: "all time")
        case .today: return String(localized: "today")
        case .yesterday: return String(localized: "yesterday")
        case .last7Days: return String(localized: "the last 7 days")
        case .last30Days: return String(localized: "the last 30 days")
        case .thisMonth: return String(localized: "this month")
        case .month:
            return Self.monthName(options.presetAnchor ?? Date())
        case .year:
            let year = Calendar.current.component(.year, from: options.presetAnchor ?? Date())
            return String(localized: "all of \(String(year))")
        case .custom:
            let lower = min(options.customStart, options.customEnd)
            let upper = max(options.customStart, options.customEnd)
            let calendar = Calendar.current
            let sameYear = calendar.isDate(lower, equalTo: upper, toGranularity: .year)
                && calendar.isDate(upper, equalTo: Date(), toGranularity: .year)
            let style: Date.FormatStyle = sameYear
                ? .dateTime.month(.abbreviated).day()
                : .dateTime.month(.abbreviated).day().year()
            if calendar.isDate(lower, inSameDayAs: upper) {
                return lower.formatted(style)
            }
            return "\(lower.formatted(style)) – \(upper.formatted(style))"
        }
    }

    private var formatPhrase: String {
        selectedFormatsList.map(\.displayName).formatted(.list(type: .and))
    }

    private var layoutPhrase: String {
        guard options.splitByDay else { return String(localized: "all in one file") }
        return showsOutingExport ? String(localized: "one file per outing") : String(localized: "one file per day")
    }

    private var destinationPhrase: String {
        switch destination {
        case .files:
            if exportFolderManager.hasDefaultFolder && useDefaultExportFolder {
                return String(localized: "Files › \(exportFolderManager.selectedFolderName ?? String(localized: "your folder"))")
            }
            return String(localized: "the share sheet")
        case .obsidian:
            return String(localized: "my Obsidian vault")
        case .server:
            return serverIsDawarich || !serverIsConfigured
                ? String(localized: "my Dawarich server")
                : String(localized: "my server")
        }
    }

    static func monthName(_ date: Date, now: Date = Date()) -> String {
        let sameYear = Calendar.current.isDate(date, equalTo: now, toGranularity: .year)
        return sameYear
            ? date.formatted(.dateTime.month(.wide))
            : date.formatted(.dateTime.month(.wide).year())
    }

    // MARK: - Server destination

    private var serverIsConfigured: Bool {
        !webhookManager.urlString.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Dawarich takes OwnTracks and Overland posts at /api/v1/owntracks and /api/v1/overland.
    private var serverIsDawarich: Bool {
        let url = webhookManager.urlString.lowercased()
        return url.contains("dawarich") || url.contains("/api/v1/owntracks") || url.contains("/api/v1/overland")
    }

    private var serverHost: String? {
        URL(string: webhookManager.urlString.trimmingCharacters(in: .whitespacesAndNewlines))?.host()
    }

    /// One request per "file": each day's points when split by day, otherwise all of them at once.
    /// The server receives route points in its configured webhook format; visits stay on this iPhone.
    private var serverBatches: [[LocationPoint]] {
        let points = filteredPoints
        guard !points.isEmpty else { return [] }
        guard options.splitByDay else { return [points] }
        let calendar = Calendar.current
        return Dictionary(grouping: points) { calendar.startOfDay(for: $0.timestamp) }
            .sorted { $0.key < $1.key }
            .map { $0.value.sorted { $0.timestamp < $1.timestamp } }
    }

    private func handle(_ token: RecipeToken) {
        if token == .layout {
            options.splitByDay.toggle()
        } else {
            editingToken = token
        }
    }

    // MARK: - What you'll get

    private var outcomeCard: some View {
        Button {
            ensurePointDataIfNeeded()
            showingPreview = true
        } label: {
            VStack(alignment: .leading, spacing: 8) {
                let layout = dynamicTypeSize.isAccessibilitySize
                    ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
                    : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 8))
                layout {
                    Text("WHAT YOU'LL GET")
                        .font(TE.mono(.caption2))
                        .tracking(1.4)
                        .foregroundStyle(TE.textMuted)
                    if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 0) }
                    Text(verbatim: outcomeSummary)
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.textPrimary)
                }

                livePreviewRows
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 18))
        }
        .buttonStyle(.plain)
        .disabled(totalCount == 0)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Shows the files and contents that will be exported")
    }

    @ViewBuilder
    private var livePreviewRows: some View {
        switch livePreview {
        case .idle, .loading:
            HStack(spacing: 10) {
                ProgressView()
                    .controlSize(.small)
                    .tint(TE.textMuted)
                Text("Building preview…")
                    .font(TE.mono(.caption))
                    .foregroundStyle(TE.textMuted)
            }
        case .empty:
            Text("Nothing matches this recipe yet.")
                .font(TE.editorial(.body))
                .foregroundStyle(TE.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        case .tooLarge:
            previewNote("Too much data to sample here. Tap to open the full preview.")
        case .failed:
            previewNote("Couldn't sample these files. Tap to open the full preview.")
        case .ready(let files, _):
            let shown = Array(files.prefix(4))
            ForEach(shown) { file in
                HStack(spacing: 10) {
                    Image(systemName: "doc")
                        .font(.footnote)
                        .foregroundStyle(TE.textMuted)
                        .accessibilityHidden(true)
                    Text(verbatim: file.filename)
                        .font(TE.mono(.caption))
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(verbatim: file.sizeLabel)
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.textMuted)
                }
            }
            let remaining = max(0, splitFileCount - shown.count)
            if remaining > 0 {
                Text(remaining == 1 ? LocalizedStringKey("+ 1 more file") : LocalizedStringKey("+ \(remaining) more files"))
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
                    .padding(.leading, 26)
            }
        }
    }

    private func previewNote(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.footnote)
            .foregroundStyle(TE.textMuted)
            .fixedSize(horizontal: false, vertical: true)
    }

    private var outcomeSummary: String {
        if showsOutingExport {
            return String(localized: "\(filteredOutings.count.formatted()) outings")
        }
        var parts: [String] = []
        if showsVisitFields {
            parts.append(String(localized: "\(filteredVisits.count.formatted()) visits"))
        }
        if showsPointFields {
            parts.append(String(localized: "\(filteredPoints.count.formatted()) pts"))
        }
        return parts.joined(separator: " · ")
    }

    /// The two-pane archive shows the first sample file's contents next to the sentence.
    private var livePreviewPane: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(livePreviewTitle)
                    .font(TE.mono(.caption2))
                    .tracking(1.4)
                    .foregroundStyle(TE.textMuted)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer(minLength: 0)
                if case .ready = livePreview, splitFileCount > 0 {
                    Text("1 of \(splitFileCount) files")
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.textPrimary)
                }
            }

            if case .ready(_, let content?) = livePreview {
                ScrollView {
                    Text(verbatim: content)
                        .font(TE.mono(.caption))
                        .lineSpacing(4)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            } else {
                livePreviewRows
                Spacer(minLength: 0)
            }

            Button {
                ensurePointDataIfNeeded()
                showingPreview = true
            } label: {
                Text("OPEN FULL PREVIEW")
                    .font(TE.mono(.caption2, weight: .semibold))
                    .tracking(1)
                    .foregroundStyle(TE.inkRust)
                    .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            .disabled(totalCount == 0)
            .accessibilityLabel("Preview Export")
            .accessibilityHint("Shows the files and contents that will be exported")
        }
        .padding(.horizontal, 18)
        .padding(.top, 16)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .padding(.horizontal, 8)
    }

    private var livePreviewTitle: String {
        if case .ready(let files, _) = livePreview, let first = files.first {
            return String(localized: "PREVIEW · \(first.filename)")
        }
        return String(localized: "PREVIEW")
    }

    private var livePreviewKey: LivePreviewKey {
        LivePreviewKey(
            options: options,
            formats: selectedFormatsList,
            filenamePattern: filenamePattern,
            visitCount: viewModel.allVisits.count,
            pointCount: viewModel.locationPoints.count,
            sessionCount: viewModel.allRecordingSessions.count,
            outingCount: cachedOutingSummaries.count,
            rootName: previewDestinationRootName
        )
    }

    @MainActor
    private func buildLivePreview() async {
        guard totalCount > 0 else {
            livePreview = .empty
            return
        }
        guard totalCount <= Self.livePreviewItemLimit else {
            livePreview = .tooLarge
            return
        }
        if case .ready = livePreview {} else { livePreview = .loading }

        do {
            let preview = try await IsoMeExportKitAdapter.preview(
                visits: viewModel.allVisits,
                points: viewModel.locationPoints,
                recordingSessions: viewModel.allRecordingSessions,
                activeTrackingStart: viewModel.locationManager.trackingStartTime,
                options: options,
                selectedFormats: selectedFormatsList,
                filenamePattern: filenamePattern
            )
            guard !Task.isCancelled else { return }
            let rootName = previewDestinationRootName
            let files = preview.records.flatMap { record in
                record.files.map { IsoMePreviewFile(plannedFile: $0, rootName: rootName) }
            }
            guard let first = files.first else {
                livePreview = .empty
                return
            }
            let content = String(first.plannedFile.displayContent().text.prefix(4_000))
            livePreview = .ready(files, firstContent: content)
        } catch {
            livePreview = .failed
        }
    }

    // MARK: - Schedule

    /// Scheduled files go to the Obsidian vault when that's the destination, otherwise the export folder.
    private var hasScheduleTarget: Bool {
        (destination == .obsidian && obsidianVault.hasVault) || exportFolderManager.hasDefaultFolder
    }

    private var scheduleCard: some View {
        VStack(spacing: 0) {
            if storeManager.canExport && hasScheduleTarget {
                Toggle(isOn: Binding(
                    get: { dailyScheduler.isEnabled },
                    set: { dailyScheduler.setEnabledFromUserSetup($0) }
                )) {
                    scheduleLabel(title: scheduleTitle, detail: scheduleDetail)
                }
                .toggleStyle(TEToggleStyle())
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .frame(minHeight: 56)

                if dailyScheduler.isEnabled {
                    Rectangle().fill(TE.border).frame(height: 1).padding(.leading, 16)
                    Button {
                        editingToken = .schedule
                    } label: {
                        HStack {
                            Text("Change schedule")
                                .font(.subheadline)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(TE.textMuted)
                                .accessibilityHidden(true)
                        }
                        .padding(.horizontal, 16)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            } else {
                Button {
                    if storeManager.canExport {
                        editingToken = .schedule
                    } else {
                        showingPaywall = true
                    }
                } label: {
                    HStack(spacing: 12) {
                        scheduleLabel(
                            title: String(localized: "Repeat automatically"),
                            detail: storeManager.canExport
                                ? String(localized: "Choose a folder for scheduled files first")
                                : String(localized: "Part of a Whereday plan")
                        )
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(TE.textMuted)
                            .accessibilityHidden(true)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .frame(minHeight: 56)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
    }

    private func scheduleLabel(title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(TE.textPrimary)
            Text(verbatim: detail)
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var scheduleTitle: String {
        var components = DateComponents()
        components.hour = dailyScheduler.hour
        components.minute = dailyScheduler.minute
        let time = (Calendar.current.date(from: components) ?? Date()).formatted(date: .omitted, time: .shortened)
        if dailyScheduler.isIntervalSchedule {
            return String(localized: "Repeat every \(dailyScheduler.intervalHours) h from \(time)")
        }
        return String(localized: "Repeat every night at \(time)")
    }

    private var scheduleDetail: String {
        let mode: ScheduledExportFileMode = dailyScheduler.isIntervalSchedule ? dailyScheduler.fileMode : .rewrite
        let modeText = mode == .append
            ? String(localized: "Appends only what's new")
            : String(localized: "Rewrites the day's file")
        return "\(modeText) · \(dailyScheduler.format.displayName)"
    }

    /// The scheduler keeps its own format and data; this offers to align them with the sentence.
    @ViewBuilder
    private var matchScheduleToRecipeRow: some View {
        if dailyScheduler.format != primarySelectedFormat || dailyScheduler.dataKind != options.dataKind {
            Button {
                dailyScheduler.format = primarySelectedFormat
                dailyScheduler.dataKind = options.dataKind
            } label: {
                Text("Use this recipe's format and data")
            }
            .buttonStyle(ArchiveCapsuleButtonStyle(filled: false))
            .padding(.horizontal, 16)
            .padding(.top, 16)
        }
    }

    // MARK: - Option sheets

    private func optionsSheet(for token: RecipeToken) -> some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    sheetSections(for: token)
                }
                .padding(.bottom, 32)
            }
            .background(TE.surface.ignoresSafeArea())
            .navigationTitle(token.sheetTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { editingToken = nil }
                }
            }
            .modifier(FolderPresentations(
                isPickingFolder: presentation($showingFolderPicker, inOptionsSheet: true),
                isConfirmingRemoval: presentation($showingClearFolderConfirmation, inOptionsSheet: true),
                folderManager: exportFolderManager
            ))
            .modifier(VaultPresentation(isPicking: presentation($showingVaultPicker, inOptionsSheet: true), vault: obsidianVault))
        }
        .presentationDetents(token == .all ? [.large] : [.medium, .large])
    }

    @ViewBuilder
    private func sheetSections(for token: RecipeToken) -> some View {
        switch token {
        case .what:
            dataKindSection
            if showsVisitFields || showsPointFields { filtersSection }
            if showsVisitFields { visitFieldsSection }
            if showsPointFields { pointFieldsSection }
            if showsOutingExport { outingFieldsSection }
        case .range:
            dateRangeSection
                .id("export-date-range")
            timeOfDaySection
        case .format:
            formatSection
        case .layout:
            outputSection
            filenameSection
        case .destination:
            destinationChoiceSection
            switch destination {
            case .files:
                exportFolderSection
                filenameSection
            case .obsidian:
                obsidianVaultSection
                filenameSection
            case .server:
                serverSection
            }
        case .schedule:
            if hasScheduleTarget && storeManager.canExport {
                dailyExportSection
                matchScheduleToRecipeRow
            } else {
                exportFolderSection
            }
        case .all:
            exportSections
        }
    }

    // MARK: - Saved recipe storage

    private var savedRecipes: [SavedExportRecipe] {
        (try? JSONDecoder().decode([SavedExportRecipe].self, from: savedRecipesData)) ?? []
    }

    private var matchingRecipe: SavedExportRecipe? {
        savedRecipes.first { recipe in
            recipe.options == options
                && Set(recipe.formats) == Set(selectedFormatsList)
                && recipe.filenamePattern == filenamePattern
                && (recipe.destination ?? .files) == destination
        }
    }

    private var suggestedRecipeName: String {
        let range = rangePhrase
        return "\(formatPhrase), \(range.prefix(1).uppercased() + range.dropFirst())"
    }

    private func apply(_ recipe: SavedExportRecipe) {
        options = recipe.options
        selectedFormats = Set(recipe.formats.isEmpty ? [.json] : recipe.formats)
        options.format = primarySelectedFormat
        filenamePattern = recipe.filenamePattern
        destinationRaw = (recipe.destination ?? .files).rawValue
        ensurePointDataIfNeeded()
    }

    /// First visit: two example recipes show what a recipe can be. They delete like any other.
    private func seedExampleRecipesIfNeeded() {
        guard !seededExampleRecipes else { return }
        seededExampleRecipes = true
        var recipes = savedRecipes
        let names = Set(recipes.map(\.name))
        recipes.append(contentsOf: SavedExportRecipe.examples.filter { !names.contains($0.name) })
        storeRecipes(recipes)
    }

    private func saveCurrentRecipe() {
        let name = newRecipeName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return }
        var recipes = savedRecipes
        recipes.append(SavedExportRecipe(
            id: UUID(),
            name: name,
            options: options,
            formats: selectedFormatsList,
            filenamePattern: filenamePattern,
            destination: destination
        ))
        storeRecipes(recipes)
    }

    private func deleteRecipe(_ recipe: SavedExportRecipe) {
        storeRecipes(savedRecipes.filter { $0.id != recipe.id })
    }

    private func storeRecipes(_ recipes: [SavedExportRecipe]) {
        savedRecipesData = (try? JSONEncoder().encode(recipes)) ?? Data()
    }

    private var exportSections: some View {
        VStack(spacing: 0) {
            formatSection
            dataKindSection
            webhookSection
            exportFolderSection
            if hasScheduleTarget && storeManager.canExport {
                dailyExportSection
            }
            filenameSection
            outputSection
            dateRangeSection
                .id("export-date-range")
            timeOfDaySection
            if showsVisitFields || showsPointFields { filtersSection }
            if showsVisitFields { visitFieldsSection }
            if showsPointFields { pointFieldsSection }
            if showsOutingExport { outingFieldsSection }
        }
    }

    // MARK: - Locked state

    private var lockedState: some View {
        VStack(spacing: 18) {
            Image(systemName: "lock.fill")
                .font(.title.weight(.light))
                .foregroundStyle(TE.textMuted)

            Text("EXPORT LOCKED")
                .font(TE.mono(.caption, weight: .bold))
                .tracking(2)
                .foregroundStyle(TE.textPrimary)

            Text("Your existing free tracking access is preserved. Choose a plan to unlock data export.")
                .font(TE.mono(.caption2, weight: .medium))
                .foregroundStyle(TE.textMuted)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.horizontal, 32)

            Button {
                showingPaywall = true
            } label: {
                Text("UNLOCK EXPORT")
            }
            .buttonStyle(TEActionButtonStyle(emphasis: .primary))

            Button {
                Task { await storeManager.restorePurchases() }
            } label: {
                Text("RESTORE PURCHASE")
                    .font(TE.mono(.caption2, weight: .semibold))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
            }
            .buttonStyle(.plain)
            .padding(.top, 4)
        }
        .padding()
    }

    // MARK: - Format

    private var formatSection: some View {
        ExportFormatSection(
            selectedFormats: selectedFormats,
            selectedFormatsList: selectedFormatsList,
            dataKind: options.dataKind,
            toggleFormat: toggleFormat
        )
    }

    // MARK: - Data Kind

    private var dataKindSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "DATA")

            TECard(paperOpacity: 0.97) {
                if dynamicTypeSize.isAccessibilitySize {
                    segmentedButton("VISITS", isSelected: options.dataKind == .visits) { selectDataKind(.visits) }
                    Rectangle().fill(TE.border).frame(height: 1)
                    segmentedButton("POINTS", isSelected: options.dataKind == .points) { selectDataKind(.points) }
                    Rectangle().fill(TE.border).frame(height: 1)
                    segmentedButton("OUTINGS", isSelected: options.dataKind == .outings) { selectDataKind(.outings) }
                    Rectangle().fill(TE.border).frame(height: 1)
                    segmentedButton("ALL", isSelected: options.dataKind == .all) { selectDataKind(.all) }
                } else {
                    HStack(spacing: 0) {
                        segmentedButton("VISITS", isSelected: options.dataKind == .visits) { selectDataKind(.visits) }
                        Rectangle().fill(TE.border).frame(width: 1)
                        segmentedButton("POINTS", isSelected: options.dataKind == .points) { selectDataKind(.points) }
                        Rectangle().fill(TE.border).frame(width: 1)
                        segmentedButton("OUTINGS", isSelected: options.dataKind == .outings) { selectDataKind(.outings) }
                        Rectangle().fill(TE.border).frame(width: 1)
                        segmentedButton("ALL", isSelected: options.dataKind == .all) { selectDataKind(.all) }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    // MARK: - Destinations

    private var destinationChoiceSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "SEND TO")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    ForEach(Array(ArchiveDestination.allCases.enumerated()), id: \.element) { index, choice in
                        TERow(showDivider: index < ArchiveDestination.allCases.count - 1) {
                            Button {
                                selectDestination(choice)
                            } label: {
                                HStack(spacing: 10) {
                                    Image(systemName: choice.systemImage)
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(TE.accent)
                                        .frame(width: 20)
                                        .accessibilityHidden(true)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(choice.title)
                                            .font(TE.mono(.caption, weight: .medium))
                                            .tracking(1)
                                            .foregroundStyle(TE.textPrimary)
                                        Text(choice.detail)
                                            .font(TE.mono(.caption2))
                                            .foregroundStyle(TE.textMuted)
                                            .fixedSize(horizontal: false, vertical: true)
                                    }
                                    Spacer(minLength: 8)
                                    if destination == choice {
                                        Image(systemName: "checkmark")
                                            .font(.caption.weight(.bold))
                                            .foregroundStyle(TE.accent)
                                    }
                                }
                                .frame(maxWidth: .infinity, minHeight: 36, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(destination == choice ? .isSelected : [])
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    /// Obsidian reads plain Markdown, so the vault gets one Markdown note per day.
    private func selectDestination(_ choice: ArchiveDestination) {
        destinationRaw = choice.rawValue
        if choice == .obsidian {
            selectedFormats = [.markdown]
            options.format = .markdown
            if options.dataKind == .outings { options.dataKind = .all }
            options.splitByDay = true
            if !obsidianVault.hasVault { showingVaultPicker = true }
        }
    }

    private var obsidianVaultSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "OBSIDIAN VAULT")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow(showDivider: obsidianVault.hasVault) {
                        Button {
                            showingVaultPicker = true
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: obsidianVault.hasVault ? "books.vertical.fill" : "folder.badge.plus")
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(TE.accent)
                                Text(obsidianVault.vaultName.map { $0.uppercased() } ?? String(localized: "CHOOSE VAULT FOLDER"))
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(0.5)
                                    .foregroundStyle(obsidianVault.hasVault ? TE.textPrimary : TE.accent)
                                    .lineLimit(2)
                                    .truncationMode(.middle)
                                Spacer(minLength: 12)
                                if obsidianVault.hasVault {
                                    Text("CHANGE")
                                        .font(TE.mono(.caption2, weight: .semibold))
                                        .tracking(1)
                                        .foregroundStyle(TE.accent)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    if obsidianVault.hasVault {
                        TERow(showDivider: false) {
                            Button {
                                obsidianVault.clear()
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "folder.badge.minus")
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(TE.danger)
                                    Text("FORGET VAULT")
                                        .font(TE.mono(.caption, weight: .medium))
                                        .tracking(1)
                                        .foregroundStyle(TE.danger)
                                    Spacer()
                                }
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Pick your vault, or a folder inside it, in Files. Each day becomes a Markdown note; the file path below decides where in the vault it lands. Nothing leaves this iPhone except through Files.")
        }
    }

    private var serverSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "SERVER")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow {
                        HStack(spacing: 8) {
                            Image(systemName: "server.rack")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(TE.accent)
                            Text(serverHost.map { $0.uppercased() } ?? String(localized: "NOT CONNECTED"))
                                .font(TE.mono(.caption, weight: .medium))
                                .tracking(0.5)
                                .foregroundStyle(serverIsConfigured ? TE.textPrimary : TE.textMuted)
                                .lineLimit(1)
                                .truncationMode(.middle)
                            Spacer(minLength: 8)
                            if serverIsConfigured {
                                Text(webhookManager.format.displayName.uppercased())
                                    .font(TE.mono(.caption2, weight: .semibold))
                                    .foregroundStyle(TE.textMuted)
                            }
                        }
                    }
                    if let error = webhookManager.lastError {
                        TERow {
                            Text(verbatim: error)
                                .font(TE.mono(.caption2))
                                .foregroundStyle(TE.inkRust)
                                .fixedSize(horizontal: false, vertical: true)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    TERow(showDivider: false) {
                        NavigationLink {
                            WebhookSettingsView()
                        } label: {
                            HStack(spacing: 8) {
                                Text(serverIsConfigured ? "SERVER SETTINGS" : "CONNECT DAWARICH OR ANOTHER SERVER")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.accent)
                                    .fixedSize(horizontal: false, vertical: true)
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(TE.accent.opacity(0.5))
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Sends this recipe's route points to the server set up under Webhook, in its format and with its sign-in. For Dawarich, use OwnTracks with /api/v1/owntracks/points. Visits stay on this iPhone; one request goes out per file.")
        }
    }

    // MARK: - Webhook

    private var webhookSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "WEBHOOK")

            TECard(paperOpacity: 0.97) {
                TERow(showDivider: false) {
                    NavigationLink {
                        WebhookSettingsView()
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(TE.accent)
                            Text("HTTP ENDPOINT")
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
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "POST all data types (visits, points, or both) to an external API or self-hosted server in real-time or batches. Supports OwnTracks, Overland, Dawarich, and generic JSON endpoints.")
        }
    }

    // MARK: - Export Folder

    private var exportFolderSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "EXPORT FOLDER")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    if let folderName = exportFolderManager.selectedFolderName {
                        TERow {
                            if dynamicTypeSize.isAccessibilitySize {
                                VStack(alignment: .leading, spacing: 10) {
                                    HStack(spacing: 8) {
                                        Image(systemName: "folder.fill")
                                            .font(.caption.weight(.medium))
                                            .foregroundStyle(TE.accent)
                                        Text(folderName.uppercased())
                                            .font(TE.mono(.caption, weight: .medium))
                                            .tracking(0.5)
                                            .foregroundStyle(TE.textPrimary)
                                            .lineLimit(2)
                                            .truncationMode(.middle)
                                    }

                                    Button {
                                        showingFolderPicker = true
                                    } label: {
                                        Text("CHANGE")
                                            .font(TE.mono(.caption2, weight: .semibold))
                                            .tracking(0.5)
                                            .foregroundStyle(TE.accent)
                                            .fixedSize(horizontal: false, vertical: true)
                                    }
                                    .buttonStyle(.plain)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                            } else {
                                HStack(spacing: 8) {
                                    Image(systemName: "folder.fill")
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(TE.accent)
                                    Text(folderName.uppercased())
                                        .font(TE.mono(.caption, weight: .medium))
                                        .tracking(0.5)
                                        .foregroundStyle(TE.textPrimary)
                                        .lineLimit(1)
                                        .truncationMode(.middle)
                                    Spacer(minLength: 12)
                                    Button {
                                        showingFolderPicker = true
                                    } label: {
                                        Text("CHANGE")
                                            .font(TE.mono(.caption2, weight: .semibold))
                                            .tracking(1)
                                            .foregroundStyle(TE.accent)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }

                        TERow {
                            toggleRow("AUTO-SAVE", isOn: $useDefaultExportFolder)
                        }

                        TERow(showDivider: false) {
                            Button {
                                showingClearFolderConfirmation = true
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "folder.badge.minus")
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(TE.danger)
                                    Text("REMOVE FOLDER")
                                        .font(TE.mono(.caption, weight: .medium))
                                        .tracking(1)
                                        .foregroundStyle(TE.danger)
                                    Spacer()
                                    Image(systemName: "arrow.right")
                                        .font(.caption2.weight(.bold))
                                        .foregroundStyle(TE.danger.opacity(0.5))
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    } else {
                        TERow(showDivider: false) {
                            Button {
                                showingFolderPicker = true
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "folder.badge.plus")
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(TE.accent)
                                    Text("SELECT FOLDER")
                                        .font(TE.mono(.caption, weight: .medium))
                                        .tracking(1)
                                        .foregroundStyle(TE.accent)
                                    Spacer()
                                    Image(systemName: "arrow.right")
                                        .font(.caption2.weight(.bold))
                                        .foregroundStyle(TE.accent.opacity(0.5))
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: exportFolderManager.hasDefaultFolder
                ? "Exports save directly to this folder when auto-save is on. Otherwise the share sheet opens."
                : "Set a default folder to save exports without the share sheet.")
        }
    }

    // MARK: - Daily Export

    private var dailyExportSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "AUTO EXPORT")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow(showDivider: dailyScheduler.isEnabled) {
                        toggleRow("ENABLE", isOn: Binding(
                            get: { dailyScheduler.isEnabled },
                            set: { dailyScheduler.setEnabledFromUserSetup($0) }
                        ))
                    }

                    if dailyScheduler.isEnabled {
                        TERow {
                            HStack {
                                Text("REPEAT")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)
                                Spacer()
                                Picker("", selection: Binding(
                                    get: { dailyScheduler.intervalHours },
                                    set: { dailyScheduler.intervalHours = IntervalExportScheduleDateMath.clampIntervalHours($0) }
                                )) {
                                    Text("ONCE DAILY").tag(0)
                                    ForEach(1...23, id: \.self) { hours in
                                        Text("EVERY \(hours) H").tag(hours)
                                    }
                                }
                                .labelsHidden()
                                .tint(TE.accent)
                            }
                        }

                        TERow {
                            HStack {
                                Text("START")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)
                                Spacer()
                                DatePicker("", selection: $dailyTimeBinding, displayedComponents: [.hourAndMinute])
                                    .labelsHidden()
                                    .tint(TE.accent)
                                    .onChange(of: dailyTimeBinding) { _, newValue in
                                        let comps = Calendar.current.dateComponents([.hour, .minute], from: newValue)
                                        dailyScheduler.hour = comps.hour ?? 21
                                        dailyScheduler.minute = comps.minute ?? 0
                                    }
                            }
                        }

                        TERow {
                            HStack {
                                Text("FILE LAYOUT")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)
                                Spacer()
                                Text("ONE FILE PER DAY")
                                    .font(TE.mono(.caption2, weight: .medium))
                                    .multilineTextAlignment(.trailing)
                                    .foregroundStyle(TE.accent)
                            }
                        }

                        if dailyScheduler.isIntervalSchedule {
                            TERow {
                                HStack {
                                    Text("UPDATE MODE")
                                        .font(TE.mono(.caption, weight: .medium))
                                        .tracking(1)
                                        .foregroundStyle(TE.textPrimary)
                                    Spacer()
                                    Picker("", selection: Binding(
                                        get: { dailyScheduler.fileMode },
                                        set: { dailyScheduler.fileMode = $0 }
                                    )) {
                                        Text("REWRITE").tag(ScheduledExportFileMode.rewrite)
                                        Text("APPEND").tag(ScheduledExportFileMode.append)
                                    }
                                    .labelsHidden()
                                    .tint(TE.accent)
                                }
                            }
                        }

                        TERow {
                            HStack {
                                Text("FORMAT")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)
                                Spacer()
                                Picker("", selection: Binding(
                                    get: { dailyScheduler.format },
                                    set: { dailyScheduler.format = $0 }
                                )) {
                                    Text("JSON").tag(ExportFormat.json)
                                    Text("CSV").tag(ExportFormat.csv)
                                    Text("MARKDOWN").tag(ExportFormat.markdown)
                                    Text("OWNTRACKS").tag(ExportFormat.owntracks)
                                    Text("OVERLAND").tag(ExportFormat.overland)
                                    Text("GPX").tag(ExportFormat.gpx)
                                    Text("KML").tag(ExportFormat.kml)
                                    Text("GEOJSON").tag(ExportFormat.geojson)
                                }
                                .labelsHidden()
                                .tint(TE.accent)
                            }
                        }

                        TERow {
                            HStack {
                                Text("DATA")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)
                                Spacer()
                                Picker("", selection: Binding(
                                    get: { dailyScheduler.dataKind },
                                    set: { dailyScheduler.dataKind = $0 }
                                )) {
                                    Text("VISITS").tag(ExportOptions.DataKind.visits)
                                    Text("POINTS").tag(ExportOptions.DataKind.points)
                                    Text("OUTINGS").tag(ExportOptions.DataKind.outings)
                                    Text("ALL").tag(ExportOptions.DataKind.all)
                                }
                                .labelsHidden()
                                .tint(TE.accent)
                            }
                        }

                        TERow {
                            HStack {
                                Text("LAST RUN")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textMuted)
                                Spacer()
                                Text(lastRunText)
                                    .font(TE.mono(.caption2, weight: .medium))
                                    .foregroundStyle(TE.textPrimary)
                            }
                        }

                        TERow(showDivider: false) {
                            Button {
                                Task { @MainActor in
                                    let outcome = await dailyScheduler.runNow()
                                    if outcome.completedExport {
                                        AppReviewPromptCoordinator.shared.recordSuccessfulFileExport()
                                    }
                                }
                            } label: {
                                HStack(spacing: 8) {
                                    Image(systemName: "arrow.down.doc.fill")
                                        .font(.caption.weight(.medium))
                                        .foregroundStyle(TE.accent)
                                    Text("RUN NOW")
                                        .font(TE.mono(.caption, weight: .medium))
                                        .tracking(1)
                                        .foregroundStyle(TE.accent)
                                    Spacer()
                                    Image(systemName: "arrow.right")
                                        .font(.caption2.weight(.bold))
                                        .foregroundStyle(TE.accent.opacity(0.5))
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: footerText(for: dailyScheduler))
        }
        .onAppear {
            var comps = DateComponents()
            comps.hour = dailyScheduler.hour
            comps.minute = dailyScheduler.minute
            dailyTimeBinding = Calendar.current.date(from: comps) ?? Date()
        }
    }

    private func footerText(for scheduler: DailyExportScheduler) -> LocalizedStringKey {
        guard scheduler.isEnabled else {
            return "Automatically save one dated file for each local calendar day."
        }

        let effectiveFileMode: ScheduledExportFileMode = scheduler.isIntervalSchedule
            ? scheduler.fileMode
            : .rewrite
        switch effectiveFileMode {
        case .rewrite:
            return "Each day keeps one file. Every run rewrites it with everything recorded so far today, so it always stays complete and valid."
        case .append:
            return "Each day keeps one file. Every run adds only records captured since the last run; GPX and KML always rewrite the full day. Wake-ups are best effort — tap the fallback notification or open the app to catch up."
        }
    }

    private var lastRunText: String {
        guard let last = dailyScheduler.lastRun else { return String(localized: "NEVER") }
        let fmt = DateFormatter()
        fmt.dateStyle = .short
        fmt.timeStyle = .short
        return fmt.string(from: last)
    }

    // MARK: - Filename

    private var filenameSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "FILE PATH")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow {
                        if dynamicTypeSize.isAccessibilitySize {
                            presetButton("READABLE", isSelected: filenamePattern == FilenameTemplate.readablePattern) {
                                filenamePattern = FilenameTemplate.readablePattern
                            }
                            Rectangle().fill(TE.border).frame(height: 1)
                            presetButton("COMPACT", isSelected: filenamePattern == FilenameTemplate.compactPattern) {
                                filenamePattern = FilenameTemplate.compactPattern
                            }
                            Rectangle().fill(TE.border).frame(height: 1)
                            presetButton("DATED", isSelected: filenamePattern == FilenameTemplate.datedFoldersPattern) {
                                filenamePattern = FilenameTemplate.datedFoldersPattern
                            }
                        } else {
                            HStack(spacing: 0) {
                                presetButton("READABLE", isSelected: filenamePattern == FilenameTemplate.readablePattern) {
                                    filenamePattern = FilenameTemplate.readablePattern
                                }
                                Rectangle().fill(TE.border).frame(width: 1)
                                presetButton("COMPACT", isSelected: filenamePattern == FilenameTemplate.compactPattern) {
                                    filenamePattern = FilenameTemplate.compactPattern
                                }
                                Rectangle().fill(TE.border).frame(width: 1)
                                presetButton("DATED", isSelected: filenamePattern == FilenameTemplate.datedFoldersPattern) {
                                    filenamePattern = FilenameTemplate.datedFoldersPattern
                                }
                            }
                        }
                    }

                    TERow {
                        TextField("", text: $filenamePattern)
                            .font(TE.mono(.caption, weight: .medium))
                            .foregroundStyle(TE.textPrimary)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled(true)
                    }

                    TERow(showDivider: false) {
                        HStack {
                            Text("PREVIEW")
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1)
                                .foregroundStyle(TE.textMuted)
                            Spacer()
                            Text(filenamePreview)
                                .font(TE.mono(.caption2, weight: .medium))
                                .foregroundStyle(TE.accent)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Use / for folders. Tokens: \(FilenameTemplate.allTokens.map { $0.token }.joined(separator: " ")). {title}/{name} use the outing title when available, otherwise the data type. The format extension is added if omitted.")
        }
    }

    private var filenamePreview: String {
        let previewOuting = showsOutingExport ? filteredOutings.first : nil
        let previewTitle = showsOutingExport ? (previewOuting?.title ?? "Outing 1") : nil
        let previewDate = previewOuting?.startedAt ?? Date()
        let previews = selectedFormatsList.compactMap { format -> String? in
            if let previewOuting {
                return try? IsoMeExportKitAdapter.plannedOutingPreviewRelativePath(
                    for: previewOuting,
                    filenamePattern: filenamePattern,
                    format: format
                )
            }
            return try? IsoMeExportPathPlanner.plannedRelativePath(
                pattern: filenamePattern,
                dataKind: effectiveDataKind(for: format),
                format: format,
                date: previewDate,
                title: previewTitle,
                safetyPolicy: .preserveCurrentBehavior
            )
        }
        guard let first = previews.first else { return String(localized: "INVALID PATH") }
        guard previews.count > 1 else { return first }
        return String(localized: "\(first) + \(previews.count - 1) more")
    }

    private var previewDestinationLabel: String {
        if destination == .obsidian {
            return String(localized: "Obsidian vault: \(obsidianVault.vaultName ?? String(localized: "not chosen"))")
        }
        if exportFolderManager.hasDefaultFolder && useDefaultExportFolder {
            let folderName = exportFolderManager.selectedFolderName ?? String(localized: "Selected Folder")
            return String(localized: "Default folder: \(folderName)")
        }
        return String(localized: "Share sheet")
    }

    private var previewDestinationRootName: String {
        if destination == .obsidian {
            return obsidianVault.vaultName ?? String(localized: "Obsidian")
        }
        if exportFolderManager.hasDefaultFolder && useDefaultExportFolder {
            return exportFolderManager.selectedFolderName ?? "Selected Folder"
        }
        return String(localized: "Share Sheet")
    }

    private func presetButton(_ title: LocalizedStringKey, isSelected: Bool, action: @escaping () -> Void) -> some View {
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
                .frame(maxWidth: .infinity, minHeight: 36)
                .background(isSelected ? TE.accent.opacity(0.08) : Color.clear)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Output

    private var outputSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "OUTPUT")

            TECard(paperOpacity: 0.97) {
                TERow(showDivider: false) {
                    toggleRow(showsOutingExport ? "ONE FILE PER OUTING" : "ONE FILE PER DAY", isOn: $options.splitByDay)
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: outputFooterText)
        }
    }

    private var outputFooterText: LocalizedStringKey {
        if showsOutingExport {
            return options.splitByDay
                ? "Each outing becomes one file per selected format. Use {title}, {date}, {time}, or {format} in the file path."
                : "Filtered outings are condensed into one file per selected format."
        }
        return options.splitByDay
            ? "Each calendar day becomes one file per selected format. Use {date}, {day}, or {format} in the filename to keep them distinct."
            : "Filtered data is condensed into one file per selected format."
    }

    // MARK: - Date Range

    private var dateRangeSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "DATE RANGE")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    ForEach(ExportOptions.DateRangePreset.simplePresets) { preset in
                        TERow {
                            Button {
                                options.datePreset = preset
                            } label: {
                                HStack {
                                    Text(verbatim: preset.label)
                                        .font(TE.mono(.caption, weight: .medium))
                                        .tracking(1)
                                        .foregroundStyle(TE.textPrimary)
                                    Spacer()
                                    if options.datePreset == preset {
                                        Image(systemName: "checkmark")
                                            .font(.caption.weight(.bold))
                                            .foregroundStyle(TE.accent)
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    TERow {
                        periodMenu(
                            title: "MONTH",
                            selection: options.datePreset == .month ? Self.monthName(options.presetAnchor ?? Date()) : nil,
                            choices: recentMonths.map { (Self.monthName($0), $0) },
                            preset: .month
                        )
                    }
                    TERow {
                        periodMenu(
                            title: "YEAR",
                            selection: options.datePreset == .year
                                ? String(localized: "ALL OF \(String(Calendar.current.component(.year, from: options.presetAnchor ?? Date())))")
                                : nil,
                            choices: recordedYears.map { (String(localized: "All of \(String(Calendar.current.component(.year, from: $0)))"), $0) },
                            preset: .year
                        )
                    }
                    TERow(showDivider: options.datePreset == .custom) {
                        Button {
                            options.datePreset = .custom
                        } label: {
                            HStack {
                                Text(verbatim: ExportOptions.DateRangePreset.custom.label)
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)
                                Spacer()
                                if options.datePreset == .custom {
                                    Image(systemName: "checkmark")
                                        .font(.caption.weight(.bold))
                                        .foregroundStyle(TE.accent)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }

                    if options.datePreset == .custom {
                        TERow {
                            HStack {
                                Text("FROM")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)
                                Spacer()
                                DatePicker("", selection: $options.customStart, displayedComponents: [.date])
                                    .labelsHidden()
                                    .tint(TE.accent)
                            }
                        }
                        TERow(showDivider: false) {
                            HStack {
                                Text("TO")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)
                                Spacer()
                                DatePicker("", selection: $options.customEnd, displayedComponents: [.date])
                                    .labelsHidden()
                                    .tint(TE.accent)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Limit the export to a specific window of time.")
        }
    }

    /// A named-month or whole-year row: a menu of periods, checked when one is in use.
    private func periodMenu(title: LocalizedStringKey, selection: String?, choices: [(String, Date)], preset: ExportOptions.DateRangePreset) -> some View {
        Menu {
            ForEach(choices, id: \.1) { label, anchor in
                Button(label) {
                    options.presetAnchor = anchor
                    options.datePreset = preset
                }
            }
        } label: {
            HStack {
                Text(title)
                    .font(TE.mono(.caption, weight: .medium))
                    .tracking(1)
                    .foregroundStyle(TE.textPrimary)
                Spacer()
                Text(verbatim: selection?.uppercased() ?? String(localized: "CHOOSE"))
                    .font(TE.mono(.caption2, weight: .medium))
                    .foregroundStyle(selection == nil ? TE.textMuted : TE.accent)
                if selection != nil {
                    Image(systemName: "checkmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(TE.accent)
                } else {
                    Image(systemName: "chevron.up.chevron.down")
                        .font(.caption2)
                        .foregroundStyle(TE.textMuted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// The twelve months before this one, newest first ("this month" has its own row).
    private var recentMonths: [Date] {
        let calendar = Calendar.current
        let start = calendar.dateInterval(of: .month, for: Date())?.start ?? Date()
        return (1...12).compactMap { calendar.date(byAdding: .month, value: -$0, to: start) }
    }

    /// Every year from the first recorded visit through this one, newest first.
    private var recordedYears: [Date] {
        let calendar = Calendar.current
        let now = Date()
        let currentYear = calendar.component(.year, from: now)
        let firstYear = viewModel.allVisits.last.map { calendar.component(.year, from: $0.arrivedAt) } ?? currentYear
        return (min(firstYear, currentYear)...currentYear).reversed().compactMap {
            calendar.date(from: DateComponents(year: $0, month: 7, day: 1))
        }
    }

    // MARK: - Time of day

    private var timeOfDaySection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "TIME OF DAY")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow(showDivider: options.timeOfDayEnabled) {
                        toggleRow("ENABLE", isOn: $options.timeOfDayEnabled)
                    }

                    if options.timeOfDayEnabled {
                        TERow {
                            HStack {
                                Text("START")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)
                                Spacer()
                                DatePicker("", selection: $options.timeOfDayStart, displayedComponents: [.hourAndMinute])
                                    .labelsHidden()
                                    .tint(TE.accent)
                            }
                        }
                        TERow(showDivider: false) {
                            HStack {
                                Text("END")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)
                                Spacer()
                                DatePicker("", selection: $options.timeOfDayEnd, displayedComponents: [.hourAndMinute])
                                    .labelsHidden()
                                    .tint(TE.accent)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Only include data captured between these hours each day. Wraps midnight if end is before start.")
        }
    }

    // MARK: - Filters

    private var filtersSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "FILTERS")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    if showsVisitFields {
                        TERow {
                            toggleRow("ONLY COMPLETED VISITS", isOn: $options.onlyCompletedVisits)
                        }
                        TERow {
                            HStack {
                                Text("MIN DURATION")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)
                                Spacer()
                                Picker("", selection: $options.minVisitDurationMinutes) {
                                    Text("None").tag(0.0)
                                    Text("5 min").tag(5.0)
                                    Text("15 min").tag(15.0)
                                    Text("30 min").tag(30.0)
                                    Text("1h").tag(60.0)
                                }
                                .labelsHidden()
                                .tint(TE.accent)
                            }
                        }
                    }
                    if showsPointFields {
                        TERow {
                            toggleRow("EXCLUDE GPS GLITCHES", isOn: $options.excludeOutliers)
                        }
                        TERow(showDivider: false) {
                            HStack {
                                Text("ACCURACY CAP")
                                    .font(TE.mono(.caption, weight: .medium))
                                    .tracking(1)
                                    .foregroundStyle(TE.textPrimary)
                                Spacer()
                                Picker("", selection: $options.maxAccuracyMeters) {
                                    Text("Any").tag(0.0)
                                    Text("≤ 10m").tag(10.0)
                                    Text("≤ 25m").tag(25.0)
                                    Text("≤ 50m").tag(50.0)
                                    Text("≤ 100m").tag(100.0)
                                }
                                .labelsHidden()
                                .tint(TE.accent)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    // MARK: - Visit Fields

    private var visitFieldsSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "VISIT FIELDS")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow {
                        toggleRow("LOCATION NAME", isOn: $options.includeVisitLocationName)
                    }
                    TERow {
                        toggleRow("ADDRESS", isOn: $options.includeVisitAddress)
                    }
                    TERow {
                        toggleRow("DURATION", isOn: $options.includeVisitDuration)
                    }
                    TERow {
                        toggleRow("COORDINATES", isOn: $options.includeVisitCoordinates)
                    }
                    TERow(showDivider: false) {
                        toggleRow("NOTES", isOn: $options.includeVisitNotes)
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Arrival and departure timestamps are always included.")
        }
    }

    // MARK: - Point Fields

    private var pointFieldsSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "POINT FIELDS")

            TECard(paperOpacity: 0.97) {
                VStack(spacing: 0) {
                    TERow {
                        toggleRow("ALTITUDE", isOn: $options.includePointAltitude)
                    }
                    TERow {
                        toggleRow("SPEED", isOn: $options.includePointSpeed)
                    }
                    TERow {
                        toggleRow("ACCURACY", isOn: $options.includePointAccuracy)
                    }
                    TERow(showDivider: false) {
                        toggleRow("OUTLIER FLAG", isOn: $options.includePointOutlierFlag)
                    }
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Timestamp and coordinates are always included.")
        }
    }

    // MARK: - Outing Fields

    private var outingFieldsSection: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "OUTING EXPORT")

            TECard(paperOpacity: 0.97) {
                TERow(showDivider: false) {
                    VStack(alignment: .leading, spacing: 10) {
                        HStack(spacing: 8) {
                            Image(systemName: "doc.text.fill")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(TE.accent)
                            Text("ALL FORMATS")
                                .font(TE.mono(.caption, weight: .medium))
                                .tracking(1)
                                .foregroundStyle(TE.textPrimary)
                        }

                        Text("JSON and CSV export outing summaries. Markdown adds YAML front matter plus visits and route points. GPX, KML, GeoJSON, OwnTracks, and Overland export the outing route points.")
                            .font(TE.mono(.caption2, weight: .medium))
                            .foregroundStyle(TE.textMuted)
                            .lineSpacing(3)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: "Stored outing names and notes are included when the selected format supports them. Inferred outings export with source: inferred.")
        }
    }

    // MARK: - Footer

    private var exportFooter: some View {
        VStack(spacing: 8) {
            if !storeManager.canExport && totalCount > 0 {
                Text("Preview is free. Choose a plan to unlock export.")
                    .font(TE.mono(.caption2, weight: .medium))
                    .foregroundStyle(TE.textMuted)
                    .multilineTextAlignment(.center)
                    .padding(.bottom, 2)
            }

            let layout = dynamicTypeSize.isAccessibilitySize || usesDuoLayout
                ? AnyLayout(VStackLayout(spacing: 8))
                : AnyLayout(HStackLayout(spacing: 8))
            layout {
                if !usesDuoLayout {
                    Button {
                        ensurePointDataIfNeeded()
                        showingPreview = true
                    } label: {
                        Text("Preview")
                    }
                    .buttonStyle(ArchiveCapsuleButtonStyle(filled: false))
                    .frame(maxWidth: dynamicTypeSize.isAccessibilitySize ? .infinity : 130)
                    .disabled(totalCount == 0)
                    .accessibilityLabel("Preview Export")
                    .accessibilityHint("Shows the files and contents that will be exported")
                }

                Button {
                    runExport()
                } label: {
                    Text(exportButtonLabel)
                }
                .buttonStyle(ArchiveCapsuleButtonStyle(filled: true))
                .disabled(exportButtonDisabled)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .padding(.bottom, 14)
        .background(TE.surface)
    }

    private var exportButtonLabel: LocalizedStringKey {
        if destination == .server {
            if isSendingToServer { return "Sending…" }
            if !storeManager.canExport { return "Unlock export" }
            if !serverIsConfigured { return "Connect a server" }
            let n = serverBatches.count
            if n == 0 { return "No points to send" }
            if n == 1 { return "Send 1 file" }
            return "Send \(n) files"
        }
        if totalCount == 0 { return "Nothing to export" }
        if !storeManager.canExport { return "Unlock export" }
        let n = splitFileCount
        if n == 0 { return "Nothing to export" }
        if destination == .obsidian && !obsidianVault.hasVault { return "Choose Obsidian vault" }
        if n == 1 { return "Export 1 file" }
        return "Export \(n) files"
    }

    private var exportButtonDisabled: Bool {
        if destination == .server {
            return isSendingToServer || (storeManager.canExport && serverIsConfigured && serverBatches.isEmpty)
        }
        return totalCount == 0
    }

    // MARK: - Reusable bits

    private func segmentedButton(_ title: LocalizedStringKey, isSelected: Bool, action: @escaping () -> Void) -> some View {
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

    private func toggleRow(_ label: LocalizedStringKey, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            Text(label)
                .font(TE.mono(.caption, weight: .medium))
                .tracking(dynamicTypeSize.isAccessibilitySize ? 0.5 : 1)
                .foregroundStyle(TE.textPrimary)
                .lineLimit(dynamicTypeSize.isAccessibilitySize ? 2 : 1)
        }
        .toggleStyle(TEToggleStyle())
    }

    private func selectDataKind(_ dataKind: ExportOptions.DataKind) {
        options.dataKind = dataKind
        if dataKind == .outings {
            options.splitByDay = true
        }
        ensurePointDataIfNeeded()
    }

    private func toggleFormat(_ format: ExportFormat) {
        if selectedFormats.contains(format) {
            guard selectedFormats.count > 1 else { return }
            selectedFormats.remove(format)
        } else {
            selectedFormats.insert(format)
        }
        options.format = primarySelectedFormat
        ensurePointDataIfNeeded()
    }

    // MARK: - Actions

    private func runExport() {
        guard storeManager.canExport else {
            showingPaywall = true
            return
        }

        ensurePointDataIfNeeded()

        switch destination {
        case .server:
            sendToServer()
            return
        case .obsidian:
            saveToObsidianVault()
            return
        case .files:
            break
        }

        if exportFolderManager.hasDefaultFolder && useDefaultExportFolder {
            do {
                let urls = try ExportService.saveToDefaultFolder(
                    visits: viewModel.allVisits,
                    points: viewModel.locationPoints,
                    recordingSessions: viewModel.allRecordingSessions,
                    activeTrackingStart: viewModel.locationManager.trackingStartTime,
                    options: options,
                    selectedFormats: selectedFormatsList,
                    filenamePattern: filenamePattern
                )
                ExportToastCenter.shared.show(.success(savedURLs: urls))
                AppReviewPromptCoordinator.shared.recordSuccessfulFileExport()
            } catch {
                viewModel.exportError = error.localizedDescription
                ExportToastCenter.shared.show(.failure(message: error.localizedDescription))
            }
        } else {
            do {
                try ExportService.share(
                    visits: viewModel.allVisits,
                    points: viewModel.locationPoints,
                    recordingSessions: viewModel.allRecordingSessions,
                    activeTrackingStart: viewModel.locationManager.trackingStartTime,
                    options: options,
                    selectedFormats: selectedFormatsList,
                    filenamePattern: filenamePattern,
                    completion: { completed in
                        guard completed else { return }
                        Task { @MainActor in
                            AppReviewPromptCoordinator.shared.recordSuccessfulFileExport()
                        }
                    }
                )
                ExportToastCenter.shared.show(.success(message: String(localized: "Share sheet opened")))
            } catch {
                viewModel.exportError = error.localizedDescription
                ExportToastCenter.shared.show(.failure(message: error.localizedDescription))
            }
        }
    }

    private func saveToObsidianVault() {
        guard obsidianVault.hasVault else {
            showingVaultPicker = true
            return
        }
        do {
            let files = try IsoMeExportKitAdapter.plannedFiles(
                visits: viewModel.allVisits,
                points: viewModel.locationPoints,
                recordingSessions: viewModel.allRecordingSessions,
                activeTrackingStart: viewModel.locationManager.trackingStartTime,
                options: options,
                selectedFormats: selectedFormatsList,
                filenamePattern: filenamePattern
            )
            let urls = try obsidianVault.write(files)
            ExportToastCenter.shared.show(.success(savedURLs: urls))
            AppReviewPromptCoordinator.shared.recordSuccessfulFileExport()
        } catch {
            viewModel.exportError = error.localizedDescription
            ExportToastCenter.shared.show(.failure(message: error.localizedDescription))
        }
    }

    /// Reuses the webhook's own queue, format and sign-in: each batch is queued, then flushed as one request.
    private func sendToServer() {
        guard serverIsConfigured else {
            editingToken = .destination
            return
        }
        viewModel.ensureAllLocationPointsLoaded()
        let batches = serverBatches
        guard !batches.isEmpty, !isSendingToServer else { return }
        isSendingToServer = true
        let host = serverHost ?? String(localized: "your server")
        Task { @MainActor in
            defer { isSendingToServer = false }
            var sent = 0
            for batch in batches {
                for point in batch { webhookManager.enqueuePendingPoint(point) }
                await webhookManager.flushBatch()
                // A failed flush puts the batch back on the queue for the webhook's next attempt.
                guard webhookManager.queuedPointCount == 0 else { break }
                sent += 1
            }
            if sent == batches.count {
                let message = sent == 1
                    ? String(localized: "Sent 1 file to \(host)")
                    : String(localized: "Sent \(sent) files to \(host)")
                ExportToastCenter.shared.show(.success(message: message))
            } else {
                let reason = webhookManager.lastError ?? String(localized: "The server didn't accept the request.")
                ExportToastCenter.shared.show(.failure(message: String(localized: "Sent \(sent) of \(batches.count) files. \(reason)")))
            }
        }
    }

    private func ensurePointDataIfNeeded() {
        if destination == .server {
            viewModel.ensureAllLocationPointsLoaded()
        }
        if effectiveDataKinds.contains(.points) || effectiveDataKinds.contains(.all) || effectiveDataKinds.contains(.outings) {
            viewModel.ensureAllLocationPointsLoaded()
        }
        refreshOutingSummariesIfNeeded()
    }

    private func refreshOutingSummariesIfNeeded() {
        guard effectiveDataKinds.contains(.outings) else { return }
        cachedOutingSummaries = viewModel.recordingSessionSummaries(inferenceConfiguration: .stored())
    }

    private func persistExportPreferences() {
        ExportPreferencesStore.save(options: options, selectedFormats: selectedFormats)
    }
}

private struct ExportFormatSection: View {
    let selectedFormats: Set<ExportFormat>
    let selectedFormatsList: [ExportFormat]
    let dataKind: ExportOptions.DataKind
    let toggleFormat: (ExportFormat) -> Void

    var body: some View {
        VStack(spacing: 0) {
            TESectionHeader(title: "FORMATS")

            TECard(paperOpacity: 0.97) {
                ExportFormatGrid(
                    selectedFormats: selectedFormats,
                    toggleFormat: toggleFormat
                )
            }
            .padding(.horizontal, 16)

            TESectionFooter(text: footerText)
        }
    }

    private var footerText: LocalizedStringKey {
        let selectedCount = selectedFormatsList.count
        let hasPointsOnlyFormat = selectedFormatsList.contains { $0.isPointsOnly }

        if hasPointsOnlyFormat {
            if dataKind == .outings {
                return "Select one or many formats. OwnTracks and Overland outing exports contain the outing route's GPS fixes; visit rows and outing notes are not represented."
            }
            return "Select one or many formats. OwnTracks and Overland only carry GPS fixes; other selected formats use the selected data type."
        }

        return selectedCount == 1
            ? "Tap more formats to export multiple files at once."
            : "Export will create one file per selected format. Add {format} to the file path if you want each filename to include its format."
    }
}

private struct ExportFormatGrid: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let selectedFormats: Set<ExportFormat>
    let toggleFormat: (ExportFormat) -> Void

    private static let accessibilityChoices: [ExportFormatChoice] = [
        ExportFormatChoice("JSON", .json),
        ExportFormatChoice("CSV", .csv),
        ExportFormatChoice("MARKDOWN", .markdown),
        ExportFormatChoice("OWNTRACKS", .owntracks),
        ExportFormatChoice("OVERLAND", .overland),
        ExportFormatChoice("GPX", .gpx),
        ExportFormatChoice("KML", .kml),
        ExportFormatChoice("GEOJSON", .geojson)
    ]

    private static let compactRows: [[ExportFormatChoice]] = [
        [ExportFormatChoice("JSON", .json), ExportFormatChoice("CSV", .csv), ExportFormatChoice("MARKDOWN", .markdown)],
        [ExportFormatChoice("OWNTRACKS", .owntracks), ExportFormatChoice("OVERLAND", .overland), ExportFormatChoice("GPX", .gpx)],
        [ExportFormatChoice("KML", .kml), ExportFormatChoice("GEOJSON", .geojson)]
    ]

    var body: some View {
        VStack(spacing: 0) {
            if dynamicTypeSize.isAccessibilitySize {
                ForEach(Array(Self.accessibilityChoices.enumerated()), id: \.element.id) { index, choice in
                    if index > 0 { Rectangle().fill(TE.border).frame(height: 1) }
                    formatButton(choice)
                }
            } else {
                ForEach(Array(Self.compactRows.enumerated()), id: \.offset) { rowIndex, row in
                    if rowIndex > 0 { Rectangle().fill(TE.border).frame(height: 1) }
                    HStack(spacing: 0) {
                        ForEach(Array(row.enumerated()), id: \.element.id) { columnIndex, choice in
                            if columnIndex > 0 { Rectangle().fill(TE.border).frame(width: 1) }
                            formatButton(choice)
                        }
                    }
                }
            }
        }
    }

    private func formatButton(_ choice: ExportFormatChoice) -> some View {
        let isSelected = selectedFormats.contains(choice.format)
        return Button {
            toggleFormat(choice.format)
        } label: {
            Text(choice.title)
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
        .accessibilityValue(isSelected ? "Selected" : "Not selected")
    }
}

// MARK: - Recipe support

/// A tappable choice in the recipe sentence, plus the sheets that aren't words in it.
private enum RecipeToken: String, Identifiable {
    case what, range, format, layout, destination, schedule, all

    var id: String { rawValue }

    private static let scheme = "whereday-recipe"

    var url: URL? { URL(string: "\(Self.scheme)://\(rawValue)") }

    init?(url: URL) {
        guard url.scheme == Self.scheme, let host = url.host(), let token = RecipeToken(rawValue: host) else { return nil }
        self = token
    }

    var sheetTitle: LocalizedStringKey {
        switch self {
        case .what: "What to export"
        case .range: "Date range"
        case .format: "Formats"
        case .layout: "File layout"
        case .destination: "Destination"
        case .schedule: "Schedule"
        case .all: "All options"
        }
    }
}

private struct SavedExportRecipe: Codable, Identifiable, Equatable {
    let id: UUID
    var name: String
    var options: ExportOptions
    var formats: [ExportFormat]
    var filenamePattern: String
    /// Optional so recipes saved before destinations existed still load (as Files).
    var destination: ArchiveDestination? = nil

    static var examples: [SavedExportRecipe] {
        var daily = ExportOptions()
        daily.dataKind = .all
        daily.format = .markdown
        daily.datePreset = .thisMonth
        daily.splitByDay = true

        var weekly = ExportOptions()
        weekly.dataKind = .points
        weekly.format = .gpx
        weekly.datePreset = .last7Days
        weekly.splitByDay = false

        return [
            SavedExportRecipe(id: UUID(), name: String(localized: "Daily note → Obsidian"), options: daily,
                              formats: [.markdown], filenamePattern: "Whereday/Daily Track - {date}", destination: .obsidian),
            SavedExportRecipe(id: UUID(), name: String(localized: "Weekly GPX"), options: weekly,
                              formats: [.gpx], filenamePattern: FilenameTemplate.readablePattern, destination: .files)
        ]
    }
}

/// Where a recipe's files go: a Files folder (or the share sheet), an Obsidian vault, or a server.
enum ArchiveDestination: String, CaseIterable, Identifiable, Codable {
    case files
    case obsidian
    case server

    var id: String { rawValue }

    var title: LocalizedStringKey {
        switch self {
        case .files: "FILES FOLDER"
        case .obsidian: "OBSIDIAN VAULT"
        case .server: "DAWARICH SERVER"
        }
    }

    var detail: LocalizedStringKey {
        switch self {
        case .files: "A folder in Files, or the share sheet"
        case .obsidian: "Markdown daily notes in your vault"
        case .server: "Route points sent through your webhook"
        }
    }

    var systemImage: String {
        switch self {
        case .files: "folder"
        case .obsidian: "books.vertical"
        case .server: "server.rack"
        }
    }
}

/// A user-picked Obsidian vault folder, kept as a security-scoped bookmark apart from the default export folder.
@MainActor
final class ObsidianVaultStore: ObservableObject {
    static let shared = ObsidianVaultStore()

    private let bookmarkKey = "obsidianVaultFolderBookmark"
    @Published private(set) var vaultURL: URL?

    var hasVault: Bool { vaultURL != nil }
    var vaultName: String? { vaultURL?.lastPathComponent }

    private init() {
        guard let data = UserDefaults.standard.data(forKey: bookmarkKey) else { return }
        var isStale = false
        guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &isStale) else {
            UserDefaults.standard.removeObject(forKey: bookmarkKey)
            return
        }
        if isStale, url.startAccessingSecurityScopedResource() {
            defer { url.stopAccessingSecurityScopedResource() }
            try? saveBookmark(for: url)
        }
        vaultURL = url
    }

    func choose(_ url: URL) {
        guard url.startAccessingSecurityScopedResource() else { return }
        defer { url.stopAccessingSecurityScopedResource() }
        do {
            try saveBookmark(for: url)
            vaultURL = url
        } catch {
            print("Failed to save Obsidian vault bookmark: \(error)")
        }
    }

    func clear() {
        UserDefaults.standard.removeObject(forKey: bookmarkKey)
        vaultURL = nil
    }

    /// The scheduler writes here instead of the export folder when a recipe's destination is the vault.
    static let destinationDefaultsKey = "exportDestination"

    /// True when the Archive's destination is Obsidian and a vault is chosen.
    var isScheduledDestination: Bool {
        hasVault && UserDefaults.standard.string(forKey: Self.destinationDefaultsKey) == ArchiveDestination.obsidian.rawValue
    }

    /// Writes planned files into the vault; by default a day's note is replaced by its latest version.
    func write(
        _ files: [PlannedExportFile],
        mode: ExportWriteMode = .overwrite,
        mergeStrategies: [String: any ExportMergeStrategy] = [:]
    ) throws -> [URL] {
        guard let vaultURL else { throw ExportFolderError.noDefaultFolder }
        guard vaultURL.startAccessingSecurityScopedResource() else { throw ExportFolderError.accessDenied }
        defer { vaultURL.stopAccessingSecurityScopedResource() }
        let destination = ExportDestination(rootURL: vaultURL, displayName: vaultName)
        let writer = ExportFileWriter(fileSystem: FileManagerExportFileSystem(), safetyPolicy: .rejectTraversalAndAbsolutePaths)
        return try writer.write(files, to: destination, mode: mode, mergeStrategies: mergeStrategies).map(\.url)
    }

    private func saveBookmark(for url: URL) throws {
        let data = try url.bookmarkData(options: [], includingResourceValuesForKeys: nil, relativeTo: nil)
        UserDefaults.standard.set(data, forKey: bookmarkKey)
    }
}

/// Presents the vault folder picker from whichever layer is frontmost.
private struct VaultPresentation: ViewModifier {
    @Binding var isPicking: Bool
    let vault: ObsidianVaultStore

    func body(content: Content) -> some View {
        content.sheet(isPresented: $isPicking) {
            FolderPicker { url in
                if let url { vault.choose(url) }
            }
        }
    }
}

private struct LivePreviewKey: Equatable {
    let options: ExportOptions
    let formats: [ExportFormat]
    let filenamePattern: String
    let visitCount: Int
    let pointCount: Int
    let sessionCount: Int
    let outingCount: Int
    let rootName: String
}

private enum RecipeLivePreview {
    case idle
    case loading
    case empty
    case tooLarge
    case failed
    case ready([IsoMePreviewFile], firstContent: String?)
}

/// Folder picking and removal, attached to whichever layer (tab or options sheet) is frontmost.
private struct FolderPresentations: ViewModifier {
    @Binding var isPickingFolder: Bool
    @Binding var isConfirmingRemoval: Bool
    let folderManager: ExportFolderManager

    init(isPickingFolder: Binding<Bool>, isConfirmingRemoval: Binding<Bool>, folderManager: ExportFolderManager) {
        _isPickingFolder = isPickingFolder
        _isConfirmingRemoval = isConfirmingRemoval
        self.folderManager = folderManager
    }

    func body(content: Content) -> some View {
        content
            .alert("Remove Default Folder?", isPresented: $isConfirmingRemoval) {
                Button("Cancel", role: .cancel) {}
                Button("Remove", role: .destructive) { folderManager.clearDefaultFolder() }
            } message: {
                Text("Exports will use the share sheet instead of saving directly to a folder.")
            }
            .sheet(isPresented: $isPickingFolder) {
                FolderPicker { url in
                    if let url = url {
                        folderManager.setDefaultFolder(url)
                    }
                }
            }
    }
}

/// Capsule actions for the Archive screens: filled ink for the main action, hairline for secondary ones.
struct ArchiveCapsuleButtonStyle: ButtonStyle {
    let filled: Bool
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(filled ? .semibold : .medium))
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: filled ? 54 : 44)
            .foregroundStyle(filled ? TE.surface : TE.textPrimary)
            .background {
                if filled {
                    Capsule().fill(isEnabled ? TE.accent : TE.textMuted)
                } else {
                    Capsule().fill(TE.card)
                }
            }
            .overlay {
                if !filled {
                    Capsule().strokeBorder(TE.border, lineWidth: 1)
                }
            }
            .opacity(configuration.isPressed ? 0.8 : (isEnabled || filled ? 1 : 0.5))
            .contentShape(Capsule())
    }
}

private struct ExportFormatChoice: Identifiable {
    let title: LocalizedStringKey
    let format: ExportFormat
    var id: String { format.token }

    init(_ title: LocalizedStringKey, _ format: ExportFormat) {
        self.title = title
        self.format = format
    }
}

struct IsoMeExportPreviewView: View {
    let visits: [Visit]
    let points: [LocationPoint]
    let recordingSessions: [RecordingSession]
    let activeTrackingStart: Date?
    let options: ExportOptions
    let selectedFormats: [ExportFormat]
    let filenamePattern: String
    let destinationLabel: String
    let destinationRootName: String
    let totalItemCount: Int
    let totalFileCount: Int

    @Environment(\.dismiss) private var dismiss
    @State private var records: [IsoMePreviewRecord] = []
    @State private var warnings: [ExportWarning] = []
    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var previewTotalRecordCount = 0

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    loadingView
                } else if let errorMessage {
                    messageView(
                        icon: "exclamationmark.triangle.fill",
                        title: "Preview Failed",
                        message: errorMessage
                    )
                } else if records.isEmpty {
                    messageView(
                        icon: "doc.text.magnifyingglass",
                        title: "No data to preview",
                        message: String(localized: "There is no data for the selected export settings.")
                    )
                } else {
                    contentList
                }
            }
            .background(TE.surface.ignoresSafeArea())
            .navigationTitle("Export Preview")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .task {
            await buildPreview()
        }
    }

    private var loadingView: some View {
        VStack(spacing: 14) {
            ProgressView()
                .controlSize(.large)
                .tint(TE.accent)
            Text("Building preview…")
                .font(TE.mono(.caption, weight: .medium))
                .foregroundStyle(TE.textMuted)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func messageView(icon: String, title: LocalizedStringKey, message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.largeTitle.weight(.light))
                .foregroundStyle(TE.textMuted)
            Text(title)
                .font(TE.mono(.headline, weight: .bold))
                .foregroundStyle(TE.textPrimary)
            Text(verbatim: message)
                .font(TE.mono(.caption, weight: .medium))
                .foregroundStyle(TE.textMuted)
                .multilineTextAlignment(.center)
                .lineSpacing(3)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var contentList: some View {
        List {
            summarySection
            warningsSection

            ForEach(records) { record in
                Section {
                    ForEach(record.files) { file in
                        NavigationLink {
                            IsoMePreviewFileContentView(file: file)
                        } label: {
                            fileRow(file)
                        }
                        .listRowBackground(TE.card)
                    }
                } header: {
                    Text(record.title)
                        .font(TE.mono(.caption, weight: .bold))
                        .foregroundStyle(TE.textMuted)
                } footer: {
                    if !record.folderPath.isEmpty {
                        Text(record.folderPath)
                            .font(TE.mono(.caption2, weight: .medium))
                            .foregroundStyle(TE.textMuted)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
    }

    private var selectedFormatSummary: String {
        selectedFormats.map(\.displayName).joined(separator: ", ")
    }

    private var summarySection: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                summaryRow("Destination", value: destinationLabel)
                summaryRow(selectedFormats.count == 1 ? "Format" : "Formats", value: selectedFormatSummary)
                summaryRow("Items", value: "\(totalItemCount)")
                summaryRow("Files", value: "\(totalFileCount)")

                if previewTotalRecordCount > records.count {
                    HStack(alignment: .top, spacing: 6) {
                        Image(systemName: "info.circle")
                            .font(.caption2)
                        Text("Recent files with data in this preview: \(records.count). Export will process every selected file.")
                            .font(TE.mono(.caption2, weight: .medium))
                    }
                    .foregroundStyle(TE.textMuted)
                    .padding(.top, 2)
                }
            }
            .padding(.vertical, 4)
            .listRowBackground(TE.card)
        }
    }

    private func summaryRow(_ label: LocalizedStringKey, value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(TE.mono(.caption, weight: .medium))
                .foregroundStyle(TE.textMuted)
            Spacer(minLength: 12)
            Text(value)
                .font(TE.mono(.caption, weight: .semibold))
                .foregroundStyle(TE.textPrimary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }

    @ViewBuilder
    private var warningsSection: some View {
        if !warnings.isEmpty {
            Section("Warnings") {
                ForEach(Array(warnings.enumerated()), id: \.offset) { _, warning in
                    Label {
                        Text(warning.message)
                            .font(TE.mono(.caption, weight: .medium))
                            .foregroundStyle(TE.textMuted)
                    } icon: {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(TE.warning)
                    }
                    .listRowBackground(TE.card)
                }
            }
        }
    }

    private func fileRow(_ file: IsoMePreviewFile) -> some View {
        HStack(spacing: 12) {
            Image(systemName: file.iconName)
                .font(.body.weight(.semibold))
                .foregroundStyle(TE.accent)
                .frame(width: 30, height: 30)
                .background(Circle().fill(TE.accent.opacity(0.1)))

            VStack(alignment: .leading, spacing: 3) {
                Text(file.filename)
                    .font(TE.mono(.caption, weight: .semibold))
                    .foregroundStyle(TE.textPrimary)
                    .lineLimit(2)
                    .truncationMode(.middle)
                Text("\(file.displayName) · \(file.sizeLabel)")
                    .font(TE.mono(.caption2, weight: .medium))
                    .foregroundStyle(TE.textMuted)
                if !file.folderPath.isEmpty {
                    Text(file.folderPath)
                        .font(TE.mono(.caption2, weight: .regular))
                        .foregroundStyle(TE.textMuted)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }
        }
        .padding(.vertical, 4)
    }

    @MainActor
    private func buildPreview() async {
        do {
            let preview = try await IsoMeExportKitAdapter.preview(
                visits: visits,
                points: points,
                recordingSessions: recordingSessions,
                activeTrackingStart: activeTrackingStart,
                options: options,
                selectedFormats: selectedFormats,
                filenamePattern: filenamePattern
            )
            let rootName = destinationRootName
            records = preview.records.map {
                IsoMePreviewRecord(record: $0, rootName: rootName, splitByDay: options.splitByDay, dataKind: options.dataKind)
            }
            warnings = preview.warnings
            previewTotalRecordCount = preview.totalRecordCount
            isLoading = false
        } catch {
            errorMessage = String(localized: "Could not build export preview: \(error.localizedDescription)")
            isLoading = false
        }
    }

    fileprivate static let dateLabelFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "EEE, MMM d, yyyy"
        return formatter
    }()
}

private struct IsoMePreviewFileContentView: View {
    let file: IsoMePreviewFile

    private var displayContent: ExportPreviewDisplayContent {
        file.plannedFile.displayContent()
    }

    var body: some View {
        let content = displayContent

        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if content.isTruncated {
                    Label {
                        Text("Showing a lightweight preview of this \(content.originalSizeLabel) file. The full export will still include all data.")
                            .font(TE.mono(.caption2, weight: .medium))
                    } icon: {
                        Image(systemName: "scissors")
                    }
                    .foregroundStyle(TE.textMuted)
                    .padding(.horizontal, 16)
                    .padding(.top, 16)
                }

                Text(content.text)
                    .font(TE.mono(.caption2, weight: .regular))
                    .foregroundStyle(TE.textPrimary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
            }
        }
        .background(TE.surface.ignoresSafeArea())
        .navigationTitle(file.filename)
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct IsoMePreviewRecord: Identifiable {
    let id: String
    let title: String
    let folderPath: String
    let files: [IsoMePreviewFile]

    init(record: ExportPreviewRecord, rootName: String, splitByDay: Bool, dataKind: ExportOptions.DataKind) {
        id = record.id
        if splitByDay, dataKind == .outings, !record.reference.displayName.isEmpty {
            title = record.reference.displayName
        } else if splitByDay, let date = record.reference.date {
            title = IsoMeExportPreviewView.dateLabelFormatter.string(from: date)
        } else {
            title = String(localized: "Export file")
        }

        let aggregateFolderPath = record.files.firstAggregateFolderPath ?? record.files.first?.relativeFolderPath ?? ""
        folderPath = Self.displayFolderPath(relativeFolderPath: aggregateFolderPath, rootName: rootName)
        files = record.files.map { IsoMePreviewFile(plannedFile: $0, rootName: rootName) }
    }

    private static func displayFolderPath(relativeFolderPath: String, rootName: String) -> String {
        var components = [rootName]
        components.append(contentsOf: relativeFolderPath.previewPathComponents)
        return components.filter { !$0.isEmpty }.joined(separator: "/") + "/"
    }
}

private struct IsoMePreviewFile: Identifiable {
    let plannedFile: PlannedExportFile
    let rootName: String

    var id: String { plannedFile.id }
    var filename: String { plannedFile.filename }
    var folderPath: String { Self.displayFolderPath(relativeFolderPath: plannedFile.relativeFolderPath, rootName: rootName) }
    var sizeLabel: String { plannedFile.sizeLabel }

    var displayName: String {
        if let format = exportFormat {
            return format.displayName
        }
        return plannedFile.displayName ?? String(localized: "Export file")
    }

    var iconName: String {
        switch exportFormat {
        case .json: return "curlybraces"
        case .csv: return "list.bullet.rectangle"
        case .markdown: return "doc.text"
        case .owntracks: return "location.fill"
        case .overland: return "point.topleft.down.curvedto.point.bottomright.up"
        case .gpx: return "map"
        case .kml: return "globe.americas"
        case .geojson: return "map.fill"
        case nil: return "doc.text"
        }
    }

    private var exportFormat: ExportFormat? {
        guard case .aggregate(let formatID) = plannedFile.role else { return nil }
        return ExportFormat(exportKitFormatID: formatID)
    }

    private static func displayFolderPath(relativeFolderPath: String, rootName: String) -> String {
        var components = [rootName]
        components.append(contentsOf: relativeFolderPath.previewPathComponents)
        return components.filter { !$0.isEmpty }.joined(separator: "/") + "/"
    }
}

private extension Array where Element == PlannedExportFile {
    var firstAggregateFolderPath: String? {
        first { file in
            if case .aggregate = file.role { return true }
            return false
        }?.relativeFolderPath
    }
}

private extension String {
    var previewPathComponents: [String] {
        split(separator: "/").map(String.init).filter { !$0.isEmpty }
    }
}

struct ExportToast: Identifiable, Equatable {
    enum Kind: Equatable {
        case success
        case failure
    }

    let id = UUID()
    let kind: Kind
    let title: String
    let message: String

    static func success(message: String) -> ExportToast {
        ExportToast(kind: .success, title: String(localized: "EXPORT SUCCESSFUL"), message: message)
    }

    static func success(savedURLs urls: [URL]) -> ExportToast {
        if urls.count == 1, let url = urls.first {
            return success(message: String(localized: "Saved to \(url.lastPathComponent)"))
        }
        if let folder = urls.first?.deletingLastPathComponent().lastPathComponent {
            return success(message: String(localized: "Saved \(urls.count) files to \(folder)"))
        }
        return success(message: String(localized: "Saved \(urls.count) files"))
    }

    static func failure(message: String) -> ExportToast {
        ExportToast(kind: .failure, title: String(localized: "EXPORT FAILED"), message: message)
    }

    var iconName: String {
        switch kind {
        case .success: return "checkmark.circle.fill"
        case .failure: return "xmark.octagon.fill"
        }
    }

    var tint: Color {
        switch kind {
        case .success: return TE.success
        case .failure: return TE.danger
        }
    }
}

@MainActor
final class ExportToastCenter: ObservableObject {
    static let shared = ExportToastCenter()

    @Published private(set) var toast: ExportToast?

    private var dismissTask: Task<Void, Never>?

    private init() {}

    func show(_ toast: ExportToast) {
        dismissTask?.cancel()
        withAnimation(.spring(response: 0.28, dampingFraction: 0.86)) {
            self.toast = toast
        }
        dismissTask = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.2)) {
                self.toast = nil
            }
        }
    }

    func clear() {
        dismissTask?.cancel()
        withAnimation(.easeOut(duration: 0.2)) {
            toast = nil
        }
    }
}

struct ExportToastBanner: View {
    let toast: ExportToast
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: toast.iconName)
                .font(.headline.weight(.semibold))
                .foregroundStyle(toast.tint)
                .padding(.top, 1)

            VStack(alignment: .leading, spacing: 4) {
                Text(toast.title)
                    .font(TE.mono(.caption, weight: .bold))
                    .tracking(dynamicTypeSize.isAccessibilitySize ? 0.5 : 1.5)
                    .foregroundStyle(TE.textPrimary)

                Text(toast.message)
                    .font(TE.mono(.caption2, weight: .medium))
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 6)
                .fill(TE.card)
                .shadow(color: .black.opacity(0.12), radius: 12, x: 0, y: 6)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 6)
                .strokeBorder(toast.tint.opacity(0.55), lineWidth: 1)
        )
        .accessibilityElement(children: .combine)
    }
}

#Preview {
    ExportView(viewModel: LocationViewModel(
        modelContext: try! ModelContainer(for: Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self).mainContext,
        locationManager: LocationManager()
    ))
}
