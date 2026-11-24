import SwiftUI
import UserNotifications

/// Settings › Reminders: the evening recap's frequency, time and privacy, with a preview of the next one.
struct RemindersSettingsView: View {
    @ObservedObject private var scheduler = RecapReminderScheduler.shared
    @ObservedObject private var storeManager = StoreManager.shared
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    @Environment(\.openURL) private var openURL

    private var settings: RecapReminderSettings { scheduler.settings }
    private var isOff: Bool { !settings.frequency.isOn }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                BackPageHeader(caption: "NOTIFICATIONS · BUILT ON THIS IPHONE", title: "Reminders")
                    .padding(.horizontal, 24)
                    .padding(.top, 8)

                RecapPreviewCard(preview: preview, isOff: isOff)
                    .padding(.horizontal, 16)
                    .padding(.top, 16)

                if !isOff, scheduler.authorizationStatus == .denied {
                    deniedCard
                        .padding(.horizontal, 16)
                        .padding(.top, 12)
                }

                BackPageLabel("DAY RECAP")
                frequencyCard
                    .padding(.horizontal, 16)

                optionsCard
                    .padding(.horizontal, 16)
                    .padding(.top, 12)

                if let trialEnds = storeManager.activeTrialEnds {
                    trialCard(trialEnds)
                        .padding(.horizontal, 16)
                        .padding(.top, 12)
                }

                BackPageFootnote("Recaps are written on this iPhone from your own days. Nothing is sent anywhere to make them.")
            }
            .padding(.bottom, 40)
        }
        .scrollIndicators(.hidden)
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .task {
            await scheduler.refreshAuthorizationStatus()
            scheduler.setNeedsRefresh(after: 0)
        }
    }

    // MARK: Preview

    /// The real counts so far when there are any; otherwise the onboarding's synthetic sample day, labelled as an example.
    private var preview: RecapPreview {
        let kind = settings.frequency.periodKind
        let plan = scheduler.plan.flatMap { $0.summary.kind == kind ? $0 : nil }
        let isReal = plan.map { !RecapSkipRules.isEmpty($0.summary) } ?? false
        let summary = isReal ? plan!.summary : RecapContentBuilder.exampleSummary(kind: kind)
        let content = RecapContentBuilder.content(for: summary, showPlaceNames: settings.showPlaceNames,
                                                  usesMetric: usesMetricDistanceUnits)
        let kicker: LocalizedStringKey = !isReal ? "EXAMPLE" : (kind == .week ? "SO FAR THIS WEEK" : "SO FAR TODAY")
        return RecapPreview(kicker: kicker, title: content.title, when: whenText, body: content.body,
                            note: plan?.skipReason.map(skipNote))
    }

    private var whenText: String {
        let clock = ClockTimeFormatter.string(
            from: Calendar.current.date(bySettingHour: settings.hour, minute: settings.minute, second: 0, of: .now) ?? .now
        )
        switch settings.frequency {
        case .off: return ""
        case .daily: return String(localized: "Daily \(clock)")
        case .weekdays: return String(localized: "Mon–Fri \(clock)")
        case .weekly: return String(localized: "Sun \(clock)")
        }
    }

    private func skipNote(_ reason: RecapSkipReason) -> LocalizedStringKey {
        switch reason {
        case .noAccess: "Not sent while your plan is inactive."
        case .trackingOff: "Not sent while recording is off."
        case .nothingRecorded: "Nothing recorded yet. A recap is sent only when something was recorded."
        case .openedThisEvening: "Not sent this time: you already opened Whereday this evening."
        case .quietDay: "Quiet so far. Skip quiet days holds this one back."
        }
    }

    // MARK: Permission

    private var deniedCard: some View {
        BackPageCard {
            Button {
                if let url = URL(string: UIApplication.openNotificationSettingsURLString) { openURL(url) }
            } label: {
                BackPageRowLabel(
                    title: Text("Notifications are off for Whereday"),
                    subtitle: Text("Recaps can’t appear until you allow notifications. Opens iOS Settings."),
                    accessory: .external,
                    minHeight: 60
                )
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("reminders-denied")
        }
    }

    // MARK: Frequency

    private var frequencyCard: some View {
        BackPageCard {
            ForEach(Array(RecapFrequency.allCases.enumerated()), id: \.element) { index, frequency in
                if index > 0 { BackPageDivider() }
                frequencyRow(frequency)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("How often"))
    }

    private func frequencyRow(_ frequency: RecapFrequency) -> some View {
        let isSelected = settings.frequency == frequency
        return Button {
            scheduler.update { $0.frequency = frequency }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title(for: frequency)).font(.subheadline)
                    Text(hint(for: frequency)).font(.caption).foregroundStyle(TE.textMuted)
                }
                .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                if isSelected {
                    Image(systemName: "checkmark")
                        .font(.subheadline.weight(.semibold))
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .frame(minHeight: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("reminders-frequency-\(frequency.rawValue)")
    }

    private func title(for frequency: RecapFrequency) -> LocalizedStringKey {
        switch frequency {
        case .off: "Off"
        case .daily: "Every evening"
        case .weekdays: "Weekdays"
        case .weekly: "Weekly, on Sunday"
        }
    }

    private func hint(for frequency: RecapFrequency) -> LocalizedStringKey {
        switch frequency {
        case .off: "Never send a recap."
        case .daily: "A short recap each night."
        case .weekdays: "Monday to Friday evenings."
        case .weekly: "Your week in one notification."
        }
    }

    // MARK: Time and privacy

    private var optionsCard: some View {
        BackPageCard {
            VStack(alignment: .leading, spacing: 10) {
                Text("Time").font(.subheadline.weight(.semibold))
                OnboardingFlowLayout(spacing: 6) {
                    ForEach(RecapTimePreset.allCases) { preset in
                        timeChip(preset)
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityLabel(Text("Recap time"))
                if settings.timePreset == .custom {
                    DatePicker("Custom time", selection: customTime, displayedComponents: .hourAndMinute)
                        .font(.subheadline)
                        .tint(TE.textPrimary)
                        .accessibilityIdentifier("reminders-time-picker")
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .disabled(isOff)
            .opacity(isOff ? 0.4 : 1)

            BackPageDivider()
            BackPageToggleRow(title: "Skip quiet days", subtitle: "Nothing new, no notification.",
                              isOn: binding(\.skipQuietDays))
                .disabled(isOff)
                .opacity(isOff ? 0.4 : 1)
                .accessibilityIdentifier("reminders-skip-quiet")
            BackPageDivider()
            BackPageToggleRow(title: "Show place names", subtitle: "Visible on your Lock Screen. Off shows counts only.",
                              isOn: binding(\.showPlaceNames))
                .disabled(isOff)
                .opacity(isOff ? 0.4 : 1)
                .accessibilityIdentifier("reminders-place-names")
        }
    }

    private func timeChip(_ preset: RecapTimePreset) -> some View {
        let isSelected = settings.timePreset == preset
        let label: String = preset.hour.map { RecapTimeFormatter.string(hour: $0, minute: 0) } ?? String(localized: "Custom")
        return Button {
            scheduler.update { $0.select(preset) }
        } label: {
            Text(verbatim: label)
                .font(.footnote.weight(.medium))
                .padding(.horizontal, 14)
                .frame(minHeight: 38)
                .foregroundStyle(isSelected ? TE.surface : TE.textPrimary)
                .background(Capsule().fill(isSelected ? TE.textPrimary : TE.card))
                .overlay(Capsule().strokeBorder(isSelected ? Color.clear : TE.border, lineWidth: 1))
                .frame(minHeight: 44)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("reminders-time-\(preset.rawValue)")
    }

    private var customTime: Binding<Date> {
        Binding {
            Calendar.current.date(bySettingHour: settings.hour, minute: settings.minute, second: 0, of: .now) ?? .now
        } set: { date in
            let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
            scheduler.update {
                $0.hour = parts.hour ?? RecapReminderSettings.defaultHour
                $0.minute = parts.minute ?? 0
                $0.usesCustomTime = true
            }
        }
    }

    private func binding(_ keyPath: WritableKeyPath<RecapReminderSettings, Bool>) -> Binding<Bool> {
        Binding {
            settings[keyPath: keyPath]
        } set: { value in
            scheduler.update { $0[keyPath: keyPath] = value }
        }
    }

    // MARK: Trial

    private func trialCard(_ trialEnds: Date) -> some View {
        let reminder = TrialReminder.fireDate(trialEnds: trialEnds) ?? trialEnds
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Trial ending").font(.subheadline.weight(.semibold))
                Text("Two days before your free week ends. Always sent.")
                    .font(.caption)
                    .foregroundStyle(TE.textMuted)
            }
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            Text(verbatim: OnboardingFreeWeekCopy.shortDate(reminder).uppercased())
                .font(TE.mono(.caption))
                .foregroundStyle(TE.textMuted)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(minHeight: 52)
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("reminders-trial")
    }
}

/// What the preview card shows.
struct RecapPreview {
    let kicker: LocalizedStringKey
    let title: String
    let when: String
    let body: String
    let note: LocalizedStringKey?
}

/// The next notification as it would appear, in ink.
private struct RecapPreviewCard: View {
    let preview: RecapPreview
    let isOff: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if isOff {
                Text("No recap notifications. Today and Archive always show your days.")
                    .font(.subheadline)
                    .foregroundStyle(TE.onInkCardMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("reminders-preview-off")
            } else {
                Text(preview.kicker)
                    .font(TE.mono(.caption2))
                    .tracking(1.2)
                    .foregroundStyle(TE.onInkCardMuted)
                HStack(alignment: .top, spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 8).fill(TE.onInkCard)
                        Circle().stroke(TE.dialTrack, lineWidth: 3.5).frame(width: 18, height: 18)
                        Circle().trim(from: 0, to: 0.55)
                            .stroke(TE.textPrimary, style: StrokeStyle(lineWidth: 3.5, lineCap: .butt))
                            .rotationEffect(.degrees(-90))
                            .frame(width: 18, height: 18)
                    }
                    .frame(width: 34, height: 34)
                    .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(verbatim: preview.title).font(.subheadline.weight(.semibold))
                            Spacer(minLength: 4)
                            Text(verbatim: preview.when)
                                .font(TE.mono(.caption2))
                                .foregroundStyle(TE.onInkCardMuted)
                                .lineLimit(1)
                        }
                        Text(verbatim: preview.body)
                            .font(.footnote)
                            .foregroundStyle(TE.onInkCard.opacity(0.85))
                            .fixedSize(horizontal: false, vertical: true)
                            .accessibilityIdentifier("reminders-preview-body")
                    }
                }
                if let note = preview.note {
                    Text(note)
                        .font(.caption)
                        .foregroundStyle(TE.onInkCardMuted)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("reminders-preview-note")
                }
            }
        }
        .foregroundStyle(TE.onInkCard)
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 60, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.inkCard))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("reminders-preview")
    }
}
