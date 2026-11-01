import SwiftUI

struct ImportFileSelection: Identifiable {
    let id = UUID()
    let url: URL
}

/// Review before saving: shows what a file would add, lets the person pick record types, then commits.
struct ImportPreviewView: View {
    let url: URL
    let viewModel: LocationViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var phase: Phase = .reading
    @State private var options = ImportOptions()

    private enum Phase {
        case reading
        case ready(ImportPreview)
        case saving
        case complete(ImportCommitResult)
        case failed(String)
    }

    private var isSaving: Bool {
        if case .saving = phase { return true }
        return false
    }

    private var isFinished: Bool {
        switch phase {
        case .complete, .failed: return true
        case .reading, .ready, .saving: return false
        }
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
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    if !isFinished {
                        Button("Cancel", role: .cancel) { dismiss() }
                            .disabled(isSaving)
                    }
                }
            }
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
                phase = .failed(error.localizedDescription)
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
        case .saving:
            statusBlock(title: "Saving import...")
        case .ready(let preview):
            review(preview)
        case .complete(let result):
            VStack(alignment: .leading, spacing: 6) {
                Text("IMPORT COMPLETE")
                    .font(TE.mono(.caption2))
                    .tracking(1.2)
                    .foregroundStyle(TE.onInkCardMuted)
                Text(result.summary)
                    .font(TE.editorial(.title2))
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("import-completion-summary")
            }
            .foregroundStyle(TE.onInkCard)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .background(RoundedRectangle(cornerRadius: 18).fill(TE.inkCard))
            .padding(.horizontal, 16)
            .padding(.top, 28)
        case .failed(let message):
            VStack(alignment: .leading, spacing: 6) {
                Text("IMPORT FAILED")
                    .font(TE.mono(.caption2))
                    .tracking(1.2)
                    .foregroundStyle(TE.inkRust)
                Text(message)
                    .font(TE.editorial(.title3))
                    .fixedSize(horizontal: false, vertical: true)
                Text("Try a Google Timeline JSON export, or a Whereday JSON, CSV or Markdown file.")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
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
                    phase = .saving
                    Task {
                        do {
                            phase = .complete(try await viewModel.commitImport(preview, options: options))
                        } catch {
                            phase = .failed(error.localizedDescription)
                        }
                    }
                } label: {
                    Text("Import Selected Records")
                }
                .buttonStyle(ArchiveCapsuleButtonStyle(filled: true))
                .disabled(selectedCount(preview) == 0)
                .accessibilityIdentifier("confirm-import")
            case .saving:
                Button {} label: { Text("Saving…") }
                    .buttonStyle(ArchiveCapsuleButtonStyle(filled: true))
                    .disabled(true)
            case .complete, .failed:
                Button("Done") { dismiss() }
                    .buttonStyle(ArchiveCapsuleButtonStyle(filled: true))
            case .reading:
                EmptyView()
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
