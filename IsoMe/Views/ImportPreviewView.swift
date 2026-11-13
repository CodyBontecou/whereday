import SwiftUI

struct ImportFileSelection: Identifiable {
    let id = UUID()
    let urls: [URL]
}

/// One owner per asynchronous policy review. Kept separate from SwiftUI rendering for deterministic completion tests.
struct ImportReviewState {
    struct Request: Sendable {
        let generation: Int
        let options: ImportOptions
    }
    private var generation = 0
    private(set) var isReviewing = false
    private(set) var errorMessage: String?

    mutating func begin(options: ImportOptions) -> Request {
        generation += 1
        isReviewing = true
        errorMessage = nil
        return Request(generation: generation, options: options)
    }

    mutating func finish(_ request: Request, current: ImportOptions, result: Result<ImportPreview, Error>) -> ImportPreview? {
        guard request.generation == generation else { return nil }
        // Only this generation owns busy/error state. Record selection is not review policy.
        defer { isReviewing = false }
        guard current.sameReviewPolicy(as: request.options) else {
            errorMessage = VisitInferenceError.reviewRequired.localizedDescription
            return nil
        }
        switch result {
        case .success(var preview):
            guard preview.options.sameReviewPolicy(as: request.options) else {
                errorMessage = VisitInferenceError.reviewRequired.localizedDescription
                return nil
            }
            preview.options.includeVisits = current.includeVisits
            preview.options.includePoints = current.includePoints
            return preview
        case .failure(let error): errorMessage = error.localizedDescription; return nil
        }
    }

    mutating func cancel(_ request: Request? = nil) {
        guard request == nil || request?.generation == generation else { return }
        generation += 1
        isReviewing = false
        errorMessage = nil
    }

    func canConfirm(_ preview: ImportPreview, current: ImportOptions) -> Bool {
        !isReviewing && errorMessage == nil && current.sameReviewPolicy(as: preview.options)
    }
}

/// Review before saving: shows what a file would add, lets the person pick record types, then commits.
struct ImportPreviewView: View {
    let urls: [URL]
    let viewModel: LocationViewModel
    /// Called from the failed state's "Choose another file"; the sheet closes so the caller can reopen its picker.
    /// Without it, the failed state offers only Done.
    var onChooseAnotherFile: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ObservedObject private var progress = ImportProgress.shared
    @State private var phase: Phase = .reading
    @State private var options = ImportOptions()
    @State private var reviewState = ImportReviewState()
    private var isReviewing: Bool { reviewState.isReviewing }
    private var reviewError: String? { reviewState.errorMessage }
    @State private var reviewTask: Task<Void, Never>?

    private enum Phase {
        case reading
        case ready(ImportPreview)
        case saving(ImportPreview)
        case complete(ImportCommitResult, ImportPreview)
        /// `detail` is the raw error, shown as secondary text; `whileSaving` is true when the
        /// file was readable but writing it failed.
        case failed(detail: String, whileSaving: Bool)
    }

    private var isSaving: Bool {
        if case .saving = phase { return true }
        return false
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    header
                    phaseContent
                }
                .padding(.bottom, 24)
            }
            .scrollIndicators(.hidden)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                footer
            }
            .background(TE.surface.ignoresSafeArea())
            .foregroundStyle(TE.textPrimary)
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("import-review-screen")
        }
        .interactiveDismissDisabled(isSaving)
        .task {
            do {
                let preview = try await viewModel.prepareImport(from: urls)
                try Task.checkCancellation()
                options = preview.options
                phase = .ready(preview)
            } catch is CancellationError {
                // Closing the sheet cancels preparation without writing anything.
            } catch {
                LogManager.shared.error("Import couldn't read \(url.lastPathComponent) · \(error.localizedDescription)", category: .general)
                phase = .failed(detail: error.localizedDescription, whileSaving: false)
            }
        }
        .onChange(of: options) { old, updated in
            guard !old.sameReviewPolicy(as: updated), case .ready(let preview) = phase else { return }
            reviewTask?.cancel()
            let request = reviewState.begin(options: updated)
            reviewTask = Task {
                do {
                    let reviewed = try await viewModel.reviewImport(preview, options: updated)
                    try Task.checkCancellation()
                    if let accepted = reviewState.finish(request, current: options, result: .success(reviewed)) {
                        phase = .ready(accepted)
                    }
                } catch is CancellationError {
                    reviewState.cancel(request)
                } catch {
                    _ = reviewState.finish(request, current: options, result: .failure(error))
                }
            }
        }
        .onDisappear { reviewTask?.cancel(); reviewState.cancel() }
    }

    private var url: URL { urls[0] }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("NOTHING IS SAVED UNTIL YOU SAY SO")
                .font(TE.mono(.caption2))
                .tracking(1.5)
                .foregroundStyle(TE.textMuted)
                .fixedSize(horizontal: false, vertical: true)
            Text("Review import")
                .font(TE.editorial(.largeTitle))
                .accessibilityAddTraits(.isHeader)
        }
        .padding(.horizontal, 24)
        .padding(.top, 8)
    }

    // MARK: - Phases

    @ViewBuilder
    private var phaseContent: some View {
        switch phase {
        case .reading:
            fileCard(name: url.lastPathComponent, detail: nil)
            statusBlock(title: "Reading and checking duplicates...")
        case .saving(let preview):
            fileCard(name: preview.fileName, detail: fileDetail(preview))
            savingBlock(preview)
        case .ready(let preview):
            review(preview)
        case .complete(let result, let preview):
            VStack(alignment: .leading, spacing: 6) {
                Text("IMPORT COMPLETE")
                    .font(TE.mono(.caption2))
                    .tracking(1.2)
                    .foregroundStyle(TE.onInkCardMuted)
                Text(verbatim: completionHeadline(result))
                    .font(TE.editorial(.title))
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("import-completion-summary")
                Text(verbatim: completionDetail(preview))
                    .font(.subheadline)
                    .fixedSize(horizontal: false, vertical: true)
                Text(verbatim: duplicatesSummary(result))
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.onInkCardMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
            }
            .foregroundStyle(TE.onInkCard)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .background(RoundedRectangle(cornerRadius: 18).fill(TE.inkCard))
            .padding(.horizontal, 16)
            .padding(.top, 28)
        case .failed(let detail, let whileSaving):
            VStack(alignment: .leading, spacing: 6) {
                Text("IMPORT FAILED · NOTHING WAS SAVED")
                    .font(TE.mono(.caption2))
                    .tracking(1.2)
                    .foregroundStyle(TE.inkRust)
                    .fixedSize(horizontal: false, vertical: true)
                Text(whileSaving ? "This import couldn't be saved." : "This file isn't one Whereday can read.")
                    .font(TE.editorial(.title3))
                    .fixedSize(horizontal: false, vertical: true)
                Text(whileSaving
                     ? "Your existing records weren't touched. Try importing the file again."
                     : "Try a Google Timeline JSON export, or a Whereday JSON, CSV or Markdown file. Your existing records weren't touched.")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                if !detail.isEmpty {
                    Text(verbatim: detail)
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 4)
                        .textSelection(.enabled)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.placeInks[1], lineWidth: 1.5))
            .padding(.horizontal, 16)
            .padding(.top, 28)
            .accessibilityElement(children: .combine)
        }
    }

    /// "Saving import…" with the live count of records written so far.
    private func savingBlock(_ preview: ImportPreview) -> some View {
        let total = progress.total > 0 ? progress.total : selectedCount(preview)
        let completed = min(progress.completed, total)
        return VStack(alignment: .leading, spacing: 10) {
            Text("Saving import…")
                .font(TE.editorial(.title3))
            ProgressView(value: Double(completed), total: Double(max(total, 1)))
                .tint(TE.inkRust)
            Text(verbatim: progressCaption(preview, completed: completed, total: total))
                .font(TE.mono(.caption2))
                .tracking(1)
                .foregroundStyle(TE.textMuted)
                .monospacedDigit()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .padding(.horizontal, 16)
        .padding(.top, 12)
        .accessibilityElement(children: .combine)
    }

    private func progressCaption(_ preview: ImportPreview, completed: Int, total: Int) -> String {
        if preview.payload.backup != nil {
            return String(localized: "\(completed.formatted()) OF \(total.formatted()) RECORDS")
        }
        let savesPoints = options.includePoints && preview.newPointCount > 0
        let savesVisits = options.includeVisits && preview.newVisitCount > 0
        let unit: String
        switch (savesPoints, savesVisits) {
        case (false, true): unit = String(localized: "VISITS")
        case (true, true): unit = String(localized: "RECORDS")
        default: unit = String(localized: "POINTS")
        }
        return String(localized: "\(completed.formatted()) OF \(total.formatted()) \(unit)")
    }

    private func completionHeadline(_ result: ImportCommitResult) -> String {
        if result.additionalRecordCount > 0 {
            return String(localized: "\((result.visitCount + result.pointCount + result.additionalRecordCount).formatted()) records restored")
        }
        let points = result.pointCount == 1
            ? String(localized: "1 point")
            : String(localized: "\(result.pointCount.formatted()) points")
        let visits = result.visitCount == 1
            ? String(localized: "1 visit")
            : String(localized: "\(result.visitCount.formatted()) visits")
        switch (result.pointCount > 0, result.visitCount > 0) {
        case (true, true): return String(localized: "\(points) and \(visits) added")
        case (false, true): return String(localized: "\(visits) added")
        default: return String(localized: "\(points) added")
        }
    }

    /// "Five years of days now show in Atlas." from the imported file's real date range.
    private func completionDetail(_ preview: ImportPreview) -> String {
        if preview.payload.backup != nil {
            return String(localized: "Your restored records are available in Whereday. Photo matches need access to the original Photos library.")
        }
        guard let range = preview.payload.dateRange else {
            return String(localized: "They now show in Atlas.")
        }
        let calendar = Calendar.current
        let days = (calendar.dateComponents([.day], from: calendar.startOfDay(for: range.lowerBound),
                                            to: calendar.startOfDay(for: range.upperBound)).day ?? 0) + 1
        if days <= 1 {
            return String(localized: "\(range.lowerBound.formatted(date: .abbreviated, time: .omitted)) now shows in Atlas.")
        }
        let formatter = DateComponentsFormatter()
        formatter.unitsStyle = .spellOut
        formatter.allowedUnits = [.year, .month, .day]
        formatter.maximumUnitCount = 1
        guard let span = formatter.string(from: range.lowerBound, to: range.upperBound.addingTimeInterval(86_400)) else {
            return String(localized: "They now show in Atlas.")
        }
        let sentence = String(localized: "\(span) of days now show in Atlas.")
        return sentence.prefix(1).uppercased() + sentence.dropFirst()
    }

    private func duplicatesSummary(_ result: ImportCommitResult) -> String {
        String(localized: "\((result.duplicatePointCount + result.duplicateVisitCount + result.duplicateAdditionalRecordCount).formatted()) DUPLICATES SKIPPED")
    }

    private func statusBlock(title: LocalizedStringKey) -> some View {
        HStack(spacing: 12) {
            ProgressView()
                .tint(TE.inkRust)
            Text(title)
                .font(TE.editorial(.title3))
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24)
        .padding(.top, 32)
        .accessibilityElement(children: .combine)
    }

    private func fileCard(name: String, detail: String?) -> some View {
        HStack(spacing: 12) {
            Image(systemName: "doc")
                .font(.title2.weight(.light))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: name)
                    .font(TE.mono(.footnote))
                    .lineLimit(2)
                    .truncationMode(.middle)
                if let detail {
                    Text(verbatim: detail)
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .accessibilityElement(children: .combine)
    }

    // MARK: - Review

    @ViewBuilder
    private func review(_ preview: ImportPreview) -> some View {
        if let backup = preview.payload.backup {
            fileCard(name: preview.fileName, detail: String(localized: "Whereday backup"))
            VStack(alignment: .leading, spacing: 12) {
                Text("Restore your records")
                    .font(TE.editorial(.title2))
                Text("\(backup.visits.count.formatted()) visits · \(backup.points.count.formatted()) points")
                Text("\(backup.recordingSessions.count.formatted()) outings · \(backup.savedPlaces.count.formatted()) saved places")
                Text("\(backup.countryStays.count.formatted()) country stays · \(backup.photoMoments.count.formatted()) photo matches")
                Text("\(selectedCount(preview).formatted()) new records")
                    .fontWeight(.semibold)
                Text("Existing record IDs are skipped. Nothing on this device is replaced. Photo matches need the original Photos library; image files are not part of the backup.")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
            .padding(.horizontal, 16)
            .padding(.top, 12)
            .accessibilityIdentifier("backup-review-summary")
        } else {
            standardReview(preview)
        }
    }

    @ViewBuilder
    private func standardReview(_ preview: ImportPreview) -> some View {
        fileCard(name: preview.fileName, detail: fileDetail(preview))

        VStack(alignment: .leading, spacing: 12) {
            recordToggle(
                count: preview.newPointCount,
                singular: "point",
                plural: "points",
                caption: String(localized: "NEW · \(preview.duplicatePointCount.formatted()) DUPLICATES SKIPPED"),
                isOn: $options.includePoints,
                isAvailable: !preview.payload.points.isEmpty
            )
            .accessibilityIdentifier("import-include-points")

            hairline

            recordToggle(
                count: preview.newVisitCount,
                singular: "visit",
                plural: "visits",
                caption: String(localized: "NEW · \(preview.duplicateVisitCount.formatted()) DUPLICATES SKIPPED"),
                isOn: $options.includeVisits,
                isAvailable: preview.newVisitCount + preview.duplicateVisitCount > 0
            )
            .accessibilityIdentifier("import-include-visits")

            if [.googleRaw, .googleSemantic, .googleDevice, .googleMixed].contains(preview.payload.format) {
                hairline
                inferenceControls(preview)
            }

            if let skipped = skippedSummary(preview) {
                hairline
                Text(verbatim: skipped)
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .padding(.horizontal, 16)
        .padding(.top, 12)

        VStack(alignment: .leading, spacing: 6) {
            Text("Duplicates are always skipped. Existing records are never changed.")
            Text("Files are processed locally on this device. Import does not sign into Google, upload your history, or look up place names.")
            Text("Duplicate matching is approximate. Review the counts before importing.")
        }
        .font(.caption)
        .foregroundStyle(TE.textMuted)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, 24)
        .padding(.top, 12)
    }

    @ViewBuilder
    private func inferenceControls(_ preview: ImportPreview) -> some View {
        if !preview.payload.rawInferencePoints.isEmpty {
            Toggle("Infer visits from raw points", isOn: Binding(
                get: { options.inferenceEnabled(for: preview.payload) },
                set: { options.inferVisits = $0 }
            ))
            .accessibilityIdentifier("import-infer-visits")
            Text("\(preview.inferredVisitCount.formatted()) inferred candidates before duplicate matching")
                .font(.caption)
                .accessibilityIdentifier("import-inferred-count")
            if options.inferenceEnabled(for: preview.payload) {
                Stepper("Dwell: \(Int(options.inference.minimumDwellSeconds / 60)) min", value: Binding(
                    get: { options.inference.minimumDwellSeconds / 60 },
                    set: { options.inference.minimumDwellSeconds = $0 * 60 }
                ), in: 5...120, step: 5)
                Stepper("Radius: \(Int(options.inference.radiusMeters)) m", value: $options.inference.radiusMeters, in: 25...500, step: 25)
            }
            if preview.payload.unorderableRawObservationCount > 0 {
                Text("Inference is disabled for this selection because a rejected raw observation has no valid timestamp. Valid route points can still be imported.")
                    .font(.caption)
                    .foregroundStyle(TE.inkRust)
                    .accessibilityIdentifier("import-unorderable-inference-warning")
            } else if !preview.payload.rawInferenceBarrierDates.isEmpty {
                Text("\(preview.payload.rawInferenceBarrierDates.count.formatted()) rejected raw samples break inference continuity")
                    .font(.caption)
                    .foregroundStyle(TE.textMuted)
            }
            Text("Inferred visits are estimates from recorded times, not Google place visits. Sparse, moving, unknown-accuracy and poor-accuracy samples cannot establish a visit. Semantic and existing visit windows are protected.")
                .font(.caption)
                .foregroundStyle(TE.textMuted)
        }
        Stepper("Accepted accuracy: \(Int(options.inference.maximumAccuracyMeters)) m", value: $options.inference.maximumAccuracyMeters, in: 1...500, step: 25)
        Toggle("Include poor-accuracy route points", isOn: $options.includePoorAccuracyPoints)
            .accessibilityIdentifier("import-include-poor-accuracy")
        Text("\(preview.filteredPointCount.formatted()) route points excluded by accuracy")
            .font(.caption)
        if preview.unknownAccuracyPointCount > 0 {
            Text("\(preview.unknownAccuracyPointCount.formatted()) route samples have unknown accuracy; excluded from visit inference")
                .font(.caption)
                .accessibilityIdentifier("import-unknown-accuracy-count")
        }
        Text("Unknown accuracy is retained for routes only. Poor-accuracy route opt-in never overrides the inference accuracy limit.")
            .font(.caption)
            .foregroundStyle(TE.textMuted)
        if preview.suppressedInferredVisitCount > 0 {
            Text("\(preview.suppressedInferredVisitCount.formatted()) inferred candidates suppressed by existing visits")
                .font(.caption)
        }
        if isReviewing {
            ProgressView("Updating review…")
        }
        if let reviewError {
            Text(verbatim: reviewError).font(.caption).foregroundStyle(TE.inkRust)
        }
    }

    private var hairline: some View {
        Rectangle()
            .fill(TE.dialTrack)
            .frame(height: 1)
            .accessibilityHidden(true)
    }

    private func recordToggle(
        count: Int,
        singular: LocalizedStringKey,
        plural: LocalizedStringKey,
        caption: String,
        isOn: Binding<Bool>,
        isAvailable: Bool
    ) -> some View {
        Toggle(isOn: isOn) {
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(count.formatted())
                    Text(count == 1 ? singular : plural)
                }
                .font(TE.editorial(.title))
                Text(verbatim: caption)
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(isAvailable ? TE.textPrimary : TE.textMuted)
        }
        .toggleStyle(TEToggleStyle())
        .disabled(!isAvailable)
    }

    private func fileDetail(_ preview: ImportPreview) -> String {
        var parts = [preview.payload.format.displayName.uppercased()]
        if preview.sourceByteCount > 0 {
            parts.append(ByteCountFormatter.string(fromByteCount: preview.sourceByteCount, countStyle: .file))
        }
        if let range = preview.payload.dateRange {
            let lower = range.lowerBound.formatted(date: .abbreviated, time: .omitted)
            let upper = range.upperBound.formatted(date: .abbreviated, time: .omitted)
            parts.append(lower == upper ? lower : "\(lower) – \(upper)")
        }
        return parts.joined(separator: " · ")
    }

    private func skippedSummary(_ preview: ImportPreview) -> String? {
        var parts: [String] = []
        if preview.payload.invalidRecordCount > 0 {
            parts.append(String(localized: "\(preview.payload.invalidRecordCount.formatted()) INVALID"))
        }
        if preview.payload.unsupportedRecordCount > 0 {
            parts.append(String(localized: "\(preview.payload.unsupportedRecordCount.formatted()) UNSUPPORTED"))
        }
        guard !parts.isEmpty else { return nil }
        return String(localized: "\(parts.joined(separator: " · ")) ENTRIES SKIPPED")
    }

    // MARK: - Footer

    @ViewBuilder
    private var footer: some View {
        VStack(spacing: 4) {
            switch phase {
            case .ready(let preview):
                if selectedCount(preview) == 0 {
                    Text("No new records selected.")
                        .font(.footnote)
                        .foregroundStyle(TE.textMuted)
                }
                Button {
                    progress.reset()
                    phase = .saving(preview)
                    Task {
                        do {
                            let result = try await viewModel.commitImport(preview, options: options)
                            phase = .complete(result, preview)
                            LogManager.shared.info("Imported \(result.pointCount) points and \(result.visitCount) visits · \(preview.payload.format.displayName)", category: .general)
                        } catch {
                            LogManager.shared.error("Import failed · \(error.localizedDescription)", category: .general)
                            phase = .failed(detail: error.localizedDescription, whileSaving: true)
                        }
                    }
                } label: {
                    if preview.payload.backup != nil {
                        Text("Restore backup")
                    } else {
                        Text("Import selected records")
                    }
                }
                .buttonStyle(ArchiveCapsuleButtonStyle(filled: true))
                .disabled(selectedCount(preview) == 0 || !reviewState.canConfirm(preview, current: options))
                .accessibilityIdentifier("confirm-import")
                Button("Cancel", role: .cancel) { dismiss() }
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .buttonStyle(.plain)
            case .saving:
                Button {} label: { Text("Saving…") }
                    .buttonStyle(ArchiveCapsuleButtonStyle(filled: true))
                    .disabled(true)
            case .complete:
                Button("Done") { dismiss() }
                    .buttonStyle(ArchiveCapsuleButtonStyle(filled: true))
            case .failed:
                if let onChooseAnotherFile {
                    Button("Choose another file") {
                        onChooseAnotherFile()
                        dismiss()
                    }
                    .buttonStyle(ArchiveCapsuleButtonStyle(filled: true))
                    Button("Cancel") { dismiss() }
                        .font(.subheadline)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .buttonStyle(.plain)
                } else {
                    Button("Done") { dismiss() }
                        .buttonStyle(ArchiveCapsuleButtonStyle(filled: true))
                }
            case .reading:
                Button("Cancel", role: .cancel) { dismiss() }
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .padding(.bottom, 12)
        .background(TE.surface)
    }

    private func selectedCount(_ preview: ImportPreview) -> Int {
        if preview.payload.backup != nil {
            return preview.newVisitCount + preview.newPointCount + preview.newAdditionalRecordCount
        }
        return (options.includeVisits ? preview.newVisitCount : 0) + (options.includePoints ? preview.newPointCount : 0)
    }
}
