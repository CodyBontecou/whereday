import Foundation
import XCTest
#if !GOOGLE_FOLDER_STANDALONE && canImport(IsoMe)
@testable import IsoMe
#endif

/// The same source can be compiled with the real Foundation module in a macOS test harness.
/// No app model, persistence, provider authorization or simulator is exercised here.
final class GoogleFolderDiscoveryTests: XCTestCase {
    private var fixture: URL!
    private var root: URL!
    private let raw = Data("{\"locations\":[{\"latitudeE7\":0,\"longitudeE7\":0,\"timestampMs\":\"0\"}]}".utf8)

    override func setUpWithError() throws {
        fixture = FileManager.default.temporaryDirectory.appendingPathComponent("google-folder-core-\(UUID().uuidString)")
        root = fixture.appendingPathComponent("authorized-root")
        try FileManager.default.createDirectory(at: fixture, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: false, attributes: [.posixPermissions: 0o700])
    }

    override func tearDownWithError() throws {
        if let fixture { try FileManager.default.removeItem(at: fixture) }
    }

    private func file(_ path: String, _ data: Data? = nil, below directory: URL? = nil) throws -> URL {
        let url = (directory ?? root!).appendingPathComponent(path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try (data ?? raw).write(to: url)
        return url
    }

    private func consume(limits: GoogleFolderDiscovery.Limits = .initial) throws -> GoogleFolderDiscovery.Manifest {
        try GoogleFolderDiscovery.withDiscoveredFiles(in: root, limits: limits) { manifest, reader in
            for candidate in manifest.candidates { _ = try reader.read(candidate) }
            return manifest
        }
    }

    private func expectFailure(_ reason: GoogleFolderDiscovery.Failure.Reason,
                               _ operation: () throws -> Void, file: StaticString = #filePath, line: UInt = #line) {
        do { try operation(); XCTFail("Expected \(reason)", file: file, line: line) }
        catch let failure as GoogleFolderDiscovery.Failure { XCTAssertEqual(failure.reason, reason, file: file, line: line) }
        catch { XCTFail("Unexpected error type: \(type(of: error))", file: file, line: line) }
    }

    func testNestedRenamedAndLocalizedFilesUseContentNotExtension() throws {
        try file("履歴/renamed.txt")
        try file("no-extension", Data("{\"timelineObjects\":[]}".utf8))
        try file("Google Photos/localized.JSON", Data("{\"semanticSegments\":[]}".utf8))
        let result = try consume()
        XCTAssertEqual(result.candidates.map(\.relativePath), ["Google Photos/localized.JSON", "no-extension", "履歴/renamed.txt"])
        XCTAssertTrue(result.requiresCodecValidation)
    }

    func testGenericPhotosAndNestedStringDecoysAreNotGoogle() throws {
        try file("real.json")
        try file("photo.json", Data("{\"title\":\"locations timelineObjects semanticSegments\",\"nested\":{\"locations\":[]}}".utf8))
        try file("array.json", Data("[{\"notes\":\"visit startTime endTime\"},{\"nested\":{\"startTime\":\"x\",\"endTime\":\"y\",\"visit\":{}}}]".utf8))
        let result = try consume()
        XCTAssertEqual(result.candidates.map(\.relativePath), ["real.json"])
        XCTAssertEqual(result.exclusionCounts[.nonGoogleJSON], 2)
    }

    func testDeviceRootArrayMayHaveUnrelatedEarlierElements() throws {
        try file("renamed.data", Data("[{\"photo\":1},{\"startTime\":\"x\",\"endTime\":\"y\",\"timelinePath\":[]}]".utf8))
        let result = try consume()
        XCTAssertEqual(result.candidates.map(\.hint), [.deviceRootArray])
    }

    func testEscapedRootKeysAndUTF8BOMAreRecognized() throws {
        var data = Data([0xef, 0xbb, 0xbf])
        data.append(Data("{\"\\u006cocations\":[]}".utf8))
        try file("escaped.json", data)
        XCTAssertEqual(try consume().candidates.map(\.hint), [.googleObject])
    }

    func testUTF8AndEscapedKeysMayCrossProbeChunkBoundaries() throws {
        let opening = "{\"description\":\""
        let padding = String(repeating: "x", count: 4_095 - opening.utf8.count)
        try file("utf8-boundary.json", Data((opening + padding + "é\",\"locations\":[]}").utf8))
        let keyPrefix = "{\"metadata\":\"" + String(repeating: "y", count: 4_076) + "\","
        try file("escaped-boundary.json", Data((keyPrefix + "\"\\u006cocations\":[]}").utf8))
        XCTAssertEqual(try consume().candidates.map(\.relativePath), ["escaped-boundary.json", "utf8-boundary.json"])
    }

    func testBackupPrecedenceInEitherKeyOrderNeverBecomesFolderCandidate() throws {
        try file("real.json")
        try file("backup-first.json", Data("{\"backupFormat\":\"whereday-backup\",\"locations\":[]}".utf8))
        try file("google-first.json", Data("{\"locations\":[],\"backupFormat\":\"whereday-backup\"}".utf8))
        let result = try consume()
        XCTAssertEqual(result.candidates.map(\.relativePath), ["real.json"])
        XCTAssertEqual(result.exclusionCounts[.backup], 2)
    }

    func testKnownMalformedGoogleBlocksBeforeConsume() throws {
        try file("bad.json", Data("{\"locations\":false}".utf8))
        var called = false
        expectFailure(.malformedLikelyGoogle) {
            try GoogleFolderDiscovery.withDiscoveredFiles(in: root) { _, _ in called = true }
        }
        XCTAssertFalse(called)
    }

    func testMalformedLikelyDeviceArrayDoesNotBecomeUnrelated() throws {
        try file("bad.data", Data("[{\"startTime\":\"x\",\"visit\":oops}]".utf8))
        expectFailure(.malformedLikelyGoogle) { _ = try consume() }
    }

    func testInconclusivePhotosSizedJSONNeverSilentlyDropsLateEvidence() throws {
        let prefix = "{\"description\":\"" + String(repeating: "x", count: 70_000) + "\","
        try file("Google Photos/late.json", Data((prefix + "\"locations\":[]}").utf8))
        expectFailure(.limit(.probeFileBytes)) { _ = try consume() }
    }

    func testLaterDeviceEvidenceBeyondProbeLimitIsBlockedNotIgnored() throws {
        let prefix = "[{\"description\":\"" + String(repeating: "x", count: 70_000) + "\"},"
        try file("late-array.json", Data((prefix + "{\"startTime\":\"x\",\"endTime\":\"y\",\"visit\":{}}]").utf8))
        expectFailure(.limit(.probeFileBytes)) { _ = try consume() }
    }

    func testUTF16AndUTF32AreIndeterminateRatherThanUnrelated() throws {
        let path = root.appendingPathComponent("encoded.json")
        for encoding: String.Encoding in [.utf16LittleEndian, .utf16BigEndian, .utf32LittleEndian, .utf32BigEndian] {
            let data = try XCTUnwrap("{\"locations\":[]}".data(using: encoding))
            try data.write(to: path)
            expectFailure(.indeterminate) { _ = try consume() }
        }
    }

    func testBinaryMediaUsesOnlySmallHeaderNotWholeBody() throws {
        try file("real.json")
        var binary = Data([0x89, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13])
        binary.append(Data(repeating: 0, count: 1_000_000))
        try file("photo.png", binary)
        let result = try consume()
        XCTAssertEqual(result.candidates.map(\.relativePath), ["real.json"])
        XCTAssertEqual(result.statistics.probeBytesRead, raw.count + 16)
    }

    func testEmptyAndUnrelatedOnlyFoldersHaveNoReadyResult() throws {
        expectFailure(.noCandidates) { _ = try consume() }
        try file("photo.json", Data("{\"title\":\"synthetic\"}".utf8))
        expectFailure(.noCandidates) { _ = try consume() }
    }

    func testStaticSymlinkAndCycleCannotReadOutsideCanary() throws {
        try file("inside.json")
        let outside = fixture.appendingPathComponent("outside")
        try file("canary.json", Data(("{\"locations\":[]}" + String(repeating: " ", count: 100_000)).utf8), below: outside)
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("escape"), withDestinationURL: outside)
        try FileManager.default.createSymbolicLink(at: root.appendingPathComponent("cycle"), withDestinationURL: root)
        let result = try consume()
        XCTAssertEqual(result.candidates.map(\.relativePath), ["inside.json"])
        XCTAssertEqual(result.exclusionCounts[.symbolicLink], 2)
        XCTAssertEqual(result.statistics.probeBytesRead, raw.count, "Outside body must not enter module probe reads")
    }

    func testRootSymlinkIsRejectedRatherThanResolvedAsAuthority() throws {
        let link = fixture.appendingPathComponent("root-alias")
        try FileManager.default.createSymbolicLink(at: link, withDestinationURL: root)
        expectFailure(.unsafeRoot) { try GoogleFolderDiscovery.withDiscoveredFiles(in: link) { _, _ in XCTFail("Unsafe root admitted") } }
    }

    #if os(macOS)
    func testFinderAliasToOutsideIsSkippedWithoutReadingTargetBody() throws {
        try file("inside.json")
        let outside = try file("outside.json", below: fixture)
        let bookmark = try outside.bookmarkData(options: [.suitableForBookmarkFile], includingResourceValuesForKeys: nil, relativeTo: nil)
        try URL.writeBookmarkData(bookmark, to: root.appendingPathComponent("finder-alias"))
        let result = try consume()
        XCTAssertEqual(result.candidates.map(\.relativePath), ["inside.json"])
        XCTAssertEqual(result.exclusionCounts[.alias], 1)
        XCTAssertEqual(result.statistics.probeBytesRead, raw.count)
    }
    #endif

    func testHiddenAndPackageScopesAreExplicitlyExcluded() throws {
        try file("inside.json")
        try file(".hidden/secret.json")
        try file("Archive.app/Contents/secret.json")
        let result = try consume()
        XCTAssertEqual(result.candidates.map(\.relativePath), ["inside.json"])
        XCTAssertEqual(result.exclusionCounts[.hidden], 1)
        XCTAssertEqual(result.exclusionCounts[.package], 1)
        XCTAssertEqual(result.statistics.probeBytesRead, raw.count)
    }

    func testHardLinkedFileFailsClosed() throws {
        let outside = try file("outside.json", below: fixture)
        try FileManager.default.linkItem(at: outside, to: root.appendingPathComponent("linked.json"))
        expectFailure(.linkedFile) { _ = try consume() }
    }

    func testOrderIsUTF8StableAcrossDifferentCreationOrder() throws {
        let names = ["z.json", "A.json", "é.json", "日本.json"]
        // Independent fixture identity and directory-name truth, captured before EITHER discovery.
        func identity(_ url: URL) throws -> [UInt64] {
            let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
            XCTAssertEqual(attributes[.type] as? FileAttributeType, .typeRegular)
            XCTAssertEqual((attributes[.referenceCount] as? NSNumber)?.intValue, 1)
            return [try XCTUnwrap(attributes[.systemNumber] as? NSNumber).uint64Value,
                    try XCTUnwrap(attributes[.systemFileNumber] as? NSNumber).uint64Value]
        }
        let other = fixture.appendingPathComponent("other-root")
        var createdFirst: [(URL, [UInt64])] = [], createdSecond: [(URL, [UInt64])] = []
        for name in names.reversed() {
            let url = try file(name); createdFirst.append((url, try identity(url)))
        }
        for name in names {
            let url = try file(name, below: other); createdSecond.append((url, try identity(url)))
        }
        func snapshot(_ directory: URL, created: [(URL, [UInt64])]) throws -> [[UInt8]] {
            let entries = try FileManager.default.contentsOfDirectory(atPath: directory.path)
            let bytes = entries.map { Array($0.utf8) }
            let identities = try entries.map { try identity(directory.appendingPathComponent($0)) }
            XCTAssertEqual(entries.count, 4)
            XCTAssertEqual(Set(bytes).count, 4, "No missing/duplicate represented names")
            XCTAssertEqual(Set(identities).count, 4)
            XCTAssertEqual(Set(identities), Set(created.map { $0.1 }), "Exactly the four created files")
            for (url, expectedIdentity) in created {
                XCTAssertEqual(try identity(url), expectedIdentity, "Each requested fixture name still resolves to its created file")
            }
            return bytes
        }
        // Explicit unsigned-byte reference, independent of production helpers/String comparison.
        func byteBefore(_ a: [UInt8], _ b: [UInt8]) -> Bool {
            for i in 0..<min(a.count, b.count) { if a[i] != b[i] { return a[i] < b[i] } }
            return a.count < b.count
        }
        let firstSnapshot = try snapshot(root, created: createdFirst)
        let secondSnapshot = try snapshot(other, created: createdSecond)
        XCTAssertEqual(Set(firstSnapshot), Set(secondSnapshot), "Same represented name-byte set despite creation order")
        let expectedFirst = firstSnapshot.sorted(by: byteBefore)
        let expectedSecond = secondSnapshot.sorted(by: byteBefore)

        let first = try consume().candidates.map { Array($0.relativePath.utf8) }
        let second = try GoogleFolderDiscovery.withDiscoveredFiles(in: other) { manifest, reader in
            for candidate in manifest.candidates { _ = try reader.read(candidate) }
            return manifest.candidates.map { Array($0.relativePath.utf8) }
        }
        XCTAssertEqual(first, expectedFirst)
        XCTAssertEqual(second, expectedSecond)
        XCTAssertEqual(second, first)
        for vector in [first, second] {
            for (a, b) in zip(vector, vector.dropFirst()) { XCTAssertTrue(byteBefore(a, b)) }
        }
    }

    func testEntryProbeAndCandidateLimitsAtBoundAndOneBeyond() throws {
        try file("a.json"); try file("b.json")
        var limits = GoogleFolderDiscovery.Limits.initial
        limits.maximumEntries = 2; limits.maximumProbedFiles = 2; limits.maximumCandidates = 2
        XCTAssertEqual(try consume(limits: limits).candidates.count, 2)
        limits.maximumEntries = 1
        expectFailure(.limit(.entries)) { _ = try consume(limits: limits) }
        limits.maximumEntries = 2; limits.maximumProbedFiles = 1
        expectFailure(.limit(.probedFiles)) { _ = try consume(limits: limits) }
        limits.maximumProbedFiles = 2; limits.maximumCandidates = 1
        expectFailure(.limit(.candidates)) { _ = try consume(limits: limits) }
    }

    func testDirectoryDepthAtBoundAndOneBeyond() throws {
        try file("one/a.json")
        var limits = GoogleFolderDiscovery.Limits.initial; limits.maximumDirectoryDepth = 1
        XCTAssertEqual(try consume(limits: limits).candidates.count, 1)
        try file("one/two/b.json")
        expectFailure(.limit(.directoryDepth)) { _ = try consume(limits: limits) }
    }

    func testJSONDepthAtBoundAndOneBeyond() throws {
        let path = try file("depth.json", Data("{\"locations\":[]}".utf8))
        var limits = GoogleFolderDiscovery.Limits.initial; limits.maximumJSONDepth = 2
        XCTAssertEqual(try consume(limits: limits).candidates.count, 1)
        try Data("{\"locations\":[{}]}".utf8).write(to: path)
        expectFailure(.limit(.jsonDepth)) { _ = try consume(limits: limits) }
    }

    func testPayloadAndTotalProbeBudgetsAtBoundAndOneBeyond() throws {
        try file("a.json"); try file("b.json")
        var limits = GoogleFolderDiscovery.Limits.initial
        limits.maximumPayloadBytes = raw.count * 2; limits.maximumTotalProbeBytes = raw.count * 2
        XCTAssertEqual(try consume(limits: limits).statistics.candidatePayloadBytes, raw.count * 2)
        limits.maximumPayloadBytes -= 1
        expectFailure(.limit(.payloadBytes)) { _ = try consume(limits: limits) }
        limits.maximumPayloadBytes += 1; limits.maximumTotalProbeBytes -= 1
        expectFailure(.limit(.probeTotalBytes)) { _ = try consume(limits: limits) }
    }

    func testPerFileProbeNeedsStructuralEvidenceWithinItsBudget() throws {
        let prefix = "{\"metadata\":\"" + String(repeating: "x", count: 5_000) + "\",\"locations\""
        try file("late.json", Data((prefix + ":[]}").utf8))
        var limits = GoogleFolderDiscovery.Limits.initial; limits.maximumProbeBytesPerFile = prefix.utf8.count
        expectFailure(.limit(.probeFileBytes)) { _ = try consume(limits: limits) }
        limits.maximumProbeBytesPerFile += 1
        XCTAssertEqual(try consume(limits: limits).candidates.count, 1)
    }

    func testDiagnosticsStayBoundedButTotalsDoNotSilentlyLoseExclusions() throws {
        try file("real.json")
        for number in 0..<35 { try file(String(format: "b%02d.bin", number), Data([0x89, 1, 2])) }
        let result = try consume()
        XCTAssertEqual(result.exclusionCounts[.nonJSON], 35)
        XCTAssertEqual(result.diagnostics.count, 32)
        XCTAssertEqual(result.diagnostics.first?.relativePath, "b00.bin")
        XCTAssertEqual(result.diagnostics.last?.relativePath, "b31.bin")
    }

    func testGrowthAndReplacementAfterScanAreRejectedBeforeReading() throws {
        let url = try file("data.json")
        expectFailure(.sourceChanged) {
            try GoogleFolderDiscovery.withDiscoveredFiles(in: root) { manifest, reader in
                try (raw + Data(" ".utf8)).write(to: url)
                _ = try reader.read(manifest.candidates[0])
            }
        }
        try raw.write(to: url)
        expectFailure(.sourceChanged) {
            try GoogleFolderDiscovery.withDiscoveredFiles(in: root) { manifest, reader in
                try FileManager.default.removeItem(at: url)
                try raw.write(to: url, options: [.atomic])
                _ = try reader.read(manifest.candidates[0])
            }
        }
    }

    func testAncestorReplacementAndUnrelatedFileChangesCannotProduceReadyResult() throws {
        try file("nested/real.json")
        let unrelated = try file("photo.json", Data("{}".utf8))
        expectFailure(.sourceChanged) {
            try GoogleFolderDiscovery.withDiscoveredFiles(in: root) { manifest, reader in
                for candidate in manifest.candidates { _ = try reader.read(candidate) }
                try raw.write(to: unrelated)
            }
        }
        try Data("{}".utf8).write(to: unrelated)
        expectFailure(.sourceChanged) {
            try GoogleFolderDiscovery.withDiscoveredFiles(in: root) { manifest, reader in
                try FileManager.default.moveItem(at: root.appendingPathComponent("nested"), to: fixture.appendingPathComponent("old-nested"))
                try file("nested/real.json")
                _ = try reader.read(manifest.candidates[0])
            }
        }
    }

    func testSharedActualPayloadBudgetCatchesGrowthBetweenValidationAndRead() throws {
        let url = try file("data.json")
        var limits = GoogleFolderDiscovery.Limits.initial; limits.maximumPayloadBytes = raw.count
        var consuming = false, checks = 0
        expectFailure(.limit(.payloadBytes)) {
            try GoogleFolderDiscovery.withDiscoveredFiles(in: root, limits: limits, checkCancellation: {
                if consuming {
                    checks += 1
                    if checks == 2 {
                        let writer = try FileHandle(forWritingTo: url)
                        defer { try? writer.close() }
                        try writer.seekToEnd(); try writer.write(contentsOf: Data(" ".utf8))
                    }
                }
            }) { manifest, reader in
                consuming = true
                _ = try reader.read(manifest.candidates[0])
            }
        }
    }

    private enum Cancelled: Error { case synthetic }

    func testCancellationBeforeConsumeAndDuringReadLeavesNoUsableReader() throws {
        try file("data.json")
        var called = false
        XCTAssertThrowsError(try GoogleFolderDiscovery.withDiscoveredFiles(in: root, checkCancellation: { throw Cancelled.synthetic }) { _, _ in called = true }) {
            XCTAssertTrue($0 is Cancelled)
        }
        XCTAssertFalse(called)
        var consuming = false
        var escaped: GoogleFolderDiscovery.CandidateReader?
        var candidate: GoogleFolderDiscovery.Candidate?
        XCTAssertThrowsError(try GoogleFolderDiscovery.withDiscoveredFiles(in: root, checkCancellation: {
            if consuming { throw Cancelled.synthetic }
        }) { manifest, reader in
            escaped = reader; candidate = manifest.candidates[0]; consuming = true
            _ = try reader.read(manifest.candidates[0])
        }) { XCTAssertTrue($0 is Cancelled) }
        let reader = try XCTUnwrap(escaped), item = try XCTUnwrap(candidate)
        expectFailure(.expiredReader) { _ = try reader.read(item) }
    }

    func testEscapedReaderExpiresAfterSuccessAndCallerThrow() throws {
        try file("data.json")
        var escaped: GoogleFolderDiscovery.CandidateReader?
        var item: GoogleFolderDiscovery.Candidate?
        try GoogleFolderDiscovery.withDiscoveredFiles(in: root) { manifest, reader in
            escaped = reader; item = manifest.candidates[0]
            _ = try reader.read(manifest.candidates[0])
            XCTAssertEqual(reader.payloadBytesRead, raw.count)
        }
        expectFailure(.expiredReader) { _ = try XCTUnwrap(escaped).read(XCTUnwrap(item)) }
        XCTAssertThrowsError(try GoogleFolderDiscovery.withDiscoveredFiles(in: root) { manifest, reader in
            escaped = reader; item = manifest.candidates[0]
            throw Cancelled.synthetic
        })
        expectFailure(.expiredReader) { _ = try XCTUnwrap(escaped).read(XCTUnwrap(item)) }
    }

    func testCrossManifestAndRepeatedReadsCannotBeCaughtToManufactureSuccess() throws {
        try file("data.json")
        let foreign = try consume().candidates[0]
        expectFailure(.foreignCandidate) {
            try GoogleFolderDiscovery.withDiscoveredFiles(in: root) { _, reader in
                do { _ = try reader.read(foreign) } catch { /* finish must still reject */ }
            }
        }
        expectFailure(.alreadyConsumed) {
            try GoogleFolderDiscovery.withDiscoveredFiles(in: root) { manifest, reader in
                _ = try reader.read(manifest.candidates[0])
                do { _ = try reader.read(manifest.candidates[0]) } catch { /* sticky failure */ }
            }
        }
    }

    func testOmittedCandidateCannotProducePartialSuccess() throws {
        try file("a.json"); try file("b.json")
        expectFailure(.incompleteConsumption) {
            try GoogleFolderDiscovery.withDiscoveredFiles(in: root) { manifest, reader in
                _ = try reader.read(manifest.candidates[0])
            }
        }
    }

    func testDeletionAfterScanIsAnErrorNotAnEmptyFile() throws {
        let url = try file("data.json")
        expectFailure(.sourceChanged) {
            try GoogleFolderDiscovery.withDiscoveredFiles(in: root) { manifest, reader in
                try FileManager.default.removeItem(at: url)
                _ = try reader.read(manifest.candidates[0])
            }
        }
    }

    func testNonDirectoryMissingAndNonFileURLRootsFailClosed() throws {
        let regular = try file("not-directory.json")
        expectFailure(.unsafeRoot) { try GoogleFolderDiscovery.withDiscoveredFiles(in: regular) { _, _ in XCTFail("File admitted as folder") } }
        expectFailure(.unsafeRoot) { try GoogleFolderDiscovery.withDiscoveredFiles(in: URL(string: "https://synthetic.invalid/")!) { _, _ in XCTFail("Network URL admitted") } }
        expectFailure(.metadataUnavailable) { try GoogleFolderDiscovery.withDiscoveredFiles(in: fixture.appendingPathComponent("missing")) { _, _ in XCTFail("Missing root admitted") } }
    }

    func testBlockedScanRetainsBoundedExclusionProgressWithoutReadyManifest() throws {
        try file(".hidden.json")
        do { _ = try consume(); XCTFail("Excluded-only scope must not produce a manifest") }
        catch let failure as GoogleFolderDiscovery.Failure {
            XCTAssertEqual(failure.reason, .noCandidates)
            XCTAssertEqual(failure.progress?.exclusionCounts[.hidden], 1)
            XCTAssertEqual(failure.progress?.statistics.enumeratedEntries, 1)
        }
    }

    func testCooperativeCancellationDuringProbeCannotAdmitAnEarlierSubset() throws {
        try file("a.json")
        try file("z.json", Data(("{\"metadata\":\"" + String(repeating: "x", count: 70_000) + "\",\"locations\":[]}").utf8))
        var checks = 0, called = false
        XCTAssertThrowsError(try GoogleFolderDiscovery.withDiscoveredFiles(in: root, checkCancellation: {
            checks += 1
            if checks == 12 { throw Cancelled.synthetic }
        }) { _, _ in called = true }) { XCTAssertTrue($0 is Cancelled) }
        XCTAssertFalse(called)
    }

    func testCancellationBetweenPayloadChunksExpiresReaderWithoutReturningPartialData() throws {
        let data = Data(("{\"locations\":[],\"padding\":\"" + String(repeating: "x", count: 100_000) + "\"}").utf8)
        try file("large.json", data)
        var consuming = false, checks = 0
        var escaped: GoogleFolderDiscovery.CandidateReader?
        var item: GoogleFolderDiscovery.Candidate?
        XCTAssertThrowsError(try GoogleFolderDiscovery.withDiscoveredFiles(in: root, checkCancellation: {
            if consuming { checks += 1; if checks == 3 { throw Cancelled.synthetic } }
        }) { manifest, reader in
            consuming = true; escaped = reader; item = manifest.candidates[0]
            _ = try reader.read(manifest.candidates[0])
        }) { XCTAssertTrue($0 is Cancelled) }
        let reader = try XCTUnwrap(escaped), candidate = try XCTUnwrap(item)
        XCTAssertGreaterThan(reader.payloadBytesRead, 0)
        XCTAssertLessThan(reader.payloadBytesRead, data.count)
        expectFailure(.expiredReader) { _ = try reader.read(candidate) }
    }

    func testKnownBackupPrecedesWrongShapedGoogleFieldInEitherOrder() throws {
        try file("real.json")
        let backup = root.appendingPathComponent("backup.data")
        let objects = [
            "{\"backupFormat\":\"whereday-backup\",\"locations\":false}",
            "{\"locations\":false,\"backupFormat\":\"whereday-backup\"}"
        ]
        for (order, object) in objects.enumerated() {
            try Data(object.utf8).write(to: backup)
            do {
                let result = try consume()
                XCTAssertEqual(result.candidates.map(\.relativePath), ["real.json"], "key order \(order)")
                XCTAssertEqual(result.exclusionCounts[.backup], 1, "key order \(order)")
            } catch { XCTFail("Backup must win over wrong-shaped locations, key order \(order): \(error)") }
        }
        // Existing testKnownMalformedGoogleBlocksBeforeConsume preserves the no-backup blocker.
    }

    func testTinyUnicodeHeaderBudgetsNeverExcludeAmbiguousPrefixes() throws {
        // Explicit byte fixtures; no platform encoder or discovery-derived oracle.
        let encodings: [(String, [UInt8])] = [
            ("utf16le-bom", [0xff, 0xfe, 0x7b, 0x00, 0x7d, 0x00]),
            ("utf16be-bom", [0xfe, 0xff, 0x00, 0x7b, 0x00, 0x7d]),
            ("utf32be-bom", [0x00, 0x00, 0xfe, 0xff, 0x00, 0x00, 0x00, 0x7b, 0x00, 0x00, 0x00, 0x7d]),
            ("utf32be-json", [0x00, 0x00, 0x00, 0x7b, 0x00, 0x00, 0x00, 0x7d])
        ]
        let encoded = root.appendingPathComponent("encoded.data")
        for (name, bytes) in encodings {
            for budget in 1...3 {
                try Data(bytes).write(to: encoded)
                var limits = GoogleFolderDiscovery.Limits.initial
                limits.maximumProbeBytesPerFile = budget
                do {
                    _ = try consume(limits: limits)
                    XCTFail("Ambiguous \(name) prefix at budget \(budget) produced success")
                } catch let failure as GoogleFolderDiscovery.Failure {
                    XCTAssertTrue(failure.reason == .indeterminate || failure.reason == .limit(.probeFileBytes),
                                  "Ambiguous \(name) prefix at budget \(budget): \(failure.reason)")
                } catch { XCTFail("Unexpected error for \(name) at budget \(budget): \(error)") }
            }
        }
    }

    func testInvalidAndOverflowSizedLimitsFailBeforeConsume() throws {
        try file("data.json")
        var limits = GoogleFolderDiscovery.Limits.initial
        limits.maximumEntries = Int.max
        expectFailure(.invalidLimits) { _ = try consume(limits: limits) }
        limits = .initial; limits.maximumPayloadBytes = 0
        expectFailure(.invalidLimits) { _ = try consume(limits: limits) }
        limits = .initial; limits.maximumDirectoryDepth = -1
        expectFailure(.invalidLimits) { _ = try consume(limits: limits) }
    }
}

#if GOOGLE_FOLDER_STANDALONE && os(macOS)
/// Compiles ONLY when explicitly admitted as a macOS Foundation/XCTest executable.
/// Uses the actual XCTestCase suite; no alternate implementation or production entry point.
@main
private enum GoogleFolderStandaloneTests {
    static func main() {
        let suite = GoogleFolderDiscoveryTests.defaultTestSuite
        suite.run()
        guard let run = suite.testRun, run.executionCount == 40, run.testCaseCount == 40,
              run.totalFailureCount == 0, run.hasSucceeded else { exit(1) }
    }
}
#endif
