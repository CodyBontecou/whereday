import AppIntents
import Foundation
import SwiftData

// MARK: - Current Outing Reads

/// Shared seam for reading the name of the outing Whereday is currently recording.
///
/// Split out of the intent itself so that:
/// (a) unit tests can inject an in-memory `ModelContext` instead of the on-disk store, and
/// (b) the rename work (and future read intents) reuses the same
///     active-session resolution — `activeSession(context:)` — instead of
///     duplicating the fetch.
@MainActor
enum CurrentOutingReader {
    /// The outing Whereday is currently recording: the one open
    /// (`endedAt == nil`) session, newest `startedAt` first, limit 1.
    ///
    /// The single active-session lookup shared by `currentOutingName` and
    /// `RenameSupport.renameActiveSession`. Throws
    /// `IsoMeIntentError.noActiveOuting` when no session is open.
    static func activeSession(context: ModelContext) throws -> RecordingSession {
        var descriptor = FetchDescriptor<RecordingSession>(
            predicate: #Predicate { session in
                session.endedAt == nil
            }
        )
        descriptor.sortBy = [SortDescriptor(\.startedAt, order: .reverse)]
        descriptor.fetchLimit = 1

        guard let activeSession = try context.fetch(descriptor).first else {
            throw IsoMeIntentError.noActiveOuting
        }
        return activeSession
    }

    static func currentOutingName(context: ModelContext) throws -> String {
        let activeSession = try activeSession(context: context)

        if let customName = activeSession.normalizedCustomName {
            return customName
        }

        // The app's "Outing N" default title (RecordingSessionSummary.defaultTitle) requires
        // rebuilding the full chronological session sequence — too heavy for an intent read.
        // A medium-style date of the session start is a stable, human-readable fallback.
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: activeSession.startedAt)
    }
}

struct GetCurrentOutingNameIntent: AppIntent {
    static var title: LocalizedStringResource = "Get Current Outing Name"
    static var description = IntentDescription("The name of the outing Whereday is currently recording.")
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<String> {
        let name = try CurrentOutingReader.currentOutingName(context: IntentSupport.makeContext())
        return .result(value: name, dialog: "Current outing: \(name).")
    }
}

// MARK: - Entity-Returning Range Reads

// Lands the cycle-2 note at the top of this file (that comment is left in
// place — this file is append-only this cycle): GetVisitsIntent /
// GetMovementsIntent return the cycle-1 entities for a date range, defaulting
// to today, so Shortcuts can chain "Get Name" / repeat-with-each over results.

/// Pure count-summary dialog text for the read intents below.
///
/// Returns `LocalizedStringResource` rather than `IntentDialog` because
/// `IntentDialog` is opaque in the SDK (no public value accessor), while a
/// resource can be rendered in unit tests via `String(localized:)` — keeping
/// the wording pinned without running `perform()`, which reads the on-disk
/// store. Mirrors the `CurrentOutingReader` / `ExportDataParameterResolution`
/// seam pattern.
enum ReadIntentsDialogs {
    static func visitCount(_ count: Int) -> LocalizedStringResource {
        switch count {
        case 0: return "No visits found."
        case 1: return "1 visit found."
        default: return "\(count) visits found."
        }
    }

    /// Plural wording follows `MovementEntity.typeDisplayRepresentation`
    /// ("Movement") and the app's "outings" vocabulary.
    static func movementCount(_ count: Int) -> LocalizedStringResource {
        switch count {
        case 0: return "No movements found."
        case 1: return "1 movement found."
        default: return "\(count) movements found."
        }
    }
}

struct GetVisitsIntent: AppIntent {
    static var title: LocalizedStringResource = "Get Visits"
    static var description = IntentDescription("The visits Whereday logged in a chosen date range. Defaults to today.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Start Date")
    var startDate: Date?

    @Parameter(title: "End Date")
    var endDate: Date?

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<[VisitEntity]> {
        let range = try ExportDataParameterResolution.dateRange(start: startDate, end: endDate)
        let visits = try VisitQuery.fetch(context: IntentSupport.makeContext(), arrivedWithin: range)
        return .result(value: visits, dialog: IntentDialog(ReadIntentsDialogs.visitCount(visits.count)))
    }
}

struct GetMovementsIntent: AppIntent {
    static var title: LocalizedStringResource = "Get Movements"
    static var description = IntentDescription("The movements, i.e. outings, Whereday logged in a chosen date range. Defaults to today.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Start Date")
    var startDate: Date?

    @Parameter(title: "End Date")
    var endDate: Date?

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<[MovementEntity]> {
        let range = try ExportDataParameterResolution.dateRange(start: startDate, end: endDate)
        let movements = try MovementQuery.fetch(context: IntentSupport.makeContext(), startedWithin: range)
        return .result(value: movements, dialog: IntentDialog(ReadIntentsDialogs.movementCount(movements.count)))
    }
}
