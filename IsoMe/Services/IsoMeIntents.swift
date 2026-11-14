import AppIntents
import SwiftData
import Foundation
import UniformTypeIdentifiers

// MARK: - Helpers

@MainActor
enum IntentSupport {
    /// A dedicated container for intent-side reads. Uses the same on-disk store as the app.
    static let modelContainer: ModelContainer = {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false, allowsSave: true)
        do {
            return try ModelContainer(for: schema, configurations: [config])
        } catch {
            fatalError("Could not open Whereday model container in intent: \(error)")
        }
    }()

    static func makeContext() -> ModelContext {
        ModelContext(modelContainer)
    }

    /// Returns the live LocationManager if one exists, otherwise creates a background-launch
    /// instance wired to the shared store. Lets Start/Stop intents work even when the app's
    /// UI hasn't booted yet.
    static func ensureLocationManager() -> LocationManager {
        if let existing = LocationManager.shared { return existing }
        let manager = LocationManager()
        manager.setModelContext(makeContext())
        return manager
    }

    static var usesMetric: Bool {
        if UserDefaults.standard.object(forKey: "usesMetricDistanceUnits") == nil { return true }
        return UserDefaults.standard.bool(forKey: "usesMetricDistanceUnits")
    }

    static func formatDuration(_ seconds: TimeInterval) -> String {
        let total = Int(seconds)
        let h = total / 3600
        let m = (total % 3600) / 60
        if h > 0 && m > 0 { return String(localized: "\(h)h \(m)m") }
        if h > 0 { return String(localized: "\(h)h") }
        return String(localized: "\(m)m")
    }
}

// MARK: - Errors

enum IsoMeIntentError: Swift.Error, CustomLocalizedStringResourceConvertible {
    case noLocationPermission
    case emptyOutingName
    case noActiveOuting
    case invalidDateRange
    case emptyName
    case itemNotFound

    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .noLocationPermission:
            return "Whereday needs location permission. Open the app to enable it."
        case .emptyOutingName:
            return "Enter a name for the current outing."
        case .noActiveOuting:
            return "Whereday is not currently recording an outing."
        case .invalidDateRange:
            return "The start date must be before the end date."
        case .emptyName:
            return "Enter a name."
        case .itemNotFound:
            return "That Whereday item couldn't be found."
        }
    }
}

// MARK: - Tracking Control

struct StartTrackingIntent: AppIntent {
    static var title: LocalizedStringResource = "Start Tracking"
    static var description = IntentDescription("Begin recording your route in Whereday.")
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let manager = IntentSupport.ensureLocationManager()
        await StoreManager.shared.checkEntitlement()
        guard StoreManager.shared.canTrack else {
            return .result(dialog: "Open Whereday to choose a plan before tracking.")
        }
        guard manager.hasLocationPermission else {
            throw IsoMeIntentError.noLocationPermission
        }
        manager.startTracking()
        return .result(dialog: "Tracking started.")
    }
}

struct StopTrackingIntent: AppIntent {
    static var title: LocalizedStringResource = "Stop Tracking"
    static var description = IntentDescription("Stop the current Whereday tracking session.")
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        if let manager = LocationManager.shared {
            manager.stopTracking()
        } else {
            // No live manager — the app isn't running. Persist the off state and close
            // the active outing so the next launch does not show it as still recording.
            let context = IntentSupport.makeContext()
            LocationManager.closePersistedRecordingSessions(in: context)
            UserDefaults.standard.set(false, forKey: "isTrackingEnabled")
        }
        return .result(dialog: "Tracking stopped.")
    }
}

struct RenameCurrentOutingIntent: AppIntent {
    static var title: LocalizedStringResource = "Rename Current Outing"
    static var description = IntentDescription("Set the name of the Whereday outing currently being recorded.")
    static var openAppWhenRun: Bool = false

    @Parameter(title: "Name")
    var name: String

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let trimmedName = try RenameSupport.renameActiveSession(to: name, context: IntentSupport.makeContext())
        return .result(dialog: "Current outing renamed to \(trimmedName).")
    }
}

// MARK: - Read-Only Stats (backed by SharedLocationData)

struct TodayDistanceIntent: AppIntent {
    static var title: LocalizedStringResource = "Today's Distance"
    static var description = IntentDescription("How far you've moved today, according to Whereday.")
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<Measurement<UnitLength>> {
        let snapshot = SharedLocationData.load() ?? .empty
        let meters = snapshot.todayDistanceMeters
        let measurement = Measurement(value: meters, unit: UnitLength.meters)
        let formatted = DistanceFormatter.format(meters: meters, usesMetric: snapshot.prefersMetricDistanceUnits)
        let dialog: IntentDialog = meters <= 0
            ? "You haven't moved yet today."
            : "You've gone \(formatted) today."
        return .result(value: measurement, dialog: dialog)
    }
}

struct TodayTrackingDurationIntent: AppIntent {
    static var title: LocalizedStringResource = "Today's Tracking Duration"
    static var description = IntentDescription("How long the current Whereday tracking session has been running.")
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<Measurement<UnitDuration>> {
        let snapshot = SharedLocationData.load() ?? .empty
        let seconds: TimeInterval
        let dialog: IntentDialog
        if let start = snapshot.trackingStartTime, snapshot.isTrackingEnabled {
            seconds = Date().timeIntervalSince(start)
            dialog = "You've been tracking for \(IntentSupport.formatDuration(seconds))."
        } else {
            seconds = 0
            dialog = "Whereday isn't tracking right now."
        }
        return .result(
            value: Measurement(value: seconds, unit: UnitDuration.seconds),
            dialog: dialog
        )
    }
}

struct TodayVisitCountIntent: AppIntent {
    static var title: LocalizedStringResource = "Today's Visits"
    static var description = IntentDescription("Number of visits Whereday has logged today.")
    static var openAppWhenRun: Bool = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<Int> {
        let snapshot = SharedLocationData.load() ?? .empty
        let count = snapshot.todayVisitsCount
        let dialog: IntentDialog
        switch count {
        case 0: dialog = "No visits yet today."
        case 1: dialog = "1 visit today."
        default: dialog = "\(count) visits today."
        }
        return .result(value: count, dialog: dialog)
    }
}

// MARK: - Export

enum IsoMeExportFormat: String, AppEnum {
    case json
    case csv
    case markdown
    case geojson
    case gpx
    case kml
    case owntracks
    case overland

    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Export Format"
    static var caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .json: "JSON",
        .csv: "CSV",
        .markdown: "Markdown",
        .geojson: "GeoJSON",
        .gpx: "GPX",
        .kml: "KML",
        .owntracks: "OwnTracks",
        .overland: "Overland",
    ]

    var format: ExportFormat {
        switch self {
        case .json: return .json
        case .csv: return .csv
        case .markdown: return .markdown
        case .geojson: return .geojson
        case .gpx: return .gpx
        case .kml: return .kml
        case .owntracks: return .owntracks
        case .overland: return .overland
        }
    }

    var contentType: UTType {
        switch self {
        case .json: return .json
        case .csv: return .commaSeparatedText
        case .markdown: return UTType(filenameExtension: "md") ?? .plainText
        // GeoJSON has no registered system UTType; declare the extension so the
        // file lands with the .geojson suffix when handed to other apps.
        case .geojson: return UTType(filenameExtension: "geojson") ?? .json
        case .gpx: return UTType(filenameExtension: "gpx") ?? .xml
        case .kml: return UTType(importedAs: "com.google.earth.kml", conformingTo: .xml)
        // OwnTracks and Overland are JSON payloads — keep the .json UTType so
        // receiving apps treat them as JSON.
        case .owntracks, .overland: return .json
        }
    }
}

struct ExportRunner {
    @MainActor
    static func run(
        range: ClosedRange<Date>,
        dataKind: ExportOptions.DataKind,
        format: IsoMeExportFormat
    ) throws -> IntentFile {
        try run(range: range, dataKind: dataKind, format: format, context: nil)
    }

    /// Testability seam: lets callers inject a `ModelContext` (e.g. an
    /// in-memory container in unit tests) instead of opening the on-disk
    /// intent store. Omitting `context` keeps production call sites on the
    /// real store.
    @MainActor
    static func run(
        range: ClosedRange<Date>,
        dataKind: ExportOptions.DataKind,
        format: IsoMeExportFormat,
        context: ModelContext? = nil
    ) throws -> IntentFile {
        let resolvedContext = context ?? IntentSupport.makeContext()
        var visitDescriptor = FetchDescriptor<Visit>(
            predicate: #Predicate { $0.arrivedAt >= range.lowerBound && $0.arrivedAt <= range.upperBound }
        )
        visitDescriptor.sortBy = [SortDescriptor(\.arrivedAt, order: .forward)]

        let visits = (try? resolvedContext.fetch(visitDescriptor)) ?? []
        let points = try fetchPoints(for: dataKind, range: range, context: resolvedContext)
        let recordingSessions = try fetchRecordingSessions(for: dataKind, context: resolvedContext)
        let activeTrackingStart = SharedLocationData.load()?.trackingStartTime

        var options = ExportOptions()
        options.dataKind = dataKind
        options.format = format.format
        options.datePreset = .custom
        options.customStart = range.lowerBound
        options.customEnd = range.upperBound

        let rendered = try ExportService.render(
            visits: visits,
            points: points,
            recordingSessions: recordingSessions,
            activeTrackingStart: activeTrackingStart,
            options: options
        )
        return IntentFile(data: rendered.data, filename: rendered.fileName, type: format.contentType)
    }

    @MainActor
    private static func fetchPoints(
        for dataKind: ExportOptions.DataKind,
        range: ClosedRange<Date>,
        context: ModelContext
    ) throws -> [LocationPoint] {
        if dataKind == .outings {
            var descriptor = FetchDescriptor<LocationPoint>()
            descriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]
            return try context.fetch(descriptor)
        }

        var descriptor = FetchDescriptor<LocationPoint>(
            predicate: #Predicate { $0.timestamp >= range.lowerBound && $0.timestamp <= range.upperBound }
        )
        descriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]
        return (try? context.fetch(descriptor)) ?? []
    }

    @MainActor
    private static func fetchRecordingSessions(
        for dataKind: ExportOptions.DataKind,
        context: ModelContext
    ) throws -> [RecordingSession] {
        guard dataKind == .outings else { return [] }
        var descriptor = FetchDescriptor<RecordingSession>()
        descriptor.sortBy = [SortDescriptor(\.startedAt, order: .forward)]
        return try context.fetch(descriptor)
    }
}

// MARK: - App Shortcuts (Siri-discoverable phrases)

struct IsoMeAppShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: CheckInIntent(),
            phrases: ["Check in with \(.applicationName)"],
            shortTitle: "Check In",
            systemImageName: "mappin.and.ellipse"
        )
        AppShortcut(
            intent: StartTrackingIntent(),
            phrases: [
                "Start \(.applicationName)",
                "Begin recording in \(.applicationName)",
                "Record route with \(.applicationName)",
            ],
            shortTitle: "Start Tracking",
            systemImageName: "play.circle"
        )
        AppShortcut(
            intent: StopTrackingIntent(),
            phrases: [
                "Stop tracking with \(.applicationName)",
                "Stop \(.applicationName) tracking",
            ],
            shortTitle: "Stop Tracking",
            systemImageName: "stop.circle"
        )
        AppShortcut(
            intent: RenameCurrentOutingIntent(),
            phrases: [
                "Rename current outing in \(.applicationName)",
                "Name my \(.applicationName) outing",
            ],
            shortTitle: "Rename Outing",
            systemImageName: "pencil"
        )
        AppShortcut(
            intent: TodayDistanceIntent(),
            phrases: [
                "How far have I gone with \(.applicationName)",
                "\(.applicationName) distance today",
            ],
            shortTitle: "Today's Distance",
            systemImageName: "ruler"
        )
        AppShortcut(
            intent: TodayTrackingDurationIntent(),
            phrases: [
                "How long have I been tracking with \(.applicationName)",
                "\(.applicationName) tracking duration",
            ],
            shortTitle: "Tracking Duration",
            systemImageName: "clock"
        )
        AppShortcut(
            intent: TodayVisitCountIntent(),
            phrases: [
                "How many visits today in \(.applicationName)",
                "\(.applicationName) visit count",
            ],
            shortTitle: "Today's Visits",
            systemImageName: "mappin.and.ellipse"
        )
        AppShortcut(
            intent: ExportDataIntent(),
            phrases: [
                "Export \(.applicationName) data",
                "Export data from \(.applicationName)",
            ],
            shortTitle: "Export Data",
            systemImageName: "square.and.arrow.up"
        )
        AppShortcut(
            intent: RenameVisitIntent(),
            phrases: [
                "Rename a visit in \(.applicationName)",
                "Name my \(.applicationName) visit",
            ],
            shortTitle: "Rename Visit",
            systemImageName: "pencil"
        )
        AppShortcut(
            intent: RenameMovementIntent(),
            phrases: [
                "Rename a movement in \(.applicationName)",
                "Name my \(.applicationName) movement",
            ],
            shortTitle: "Rename Movement",
            systemImageName: "pencil"
        )
    }
}
