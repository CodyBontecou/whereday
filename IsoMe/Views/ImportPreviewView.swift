import SwiftUI

struct ImportFileSelection: Identifiable {
    let id = UUID()
    let url: URL
}

/// Review before saving: shows what a file would add, lets the person pick record types, then commits.
struct ImportPreviewView: View {
    let url: URL
    let viewModel: LocationViewModel
    /// Called from the failed state's "Choose another file"; the sheet closes so the caller can reopen its picker.
    /// Without it, the failed state offers only Done.
    var onChooseAnotherFile: (() -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @ObservedObject private var progress = ImportProgress.shared
    @State private var phase: Phase = .reading
    @State private var options = ImportOptions()

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
                let preview = try await viewModel.prepareImport(from: url)
                try Task.checkCancellation()
                options.includeVisits = !preview.payload.visits.isEmpty
                options.includePoints = !preview.payload.points.isEmpty
                phase = .ready(preview)
            } catch is CancellationError {
                // Closing the sheet cancels preparation without writing anything.
            } catch {
                LogManager.shared.error("Import couldn't read \(url.lastPathComponent) · \(error.localizedDescription)", category: .general)
                phase = .failed(detail: error.localizedDescription, whileSaving: false)
            }
        }
    }

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
        String(localized: "\((result.duplicatePointCount + result.duplicateVisitCount).formatted()) DUPLICATES SKIPPED")
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
                isAvailable: !preview.payload.visits.isEmpty
            )
            .accessibilityIdentifier("import-include-visits")

            if preview.payload.format == .googleRaw {
                Text("Raw location history contains points only. Visits are not inferred in this version.")
                    .font(.caption)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
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
        if let size = fileSizeLabel {
            parts.append(size)
        }
        if let range = preview.payload.dateRange {
            let lower = range.lowerBound.formatted(date: .abbreviated, time: .omitted)
            let upper = range.upperBound.formatted(date: .abbreviated, time: .omitted)
            parts.append(lower == upper ? lower : "\(lower) – \(upper)")
        }
        return parts.joined(separator: " · ")
    }

    private var fileSizeLabel: String? {
        guard let bytes = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize else { return nil }
        return ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
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
                    Text("Import selected records")
                }
                .buttonStyle(ArchiveCapsuleButtonStyle(filled: true))
                .disabled(selectedCount(preview) == 0)
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
        (options.includeVisits ? preview.newVisitCount : 0) + (options.includePoints ? preview.newPointCount : 0)
    }
}
