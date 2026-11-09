import Foundation
import SwiftData

/// A local, versioned backup of every model erased by Clear All Data. This is distinct
/// from an Archive export: field preferences and date filters must never trim a backup.
/// Photo matches retain Photos identifiers, not image files; the library must still be available.
struct WheredayBackup: Codable, Sendable {
    var backupFormat = "whereday-backup"
    var formatVersion = 1
    var exportedAt = Date()
    /// Present only on a file that belongs to a multipart backup. All parts must
    /// be selected together; a partial set is never committed.
    struct Part: Codable, Sendable, Equatable {
        let backupID: UUID
        let index: Int
        let count: Int
    }
    var part: Part?
    var visits: [VisitRecord] = []
    var points: [LocationPointRecord] = []
    var recordingSessions: [RecordingSessionRecord] = []
    var savedPlaces: [SavedPlaceRecord] = []
    var photoMoments: [PhotoMomentRecord] = []
    var countryStays: [CountryStayRecord] = []

    var additionalRecordCount: Int {
        recordingSessions.count + savedPlaces.count + photoMoments.count + countryStays.count
    }
    var recordCount: Int { visits.count + points.count + additionalRecordCount }

    struct VisitRecord: Codable, Identifiable, Sendable {
        let id: UUID
        let latitude: Double
        let longitude: Double
        let arrivedAt: Date
        let departedAt: Date?
        let customName: String?
        let locationName: String?
        let address: String?
        let notes: String?
        let geocodingCompleted: Bool
        let sourceRaw: String?
        let confirmationStatusRaw: String?
        let confirmedAt: Date?
        let updatedAt: Date?
        let originalLatitude: Double?
        let originalLongitude: Double?
        let originalLocationName: String?
        let originalAddress: String?
        let detectedLatitude: Double?
        let detectedLongitude: Double?
        let detectedLocationName: String?
        let detectedAddress: String?
        let placeSourceRaw: String?
        let placeDistanceMeters: Double?
        let originalConfirmationStatusRaw: String?
        let originalConfirmedAt: Date?
        let originalPlaceSourceRaw: String?
        let originalPlaceDistanceMeters: Double?
        let countryCode: String?
        let countryTimeZoneIdentifier: String?
        let countryResolutionAttemptedAt: Date?
        let countryDepartureIsInferred: Bool?

        init(_ model: Visit) {
            id = model.id
            latitude = model.latitude
            longitude = model.longitude
            arrivedAt = model.arrivedAt
            departedAt = model.departedAt
            customName = model.customName
            locationName = model.locationName
            address = model.address
            notes = model.notes
            geocodingCompleted = model.geocodingCompleted
            sourceRaw = model.sourceRaw
            confirmationStatusRaw = model.confirmationStatusRaw
            confirmedAt = model.confirmedAt
            updatedAt = model.updatedAt
            originalLatitude = model.originalLatitude
            originalLongitude = model.originalLongitude
            originalLocationName = model.originalLocationName
            originalAddress = model.originalAddress
            detectedLatitude = model.detectedLatitude
            detectedLongitude = model.detectedLongitude
            detectedLocationName = model.detectedLocationName
            detectedAddress = model.detectedAddress
            placeSourceRaw = model.placeSourceRaw
            placeDistanceMeters = model.placeDistanceMeters
            originalConfirmationStatusRaw = model.originalConfirmationStatusRaw
            originalConfirmedAt = model.originalConfirmedAt
            originalPlaceSourceRaw = model.originalPlaceSourceRaw
            originalPlaceDistanceMeters = model.originalPlaceDistanceMeters
            countryCode = model.countryCode
            countryTimeZoneIdentifier = model.countryTimeZoneIdentifier
            countryResolutionAttemptedAt = model.countryResolutionAttemptedAt
            countryDepartureIsInferred = model.countryDepartureIsInferred
        }

        func makeModel() -> Visit {
            let model = Visit(latitude: latitude, longitude: longitude, arrivedAt: arrivedAt, departedAt: departedAt)
            model.id = id
            model.latitude = latitude
            model.longitude = longitude
            model.arrivedAt = arrivedAt
            model.departedAt = departedAt
            model.customName = customName
            model.locationName = locationName
            model.address = address
            model.notes = notes
            model.geocodingCompleted = geocodingCompleted
            model.sourceRaw = sourceRaw
            model.confirmationStatusRaw = confirmationStatusRaw
            model.confirmedAt = confirmedAt
            model.updatedAt = updatedAt
            model.originalLatitude = originalLatitude
            model.originalLongitude = originalLongitude
            model.originalLocationName = originalLocationName
            model.originalAddress = originalAddress
            model.detectedLatitude = detectedLatitude
            model.detectedLongitude = detectedLongitude
            model.detectedLocationName = detectedLocationName
            model.detectedAddress = detectedAddress
            model.placeSourceRaw = placeSourceRaw
            model.placeDistanceMeters = placeDistanceMeters
            model.originalConfirmationStatusRaw = originalConfirmationStatusRaw
            model.originalConfirmedAt = originalConfirmedAt
            model.originalPlaceSourceRaw = originalPlaceSourceRaw
            model.originalPlaceDistanceMeters = originalPlaceDistanceMeters
            model.countryCode = countryCode
            model.countryTimeZoneIdentifier = countryTimeZoneIdentifier
            model.countryResolutionAttemptedAt = countryResolutionAttemptedAt
            model.countryDepartureIsInferred = countryDepartureIsInferred
            return model
        }
    }

    struct LocationPointRecord: Codable, Identifiable, Sendable {
        let id: UUID
        let latitude: Double
        let longitude: Double
        let timestamp: Date
        let altitude: Double?
        let speed: Double?
        let horizontalAccuracy: Double
        let isOutlier: Bool
        let countryCode: String?
        let countryTimeZoneIdentifier: String?
        let countryResolutionAttemptedAt: Date?

        init(_ model: LocationPoint) {
            id = model.id
            latitude = model.latitude
            longitude = model.longitude
            timestamp = model.timestamp
            altitude = model.altitude
            speed = model.speed
            horizontalAccuracy = model.horizontalAccuracy
            isOutlier = model.isOutlier
            countryCode = model.countryCode
            countryTimeZoneIdentifier = model.countryTimeZoneIdentifier
            countryResolutionAttemptedAt = model.countryResolutionAttemptedAt
        }

        func makeModel() -> LocationPoint {
            let model = LocationPoint(latitude: latitude, longitude: longitude, timestamp: timestamp, horizontalAccuracy: horizontalAccuracy)
            model.id = id
            model.latitude = latitude
            model.longitude = longitude
            model.timestamp = timestamp
            model.altitude = altitude
            model.speed = speed
            model.horizontalAccuracy = horizontalAccuracy
            model.isOutlier = isOutlier
            model.countryCode = countryCode
            model.countryTimeZoneIdentifier = countryTimeZoneIdentifier
            model.countryResolutionAttemptedAt = countryResolutionAttemptedAt
            return model
        }
    }

    struct RecordingSessionRecord: Codable, Identifiable, Sendable {
        let id: UUID
        let startedAt: Date
        let endedAt: Date?
        let customName: String?
        let notes: String?
        let receivedLocationCount: Int?
        let distanceFilterMeters: Double?
        let routeSamplingModeRawValue: String?
        let routeSampleIntervalSeconds: Double?

        init(_ model: RecordingSession) {
            id = model.id
            startedAt = model.startedAt
            endedAt = model.endedAt
            customName = model.customName
            notes = model.notes
            receivedLocationCount = model.receivedLocationCount
            distanceFilterMeters = model.distanceFilterMeters
            routeSamplingModeRawValue = model.routeSamplingModeRawValue
            routeSampleIntervalSeconds = model.routeSampleIntervalSeconds
        }

        func makeModel() -> RecordingSession {
            let model = RecordingSession(startedAt: startedAt, endedAt: endedAt)
            model.id = id
            model.startedAt = startedAt
            model.endedAt = endedAt
            model.customName = customName
            model.notes = notes
            model.receivedLocationCount = receivedLocationCount
            model.distanceFilterMeters = distanceFilterMeters
            model.routeSamplingModeRawValue = routeSamplingModeRawValue
            model.routeSampleIntervalSeconds = routeSampleIntervalSeconds
            return model
        }
    }

    struct SavedPlaceRecord: Codable, Identifiable, Sendable {
        let id: UUID
        let name: String
        let latitude: Double
        let longitude: Double
        let address: String?
        let radiusMeters: Double
        let createdAt: Date
        let updatedAt: Date
        let kindRaw: String?
        let notes: String?

        init(_ model: SavedPlace) {
            id = model.id
            name = model.name
            latitude = model.latitude
            longitude = model.longitude
            address = model.address
            radiusMeters = model.radiusMeters
            createdAt = model.createdAt
            updatedAt = model.updatedAt
            kindRaw = model.kindRaw
            notes = model.notes
        }

        func makeModel() -> SavedPlace {
            let model = SavedPlace(name: name, latitude: latitude, longitude: longitude)
            model.id = id
            model.name = name
            model.latitude = latitude
            model.longitude = longitude
            model.address = address
            model.radiusMeters = radiusMeters
            model.createdAt = createdAt
            model.updatedAt = updatedAt
            model.kindRaw = kindRaw
            model.notes = notes
            return model
        }
    }

    struct PhotoMomentRecord: Codable, Identifiable, Sendable {
        let id: UUID
        let assetLocalIdentifier: String
        let takenAt: Date
        let latitude: Double
        let longitude: Double
        let coordinateSourceRawValue: String
        let lastSyncedAt: Date

        init(_ model: PhotoMoment) {
            id = model.id
            assetLocalIdentifier = model.assetLocalIdentifier
            takenAt = model.takenAt
            latitude = model.latitude
            longitude = model.longitude
            coordinateSourceRawValue = model.coordinateSourceRawValue
            lastSyncedAt = model.lastSyncedAt
        }

        func makeModel() -> PhotoMoment {
            let model = PhotoMoment(assetLocalIdentifier: assetLocalIdentifier, takenAt: takenAt, latitude: latitude, longitude: longitude)
            model.id = id
            model.assetLocalIdentifier = assetLocalIdentifier
            model.takenAt = takenAt
            model.latitude = latitude
            model.longitude = longitude
            model.coordinateSourceRawValue = coordinateSourceRawValue
            model.lastSyncedAt = lastSyncedAt
            return model
        }
    }

    struct CountryStayRecord: Codable, Identifiable, Sendable {
        let id: UUID
        let countryCode: String
        let firstDay: String
        let lastDay: String
        let note: String?

        init(_ model: CountryStay) {
            id = model.id
            countryCode = model.countryCode
            firstDay = model.firstDay
            lastDay = model.lastDay
            note = model.note
        }

        func makeModel() -> CountryStay {
            let model = CountryStay(countryCode: countryCode, firstDay: firstDay, lastDay: lastDay)
            model.id = id
            model.countryCode = countryCode
            model.firstDay = firstDay
            model.lastDay = lastDay
            model.note = note
            return model
        }
    }

    func validate() throws {
        guard backupFormat == "whereday-backup",
              (formatVersion == 1 && part == nil) || (formatVersion == 2 && part != nil) else {
            throw ImportError.invalidData(String(localized: "This backup version is not supported. Update Whereday before restoring it."))
        }
        func unique<R: Identifiable>(_ records: [R]) throws where R.ID == UUID {
            guard Set(records.map(\.id)).count == records.count else {
                throw ImportError.invalidData(String(localized: "The backup contains duplicate record identifiers."))
            }
        }
        func coordinate(_ latitude: Double, _ longitude: Double) -> Bool {
            latitude.isFinite && longitude.isFinite && (-90...90).contains(latitude) && (-180...180).contains(longitude)
        }
        try unique(visits)
        try unique(points)
        try unique(recordingSessions)
        try unique(savedPlaces)
        try unique(photoMoments)
        try unique(countryStays)
        guard visits.allSatisfy({ visit in coordinate(visit.latitude, visit.longitude) && GoogleTimelineImportService.validDate(visit.arrivedAt)
                    && (visit.departedAt.map { GoogleTimelineImportService.validDate($0) && $0 >= visit.arrivedAt } ?? true) }),
              points.allSatisfy({ coordinate($0.latitude, $0.longitude) && GoogleTimelineImportService.validDate($0.timestamp)
                    && $0.horizontalAccuracy.isFinite && $0.horizontalAccuracy >= -1 && ($0.altitude?.isFinite ?? true) && ($0.speed?.isFinite ?? true) }),
              savedPlaces.allSatisfy({ coordinate($0.latitude, $0.longitude) && $0.radiusMeters.isFinite && $0.radiusMeters > 0 }),
              photoMoments.allSatisfy({ coordinate($0.latitude, $0.longitude) && !$0.assetLocalIdentifier.isEmpty }),
              recordingSessions.allSatisfy({ session in GoogleTimelineImportService.validDate(session.startedAt)
                    && (session.endedAt.map { GoogleTimelineImportService.validDate($0) && $0 >= session.startedAt } ?? true) }),
              countryStays.allSatisfy({ CountryDayCalculator.date(for: $0.firstDay) != nil
                    && CountryDayCalculator.date(for: $0.lastDay) != nil && $0.firstDay <= $0.lastDay }) else {
            throw ImportError.invalidData(String(localized: "The backup contains invalid records. Nothing was restored."))
        }
    }
}

enum WheredayBackupService {
    /// Fetch from storage, never from a partially loaded view-model cache.
    private static func snapshot(context: ModelContext) throws -> WheredayBackup {
        var backup = WheredayBackup()
        backup.visits = try context.fetch(FetchDescriptor<Visit>()).map(WheredayBackup.VisitRecord.init)
        backup.points = try context.fetch(FetchDescriptor<LocationPoint>()).map(WheredayBackup.LocationPointRecord.init)
        backup.recordingSessions = try context.fetch(FetchDescriptor<RecordingSession>()).map(WheredayBackup.RecordingSessionRecord.init)
        backup.savedPlaces = try context.fetch(FetchDescriptor<SavedPlace>()).map(WheredayBackup.SavedPlaceRecord.init)
        backup.photoMoments = try context.fetch(FetchDescriptor<PhotoMoment>()).map(WheredayBackup.PhotoMomentRecord.init)
        backup.countryStays = try context.fetch(FetchDescriptor<CountryStay>()).map(WheredayBackup.CountryStayRecord.init)
        try backup.validate()
        return backup
    }

    static func export(context: ModelContext) throws -> Data {
        try encodeFile(snapshot(context: context))
    }

    private static func encodeFile(_ backup: WheredayBackup) throws -> Data {
        guard backup.recordCount <= ImportPayload.maximumRecordCount else { throw TimelineImportError.tooManyRecords }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let data = try encoder.encode(backup)
        guard data.count <= ImportPayload.maximumFileSize else { throw TimelineImportError.fileTooLarge }
        return data
    }

    static func decode(_ data: Data) throws -> WheredayBackup {
        guard data.count <= ImportPayload.maximumFileSize else { throw TimelineImportError.fileTooLarge }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        let backup = try decoder.decode(WheredayBackup.self, from: data)
        try backup.validate()
        guard backup.recordCount <= ImportPayload.maximumRecordCount else { throw TimelineImportError.tooManyRecords }
        if let part = backup.part {
            guard part.count > 1, part.index >= 0, part.index < part.count else { throw incompleteBackup }
        }
        return backup
    }

    private static var incompleteBackup: ImportError {
        .invalidData(String(localized: "Select all files from the same backup. Nothing was restored."))
    }

    /// Validate the whole set before preview or persistence. Duplicate IDs across
    /// files are invalid, rather than silently losing one version of a record.
    static func combine(_ files: [WheredayBackup]) throws -> WheredayBackup {
        guard let first = files.first else { throw incompleteBackup }
        guard let part = first.part else {
            guard files.count == 1 else { throw incompleteBackup }
            return first
        }
        guard files.count == part.count,
              Set(files.compactMap { $0.part?.index }).count == part.count,
              files.allSatisfy({ $0.part?.backupID == part.backupID && $0.part?.count == part.count
                  && $0.exportedAt == first.exportedAt }) else { throw incompleteBackup }
        var result = WheredayBackup()
        result.exportedAt = first.exportedAt
        for file in files.sorted(by: { ($0.part?.index ?? 0) < ($1.part?.index ?? 0) }) {
            try Task.checkCancellation()
            try file.validate()
            guard let index = file.part?.index, (0..<part.count).contains(index) else { throw incompleteBackup }
            result.visits += file.visits
            result.points += file.points
            result.recordingSessions += file.recordingSessions
            result.savedPlaces += file.savedPlaces
            result.photoMoments += file.photoMoments
            result.countryStays += file.countryStays
        }
        try result.validate()
        return result
    }

    /// Export bounded files while keeping one logical backup. The destination is
    /// new and removed on failure, so an incomplete set cannot reach the share sheet.
    static func exportFiles(context: ModelContext, directory: URL) throws -> [URL] {
        try writeFiles(snapshot(context: context), directory: directory)
    }

    static func writeFiles(_ backup: WheredayBackup, directory: URL,
                           maximumRecords: Int = ImportPayload.maximumRecordCount,
                           maximumBytes: Int = ImportPayload.maximumFileSize) throws -> [URL] {
        precondition(maximumRecords > 0 && maximumRecords <= ImportPayload.maximumRecordCount)
        precondition(maximumBytes > 0 && maximumBytes <= ImportPayload.maximumFileSize)
        try Task.checkCancellation()
        try backup.validate()
        guard backup.part == nil else { throw incompleteBackup }
        // Refuse to overwrite another export or remove an existing directory on error.
        guard !FileManager.default.fileExists(atPath: directory.path) else { throw CocoaError(.fileWriteFileExists) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        do {
            let backupID = UUID()
            var ranges: [Range<Int>] = []
            func plan(_ range: Range<Int>) throws {
                try Task.checkCancellation()
                if range.count > maximumRecords {
                    let middle = range.lowerBound + range.count / 2
                    try plan(range.lowerBound..<middle)
                    try plan(middle..<range.upperBound)
                    return
                }
                var candidate = slice(backup, range: range)
                // Reserve the largest metadata representation during planning.
                candidate.formatVersion = 2 // Older apps must reject an incomplete multipart file.
                candidate.part = .init(backupID: backupID, index: Int.max - 1, count: Int.max)
                let data = try? encodeFile(candidate)
                if let data, data.count <= maximumBytes { ranges.append(range); return }
                guard range.count > 1 else { throw TimelineImportError.fileTooLarge }
                let middle = range.lowerBound + range.count / 2
                try plan(range.lowerBound..<middle)
                try plan(middle..<range.upperBound)
            }
            // Preserve the single-file format, including empty backups.
            if backup.recordCount <= maximumRecords, let data = try? encodeFile(backup), data.count <= maximumBytes {
                let url = directory.appendingPathComponent("Whereday Backup.json")
                try data.write(to: url, options: .atomic)
                return [url]
            }
            try plan(0..<backup.recordCount)
            var urls: [URL] = []
            for (index, range) in ranges.enumerated() {
                try Task.checkCancellation()
                var file = slice(backup, range: range)
                file.formatVersion = 2
                file.part = .init(backupID: backupID, index: index, count: ranges.count)
                let data = try encodeFile(file)
                guard data.count <= maximumBytes else { throw TimelineImportError.fileTooLarge }
                let url = directory.appendingPathComponent("Whereday Backup \(index + 1) of \(ranges.count).json")
                try data.write(to: url, options: .atomic)
                urls.append(url)
            }
            return urls
        } catch {
            try? FileManager.default.removeItem(at: directory)
            throw error
        }
    }

    /// Slice the six model collections as one contiguous sequence without
    /// dropping metadata or assigning new record identities.
    private static func slice(_ backup: WheredayBackup, range: Range<Int>) -> WheredayBackup {
        var offset = 0
        func take<T>(_ records: [T]) -> [T] {
            defer { offset += records.count }
            let start = max(0, range.lowerBound - offset)
            let end = min(records.count, range.upperBound - offset)
            return start < end ? Array(records[start..<end]) : []
        }
        var result = WheredayBackup()
        result.exportedAt = backup.exportedAt
        result.visits = take(backup.visits)
        result.points = take(backup.points)
        result.recordingSessions = take(backup.recordingSessions)
        result.savedPlaces = take(backup.savedPlaces)
        result.photoMoments = take(backup.photoMoments)
        result.countryStays = take(backup.countryStays)
        return result
    }

    /// A preview is advisory. Commit rechecks IDs in a fresh context, so another
    /// import between review and confirmation cannot duplicate or overwrite records.
    static func pending(_ backup: WheredayBackup, context: ModelContext) throws -> WheredayBackup {
        try backup.validate()
        guard backup.part == nil else { throw incompleteBackup }
        func missing<R: Identifiable, M: PersistentModel>(_ records: [R], model: M.Type,
                                                         id: KeyPath<M, UUID>) throws -> [R] where R.ID == UUID {
            var seen = Set(try context.fetch(FetchDescriptor<M>()).map { $0[keyPath: id] })
            return records.filter { seen.insert($0.id).inserted }
        }
        var pending = backup
        pending.visits = try missing(backup.visits, model: Visit.self, id: \Visit.id)
        pending.points = try missing(backup.points, model: LocationPoint.self, id: \LocationPoint.id)
        pending.recordingSessions = try missing(backup.recordingSessions, model: RecordingSession.self, id: \RecordingSession.id)
        pending.savedPlaces = try missing(backup.savedPlaces, model: SavedPlace.self, id: \SavedPlace.id)
        pending.photoMoments = try missing(backup.photoMoments, model: PhotoMoment.self, id: \PhotoMoment.id)
        pending.countryStays = try missing(backup.countryStays, model: CountryStay.self, id: \CountryStay.id)
        return pending
    }

    static func restore(_ backup: WheredayBackup, context: ModelContext) throws -> ImportCommitResult {
        let plan = try pending(backup, context: context)
        context.autosaveEnabled = false
        var completed = 0
        ImportProgress.post(completed: completed, total: plan.recordCount)
        func insert<M: PersistentModel>(_ model: M) throws {
            if completed.isMultiple(of: 500) {
                try Task.checkCancellation()
                ImportProgress.post(completed: completed, total: plan.recordCount)
            }
            context.insert(model)
            completed += 1
        }
        do {
            for record in plan.visits { try insert(record.makeModel()) }
            for record in plan.points { try insert(record.makeModel()) }
            for record in plan.recordingSessions { try insert(record.makeModel()) }
            for record in plan.savedPlaces { try insert(record.makeModel()) }
            for record in plan.photoMoments { try insert(record.makeModel()) }
            for record in plan.countryStays { try insert(record.makeModel()) }
            try Task.checkCancellation()
            try context.save()
            ImportProgress.post(completed: completed, total: plan.recordCount)
        } catch {
            context.rollback()
            throw error
        }
        return ImportCommitResult(visitCount: plan.visits.count, pointCount: plan.points.count,
                                  duplicateVisitCount: backup.visits.count - plan.visits.count,
                                  duplicatePointCount: backup.points.count - plan.points.count,
                                  additionalRecordCount: plan.additionalRecordCount,
                                  duplicateAdditionalRecordCount: backup.additionalRecordCount - plan.additionalRecordCount)
    }
}
