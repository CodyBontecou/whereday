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

    private static var appName: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "Whereday"
    }

    private var groups: [ActionGroup] {
        let app = Self.appName
        return [
            ActionGroup(name: "RECORD", actions: [
                Action(title: "Start Tracking", detail: "Begin recording your route", phrase: String(localized: "Start \(app)")),
                Action(title: "Stop Tracking", detail: "End the current tracking session", phrase: String(localized: "Stop tracking with \(app)")),
                Action(title: "Check In", detail: "Start a manual visit at a saved place, coordinates or your current location", phrase: String(localized: "Check in with \(app)")),
                Action(title: "Check Out", detail: "Finish an open manual visit", phrase: nil)
            ]),
            ActionGroup(name: "ASK", actions: [
                Action(title: "Today's Distance", detail: "How far you've moved today", phrase: String(localized: "How far have I gone with \(app)")),
                Action(title: "Today's Tracking Duration", detail: "How long the current session has been running", phrase: String(localized: "How long have I been tracking with \(app)")),
                Action(title: "Today's Visits", detail: "Number of visits logged today", phrase: String(localized: "How many visits today in \(app)")),
                Action(title: "Get Visits", detail: "Visits in a date range, for other shortcuts", phrase: nil),
                Action(title: "Get Movements", detail: "Outings in a date range, for other shortcuts", phrase: nil),
                Action(title: "Get Current Outing Name", detail: "The name of the outing being recorded", phrase: nil)
            ]),
            ActionGroup(name: "CHANGE", actions: [
                Action(title: "Rename Current Outing", detail: "Name the outing being recorded", phrase: String(localized: "Rename current outing in \(app)")),
                Action(title: "Rename Visit", detail: "Fix a place name after the fact", phrase: String(localized: "Rename a visit in \(app)")),
                Action(title: "Rename Movement", detail: "Label a recorded movement", phrase: String(localized: "Rename a movement in \(app)")),
                Action(title: "Export Data", detail: "Export visits, points or outings for a date range as a file", phrase: String(localized: "Export \(app) data"))
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
            Text("→ Answers with today's distance")
                .font(TE.mono(.caption))
                .foregroundStyle(TE.liveDot)
        }
        .foregroundStyle(TE.onInkCard)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.inkCard))
        .accessibilityElement(children: .combine)
    }

    private func actionRow(_ action: Action) -> some View {
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
        .frame(maxWidth: .infinity, minHeight: 56, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .accessibilityElement(children: .combine)
    }
}
