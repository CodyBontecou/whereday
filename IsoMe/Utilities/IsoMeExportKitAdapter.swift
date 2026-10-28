import Foundation
import ExportKit

/// App-local bridge from Whereday's SwiftData models and export settings into the
/// domain-free ExportKit primitives. ExportKit never sees `Visit`,
/// `LocationPoint`, or Whereday's UI/settings copy except through this adapter.
struct IsoMeExportSnapshot: ExportRecord {
    let id: String
    let exportDate: Date
    let visits: [Visit]
    let points: [LocationPoint]
    let outings: [RecordingSessionSummary]
    let options: ExportOptions
    let dataKind: ExportOptions.DataKind
    let isSplitByDay: Bool
    let isSplitOuting: Bool

    var exportRecordID: String { id }

    var displayTitle: String {
        if isSplitOuting, let outing = outings.first {
            return outing.title
        }
        return id
    }
}

enum IsoMeExportKitError: LocalizedError, Equatable {
    case missingFormat(String)
    case nonUTF8Payload(formatID: String)
    case noDestination

    var errorDescription: String? {
        switch self {
        case .missingFormat(let id):
            return String(localized: "Unsupported export format: \(id)")
        case .nonUTF8Payload(let formatID):
            return String(localized: "Export payload for \(formatID) was not valid UTF-8 text.")
        case .noDestination:
            return String(localized: "No export destination was provided.")
        }
    }
}

enum IsoMeExportPathPlanner {
    static func plannedRelativePath(
        pattern: String,
        dataKind: ExportOptions.DataKind,
        format: ExportFormat,
        date: Date = Date(),
        title: String? = nil,
        safetyPolicy: ExportPathSafetyPolicy = .rejectTraversalAndAbsolutePaths
    ) throws -> String {
        let expandedPath = FilenameTemplate.stem(
            pattern: pattern,
            dataKind: dataKind,
            format: format,
            date: date,
            title: title
        )

        // Validate the raw user-expanded pattern before applying filename/path
        // sanitization so obvious traversal/absolute-path attempts are rejected
        // instead of silently rewritten into a surprising destination. Forward
        // slashes are intentionally preserved as folder separators.
        let rawPath = FilenameTemplate.appendingFormatExtensionIfNeeded(
            to: expandedPath,
            format: format,
            fallbackBasename: dataKind.rawValue
        )
        .replacingOccurrences(of: "\\", with: "/")
        _ = try ExportPathSafetyPolicy.rejectTraversalAndAbsolutePaths.pathSegments(from: rawPath)

        let sanitizedPath = FilenameTemplate.sanitizePath(rawPath)
        return try safetyPolicy.pathSegments(from: sanitizedPath).joined(separator: "/")
    }
}

enum IsoMeExportKitAdapter {
    static let allFormats: [ExportFormat] = ExportFormat.allCases

    static func descriptor(for format: ExportFormat) -> ExportFormatDescriptor {
        ExportFormatDescriptor(
            id: format.exportKitFormatID,
            displayName: format.displayName,
            fileExtension: format.fileExtension,
            collisionSuffix: format.token,
            contentType: format.mimeType,
            defaultSortKey: format.defaultSortKey
        )
    }

    static func rendererRegistry() throws -> ExportRendererRegistry<IsoMeExportSnapshot> {
        try ExportRendererRegistry(renderers: allFormats.map { format in
            AnyExportRenderer<IsoMeExportSnapshot>(descriptor: descriptor(for: format)) { snapshot, _ in
                let data = try renderData(
                    visits: snapshot.visits,
                    points: snapshot.points,
                    outings: snapshot.outings,
                    dataKind: snapshot.dataKind,
                    format: format,
                    options: snapshot.options
                )
                guard let content = String(data: data, encoding: .utf8) else {
                    throw IsoMeExportKitError.nonUTF8Payload(formatID: format.exportKitFormatID)
                }
                return RenderedExport(content: content, contentType: format.mimeType)
            }
        })
    }

    static func render(
        visits: [Visit],
        points: [LocationPoint],
        recordingSessions: [RecordingSession] = [],
        activeTrackingStart: Date? = nil,
        options: ExportOptions,
        filenamePattern: String = FilenameTemplate.defaultPattern
    ) throws -> (data: Data, fileName: String) {
        guard let file = try plannedFiles(
            visits: visits,
            points: points,
            recordingSessions: recordingSessions,
            activeTrackingStart: activeTrackingStart,
            options: options,
            filenamePattern: filenamePattern,
            forceSplitByDay: false
        ).first else {
            let effectiveKind = effectiveDataKind(for: options)
            let fileName = try IsoMeExportPathPlanner.plannedRelativePath(
                pattern: filenamePattern,
                dataKind: effectiveKind,
                format: options.format
            )
            return (Data(), fileName)
        }

        return (Data(file.content.utf8), file.relativePath)
    }

    static func renderPerDay(
        visits: [Visit],
        points: [LocationPoint],
        recordingSessions: [RecordingSession] = [],
        activeTrackingStart: Date? = nil,
        options: ExportOptions,
        filenamePattern: String = FilenameTemplate.defaultPattern
    ) throws -> [(data: Data, fileName: String)] {
        try plannedFiles(
            visits: visits,
            points: points,
            recordingSessions: recordingSessions,
            activeTrackingStart: activeTrackingStart,
            options: options,
            filenamePattern: filenamePattern,
            forceSplitByDay: true
        ).map { (Data($0.content.utf8), $0.relativePath) }
    }

    static func plannedFiles(
        visits: [Visit],
        points: [LocationPoint],
        recordingSessions: [RecordingSession] = [],
        activeTrackingStart: Date? = nil,
        options: ExportOptions,
        selectedFormats: [ExportFormat]? = nil,
        filenamePattern: String = FilenameTemplate.defaultPattern,
        aggregateFilenameDate: Date? = nil,
        forceSplitByDay: Bool? = nil
    ) throws -> [PlannedExportFile] {
        let formats = normalizedFormats(selectedFormats ?? [options.format])
        let registry = try rendererRegistry()
        var globalUsedNames = Set<String>()

        return try formats.flatMap { format -> [PlannedExportFile] in
            var formatOptions = options
            formatOptions.format = format
            let splitByDay = forceSplitByDay ?? formatOptions.splitByDay
            let snapshots = try exportSnapshots(
                visits: visits,
                points: points,
                recordingSessions: recordingSessions,
                activeTrackingStart: activeTrackingStart,
                options: formatOptions,
                splitByDay: splitByDay
            )
            var formatUsedNames = Set<String>()
            let descriptor = descriptor(for: format)

            return try snapshots.map { snapshot in
                let rendered = try registry.render(
                    record: snapshot,
                    formatID: descriptor.id,
                    context: .default
                )
                let plannedName = try plannedRelativePath(
                    for: snapshot,
                    filenamePattern: filenamePattern,
                    format: format,
                    usedNames: &formatUsedNames,
                    aggregateDateOverride: aggregateFilenameDate
                )
                let fileName = uniqueFilenameAcrossFormats(plannedName, in: &globalUsedNames, format: format)
                return PlannedExportFile(
                    id: "\(snapshot.exportRecordID)-\(descriptor.id)",
                    role: .aggregate(formatID: descriptor.id),
                    relativePath: fileName,
                    content: rendered.content,
                    format: descriptor,
                    contentType: rendered.contentType,
                    displayName: descriptor.displayName,
                    estimatedByteCount: rendered.content.utf8.count
                )
            }
        }
    }

    static func preview(
        visits: [Visit],
        points: [LocationPoint],
        recordingSessions: [RecordingSession] = [],
        activeTrackingStart: Date? = nil,
        options: ExportOptions,
        selectedFormats: [ExportFormat]? = nil,
        filenamePattern: String = FilenameTemplate.defaultPattern
    ) async throws -> ExportPreview {
        let formats = normalizedFormats(selectedFormats ?? [options.format])
        let entries = try previewEntries(
            visits: visits,
            points: points,
            recordingSessions: recordingSessions,
            activeTrackingStart: activeTrackingStart,
            options: options,
            formats: formats
        )

        let grouped = groupedPreviewEntries(entries)
        let selectedGroups = Array(grouped.prefix(ExportPreviewBuilder<IsoMeExportSnapshot, IsoMeExportSnapshot>.defaultMaxRenderedRecords))
        let registry = try rendererRegistry()
        var formatUsedNames: [ExportFormat: Set<String>] = [:]
        var globalUsedNames = Set<String>()
        var warnings: [ExportWarning] = []

        let records = try selectedGroups.map { group -> ExportPreviewRecord in
            let files = try group.entries.map { entry -> PlannedExportFile in
                let descriptor = descriptor(for: entry.format)
                let rendered = try registry.render(
                    record: entry.snapshot,
                    formatID: descriptor.id,
                    context: .default
                )
                var usedNames = formatUsedNames[entry.format] ?? Set<String>()
                let plannedName = try plannedRelativePath(
                    for: entry.snapshot,
                    filenamePattern: filenamePattern,
                    format: entry.format,
                    usedNames: &usedNames,
                    safetyPolicy: .preserveCurrentBehavior
                )
                formatUsedNames[entry.format] = usedNames
                let fileName = uniqueFilenameAcrossFormats(plannedName, in: &globalUsedNames, format: entry.format)
                let file = PlannedExportFile(
                    id: "\(entry.snapshot.exportRecordID)-\(descriptor.id)",
                    role: .aggregate(formatID: descriptor.id),
                    relativePath: fileName,
                    content: rendered.content,
                    format: descriptor,
                    contentType: rendered.contentType,
                    displayName: descriptor.displayName,
                    estimatedByteCount: rendered.content.utf8.count
                )
                warnings.append(contentsOf: file.warnings)
                return file
            }

            return ExportPreviewRecord(reference: group.reference, files: files)
        }

        return ExportPreview(
            records: records,
            warnings: warnings,
            totalRecordCount: grouped.count,
            fetchAttemptCount: selectedGroups.count,
            maxRenderedRecords: ExportPreviewBuilder<IsoMeExportSnapshot, IsoMeExportSnapshot>.defaultMaxRenderedRecords,
            maxFetchAttempts: ExportPreviewBuilder<IsoMeExportSnapshot, IsoMeExportSnapshot>.defaultMaxFetchAttempts
        )
    }

    static func plannedOutingPreviewRelativePath(
        for outing: RecordingSessionSummary,
        filenamePattern: String,
        format: ExportFormat,
        safetyPolicy: ExportPathSafetyPolicy = .preserveCurrentBehavior
    ) throws -> String {
        var options = ExportOptions()
        options.dataKind = .outings
        options.format = format
        options.splitByDay = true
        let snapshot = IsoMeExportSnapshot(
            id: outingIdentifier(for: outing),
            exportDate: outing.startedAt,
            visits: [],
            points: outing.points,
            outings: [outing],
            options: options,
            dataKind: .outings,
            isSplitByDay: false,
            isSplitOuting: true
        )
        var usedNames = Set<String>()
        return try plannedRelativePath(
            for: snapshot,
            filenamePattern: filenamePattern,
            format: format,
            usedNames: &usedNames,
            safetyPolicy: safetyPolicy
        )
    }

    static func writeTemporaryFiles(_ files: [PlannedExportFile]) throws -> [URL] {
        let destination = ExportDestination(
            rootURL: FileManager.default.temporaryDirectory,
            displayName: "Temporary Exports"
        )
        let writer = ExportFileWriter(
            fileSystem: FileManagerExportFileSystem(),
            safetyPolicy: .rejectTraversalAndAbsolutePaths
        )
        return try writer.write(files, to: destination, mode: .overwrite).map(\.url)
    }

    static func plannedOutingFiles(
        outings: [RecordingSessionSummary],
        visits: [Visit],
        options: ExportOptions,
        filenamePattern: String = FilenameTemplate.defaultPattern
    ) throws -> [PlannedExportFile] {
        var outingOptions = options
        outingOptions.dataKind = .outings
        outingOptions.datePreset = .allTime
        outingOptions.timeOfDayEnabled = false
        outingOptions.splitByDay = false

        let registry = try rendererRegistry()
        var usedNames = Set<String>()

        return try outings.map { outing in
            let descriptor = descriptor(for: outingOptions.format)
            let snapshot = IsoMeExportSnapshot(
                id: outingIdentifier(for: outing),
                exportDate: outing.startedAt,
                visits: visitsForOuting(outing, visits: visits),
                points: outing.points,
                outings: [outing],
                options: outingOptions,
                dataKind: .outings,
                isSplitByDay: false,
                isSplitOuting: true
            )
            let rendered = try registry.render(
                record: snapshot,
                formatID: descriptor.id,
                context: .default
            )
            let fileName = try plannedRelativePath(
                for: snapshot,
                filenamePattern: filenamePattern,
                format: outingOptions.format,
                usedNames: &usedNames
            )
            return PlannedExportFile(
                id: "\(snapshot.exportRecordID)-\(descriptor.id)",
                role: .aggregate(formatID: descriptor.id),
                relativePath: fileName,
                content: rendered.content,
                format: descriptor,
                contentType: rendered.contentType,
                displayName: descriptor.displayName,
                estimatedByteCount: rendered.content.utf8.count
            )
        }
    }

    static func run(
        visits: [Visit],
        points: [LocationPoint],
        recordingSessions: [RecordingSession] = [],
        activeTrackingStart: Date? = nil,
        options: ExportOptions,
        filenamePattern: String = FilenameTemplate.defaultPattern,
        destination: ExportDestination?,
        writeMode: ExportWriteMode = .overwrite
    ) async -> ExportRunResult {
        let splitByDay = options.splitByDay
        let snapshots: [IsoMeExportSnapshot]
        do {
            snapshots = try exportSnapshots(
                visits: visits,
                points: points,
                recordingSessions: recordingSessions,
                activeTrackingStart: activeTrackingStart,
                options: options,
                splitByDay: splitByDay
            )
        } catch {
            return ExportRunResult(
                successCount: 0,
                totalCount: 1,
                filesWritten: 0,
                failedRecords: [ExportFailedRecord(
                    record: ExportRecordReference(id: "iso.me-export"),
                    failure: failure(for: error)
                )],
                formatsPerRecord: 1
            )
        }

        var usedNames = Set<String>()
        let dataSource = AnyExportRecordDataSource<IsoMeExportSnapshot, IsoMeExportSnapshot> { snapshot in
            ExportFetchedRecord(record: snapshot)
        }
        let fileWriter = ExportFileWriter(
            fileSystem: FileManagerExportFileSystem(),
            safetyPolicy: .rejectTraversalAndAbsolutePaths
        )

        let writer = AnyExportRecordWriter<IsoMeExportSnapshot> { snapshot, context in
            guard let destination = context.destination else {
                throw IsoMeExportKitError.noDestination
            }
            let descriptor = descriptor(for: options.format)
            let registry = try rendererRegistry()
            let rendered = try registry.render(record: snapshot, formatID: descriptor.id)
            let fileName = try plannedRelativePath(
                for: snapshot,
                filenamePattern: filenamePattern,
                format: options.format,
                usedNames: &usedNames
            )
            let file = PlannedExportFile(
                id: "\(snapshot.exportRecordID)-\(descriptor.id)",
                role: .aggregate(formatID: descriptor.id),
                relativePath: fileName,
                content: rendered.content,
                format: descriptor,
                contentType: rendered.contentType,
                displayName: descriptor.displayName,
                estimatedByteCount: rendered.content.utf8.count
            )
            let results = try fileWriter.write([file], to: destination, mode: context.writeMode)
            return ExportRecordWriteSummary(filesWritten: results.count)
        }

        let orchestrator = ExportRunOrchestrator(
            dataSource: dataSource,
            writer: writer,
            failureMapper: failure(for:)
        )
        let request = ExportRunRequest(
            recordInputs: snapshots,
            formatIDs: [options.format.exportKitFormatID],
            destination: destination,
            writeMode: writeMode,
            recordReference: { snapshot in
                ExportRecordReference(
                    id: snapshot.exportRecordID,
                    date: snapshot.exportDate,
                    displayName: snapshot.displayTitle
                )
            }
        )
        return await orchestrator.run(request)
    }

    private struct PreviewEntry {
        let format: ExportFormat
        let snapshot: IsoMeExportSnapshot
    }

    private struct PreviewEntryGroup {
        let reference: ExportRecordReference
        var entries: [PreviewEntry]
    }

    private static func previewEntries(
        visits: [Visit],
        points: [LocationPoint],
        recordingSessions: [RecordingSession],
        activeTrackingStart: Date?,
        options: ExportOptions,
        formats: [ExportFormat]
    ) throws -> [PreviewEntry] {
        try formats.flatMap { format -> [PreviewEntry] in
            var formatOptions = options
            formatOptions.format = format
            let snapshots = try exportSnapshots(
                visits: visits,
                points: points,
                recordingSessions: recordingSessions,
                activeTrackingStart: activeTrackingStart,
                options: formatOptions,
                splitByDay: formatOptions.splitByDay
            )
            return snapshots.map { PreviewEntry(format: format, snapshot: $0) }
        }
    }

    private static func groupedPreviewEntries(_ entries: [PreviewEntry]) -> [PreviewEntryGroup] {
        var groupsByID: [String: PreviewEntryGroup] = [:]
        var orderedIDs: [String] = []

        for entry in entries {
            let snapshot = entry.snapshot
            if groupsByID[snapshot.exportRecordID] == nil {
                orderedIDs.append(snapshot.exportRecordID)
                groupsByID[snapshot.exportRecordID] = PreviewEntryGroup(
                    reference: ExportRecordReference(
                        id: snapshot.exportRecordID,
                        date: snapshot.exportDate,
                        displayName: snapshot.displayTitle
                    ),
                    entries: []
                )
            }
            groupsByID[snapshot.exportRecordID]?.entries.append(entry)
        }

        return orderedIDs
            .compactMap { groupsByID[$0] }
            .sorted { lhs, rhs in
                switch (lhs.reference.date, rhs.reference.date) {
                case let (left?, right?): return left > right
                case (_?, nil): return true
                case (nil, _?): return false
                case (nil, nil): return lhs.reference.id < rhs.reference.id
                }
            }
    }

    private static func exportSnapshots(
        visits: [Visit],
        points: [LocationPoint],
        recordingSessions: [RecordingSession],
        activeTrackingStart: Date?,
        options: ExportOptions,
        splitByDay: Bool
    ) throws -> [IsoMeExportSnapshot] {
        let effectiveKind = effectiveDataKind(for: options)
        let filteredVisits: [Visit] = {
            switch effectiveKind {
            case .visits, .outings, .all: return options.filterVisits(visits)
            case .points: return []
            }
        }()
        let filteredPoints: [LocationPoint] = {
            switch effectiveKind {
            case .points, .all: return options.filterPoints(points)
            case .visits, .outings: return []
            }
        }()
        let outingSummaries: [RecordingSessionSummary] = {
            guard effectiveKind == .outings else { return [] }
            return options.filterOutings(
                RecordingSessionBuilder.summaries(
                    storedSessions: recordingSessions,
                    points: points,
                    activeTrackingStart: activeTrackingStart,
                    inferenceConfiguration: .stored()
                )
            )
        }()

        guard splitByDay else {
            return [IsoMeExportSnapshot(
                id: "iso.me-\(effectiveKind.rawValue)",
                exportDate: Date(),
                visits: filteredVisits,
                points: filteredPoints,
                outings: effectiveKind == .outings ? outingSummaries : [],
                options: options,
                dataKind: effectiveKind,
                isSplitByDay: false,
                isSplitOuting: false
            )]
        }

        if effectiveKind == .outings {
            return outingSummaries.map { outing in
                var outingOptions = options
                outingOptions.datePreset = .allTime
                outingOptions.timeOfDayEnabled = false
                outingOptions.splitByDay = false
                return IsoMeExportSnapshot(
                    id: outingIdentifier(for: outing),
                    exportDate: outing.startedAt,
                    visits: visitsForOuting(outing, visits: visits),
                    points: outing.points,
                    outings: [outing],
                    options: outingOptions,
                    dataKind: effectiveKind,
                    isSplitByDay: false,
                    isSplitOuting: true
                )
            }
        }

        return options.groupByDay(visits: filteredVisits, points: filteredPoints).map { group in
            var dayOptions = options
            dayOptions.datePreset = .allTime
            dayOptions.timeOfDayEnabled = false
            dayOptions.excludeOutliers = false
            dayOptions.onlyCompletedVisits = false
            dayOptions.minVisitDurationMinutes = 0
            dayOptions.maxAccuracyMeters = 0
            dayOptions.splitByDay = false
            let dayKind = effectiveDataKind(for: dayOptions)
            return IsoMeExportSnapshot(
                id: dayIdentifier(for: group.day, dataKind: dayKind),
                exportDate: group.day,
                visits: group.visits,
                points: group.points,
                outings: [],
                options: dayOptions,
                dataKind: dayKind,
                isSplitByDay: true,
                isSplitOuting: false
            )
        }
    }

    private static func renderData(
        visits: [Visit],
        points: [LocationPoint],
        outings: [RecordingSessionSummary],
        dataKind: ExportOptions.DataKind,
        format: ExportFormat,
        options: ExportOptions
    ) throws -> Data {
        switch dataKind {
        case .visits:
            switch format {
            case .json: return try ExportService.exportToJSON(visits: visits, options: options)
            case .csv: return ExportService.exportToCSV(visits: visits, options: options)
            case .markdown: return ExportService.exportToMarkdown(visits: visits, options: options)
            case .owntracks, .overland: return try ExportService.exportToJSON(visits: visits, options: options)
            case .gpx: return ExportService.exportVisitsToGPX(visits: visits, options: options)
            case .kml: return ExportService.exportVisitsToKML(visits: visits, options: options)
            case .geojson: return try ExportService.exportVisitsToGeoJSON(visits: visits, options: options)
            }
        case .points:
            switch format {
            case .json: return try ExportService.exportLocationPointsToJSON(points: points, options: options)
            case .csv: return ExportService.exportLocationPointsToCSV(points: points, options: options)
            case .markdown: return ExportService.exportLocationPointsToMarkdown(points: points, options: options)
            case .owntracks: return try ExportService.exportLocationPointsToOwnTracks(points: points, options: options)
            case .overland: return try ExportService.exportLocationPointsToOverland(points: points, options: options)
            case .gpx: return ExportService.exportLocationPointsToGPX(points: points, options: options)
            case .kml: return ExportService.exportLocationPointsToKML(points: points, options: options)
            case .geojson: return try ExportService.exportLocationPointsToGeoJSON(points: points, options: options)
            }
        case .outings:
            return try ExportService.outingsData(outings: outings, visits: visits, format: format, options: options)
        case .all:
            return try ExportService.combinedData(visits: visits, points: points, format: format, options: options)
        }
    }

    private static func effectiveDataKind(for options: ExportOptions) -> ExportOptions.DataKind {
        if options.format.isPointsOnly, options.dataKind != .outings {
            return .points
        }
        return options.dataKind
    }

    private static func normalizedFormats(_ formats: [ExportFormat]) -> [ExportFormat] {
        let selected = Set(formats)
        let ordered = ExportFormat.allCases.filter { selected.contains($0) }
        return ordered.isEmpty ? [.json] : ordered
    }

    private static func uniqueFilenameAcrossFormats(
        _ baseName: String,
        in used: inout Set<String>,
        format: ExportFormat
    ) -> String {
        guard used.contains(baseName) else {
            used.insert(baseName)
            return baseName
        }

        let splitIndex = baseName.lastIndex(of: "/")
        let directoryPrefix: String
        let filename: String
        if let splitIndex {
            directoryPrefix = String(baseName[...splitIndex])
            filename = String(baseName[baseName.index(after: splitIndex)...])
        } else {
            directoryPrefix = ""
            filename = baseName
        }

        let ext = ".\(format.fileExtension)"
        let stem: String
        if filename.lowercased().hasSuffix(ext.lowercased()) {
            stem = String(filename.dropLast(ext.count))
        } else {
            stem = filename
        }

        var candidate = "\(directoryPrefix)\(stem)-\(format.token)\(ext)"
        var counter = 2
        while used.contains(candidate) {
            candidate = "\(directoryPrefix)\(stem)-\(format.token)-\(counter)\(ext)"
            counter += 1
        }
        used.insert(candidate)
        return candidate
    }

    private static func failure(for error: Error) -> ExportRunFailure {
        if error as? IsoMeExportKitError == .noDestination {
            return ExportRunFailure(reason: .noDestination, errorDescription: error.localizedDescription)
        }
        if error is ExportPathTemplateError {
            return ExportRunFailure(reason: .writeError, errorDescription: error.localizedDescription)
        }
        if error is ExportFolderError {
            return ExportRunFailure(reason: .accessDenied, errorDescription: error.localizedDescription)
        }
        return ExportRunFailure(reason: .unknown, errorDescription: error.localizedDescription)
    }

    private static func plannedRelativePath(
        for snapshot: IsoMeExportSnapshot,
        filenamePattern: String,
        format: ExportFormat,
        usedNames: inout Set<String>,
        aggregateDateOverride: Date? = nil,
        safetyPolicy: ExportPathSafetyPolicy = .rejectTraversalAndAbsolutePaths
    ) throws -> String {
        let outing = snapshot.isSplitOuting ? snapshot.outings.first : nil
        let patternIncludesTitle = filenamePattern.contains("{title}") || filenamePattern.contains("{name}")
        let patternIncludesTime = filenamePattern.contains("{time}") || filenamePattern.contains("{datetime}")
        let filenameDate = snapshot.isSplitByDay || snapshot.isSplitOuting
            ? snapshot.exportDate
            : (aggregateDateOverride ?? snapshot.exportDate)
        var fileName = try IsoMeExportPathPlanner.plannedRelativePath(
            pattern: filenamePattern,
            dataKind: snapshot.dataKind,
            format: format,
            date: filenameDate,
            title: outing?.title,
            safetyPolicy: safetyPolicy
        )

        if let outing, !patternIncludesTitle {
            fileName = appendFilenameSuffix(
                outingFilenameSuffix(for: outing, includesTimeToken: patternIncludesTime),
                to: fileName,
                format: format
            )
        }

        if snapshot.isSplitByDay || snapshot.isSplitOuting {
            fileName = uniqueFilename(fileName, in: &usedNames, day: snapshot.exportDate, format: format)
        }

        return fileName
    }

    private static func visitsForOuting(_ outing: RecordingSessionSummary, visits: [Visit]) -> [Visit] {
        visits
            .filter { outing.dateRange.contains($0.arrivedAt) }
            .sorted { $0.arrivedAt < $1.arrivedAt }
    }

    private static func outingIdentifier(for outing: RecordingSessionSummary) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd-HHmmss"
        return "Whereday-outing-\(outing.sequenceNumber)-\(formatter.string(from: outing.startedAt))"
    }

    private static func outingFilenameSuffix(for outing: RecordingSessionSummary, includesTimeToken: Bool) -> String {
        let title = FilenameTemplate.sanitize(outing.title)
        if !title.isEmpty { return title }
        if includesTimeToken { return "" }

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "HH-mm"
        return formatter.string(from: outing.startedAt)
    }

    private static func appendFilenameSuffix(_ suffix: String, to path: String, format: ExportFormat) -> String {
        let sanitizedSuffix = FilenameTemplate.sanitize(suffix)
        guard !sanitizedSuffix.isEmpty else { return path }

        let splitIndex = path.lastIndex(of: "/")
        let directoryPrefix: String
        let filename: String
        if let splitIndex {
            directoryPrefix = String(path[...splitIndex])
            filename = String(path[path.index(after: splitIndex)...])
        } else {
            directoryPrefix = ""
            filename = path
        }

        let ext = ".\(format.fileExtension)"
        if filename.lowercased().hasSuffix(ext.lowercased()) {
            let stem = String(filename.dropLast(ext.count))
            return "\(directoryPrefix)\(stem) - \(sanitizedSuffix)\(ext)"
        }

        return "\(directoryPrefix)\(filename) - \(sanitizedSuffix)"
    }

    private static func dayIdentifier(for day: Date, dataKind: ExportOptions.DataKind) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return "Whereday-\(dataKind.rawValue)-\(formatter.string(from: day))"
    }

    private static func uniqueFilename(
        _ baseName: String,
        in used: inout Set<String>,
        day: Date,
        format: ExportFormat
    ) -> String {
        if !used.contains(baseName) {
            used.insert(baseName)
            return baseName
        }

        let dateFmt = DateFormatter()
        dateFmt.locale = Locale(identifier: "en_US_POSIX")
        dateFmt.dateFormat = "yyyy-MM-dd"
        let isoDay = dateFmt.string(from: day)

        let ext = ".\(format.fileExtension)"
        let splitIndex = baseName.lastIndex(of: "/")
        let directoryPrefix: String
        let filename: String
        if let splitIndex {
            directoryPrefix = String(baseName[...splitIndex])
            filename = String(baseName[baseName.index(after: splitIndex)...])
        } else {
            directoryPrefix = ""
            filename = baseName
        }

        let stem: String
        if filename.lowercased().hasSuffix(ext.lowercased()) {
            stem = String(filename.dropLast(ext.count))
        } else {
            stem = filename
        }

        var candidate = "\(directoryPrefix)\(isoDay)_\(stem)\(ext)"
        var counter = 2
        while used.contains(candidate) {
            candidate = "\(directoryPrefix)\(isoDay)_\(stem)_\(counter)\(ext)"
            counter += 1
        }
        used.insert(candidate)
        return candidate
    }
}

extension ExportFormat {
    var exportKitFormatID: String { token }

    var displayName: String {
        switch self {
        case .json: return String(localized: "JSON")
        case .csv: return String(localized: "CSV")
        case .markdown: return String(localized: "Markdown")
        case .owntracks: return String(localized: "OwnTracks")
        case .overland: return String(localized: "Overland")
        case .gpx: return String(localized: "GPX")
        case .kml: return String(localized: "KML")
        case .geojson: return String(localized: "GeoJSON")
        }
    }

    var defaultSortKey: String {
        switch self {
        case .json: return "10-json"
        case .csv: return "20-csv"
        case .markdown: return "30-markdown"
        case .owntracks: return "40-owntracks"
        case .overland: return "50-overland"
        case .gpx: return "60-gpx"
        case .kml: return "70-kml"
        case .geojson: return "80-geojson"
        }
    }

    init?(exportKitFormatID: String) {
        guard let format = Self.allCases.first(where: { $0.exportKitFormatID == exportKitFormatID }) else {
            return nil
        }
        self = format
    }
}
