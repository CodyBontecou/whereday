import SwiftUI
import AppIntents

/// Lists the App Shortcuts and Siri phrases Whereday offers.
/// Mirrors the intents in IsoMe*Intents.swift and the phrases in IsoMeAppShortcuts.
struct ShortcutsGuideView: View {
    private struct Action: Identifiable {
        let title: LocalizedStringKey
        let detail: LocalizedStringKey
        /// The first Siri phrase from IsoMeAppShortcuts; nil when the action is only in the Shortcuts app.
        let phrase: String?
        var id: String { "\(title)" }
    }

    private struct ActionGroup: Identifiable {
        let name: LocalizedStringKey
        let actions: [Action]
        var id: String { "\(name)" }
    }

    @Environment(\.openURL) private var openURL

    private static var appName: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "Whereday"
    }

    private var groups: [ActionGroup] {
        let app = Self.appName
        return [
            ActionGroup(name: "RECORD", actions: [
                Action(title: "Start Tracking", detail: "Begin a recorded outing", phrase: String(localized: "Start \(app)")),
                Action(title: "Stop Tracking", detail: "End the current outing", phrase: String(localized: "Stop tracking with \(app)")),
                Action(title: "Check In", detail: "Start a manual visit at a saved place or coordinates", phrase: String(localized: "Check in with \(app)")),
                Action(title: "Check Out", detail: "Finish an open visit", phrase: nil)
            ]),
            ActionGroup(name: "ASK", actions: [
                Action(title: "Today's Distance", detail: "Kilometres or miles moved since midnight", phrase: String(localized: "How far have I gone with \(app)")),
                Action(title: "Today's Tracking Duration", detail: "How long recording has run", phrase: String(localized: "How long have I been tracking with \(app)")),
                Action(title: "Today's Visits", detail: "Places stopped at today", phrase: String(localized: "How many visits today in \(app)")),
                Action(title: "Get Visits", detail: "Visits in a date range, for other shortcuts", phrase: nil),
                Action(title: "Get Movements", detail: "Walks, rides and drives in a range", phrase: nil),
                Action(title: "Get Current Outing Name", detail: "The name of what you are recording", phrase: nil)
            ]),
            ActionGroup(name: "CHANGE", actions: [
                Action(title: "Rename Current Outing", detail: "Give the live recording a name", phrase: String(localized: "Rename current outing in \(app)")),
                Action(title: "Rename Visit", detail: "Fix a place name after the fact", phrase: String(localized: "Rename a visit in \(app)")),
                Action(title: "Rename Movement", detail: "Label a walk, ride or drive", phrase: String(localized: "Rename a movement in \(app)")),
                Action(title: "Export Data", detail: "Run an export in any format", phrase: String(localized: "Export \(app) data"))
            ])
        ]
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackPageHeader(caption: "ASK, LOG AND RENAME WITHOUT OPENING THE APP", title: "Shortcuts & Siri")
                    .padding(.horizontal, 24)
                    .padding(.top, 8)

                trySaying
                    .padding(.horizontal, 16)
                    .padding(.top, 18)

                ShortcutsLink()
                    .shortcutsLinkStyle(.automaticOutline)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, 16)
                    .padding(.top, 14)

                ForEach(groups) { group in
                    BackPageLabel(group.name)
                    BackPageCard {
                        ForEach(Array(group.actions.enumerated()), id: \.element.id) { index, action in
                            if index > 0 { BackPageDivider() }
                            actionRow(action)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
    }

    private var trySaying: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("TRY SAYING")
                .font(TE.mono(.caption2))
                .tracking(1.2)
                .foregroundStyle(TE.onInkCardMuted)
            Text("“Hey Siri, how far have I gone with \(Self.appName)?”")
                .font(TE.editorial(.title2))
                .fixedSize(horizontal: false, vertical: true)
            TimelineView(.periodic(from: .now, by: 30)) { context in
                Text(verbatim: "→ " + Self.liveAnswer(now: context.date))
                    .font(TE.mono(.caption))
                    .foregroundStyle(TE.liveDot)
                    .contentTransition(.numericText())
            }
        }
        .foregroundStyle(TE.onInkCard)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.inkCard))
        .accessibilityElement(children: .combine)
    }

    /// What Today's Distance would answer right now, from the same shared snapshot the intent reads.
    static func liveAnswer(now: Date = Date(), snapshot: SharedLocationData? = SharedLocationData.load()) -> String {
        let data = snapshot ?? .empty
        // A snapshot last written before midnight still holds yesterday's total.
        let isToday = data.lastUpdateTime.map { Calendar.current.isDate($0, inSameDayAs: now) } ?? false
        let meters = isToday ? data.todayDistanceMeters : 0
        guard meters > 0 else { return String(localized: "You haven't moved yet today") }
        let formatted = DistanceFormatter.format(meters: meters, usesMetric: data.prefersMetricDistanceUnits)
        return String(localized: "\(formatted) since midnight")
    }

    private func actionRow(_ action: Action) -> some View {
        HStack(spacing: 12) {
            actionText(action)
            Button {
                // App Shortcuts can't be added one by one from inside an app; this opens Shortcuts, where they're listed.
                openURL(Self.shortcutsAppURL)
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 15, weight: .regular))
                    .frame(width: 40, height: 40)
                    .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .frame(minWidth: 44, minHeight: 44)
            .accessibilityLabel(Text("Add \(Text(action.title)) to Shortcuts"))
        }
        .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private static let shortcutsAppURL = URL(string: "shortcuts://")!

    private func actionText(_ action: Action) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(action.title)
                .font(.subheadline)
            Text(action.detail)
                .font(.caption)
                .foregroundStyle(TE.textMuted)
            Group {
                if let phrase = action.phrase {
                    Text(verbatim: "“\(phrase)”")
                } else {
                    Text("In the Shortcuts app")
                }
            }
            .font(TE.mono(.caption2))
            .foregroundStyle(action.phrase == nil ? TE.textMuted : TE.inkRust)
            .padding(.top, 2)
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}
