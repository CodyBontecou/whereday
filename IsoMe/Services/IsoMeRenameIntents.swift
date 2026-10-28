import AppIntents
import Foundation
import SwiftData

// MARK: - Rename Support (injectable seam)

/// Shared seam for renaming Whereday items (visits, movements/outings).
///
/// Split out of the intents themselves so that:
/// (a) unit tests can inject an in-memory `ModelContext` instead of the
///     on-disk store (mirrors `CurrentOutingReader` in `IsoMeReadIntents.swift`
///     and `ExportRunner`'s context-injectable overload in `IsoMeIntents.swift`), and
/// (b) `RenameCurrentOutingIntent`, `RenameVisitIntent`, and
///     `RenameMovementIntent` all reuse the same trim/validate/persist logic,
///     and (c) the active-outing rename resolves its session through the
///     shared `CurrentOutingReader.activeSession(context:)` helper in
///     `IsoMeReadIntents.swift` instead of a duplicated fetch.
@MainActor
enum RenameSupport {
    /// Renames a visit by id. Trims the name, rejects empty names, writes
    /// `customName` plus `updatedAt` (per `IsoMe/Models/Visit.swift`
    /// conventions), saves, and returns a fresh `VisitEntity` snapshot.
    static func rename(visitWith id: UUID, to name: String, context: ModelContext) throws -> VisitEntity {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else { throw IsoMeIntentError.emptyName }

        var descriptor = FetchDescriptor<Visit>(
            predicate: #Predicate { visit in
                visit.id == id
            }
        )
        descriptor.fetchLimit = 1

        guard let visit = try context.fetch(descriptor).first else {
            throw IsoMeIntentError.itemNotFound
        }

        visit.customName = trimmedName
        visit.updatedAt = Date()
        try context.save()
        return VisitEntity(visit: visit)
    }

    /// Renames a movement (RecordingSession) by id. Same trim/validate shape
    /// as the visit rename, but `RecordingSession` has no `updatedAt` field —
    /// only `customName` is written.
    static func rename(sessionWith id: UUID, to name: String, context: ModelContext) throws -> MovementEntity {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else { throw IsoMeIntentError.emptyName }

        var descriptor = FetchDescriptor<RecordingSession>(
            predicate: #Predicate { session in
                session.id == id
            }
        )
        descriptor.fetchLimit = 1

        guard let session = try context.fetch(descriptor).first else {
            throw IsoMeIntentError.itemNotFound
        }

        session.customName = trimmedName
        try context.save()
        return MovementEntity(session: session)
    }

    /// The extracted logic of `RenameCurrentOutingIntent`: rename the outing
    /// Whereday is currently recording. The active session (one open session,
    /// newest `startedAt` first, limit 1) comes from the shared
    /// `CurrentOutingReader.activeSession(context:)` helper. Returns the
    /// trimmed name for the dialog.
    ///
    /// Throws `emptyOutingName` (not the generic `emptyName`) and
    /// `noActiveOuting` to keep `RenameCurrentOutingIntent`'s user-facing
    /// error wording byte-for-byte identical to before the extraction.
    /// Empty names are rejected BEFORE any fetch.
    static func renameActiveSession(to name: String, context: ModelContext) throws -> String {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty else { throw IsoMeIntentError.emptyOutingName }

        let activeSession = try CurrentOutingReader.activeSession(context: context)

        activeSession.customName = trimmedName
        try context.save()
        return trimmedName
    }
}

// MARK: - Rename Intents

struct RenameVisitIntent: AppIntent {
    static var title: LocalizedStringResource = "Rename Visit"
    static var description = IntentDescription("Set the name of a visit saved in Whereday.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Visit")
    var visit: VisitEntity

    @Parameter(title: "Name")
    var name: String

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<VisitEntity> {
        let renamed = try RenameSupport.rename(visitWith: visit.id, to: name, context: IntentSupport.makeContext())
        return .result(value: renamed, dialog: "Visit renamed to \(renamed.name).")
    }
}

struct RenameMovementIntent: AppIntent {
    static var title: LocalizedStringResource = "Rename Movement"
    static var description = IntentDescription("Set the name of a movement recorded in Whereday.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Movement")
    var movement: MovementEntity

    @Parameter(title: "Name")
    var name: String

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<MovementEntity> {
        let renamed = try RenameSupport.rename(sessionWith: movement.id, to: name, context: IntentSupport.makeContext())
        return .result(value: renamed, dialog: "Movement renamed to \(renamed.name).")
    }
}
