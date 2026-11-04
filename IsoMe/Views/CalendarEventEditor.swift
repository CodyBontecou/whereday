import SwiftUI
import EventKit
import EventKitUI
import CoreLocation

/// A snapshot of a timeline event. Opening the editor never changes the source activity.
struct CalendarEventDraft: Identifiable {
    let id = UUID()
    // Title, times and notes can be adjusted on the review sheet before Calendar sees them.
    var title: String
    var startDate: Date
    var endDate: Date
    let location: String?
    let coordinate: CLLocationCoordinate2D?
    var notes: String?

    // Detail screens pass their current text so export does not depend on focus-loss saving.
    init(visit: Visit, now: Date = Date(), notes: String? = nil) {
        title = visit.displayName
        self.notes = Self.nonblankNotes(notes ?? visit.notes)
        startDate = visit.arrivedAt
        endDate = max(visit.arrivedAt, visit.departedAt ?? now)
        coordinate = visit.coordinate
        location = Self.locationName(address: visit.address, name: visit.locationName, coordinate: visit.coordinate)
    }

    init(session: RecordingSessionSummary, now: Date = Date(), notes: String? = nil) {
        title = session.title
        self.notes = Self.nonblankNotes(notes ?? session.storedSession?.notes)
        startDate = session.startedAt
        endDate = max(session.startedAt, session.isActive ? now : session.effectiveEndDate)
        coordinate = session.startPoint?.coordinate
        location = coordinate.map { Self.coordinateText($0) }
    }

    private static func nonblankNotes(_ notes: String?) -> String? {
        guard let notes, !notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return nil
        }
        return notes
    }

    private static func locationName(address: String?, name: String?, coordinate: CLLocationCoordinate2D) -> String {
        for candidate in [address, name] {
            if let value = candidate?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty {
                return value
            }
        }
        return coordinateText(coordinate)
    }

    private static func coordinateText(_ coordinate: CLLocationCoordinate2D) -> String {
        String(format: "%.6f, %.6f", locale: Locale(identifier: "en_US_POSIX"), coordinate.latitude, coordinate.longitude)
    }

    func makeEvent(in store: EKEventStore) -> EKEvent {
        let event = EKEvent(eventStore: store)
        event.title = title
        event.startDate = startDate
        event.endDate = endDate
        event.location = location
        event.notes = notes
        if let coordinate {
            let structuredLocation = EKStructuredLocation(title: location ?? "")
            structuredLocation.geoLocation = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
            event.structuredLocation = structuredLocation
        }
        return event
    }
}

/// On iOS 17+, the system editor handles saving without app calendar access.
/// Only the editor writes to Calendar, after the user chooses Add.
struct CalendarEventEditor: UIViewControllerRepresentable {
    @Environment(\.dismiss) private var dismiss
    let draft: CalendarEventDraft
    /// Called instead of dismissing when a parent needs to know whether the event was saved.
    var onComplete: ((EKEventEditViewAction) -> Void)?

    func makeUIViewController(context: Context) -> EKEventEditViewController {
        let editor = EKEventEditViewController()
        let store = EKEventStore()
        editor.eventStore = store
        editor.event = draft.makeEvent(in: store)
        editor.editViewDelegate = context.coordinator
        return editor
    }

    func updateUIViewController(_ editor: EKEventEditViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(dismiss: dismiss, onComplete: onComplete)
    }

    final class Coordinator: NSObject, EKEventEditViewDelegate {
        let dismiss: DismissAction
        let onComplete: ((EKEventEditViewAction) -> Void)?

        init(dismiss: DismissAction, onComplete: ((EKEventEditViewAction) -> Void)?) {
            self.dismiss = dismiss
            self.onComplete = onComplete
        }

        func eventEditViewController(_ controller: EKEventEditViewController, didCompleteWith action: EKEventEditViewAction) {
            if let onComplete {
                onComplete(action)
            } else {
                dismiss()
            }
        }
    }
}

/// Reviews a timeline event before handing it to Calendar's own editor, which picks the
/// calendar and saves without Whereday needing calendar access.
struct CalendarEventReviewSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var draft: CalendarEventDraft
    @State private var notesText: String
    @State private var systemEditorDraft: CalendarEventDraft?

    init(draft: CalendarEventDraft) {
        _draft = State(initialValue: draft)
        _notesText = State(initialValue: draft.notes ?? "")
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                titleField
                    .padding(.horizontal, 24)
                    .padding(.top, 8)
                timesCard
                    .padding(.horizontal, 16)
                    .padding(.top, 16)
                notesCard
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                Text("Times come from your timeline. Editing them here changes only the calendar event, not your record. You'll choose the calendar in the next step.")
                    .font(.caption)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 24)
                    .padding(.top, 12)
            }
            .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom) {
            Button(action: continueToCalendar) {
                Text("Add event")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .foregroundStyle(TE.surface)
                    .background(Capsule().fill(isTitleBlank ? TE.textMuted : TE.accent))
            }
            .buttonStyle(.plain)
            .disabled(isTitleBlank)
            .accessibilityHint("Opens Calendar's editor to choose a calendar and save.")
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 12)
            .background(TE.surface)
        }
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .presentationDragIndicator(.visible)
        .sheet(item: $systemEditorDraft) { editorDraft in
            CalendarEventEditor(draft: editorDraft) { action in
                systemEditorDraft = nil
                // Cancelling Calendar's editor returns here so the draft is not lost.
                if action == .saved { dismiss() }
            }
            .ignoresSafeArea()
        }
    }

    private var header: some View {
        ZStack {
            Text("ADD TO CALENDAR")
                .font(TE.mono(.caption2))
                .tracking(1.5)
                .foregroundStyle(TE.textMuted)
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 80)
                .multilineTextAlignment(.center)
            HStack {
                Button("Cancel") { dismiss() }
                    .font(.subheadline)
                    .frame(minHeight: 44)
                    .buttonStyle(.plain)
                    .foregroundStyle(TE.textPrimary)
                Spacer()
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 14)
    }

    private var titleField: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("TITLE")
                .font(TE.mono(.caption2))
                .tracking(1)
                .foregroundStyle(TE.textMuted)
                .accessibilityHidden(true)
            TextField("Event title", text: $draft.title, axis: .vertical)
                .font(TE.editorial(.title2))
                .textFieldStyle(.plain)
                .submitLabel(.done)
                .padding(.vertical, 10)
                .accessibilityLabel("Title")
            Rectangle()
                .fill(TE.border)
                .frame(height: 1)
                .accessibilityHidden(true)
        }
    }

    private var timesCard: some View {
        VStack(spacing: 0) {
            dateRow("Starts", selection: $draft.startDate, range: nil)
            divider
            dateRow("Ends", selection: $draft.endDate, range: draft.startDate...)
            if let location = draft.location {
                divider
                infoRow("Location", value: location)
            }
        }
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .onChange(of: draft.startDate) { _, start in
            if draft.endDate < start { draft.endDate = start }
        }
    }

    private var divider: some View {
        Rectangle()
            .fill(TE.dialTrack)
            .frame(height: 1)
            .accessibilityHidden(true)
    }

    @ViewBuilder
    private func dateRow(_ title: LocalizedStringKey, selection: Binding<Date>, range: PartialRangeFrom<Date>?) -> some View {
        let picker = Group {
            if let range {
                DatePicker(title, selection: selection, in: range, displayedComponents: [.date, .hourAndMinute])
            } else {
                DatePicker(title, selection: selection, displayedComponents: [.date, .hourAndMinute])
            }
        }
        .labelsHidden()
        .tint(TE.accent)

        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(spacing: 12))
        layout {
            Text(title)
                .font(.subheadline)
            if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 8) }
            picker
        }
        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, dynamicTypeSize.isAccessibilitySize ? 10 : 0)
    }

    private func infoRow(_ title: LocalizedStringKey, value: String) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 4))
            : AnyLayout(HStackLayout(spacing: 12))
        return layout {
            Text(title)
                .font(.subheadline)
            if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 8) }
            Text(value)
                .font(.footnote)
                .foregroundStyle(TE.textMuted)
                .multilineTextAlignment(dynamicTypeSize.isAccessibilitySize ? .leading : .trailing)
                .lineLimit(3)
        }
        .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
    }

    private var notesCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("NOTES")
                .font(TE.mono(.caption2))
                .tracking(1)
                .foregroundStyle(TE.textMuted)
                .accessibilityHidden(true)
            TextField("Add notes", text: $notesText, axis: .vertical)
                .font(.subheadline)
                .textFieldStyle(.plain)
                .lineLimit(3...8)
                .accessibilityLabel("Notes")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
    }

    private var isTitleBlank: Bool {
        draft.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func continueToCalendar() {
        var final = draft
        final.endDate = max(final.startDate, final.endDate)
        final.notes = notesText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : notesText
        systemEditorDraft = final
    }
}

struct AddEventToCalendarButton: View {
    let makeDraft: () -> CalendarEventDraft
    @State private var draft: CalendarEventDraft?

    var body: some View {
        Button {
            draft = makeDraft()
        } label: {
            Label("Add Event to Calendar", systemImage: "calendar.badge.plus")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(TEActionButtonStyle())
        .sheet(item: $draft) { draft in
            CalendarEventReviewSheet(draft: draft)
        }
    }
}
