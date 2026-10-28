import SwiftUI
import EventKit
import EventKitUI
import CoreLocation

/// A snapshot of a timeline event. Opening the editor never changes the source activity.
struct CalendarEventDraft: Identifiable {
    let id = UUID()
    let title: String
    let startDate: Date
    let endDate: Date
    let location: String?
    let coordinate: CLLocationCoordinate2D?

    init(visit: Visit, now: Date = Date()) {
        title = visit.displayName
        startDate = visit.arrivedAt
        endDate = max(visit.arrivedAt, visit.departedAt ?? now)
        coordinate = visit.coordinate
        location = Self.locationName(address: visit.address, name: visit.locationName, coordinate: visit.coordinate)
    }

    init(session: RecordingSessionSummary, now: Date = Date()) {
        title = session.title
        startDate = session.startedAt
        endDate = max(session.startedAt, session.isActive ? now : session.effectiveEndDate)
        coordinate = session.startPoint?.coordinate
        location = coordinate.map { Self.coordinateText($0) }
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
        Coordinator(dismiss: dismiss)
    }

    final class Coordinator: NSObject, EKEventEditViewDelegate {
        let dismiss: DismissAction

        init(dismiss: DismissAction) {
            self.dismiss = dismiss
        }

        func eventEditViewController(_ controller: EKEventEditViewController, didCompleteWith action: EKEventEditViewAction) {
            dismiss()
        }
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
        .buttonStyle(.bordered)
        .sheet(item: $draft) { draft in
            CalendarEventEditor(draft: draft)
        }
    }
}
