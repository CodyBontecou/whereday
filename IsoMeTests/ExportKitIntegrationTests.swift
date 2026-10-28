import XCTest
import ExportKit
import ExportAutomationKit
@testable import IsoMe

final class ExportKitIntegrationTests: XCTestCase {
    func testExportKitRenderersEmitEveryIsoMeFormat() throws {
        let visits = makeVisits()
        let points = makePoints()

        for format in ExportFormat.allCases {
            var options = ExportOptions()
            options.dataKind = .all
            options.format = format

            let rendered = try ExportService.render(
                visits: visits,
                points: points,
                options: options,
                filenamePattern: "{type}-{format}"
            )

            XCTAssertTrue(rendered.fileName.hasSuffix(".\(format.fileExtension)"), "\(format) filename mismatch")
            XCTAssertFalse(rendered.data.isEmpty, "\(format) renderer emitted empty data")
        }
    }

    func testYesterdayDateRangeCoversPreviousCalendarDay() throws {
        var options = ExportOptions()
        options.datePreset = .yesterday

        let calendar = Calendar.current
        let now = calendar.date(from: DateComponents(year: 2026, month: 4, day: 3, hour: 12))!
        let range = try XCTUnwrap(options.resolvedDateRange(now: now))
        let startOfToday = calendar.startOfDay(for: now)
        let startOfYesterday = calendar.date(byAdding: .day, value: -1, to: startOfToday)!
        let endOfYesterday = Date(timeIntervalSinceReferenceDate: startOfToday.timeIntervalSinceReferenceDate.nextDown)

        XCTAssertEqual(range.lowerBound, startOfYesterday)
        XCTAssertEqual(range.upperBound, endOfYesterday)
        XCTAssertTrue(range.contains(startOfYesterday.addingTimeInterval(12 * 60 * 60)))
        XCTAssertFalse(range.contains(now))
    }

    func testMapYesterdayPresetCoversPreviousCalendarDay() throws {
        let calendar = Calendar.current
        let now = calendar.date(from: DateComponents(year: 2026, month: 4, day: 3, hour: 12))!
        let range = MapDatePreset.yesterday.range(referenceDate: now)
        let startOfToday = calendar.startOfDay(for: now)
        let startOfYesterday = calendar.date(byAdding: .day, value: -1, to: startOfToday)!
        let endOfYesterday = Date(timeIntervalSinceReferenceDate: startOfToday.timeIntervalSinceReferenceDate.nextDown)

        XCTAssertEqual(range.lowerBound, startOfYesterday)
        XCTAssertEqual(range.upperBound, endOfYesterday)
        XCTAssertTrue(range.contains(startOfYesterday.addingTimeInterval(12 * 60 * 60)))
        XCTAssertFalse(range.contains(now))
    }

    func testPathPlannerRejectsTraversalAndPreservesPathFolders() throws {
        let date = fixtureDate(hour: 12)

        XCTAssertThrowsError(
            try IsoMeExportPathPlanner.plannedRelativePath(
                pattern: "../escape",
                dataKind: .visits,
                format: .json,
                date: date
            )
        )
        XCTAssertThrowsError(
            try IsoMeExportPathPlanner.plannedRelativePath(
                pattern: "/tmp/escape",
                dataKind: .visits,
                format: .json,
                date: date
            )
        )

        let filename = try IsoMeExportPathPlanner.plannedRelativePath(
            pattern: "trip:{date}/{type}",
            dataKind: .visits,
            format: .json,
            date: date
        )
        XCTAssertEqual(filename, "trip-2026-04-03/visits.json")
    }

    func testPathPlannerSupportsDatedFoldersAndOptionalExtension() throws {
        let date = fixtureDate(hour: 12)

        let markdownPath = try IsoMeExportPathPlanner.plannedRelativePath(
            pattern: "{year}/{year}-{month}/Daily Track - {date}.md",
            dataKind: .all,
            format: .markdown,
            date: date
        )
        XCTAssertEqual(markdownPath, "2026/2026-04/Daily Track - 2026-04-03.md")

        let calendarPath = try IsoMeExportPathPlanner.plannedRelativePath(
            pattern: "{year}/{quarter}/{monthName}/{dayNumber}-{weekday}-{type}",
            dataKind: .points,
            format: .csv,
            date: date
        )
        XCTAssertEqual(calendarPath, "2026/Q2/April/03-Friday-points.csv")
    }

    func testTitleTokenFallsBackToDataKindWhenNoOutingTitleExists() throws {
        let date = fixtureDate(hour: 12)
        let pattern = "{date}/{format}/{title}"

        let visitsPath = try IsoMeExportPathPlanner.plannedRelativePath(
            pattern: pattern,
            dataKind: .visits,
            format: .markdown,
            date: date
        )
        XCTAssertEqual(visitsPath, "2026-04-03/md/visits.md")

        let pointsPath = try IsoMeExportPathPlanner.plannedRelativePath(
            pattern: pattern,
            dataKind: .points,
            format: .markdown,
            date: date
        )
        XCTAssertEqual(pointsPath, "2026-04-03/md/points.md")

        let allPath = try IsoMeExportPathPlanner.plannedRelativePath(
            pattern: pattern,
            dataKind: .all,
            format: .markdown,
            date: date
        )
        XCTAssertEqual(allPath, "2026-04-03/md/all.md")

        let outingPath = try IsoMeExportPathPlanner.plannedRelativePath(
            pattern: pattern,
            dataKind: .outings,
            format: .markdown,
            date: date,
            title: "Morning Ferry Loop"
        )
        XCTAssertEqual(outingPath, "2026-04-03/md/Morning Ferry Loop.md")
    }

    func testExtensionAppenderUsesFallbackBasenameForEmptyFilenameComponent() throws {
        XCTAssertEqual(
            FilenameTemplate.appendingFormatExtensionIfNeeded(
                to: "2026-04-03/md/",
                format: .markdown,
                fallbackBasename: "visits"
            ),
            "2026-04-03/md/visits.md"
        )
        XCTAssertEqual(
            FilenameTemplate.appendingFormatExtensionIfNeeded(
                to: "2026-04-03/md/.md",
                format: .markdown,
                fallbackBasename: "visits"
            ),
            "2026-04-03/md/visits.md"
        )
    }

    func testSplitByDayFilenameCollisionsStayInSameFolder() throws {
        var options = ExportOptions()
        options.dataKind = .visits
        options.format = .markdown
        options.splitByDay = true

        let visits = [
            Visit(
                latitude: 37.7749,
                longitude: -122.4194,
                arrivedAt: fixtureDate(hour: 9),
                departedAt: fixtureDate(hour: 10),
                locationName: "Ferry Building"
            ),
            Visit(
                latitude: 37.775,
                longitude: -122.42,
                arrivedAt: date(year: 2026, month: 4, day: 4, hour: 9),
                departedAt: date(year: 2026, month: 4, day: 4, hour: 10),
                locationName: "Market Street"
            )
        ]

        let files = try IsoMeExportKitAdapter.plannedFiles(
            visits: visits,
            points: [],
            options: options,
            filenamePattern: "{year}/{month}/Daily Track"
        )

        XCTAssertEqual(files.map(\.relativePath), [
            "2026/04/Daily Track.md",
            "2026/04/2026-04-04_Daily Track.md"
        ])
    }

    func testExportFileWriterWriteModesAndTraversalRejection() throws {
        let root = try makeTemporaryDirectory()
        let destination = ExportDestination(rootURL: root)
        let writer = ExportFileWriter(
            fileSystem: FileManagerExportFileSystem(),
            safetyPolicy: .rejectTraversalAndAbsolutePaths
        )
        let descriptor = ExportFormatDescriptor(
            id: "markdown",
            displayName: "Markdown",
            fileExtension: "md",
            contentType: "text/markdown"
        )
        let file = PlannedExportFile(
            id: "daily-note",
            role: .aggregate(formatID: descriptor.id),
            relativePath: "exports/day.md",
            content: "## iso.me\nFirst",
            format: descriptor
        )

        let overwrite = try writer.write(file, to: destination, mode: .overwrite)
        XCTAssertEqual(overwrite.action, .exported)
        XCTAssertEqual(try String(contentsOf: overwrite.url, encoding: .utf8), "## iso.me\nFirst")

        let appendedFile = PlannedExportFile(
            id: "daily-note",
            role: .aggregate(formatID: descriptor.id),
            relativePath: "exports/day.md",
            content: "Second",
            format: descriptor
        )
        let append = try writer.write(appendedFile, to: destination, mode: .append)
        XCTAssertEqual(append.action, .appended)
        XCTAssertEqual(try String(contentsOf: append.url, encoding: .utf8), "## iso.me\nFirst\n\nSecond")

        let userAuthored = "Intro\n\n## Notes\nKeep me\n\n## iso.me\nOld managed text"
        try userAuthored.write(to: append.url, atomically: true, encoding: .utf8)
        let updatedFile = PlannedExportFile(
            id: "daily-note",
            role: .aggregate(formatID: descriptor.id),
            relativePath: "exports/day.md",
            content: "## iso.me\nNew managed text",
            format: descriptor
        )
        let update = try writer.write(
            updatedFile,
            to: destination,
            mode: .update,
            mergeStrategy: MarkdownMergeStrategy(managedSectionNames: ["iso.me"])
        )
        let updated = try String(contentsOf: update.url, encoding: .utf8)
        XCTAssertEqual(update.action, .updated)
        XCTAssertTrue(updated.contains("## Notes\nKeep me"))
        XCTAssertTrue(updated.contains("## iso.me\nNew managed text"))
        XCTAssertFalse(updated.contains("Old managed text"))

        let traversalFile = PlannedExportFile(
            id: "escape",
            role: .aggregate(formatID: descriptor.id),
            relativePath: "../escape.md",
            content: "nope",
            format: descriptor
        )
        XCTAssertThrowsError(try writer.write(traversalFile, to: destination, mode: .overwrite))
    }

    func testOutingMarkdownSplitCreatesOneYamlPagePerOuting() throws {
        let morning = RecordingSession(
            startedAt: fixtureDate(hour: 9),
            endedAt: fixtureDate(hour: 10, minute: 30),
            customName: "Morning Ferry Loop",
            notes: "Breakfast run\nPicked up receipts"
        )
        let afternoon = RecordingSession(
            startedAt: fixtureDate(hour: 14),
            endedAt: fixtureDate(hour: 15),
            customName: "Afternoon Walk",
            notes: "Checked the route replay"
        )
        let points = makePoints() + [
            LocationPoint(
                latitude: 37.781,
                longitude: -122.41,
                timestamp: fixtureDate(hour: 14, minute: 5),
                altitude: 12,
                speed: 1.8,
                horizontalAccuracy: 4
            ),
            LocationPoint(
                latitude: 37.782,
                longitude: -122.409,
                timestamp: fixtureDate(hour: 14, minute: 20),
                altitude: 14,
                speed: 2.1,
                horizontalAccuracy: 5
            )
        ]

        var options = ExportOptions()
        options.dataKind = .outings
        options.format = .markdown
        options.splitByDay = true

        let files = try IsoMeExportKitAdapter.plannedFiles(
            visits: makeVisits(),
            points: points,
            recordingSessions: [morning, afternoon],
            options: options,
            filenamePattern: "Outings/{date}-{title}"
        )

        XCTAssertEqual(files.count, 2)
        XCTAssertEqual(files.map(\.relativePath), [
            "Outings/2026-04-03-Morning Ferry Loop.md",
            "Outings/2026-04-03-Afternoon Walk.md"
        ])

        let firstPage = files[0].content
        XCTAssertTrue(firstPage.hasPrefix("---\n"))
        XCTAssertTrue(firstPage.contains("type: \"outing\""))
        XCTAssertTrue(firstPage.contains("title: \"Morning Ferry Loop\""))
        XCTAssertTrue(firstPage.contains("start: \"2026-04-03T09:00:00.000Z\""))
        XCTAssertTrue(firstPage.contains("end: \"2026-04-03T10:30:00.000Z\""))
        XCTAssertTrue(firstPage.contains("notes: |-\n  Breakfast run\n  Picked up receipts"))
        XCTAssertTrue(firstPage.contains("visit_count: 1"))
        XCTAssertTrue(firstPage.contains("source: \"recorded\""))
        XCTAssertTrue(firstPage.contains("## Visits"))
        XCTAssertTrue(firstPage.contains("## Route Points"))
    }

    func testOutingAutomaticFilenameSuffixUsesTitleWithoutStartTime() throws {
        let outing = RecordingSession(
            startedAt: fixtureDate(hour: 11, minute: 7),
            endedAt: fixtureDate(hour: 12),
            customName: "LAX to MSP"
        )

        var options = ExportOptions()
        options.dataKind = .outings
        options.format = .markdown
        options.splitByDay = true

        let files = try IsoMeExportKitAdapter.plannedFiles(
            visits: [],
            points: [],
            recordingSessions: [outing],
            options: options,
            filenamePattern: "{date}"
        )

        XCTAssertEqual(files.map(\.relativePath), ["2026-04-03 - LAX to MSP.md"])
    }

    func testOutingNameTokenMatchesTitleToken() throws {
        let outing = RecordingSession(
            startedAt: fixtureDate(hour: 9),
            endedAt: fixtureDate(hour: 10),
            customName: "Morning Ferry Loop"
        )

        var options = ExportOptions()
        options.dataKind = .outings
        options.format = .markdown
        options.splitByDay = true

        let titleFile = try IsoMeExportKitAdapter.plannedFiles(
            visits: [],
            points: makePoints(),
            recordingSessions: [outing],
            options: options,
            filenamePattern: "{date}-{title}"
        ).first
        let nameFile = try IsoMeExportKitAdapter.plannedFiles(
            visits: [],
            points: makePoints(),
            recordingSessions: [outing],
            options: options,
            filenamePattern: "{date}-{name}"
        ).first

        XCTAssertEqual(titleFile?.relativePath, "2026-04-03-Morning Ferry Loop.md")
        XCTAssertEqual(nameFile?.relativePath, titleFile?.relativePath)
    }

    func testOutingTrackingProtocolFormatKeepsOutingsDataKind() throws {
        let outing = RecordingSession(
            startedAt: fixtureDate(hour: 9),
            endedAt: fixtureDate(hour: 10),
            customName: "Protocol Walk"
        )
        var options = ExportOptions()
        options.dataKind = .outings
        options.format = .owntracks
        options.splitByDay = true

        let files = try IsoMeExportKitAdapter.plannedFiles(
            visits: [],
            points: makePoints(),
            recordingSessions: [outing],
            options: options,
            filenamePattern: "{type}-{format}-{title}"
        )

        XCTAssertEqual(files.count, 1)
        XCTAssertEqual(files[0].relativePath, "outings-owntracks-Protocol Walk.json")
    }

    func testSingleOutingPlannerExportsDetailPageMarkdown() throws {
        let outing = RecordingSessionSummary(
            id: "inferred-20260403-0900",
            storedSession: nil,
            sequenceNumber: 7,
            startedAt: fixtureDate(hour: 9),
            endedAt: fixtureDate(hour: 10, minute: 30),
            points: makePoints(),
            isInferred: true,
            isActive: false,
            now: fixtureDate(hour: 12)
        )

        var options = ExportOptions()
        options.dataKind = .outings
        options.format = .markdown
        options.splitByDay = true

        let files = try IsoMeExportKitAdapter.plannedOutingFiles(
            outings: [outing],
            visits: makeVisits(),
            options: options,
            filenamePattern: "Outings/{title}"
        )

        XCTAssertEqual(files.count, 1)
        XCTAssertEqual(files[0].relativePath, "Outings/Outing 7.md")
        XCTAssertTrue(files[0].content.hasPrefix("---\n"))
        XCTAssertTrue(files[0].content.contains("title: \"Outing 7\""))
        XCTAssertTrue(files[0].content.contains("source: \"inferred\""))
        XCTAssertTrue(files[0].content.contains("## Visits"))
        XCTAssertTrue(files[0].content.contains("## Route Points"))
    }

    func testPreviewBuilderUsesIsoMeAdapterWithoutWriting() async throws {
        var options = ExportOptions()
        options.dataKind = .all
        options.format = .markdown

        let preview = try await IsoMeExportKitAdapter.preview(
            visits: makeVisits(),
            points: makePoints(),
            options: options,
            filenamePattern: "{date}-{type}"
        )

        XCTAssertEqual(preview.records.count, 1)
        let file = try XCTUnwrap(preview.records.first?.files.first)
        XCTAssertEqual(file.format?.id, ExportFormat.markdown.exportKitFormatID)
        XCTAssertEqual(String(file.relativePath.suffix(7)), "-all.md")
        XCTAssertTrue(file.displayContent().text.contains("# Whereday Complete Export"))
    }

    func testAdapterPlansMultipleSelectedFormats() async throws {
        var options = ExportOptions()
        options.dataKind = .points
        options.format = .json

        let files = try IsoMeExportKitAdapter.plannedFiles(
            visits: makeVisits(),
            points: makePoints(),
            options: options,
            selectedFormats: [.json, .csv, .owntracks],
            filenamePattern: "{type}"
        )

        XCTAssertEqual(files.map { $0.format?.id }, ["json", "csv", "owntracks"])
        XCTAssertEqual(files.map(\.relativePath), ["points.json", "points.csv", "points-owntracks.json"])

        let preview = try await IsoMeExportKitAdapter.preview(
            visits: makeVisits(),
            points: makePoints(),
            options: options,
            selectedFormats: [.json, .csv, .owntracks],
            filenamePattern: "{type}"
        )

        XCTAssertEqual(preview.records.count, 1)
        XCTAssertEqual(preview.records.first?.files.map { $0.format?.id }, ["json", "csv", "owntracks"])
    }

    func testIsoMeExportRunOrchestratorReportsSuccessAndNoDestinationFailure() async throws {
        var options = ExportOptions()
        options.dataKind = .points
        options.format = .json

        let destination = ExportDestination(rootURL: try makeTemporaryDirectory())
        let success = await IsoMeExportKitAdapter.run(
            visits: makeVisits(),
            points: makePoints(),
            options: options,
            filenamePattern: "{type}-{format}",
            destination: destination
        )
        XCTAssertEqual(success.status, .fullSuccess)
        XCTAssertEqual(success.filesWritten, 1)

        let failure = await IsoMeExportKitAdapter.run(
            visits: makeVisits(),
            points: makePoints(),
            options: options,
            filenamePattern: "{type}-{format}",
            destination: nil
        )
        XCTAssertEqual(failure.status, .failure)
        XCTAssertEqual(failure.primaryFailure?.reason, .noDestination)
    }

    func testAutomationScheduleMathKeepsDailyExportBoundaries() throws {
        let calendar = fixedCalendar
        let schedule = AutomationSchedule(
            isEnabled: true,
            frequency: .daily,
            preferredHour: 21,
            preferredMinute: 0,
            lookbackDays: 1,
            timeZoneIdentifier: "UTC"
        )

        let beforePreferredTime = date(year: 2026, month: 4, day: 3, hour: 20, minute: 30)
        let nextBefore = try XCTUnwrap(AutomationScheduleDateMath.calculateNextRunDate(
            schedule: schedule,
            now: beforePreferredTime,
            calendar: calendar
        ))
        XCTAssertEqual(nextBefore, date(year: 2026, month: 4, day: 3, hour: 21, minute: 0))

        let afterPreferredTime = date(year: 2026, month: 4, day: 3, hour: 21, minute: 30)
        let nextAfter = try XCTUnwrap(AutomationScheduleDateMath.calculateNextRunDate(
            schedule: schedule,
            now: afterPreferredTime,
            calendar: calendar
        ))
        XCTAssertEqual(nextAfter, date(year: 2026, month: 4, day: 4, hour: 21, minute: 0))
    }

    func testNonDomainInvoiceSampleProvesExportKitIsGeneric() async throws {
        struct InvoiceRecord: ExportRecord, Equatable {
            let id: String
            let issuedAt: Date
            let customer: String
            let total: Decimal

            var exportRecordID: String { id }
            var exportDate: Date { issuedAt }
        }

        let markdown = ExportFormatDescriptor(
            id: "invoice-markdown",
            displayName: "Invoice Markdown",
            fileExtension: "md",
            contentType: "text/markdown"
        )
        let registry = try ExportRendererRegistry(renderers: [
            AnyExportRenderer<InvoiceRecord>(descriptor: markdown) { invoice, _ in
                RenderedExport(
                    content: "# Invoice \(invoice.id)\nCustomer: \(invoice.customer)\nTotal: \(invoice.total)",
                    contentType: markdown.contentType
                )
            }
        ])
        let invoice = InvoiceRecord(
            id: "INV-001",
            issuedAt: date(year: 2026, month: 4, day: 3, hour: 9),
            customer: "Acme Co",
            total: Decimal(42)
        )
        let rendered = try registry.render(record: invoice, formatID: markdown.id)
        let template = ExportPathTemplate(
            folderTemplate: "Invoices/{year}/{customerSlug}",
            filenameTemplate: "{recordID}",
            fileExtension: markdown.fileExtension
        )
        let relativePath = try template.plannedRelativePath(
            variables: ExportPathVariables(date: invoice.exportDate, values: [
                "customerSlug": "acme-co",
                "recordID": invoice.exportRecordID
            ]),
            safetyPolicy: .rejectTraversalAndAbsolutePaths
        )
        let plannedFile = PlannedExportFile(
            id: "\(invoice.exportRecordID)-\(markdown.id)",
            role: .aggregate(formatID: markdown.id),
            relativePath: relativePath,
            content: rendered.content,
            format: markdown
        )

        let root = try makeTemporaryDirectory()
        let writer = ExportFileWriter(fileSystem: FileManagerExportFileSystem())
        let writeResult = try writer.write(plannedFile, to: ExportDestination(rootURL: root), mode: .overwrite)
        XCTAssertEqual(writeResult.relativePath, "Invoices/2026/acme-co/INV-001.md")
        XCTAssertTrue(try String(contentsOf: writeResult.url, encoding: .utf8).contains("Acme Co"))

        let dataSource = AnyExportRecordDataSource<Date, InvoiceRecord> { _ in
            ExportFetchedRecord(record: invoice)
        }
        let previewRequest = ExportPreviewRequest(
            recordInputs: [invoice.issuedAt],
            selectedFormatIDs: [markdown.id],
            dataSource: dataSource,
            rendererRegistry: registry,
            recordReference: { ExportRecordReference(id: "invoice", date: $0) },
            planAggregateFile: { _, _, rendered in
                PlannedExportFile(
                    id: "invoice-preview",
                    role: .aggregate(formatID: markdown.id),
                    relativePath: "preview.md",
                    content: rendered.content,
                    format: markdown
                )
            }
        )
        let preview = try await ExportPreviewBuilder<Date, InvoiceRecord>().buildPreview(previewRequest)
        XCTAssertEqual(preview.records.first?.files.first?.filename, "preview.md")
    }

    private func makeVisits() -> [Visit] {
        [
            Visit(
                latitude: 37.7749,
                longitude: -122.4194,
                arrivedAt: fixtureDate(hour: 9),
                departedAt: fixtureDate(hour: 10, minute: 30),
                locationName: "Ferry Building",
                address: "1 Ferry Building, San Francisco, CA",
                notes: "Breakfast"
            )
        ]
    }

    private func makePoints() -> [LocationPoint] {
        [
            LocationPoint(
                latitude: 37.7749,
                longitude: -122.4194,
                timestamp: fixtureDate(hour: 9, minute: 5),
                altitude: 4.25,
                speed: 1.2,
                horizontalAccuracy: 5
            ),
            LocationPoint(
                latitude: 37.7752,
                longitude: -122.421,
                timestamp: fixtureDate(hour: 9, minute: 10),
                altitude: nil,
                speed: nil,
                horizontalAccuracy: 8,
                isOutlier: true
            )
        ]
    }

    private func fixtureDate(hour: Int, minute: Int = 0) -> Date {
        Self.date(year: 2026, month: 4, day: 3, hour: hour, minute: minute)
    }

    private var fixedCalendar: Calendar { Self.fixedCalendar }

    private static var fixedCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        return calendar
    }

    private static func date(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) -> Date {
        fixedCalendar.date(from: DateComponents(
            timeZone: TimeZone(secondsFromGMT: 0),
            year: year,
            month: month,
            day: day,
            hour: hour,
            minute: minute
        ))!
    }

    private func date(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) -> Date {
        Self.date(year: year, month: month, day: day, hour: hour, minute: minute)
    }

    private func makeTemporaryDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("IsoMeExportKitTests", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }
}
