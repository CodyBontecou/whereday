import AppIntents
import Foundation
import SwiftData

// MARK: - VisitEntity

/// App entity snapshot of a Visit (`IsoMe/Models/Visit.swift`).
///
/// AppEntities cross process boundaries (Shortcuts pickers, Siri resolution),
/// so the entity snapshots display fields as plain values rather than holding
/// the SwiftData `@Model` instance alive.
struct VisitEntity: AppEntity {
    let id: UUID
    let customName: String?
    let locationName: String?
    let address: String?

    /// Arrival date, exposed as an `@Property` so `VisitQuery`'s
    /// `EntityPropertyQuery` conformance can offer date comparators
    /// ("Arrived is after / before / between") in Shortcuts' filter UI.
    @Property(title: "Arrived")
    var arrivedAt: Date

    /// Fallback chain mirrors `Visit.normalizedCustomName` guarding plus
    /// `Visit.automaticDisplayName`: trimmed customName → locationName →
    /// address → "Visit".
    var name: String {
        Self.displayName(customName: customName, locationName: locationName, address: address)
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)")
    }

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Visit"
    static var defaultQuery = VisitQuery()

    init(
        id: UUID,
        customName: String?,
        locationName: String?,
        address: String?,
        arrivedAt: Date
    ) {
        self.id = id
        self.customName = customName
        self.locationName = locationName
        self.address = address
        self.arrivedAt = arrivedAt
    }

    init(visit: Visit) {
        self.init(
            id: visit.id,
            customName: visit.customName,
            locationName: visit.locationName,
            address: visit.address,
            arrivedAt: visit.arrivedAt
        )
    }

    static func displayName(customName: String?, locationName: String?, address: String?) -> String {
        normalized(customName) ?? normalized(locationName) ?? normalized(address) ?? String(localized: "Visit")
    }

    /// Same trim/nil guarding as `Visit.normalizedCustomName` in
    /// `IsoMe/Models/RecordingSession.swift`'s sibling models.
    private static func normalized(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }
}

// MARK: - MovementEntity

/// App entity snapshot of a RecordingSession (`IsoMe/Models/RecordingSession.swift`).
/// The app UI calls these "Movements" / outings.
struct MovementEntity: AppEntity {
    let id: UUID
    let customName: String?

    /// Start date, exposed as an `@Property` so `MovementQuery`'s
    /// `EntityPropertyQuery` conformance can offer date comparators
    /// ("Started is after / before / between") in Shortcuts' filter UI.
    @Property(title: "Started")
    var startedAt: Date
    let endedAt: Date?

    var name: String {
        Self.displayName(customName: customName, startedAt: startedAt)
    }

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: "\(name)")
    }

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Movement"
    static var defaultQuery = MovementQuery()

    init(
        id: UUID,
        customName: String?,
        startedAt: Date,
        endedAt: Date?
    ) {
        self.id = id
        self.customName = customName
        self.endedAt = endedAt
        // Assign plain stored properties first: the wrapper storage is
        // default-initialized from the attribute arguments above.
        self.startedAt = startedAt
    }

    init(session: RecordingSession) {
        self.init(
            id: session.id,
            customName: session.customName,
            startedAt: session.startedAt,
            endedAt: session.endedAt
        )
    }

    static func displayName(customName: String?, startedAt: Date) -> String {
        if let custom = normalized(customName) {
            return custom
        }
        // The UI's full default title ("Outing N" via
        // `RecordingSessionSummary.defaultTitle`) requires sequencing every
        // session in the store; that math is too heavy for an entity title, so
        // unnamed movements fall back to their medium-style start date.
        return dateFallbackFormatter.string(from: startedAt)
    }

    private static let dateFallbackFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter
    }()

    private static func normalized(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }
}

// MARK: - Date comparators

/// Comparator mapping shared by `VisitQuery` ("Arrived") and `MovementQuery`
/// ("Started"): what Shortcuts' property filter produces for a date property
/// ("is after", "is before", "is between").
///
/// Semantics:
/// - `after` / `before` are STRICT (`>` / `<`);
/// - `between` is INCLUSIVE on both ends, matching the existing
///   `fetch(context:arrivedWithin:)` / `fetch(context:startedWithin:)`
///   ClosedRange helpers it feeds.
enum DateWindow: Hashable, Sendable {
    case after(Date)
    case before(Date)
    case between(Date, Date)

    /// Exact membership test for one comparator's semantics.
    func contains(_ date: Date) -> Bool {
        switch self {
        case .after(let bound): date > bound
        case .before(let bound): date < bound
        case .between(let lower, let upper): date >= lower && date <= upper
        }
    }

    /// An inclusive `ClosedRange` that is a superset of every date
    /// `contains(_:)` accepts. Window fetches run against this widened range
    /// (the predicate helpers are inclusive), and strict bounds are refined
    /// in memory with `contains(_:)` afterwards. Returns nil for a malformed
    /// `between` (lower bound after upper bound).
    var widenedRange: ClosedRange<Date>? {
        switch self {
        case .after(let bound): bound...Date.distantFuture
        case .before(let bound): Date.distantPast...bound
        case .between(let lower, let upper): lower <= upper ? lower...upper : nil
        }
    }

    /// Folds every comparator's widened range into one inclusive window that
    /// still contains the exact `.and` intersection. Returns nil when the
    /// comparators are mutually exclusive (folded lower bound past upper).
    static func widenedIntersection(of comparators: [DateWindow]) -> ClosedRange<Date>? {
        var lower = Date.distantPast
        var upper = Date.distantFuture
        for comparator in comparators {
            switch comparator {
            case .after(let bound):
                if bound > lower { lower = bound }
            case .before(let bound):
                if bound < upper { upper = bound }
            case .between(let newLower, let newUpper):
                if newLower > lower { lower = newLower }
                if newUpper < upper { upper = newUpper }
            }
        }
        return lower <= upper ? lower...upper : nil
    }
}

/// Shared engine behind the queries' `entities(matching:mode:sortedBy:limit:)`
/// implementations: maps `DateWindow` comparators onto the inclusive window
/// fetch helpers, refines strict bounds in memory, then applies sort + limit.
/// Kept behind injected fetch closures so both queries share one copy of the
/// mode/sort/limit semantics while keeping their concrete entity types.
@MainActor
private enum DateWindowQuerySupport {
    @MainActor
    static func fetch<Entity: AppEntity>(
        context: ModelContext,
        matching comparators: [DateWindow],
        mode: EntityQueryComparatorMode,
        dateOrder: EntityQuerySort<Entity>.Ordering?,
        limit: Int?,
        fetchAll: (ModelContext) throws -> [Entity],
        fetchWithin: (ModelContext, ClosedRange<Date>) throws -> [Entity],
        date: (Entity) -> Date,
        id: (Entity) -> UUID
    ) throws -> [Entity] {
        let candidates: [Entity]
        if comparators.isEmpty {
            // No filter applied: behave like an unfiltered listing.
            candidates = try fetchAll(context)
        } else {
            switch mode {
            case .and:
                // One fetch against the folded inclusive superset window,
                // then refine strict bounds with the exact comparators.
                guard let window = DateWindow.widenedIntersection(of: comparators) else {
                    return []
                }
                candidates = try fetchWithin(context, window)
                    .filter { entity in comparators.allSatisfy { $0.contains(date(entity)) } }
            case .or:
                // Union each comparator's widened window, dedupe by id, then
                // refine with the exact comparators (widened bounds are only
                // supersets, e.g. after(d) widens to include == d).
                var union: [Entity] = []
                var seen = Set<UUID>()
                for comparator in comparators {
                    guard let window = comparator.widenedRange else { continue }
                    for entity in try fetchWithin(context, window) {
                        guard !seen.contains(id(entity)),
                              comparators.contains(where: { $0.contains(date(entity)) })
                        else { continue }
                        seen.insert(id(entity))
                        union.append(entity)
                    }
                }
                candidates = union
            }
        }
        return apply(candidates, date: date, dateOrder: dateOrder, limit: limit)
    }

    /// Sorts by the date property (newest-first unless ascending was
    /// requested — the default matches `suggestedEntities()` recency), then
    /// applies the limit AFTER sorting so it selects from the ordered end.
    private static func apply<Entity>(
        _ entities: [Entity],
        date: (Entity) -> Date,
        dateOrder: EntityQuerySort<Entity>.Ordering?,
        limit: Int?
    ) -> [Entity] {
        let sorted = dateOrder == .ascending
            ? entities.sorted { date($0) < date($1) }
            : entities.sorted { date($0) > date($1) }
        guard let limit else { return sorted }
        return Array(sorted.prefix(max(0, limit)))
    }
}

// MARK: - VisitQuery

/// Query backing VisitEntity pickers in Shortcuts. Reads go through
/// `IntentSupport.modelContainer` / `makeContext()`, the same on-disk store
/// the existing intents use. Conforms to `EntityPropertyQuery` so Shortcuts'
/// property filter can find visits by arrival date ("Arrived is after X /
/// before Y / between X and Y"); there is no `EntityDateRangeQuery` in the
/// AppIntents SDK — this protocol is the date-filtering surface.
struct VisitQuery: EntityPropertyQuery {
    /// How many recent visits to offer in pickers.
    static let suggestedEntitiesLimit = 20

    @MainActor
    func suggestedEntities() async throws -> [VisitEntity] {
        try Self.fetchRecent(context: IntentSupport.makeContext(), limit: Self.suggestedEntitiesLimit)
    }

    @MainActor
    func entities(for identifiers: [UUID]) async throws -> [VisitEntity] {
        try Self.fetch(context: IntentSupport.makeContext(), identifiers: identifiers)
    }

    // MARK: Fetch helpers
    // Context is injected so tests run against an in-memory store instead of
    // the on-disk one; the `perform`-adjacent glue above stays thin.

    /// Most recent visits, newest first (recency for pickers — the export
    /// conventions in `ExportRunner` sort ascending only when feeding files).
    @MainActor
    static func fetchRecent(context: ModelContext, limit: Int) throws -> [VisitEntity] {
        var descriptor = FetchDescriptor<Visit>()
        descriptor.sortBy = [SortDescriptor(\.arrivedAt, order: .reverse)]
        descriptor.fetchLimit = limit
        return try context.fetch(descriptor).map(VisitEntity.init(visit:))
    }

    /// Resolve by id, newest first.
    @MainActor
    static func fetch(context: ModelContext, identifiers: [UUID]) throws -> [VisitEntity] {
        guard !identifiers.isEmpty else { return [] }
        var descriptor = FetchDescriptor<Visit>(
            predicate: #Predicate { visit in identifiers.contains(visit.id) }
        )
        descriptor.sortBy = [SortDescriptor(\.arrivedAt, order: .reverse)]
        return try context.fetch(descriptor).map(VisitEntity.init(visit:))
    }

    /// Date-windowed visits (e.g. "visits from last week"), newest first.
    /// Inclusive bounds; also feeds the `EntityPropertyQuery` matching fetch
    /// below, which refines strict comparator bounds in memory.
    @MainActor
    static func fetch(context: ModelContext, arrivedWithin range: ClosedRange<Date>) throws -> [VisitEntity] {
        var descriptor = FetchDescriptor<Visit>(
            predicate: #Predicate { visit in
                visit.arrivedAt >= range.lowerBound && visit.arrivedAt <= range.upperBound
            }
        )
        descriptor.sortBy = [SortDescriptor(\.arrivedAt, order: .reverse)]
        return try context.fetch(descriptor).map(VisitEntity.init(visit:))
    }

    // MARK: EntityPropertyQuery (date-windowed Shortcuts filtering)

    /// Exposes "Arrived" with after / before / between comparators.
    static var properties: EntityQueryProperties<VisitEntity, DateWindow> = EntityQueryProperties {
        Property(\VisitEntity.$arrivedAt) {
            GreaterThanComparator { DateWindow.after($0) }
            LessThanComparator { DateWindow.before($0) }
            IsBetweenComparator { DateWindow.between($0, $1) }
        }
    }

    /// Arrival date is the only sortable property (ascending / descending).
    static var sortingOptions: EntityQuerySortingOptions<VisitEntity> = EntityQuerySortingOptions {
        SortableBy(\VisitEntity.$arrivedAt)
    }

    static var findIntentDescription: IntentDescription? =
        IntentDescription("Finds visits by when you arrived.", resultValueName: "Visits")

    /// Shortcuts property-query surface ("find visits where Arrived is…").
    /// Thin glue over the injectable static below; see `DateWindow` for the
    /// comparator semantics and `DateWindowQuerySupport` for mode handling.
    @MainActor
    func entities(
        matching comparators: [DateWindow],
        mode: EntityQueryComparatorMode,
        sortedBy: [EntityQuerySort<VisitEntity>],
        limit: Int?
    ) async throws -> [VisitEntity] {
        try Self.fetch(
            context: IntentSupport.makeContext(),
            matching: comparators,
            mode: mode,
            dateOrder: Self.resolveDateOrder(sortedBy: sortedBy),
            limit: limit
        )
    }

    /// Comparator-backed fetch: window-filtered, then sorted (date property
    /// only) and limited after sorting. Default order is newest-first.
    @MainActor
    static func fetch(
        context: ModelContext,
        matching comparators: [DateWindow],
        mode: EntityQueryComparatorMode,
        dateOrder: EntityQuerySort<VisitEntity>.Ordering?,
        limit: Int?
    ) throws -> [VisitEntity] {
        try DateWindowQuerySupport.fetch(
            context: context,
            matching: comparators,
            mode: mode,
            dateOrder: dateOrder,
            limit: limit,
            fetchAll: { context in try fetchRecent(context: context, limit: .max) },
            fetchWithin: { context, window in try fetch(context: context, arrivedWithin: window) },
            date: { $0.arrivedAt },
            id: { $0.id }
        )
    }

    /// Only the arrival date is exposed as sortable, so the first sort keyed
    /// on `\VisitEntity.$arrivedAt` decides the direction. Empty or
    /// unrecognized sorts fall back to newest-first, matching
    /// `suggestedEntities()` recency.
    static func resolveDateOrder(
        sortedBy: [EntityQuerySort<VisitEntity>]
    ) -> EntityQuerySort<VisitEntity>.Ordering? {
        guard let requested = sortedBy.first, requested.by == \VisitEntity.$arrivedAt else { return nil }
        return requested.order
    }
}

// MARK: - MovementQuery

/// Query backing MovementEntity pickers in Shortcuts. Reads go through
/// `IntentSupport.modelContainer` / `makeContext()`, the same on-disk store
/// the existing intents use. Conforms to `EntityPropertyQuery` so Shortcuts'
/// property filter can find movements by start date ("Started is after X /
/// before Y / between X and Y"); see `VisitQuery` for the protocol notes.
struct MovementQuery: EntityPropertyQuery {
    /// How many recent movements to offer in pickers.
    static let suggestedEntitiesLimit = 20

    @MainActor
    func suggestedEntities() async throws -> [MovementEntity] {
        try Self.fetchRecent(context: IntentSupport.makeContext(), limit: Self.suggestedEntitiesLimit)
    }

    @MainActor
    func entities(for identifiers: [UUID]) async throws -> [MovementEntity] {
        try Self.fetch(context: IntentSupport.makeContext(), identifiers: identifiers)
    }

    // MARK: Fetch helpers
    // Context is injected so tests run against an in-memory store instead of
    // the on-disk one; the `perform`-adjacent glue above stays thin.

    /// Most recent movements, newest first (recency for pickers — the export
    /// conventions in `ExportRunner` sort ascending only when feeding files).
    @MainActor
    static func fetchRecent(context: ModelContext, limit: Int) throws -> [MovementEntity] {
        var descriptor = FetchDescriptor<RecordingSession>()
        descriptor.sortBy = [SortDescriptor(\.startedAt, order: .reverse)]
        descriptor.fetchLimit = limit
        return try context.fetch(descriptor).map(MovementEntity.init(session:))
    }

    /// Resolve by id, newest first.
    @MainActor
    static func fetch(context: ModelContext, identifiers: [UUID]) throws -> [MovementEntity] {
        guard !identifiers.isEmpty else { return [] }
        var descriptor = FetchDescriptor<RecordingSession>(
            predicate: #Predicate { session in identifiers.contains(session.id) }
        )
        descriptor.sortBy = [SortDescriptor(\.startedAt, order: .reverse)]
        return try context.fetch(descriptor).map(MovementEntity.init(session:))
    }

    /// Date-windowed movements (e.g. "outings from last week"), newest first.
    /// Inclusive bounds; also feeds the `EntityPropertyQuery` matching fetch
    /// below, which refines strict comparator bounds in memory.
    @MainActor
    static func fetch(context: ModelContext, startedWithin range: ClosedRange<Date>) throws -> [MovementEntity] {
        var descriptor = FetchDescriptor<RecordingSession>(
            predicate: #Predicate { session in
                session.startedAt >= range.lowerBound && session.startedAt <= range.upperBound
            }
        )
        descriptor.sortBy = [SortDescriptor(\.startedAt, order: .reverse)]
        return try context.fetch(descriptor).map(MovementEntity.init(session:))
    }

    // MARK: EntityPropertyQuery (date-windowed Shortcuts filtering)

    /// Exposes "Started" with after / before / between comparators.
    static var properties: EntityQueryProperties<MovementEntity, DateWindow> = EntityQueryProperties {
        Property(\MovementEntity.$startedAt) {
            GreaterThanComparator { DateWindow.after($0) }
            LessThanComparator { DateWindow.before($0) }
            IsBetweenComparator { DateWindow.between($0, $1) }
        }
    }

    /// Start date is the only sortable property (ascending / descending).
    static var sortingOptions: EntityQuerySortingOptions<MovementEntity> = EntityQuerySortingOptions {
        SortableBy(\MovementEntity.$startedAt)
    }

    static var findIntentDescription: IntentDescription? =
        IntentDescription("Finds movements by when they started.", resultValueName: "Movements")

    /// Shortcuts property-query surface ("find movements where Started is…").
    /// Thin glue over the injectable static below; see `DateWindow` for the
    /// comparator semantics and `DateWindowQuerySupport` for mode handling.
    @MainActor
    func entities(
        matching comparators: [DateWindow],
        mode: EntityQueryComparatorMode,
        sortedBy: [EntityQuerySort<MovementEntity>],
        limit: Int?
    ) async throws -> [MovementEntity] {
        try Self.fetch(
            context: IntentSupport.makeContext(),
            matching: comparators,
            mode: mode,
            dateOrder: Self.resolveDateOrder(sortedBy: sortedBy),
            limit: limit
        )
    }

    /// Comparator-backed fetch: window-filtered, then sorted (date property
    /// only) and limited after sorting. Default order is newest-first.
    @MainActor
    static func fetch(
        context: ModelContext,
        matching comparators: [DateWindow],
        mode: EntityQueryComparatorMode,
        dateOrder: EntityQuerySort<MovementEntity>.Ordering?,
        limit: Int?
    ) throws -> [MovementEntity] {
        try DateWindowQuerySupport.fetch(
            context: context,
            matching: comparators,
            mode: mode,
            dateOrder: dateOrder,
            limit: limit,
            fetchAll: { context in try fetchRecent(context: context, limit: .max) },
            fetchWithin: { context, window in try fetch(context: context, startedWithin: window) },
            date: { $0.startedAt },
            id: { $0.id }
        )
    }

    /// Only the start date is exposed as sortable, so the first sort keyed on
    /// `\MovementEntity.$startedAt` decides the direction. Empty or
    /// unrecognized sorts fall back to newest-first, matching
    /// `suggestedEntities()` recency.
    static func resolveDateOrder(
        sortedBy: [EntityQuerySort<MovementEntity>]
    ) -> EntityQuerySort<MovementEntity>.Ordering? {
        guard let requested = sortedBy.first, requested.by == \MovementEntity.$startedAt else { return nil }
        return requested.order
    }
}
