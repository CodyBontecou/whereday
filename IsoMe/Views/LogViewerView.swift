import SwiftUI

struct LogViewerView: View {
    private enum Filter: CaseIterable, Identifiable {
        case all, location, export, webhook, errors
        var id: Self { self }

        var title: LocalizedStringKey {
            switch self {
            case .all: "All"
            case .location: "Location"
            case .export: "Export"
            case .webhook: "Webhook"
            case .errors: "Errors"
            }
        }

        func includes(_ entry: LogManager.LogEntry) -> Bool {
            switch self {
            case .all: true
            case .location: entry.category == .location
            case .export: entry.category == .export
            case .webhook: entry.category == .webhook
            case .errors: entry.level == .error
            }
        }
    }

    @ObservedObject private var logManager = LogManager.shared
    @State private var showingCopyConfirmation = false
    @State private var filter: Filter = .all

    private var visibleEntries: [LogManager.LogEntry] {
        logManager.entries.reversed().filter { filter.includes($0) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            BackPageHeader(caption: "WHAT THE APP WROTE DOWN", title: "Diagnostics")
                .padding(.horizontal, 24)
                .padding(.top, 8)

            if logManager.entries.isEmpty {
                emptyState
            } else {
                filterChips
                    .padding(.horizontal, 16)
                    .padding(.top, 16)
                entryList
                    .padding(.horizontal, 16)
                    .padding(.top, 14)
            }

            actions
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 16)
        }
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                if !logManager.entries.isEmpty {
                    Button {
                        logManager.clear()
                    } label: {
                        Image(systemName: "trash")
                            .foregroundStyle(TE.inkRust)
                    }
                    .accessibilityLabel("Clear logs")
                }
            }
        }
        .alert("Copied to Clipboard", isPresented: $showingCopyConfirmation) {
            Button("OK", role: .cancel) {}
        }
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Text("NO LOG ENTRIES")
                .font(TE.mono(.caption2))
                .tracking(1.5)
                .foregroundStyle(TE.textMuted)
            Text("Nothing to report yet.")
                .font(TE.editorial(.title2))
        }
        .multilineTextAlignment(.center)
        .padding(.horizontal, 40)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }

    private var filterChips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 6) {
                ForEach(Filter.allCases) { option in
                    let isSelected = option == filter
                    Button { filter = option } label: {
                        Text(option.title)
                            .font(.caption.weight(.medium))
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .foregroundStyle(isSelected ? TE.surface : (option == .errors ? TE.inkRust : TE.textPrimary))
                            .background(Capsule().fill(isSelected ? TE.textPrimary : Color.clear))
                            .overlay(Capsule().strokeBorder(isSelected ? TE.textPrimary : TE.border, lineWidth: 1))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isSelected ? .isSelected : [])
                }
            }
        }
        .scrollIndicators(.hidden)
    }

    private var entryList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if visibleEntries.isEmpty {
                    Text("No entries match this filter.")
                        .font(.footnote)
                        .foregroundStyle(TE.textMuted)
                        .padding(16)
                }
                ForEach(Array(visibleEntries.enumerated()), id: \.element.id) { index, entry in
                    if index > 0 {
                        BackPageDivider()
                    }
                    logRow(entry)
                }
            }
        }
        .frame(maxHeight: .infinity)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 18))
    }

    private func logRow(_ entry: LogManager.LogEntry) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 10) {
                Text(entry.timestamp.formatted(.verbatim("\(hour: .twoDigits(clock: .twentyFourHour, hourCycle: .zeroBased)):\(minute: .twoDigits):\(second: .twoDigits)", timeZone: .current, calendar: .current)))
                    .font(TE.mono(.caption))
                    .foregroundStyle(TE.textMuted)
                Text(verbatim: entry.category.rawValue)
                    .font(TE.mono(.caption2))
                    .tracking(1)
                    .foregroundStyle(colorForLevel(entry.level))
                if entry.level != .info {
                    Text(verbatim: entry.level.rawValue)
                        .font(TE.mono(.caption2))
                        .tracking(1)
                        .foregroundStyle(colorForLevel(entry.level))
                }
            }
            Text(verbatim: entry.message)
                .font(TE.mono(.footnote))
                .foregroundStyle(entry.level == .info ? TE.textPrimary : colorForLevel(entry.level))
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private func colorForLevel(_ level: LogManager.LogEntry.Level) -> Color {
        switch level {
        case .info: return TE.textPrimary
        case .warning: return TE.placeInks[2]
        case .error: return TE.inkRust
        }
    }

    private var actions: some View {
        let layout = AnyLayout(HStackLayout(spacing: 8))
        return layout {
            Button("Copy") {
                UIPasteboard.general.string = logManager.exportText
                showingCopyConfirmation = true
            }
            .buttonStyle(BackPagePillButtonStyle(kind: .outlined, minHeight: 48))

            ShareLink(item: logManager.exportText) {
                Text("Share with support")
            }
            .buttonStyle(BackPagePillButtonStyle(kind: .filled, minHeight: 48))
        }
        .disabled(logManager.entries.isEmpty)
    }
}
