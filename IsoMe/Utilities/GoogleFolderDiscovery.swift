import Foundation

/// Read-only discovery for a caller-authorized, already-local, stable/cooperating root.
/// The caller holds authorization through consume. No FileProvider materialization, scope acquisition,
/// record decoding or persistence occurs here. Metadata rechecks are NOT hostile same-UID race protection.
enum GoogleFolderDiscovery {
    enum Bound: String, Equatable {
        case entries, probedFiles, candidates, directoryDepth, probeFileBytes, probeTotalBytes, payloadBytes, jsonDepth
    }

    struct Failure: LocalizedError {
        enum Reason: Equatable {
            case invalidLimits, unsafeRoot, metadataUnavailable, enumerationFailed, sourceChanged
            case indeterminate, malformedLikelyGoogle, noCandidates, linkedFile, readFailed
            case expiredReader, foreignCandidate, alreadyConsumed, incompleteConsumption
            case limit(Bound)
        }
        let reason: Reason
        var errorDescription: String? {
            switch reason {
            case .noCandidates:
                return String(localized: "No supported Google Timeline JSON files were found in this folder.")
            case .limit:
                return String(localized: "This folder exceeds the safe scan or import limits. Choose a smaller folder; nothing was saved.")
            case .unsafeRoot, .metadataUnavailable, .enumerationFailed, .readFailed:
                return String(localized: "This folder could not be read locally. Make it available offline in Files, then choose it again. Nothing was saved.")
            case .malformedLikelyGoogle, .indeterminate:
                return String(localized: "A possible Google export could not be validated. Check the files before trying again; nothing was saved.")
            case .sourceChanged, .linkedFile:
                return String(localized: "Some files changed or use unsafe links. Choose stable local copies and try again; nothing was saved.")
            default:
                return String(localized: "This folder could not be safely reviewed. Choose the folder again; nothing was saved.")
            }
        }
        /// Relative only; this module does not log paths or source content.
        let relativePath: String?
        /// Accounted work only, never a complete manifest or a ready partial subset.
        let progress: Progress?
        init(_ reason: Reason, _ relativePath: String? = nil, progress: Progress? = nil) {
            self.reason = reason
            self.relativePath = relativePath
            self.progress = progress
        }
    }

    struct Limits {
        var maximumEntries = 10_000
        var maximumProbedFiles = 1_024
        var maximumCandidates = 256
        var maximumDirectoryDepth = 16
        var maximumProbeBytesPerFile = 64 * 1_024
        var maximumTotalProbeBytes = 8 * 1_024 * 1_024
        var maximumPayloadBytes = 50 * 1_024 * 1_024
        var maximumJSONDepth = 64
        var maximumDiagnostics = 32
        static let initial = Limits()

        fileprivate func validate() throws {
            let ceiling = Self.initial
            let positive = [(maximumEntries, ceiling.maximumEntries), (maximumProbedFiles, ceiling.maximumProbedFiles),
                            (maximumCandidates, ceiling.maximumCandidates), (maximumProbeBytesPerFile, ceiling.maximumProbeBytesPerFile),
                            (maximumTotalProbeBytes, ceiling.maximumTotalProbeBytes), (maximumPayloadBytes, ceiling.maximumPayloadBytes),
                            (maximumJSONDepth, ceiling.maximumJSONDepth)]
            guard positive.allSatisfy({ $0.0 > 0 && $0.0 <= $0.1 }),
                  (0...ceiling.maximumDirectoryDepth).contains(maximumDirectoryDepth),
                  (0...ceiling.maximumDiagnostics).contains(maximumDiagnostics) else { throw Failure(.invalidLimits) }
        }
    }

    enum Hint: Equatable { case googleObject, deviceRootArray }
    enum Exclusion: String, Hashable {
        case symbolicLink, alias, hidden, package, specialFile, nonJSON, nonGoogleJSON, backup
    }
    struct Diagnostic: Equatable {
        let kind: Exclusion
        let relativePath: String
    }
    struct Statistics {
        var enumeratedEntries = 0
        var probedFiles = 0
        var probeBytesRead = 0
        var candidatePayloadBytes = 0
    }
    struct Progress {
        let statistics: Statistics
        let diagnostics: [Diagnostic]
        let exclusionCounts: [Exclusion: Int]
    }
    struct Candidate: Equatable {
        fileprivate let session: UUID
        fileprivate let id: UUID
        let relativePath: String
        let hint: Hint
        let byteCount: Int
    }
    struct Manifest {
        let candidates: [Candidate]
        let statistics: Statistics
        let diagnostics: [Diagnostic]
        let exclusionCounts: [Exclusion: Int]
        /// Hints do not establish record validity or precedence of unseen later JSON keys.
        var requiresCodecValidation: Bool { true }
    }

    static func withDiscoveredFiles<R>(
        in authorizedLocalRoot: URL,
        limits: Limits = .initial,
        checkCancellation: @escaping () throws -> Void = { try Task.checkCancellation() },
        consume: (Manifest, CandidateReader) throws -> R
    ) throws -> R {
        try limits.validate()
        try checkCancellation()
        guard authorizedLocalRoot.isFileURL else { throw Failure(.unsafeRoot) }
        let supplied = authorizedLocalRoot.standardizedFileURL
        let original = try inspect(supplied, relative: "")
        guard original.type == .typeDirectory, !original.alias, !original.hidden, !original.package else { throw Failure(.unsafeRoot) }
        let root = supplied.resolvingSymlinksInPath().standardizedFileURL
        let rootNode = try inspect(root, relative: "")
        guard rootNode == original else { throw Failure(.sourceChanged) }
        var resourceURL = root
        resourceURL.removeAllCachedResourceValues()
        guard (try? resourceURL.resourceValues(forKeys: [.volumeIsLocalKey]).volumeIsLocal) == true else {
            throw Failure(.unsafeRoot)
        }
        let state = ScanState(limits: limits, checkCancellation: checkCancellation)
        do { return try discover(root: root, rootNode: rootNode, state: state, consume: consume) }
        catch let failure as Failure {
            let progress = Progress(statistics: state.statistics, diagnostics: state.diagnostics, exclusionCounts: state.exclusionCounts)
            throw Failure(failure.reason, failure.relativePath, progress: progress)
        }
    }

    private static func discover<R>(root: URL, rootNode: Node, state: ScanState,
                                   consume: (Manifest, CandidateReader) throws -> R) throws -> R {
        let limits = state.limits
        let checkCancellation = state.checkCancellation
        var directories = ["": rootNode]
        var files: [Node] = []
        var seenDirectories: Set<Identity> = [rootNode.identity]
        var enumerationFailed = false
        guard let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil, options: [], errorHandler: { _, _ in
            enumerationFailed = true
            return false
        }) else { throw Failure(.enumerationFailed) }

        while let url = enumerator.nextObject() as? URL {
            try checkCancellation()
            if enumerationFailed { throw Failure(.enumerationFailed) }
            try charge(&state.statistics.enumeratedEntries, 1, limit: limits.maximumEntries, bound: .entries)
            let path = try relativePath(url, under: root)
            try validateAncestors(of: path, root: root, directories: directories)
            let node = try inspect(url, relative: path)
            if node.type == .typeSymbolicLink {
                enumerator.skipDescendants(); state.exclude(.symbolicLink, path); continue
            }
            if node.alias { enumerator.skipDescendants(); state.exclude(.alias, path); continue }
            if node.hidden { enumerator.skipDescendants(); state.exclude(.hidden, path); continue }
            if node.package { enumerator.skipDescendants(); state.exclude(.package, path); continue }
            if node.type == .typeDirectory {
                guard path.split(separator: "/").count <= limits.maximumDirectoryDepth else { throw Failure(.limit(.directoryDepth), path) }
                guard seenDirectories.insert(node.identity).inserted else { throw Failure(.sourceChanged, path) }
                directories[path] = node
            } else if node.type == .typeRegular {
                guard node.links == 1 else { throw Failure(.linkedFile, path) }
                guard files.count < limits.maximumProbedFiles else { throw Failure(.limit(.probedFiles), path) }
                files.append(node)
            } else { state.exclude(.specialFile, path) }
        }
        if enumerationFailed { throw Failure(.enumerationFailed) }
        files.sort { ordered($0.relativePath, $1.relativePath) }
        let session = UUID()
        var candidates: [Candidate] = []
        var candidateNodes: [UUID: Node] = [:]
        for node in files {
            try checkCancellation()
            try validateAncestors(of: node.relativePath, root: root, directories: directories)
            let url = root.appendingPathComponent(node.relativePath)
            try verify(node, at: url)
            try charge(&state.statistics.probedFiles, 1, limit: limits.maximumProbedFiles, bound: .probedFiles)
            let outcome = try probe(node, at: url, state: state)
            try verify(node, at: url)
            switch outcome {
            case .excluded(let kind): state.exclude(kind, node.relativePath)
            case .candidate(let hint):
                guard candidates.count < limits.maximumCandidates else { throw Failure(.limit(.candidates), node.relativePath) }
                try charge(&state.statistics.candidatePayloadBytes, node.size, limit: limits.maximumPayloadBytes, bound: .payloadBytes)
                let id = UUID()
                candidates.append(Candidate(session: session, id: id, relativePath: node.relativePath, hint: hint, byteCount: node.size))
                candidateNodes[id] = node
            }
        }
        guard !candidates.isEmpty else { throw Failure(.noCandidates) }
        let manifest = Manifest(candidates: candidates, statistics: state.statistics,
                                diagnostics: state.diagnostics, exclusionCounts: state.exclusionCounts)
        let reader = CandidateReader(root: root, directories: directories, files: files, candidates: candidateNodes, session: session, state: state)
        defer { reader.invalidate() }
        try checkCancellation()
        let result = try consume(manifest, reader)
        try reader.finish()
        return result
    }

    /// A synchronous, non-Sendable borrowed read capability, invalidated when consume exits.
    final class CandidateReader {
        private let root: URL
        private let directories: [String: Node]
        private let files: [Node]
        private let candidates: [UUID: Node]
        private let session: UUID
        private let state: ScanState
        private var active = true
        private var consumed: Set<UUID> = []
        private var failed: Error?
        private(set) var payloadBytesRead = 0

        fileprivate init(root: URL, directories: [String: Node], files: [Node], candidates: [UUID: Node], session: UUID, state: ScanState) {
            self.root = root; self.directories = directories; self.files = files
            self.candidates = candidates; self.session = session; self.state = state
        }
        fileprivate func invalidate() { active = false }

        func read(_ candidate: Candidate) throws -> Data {
            guard active else { throw Failure(.expiredReader) }
            if let failed { throw failed }
            do {
                guard candidate.session == session, let node = candidates[candidate.id] else { throw Failure(.foreignCandidate) }
                guard !consumed.contains(candidate.id) else { throw Failure(.alreadyConsumed, node.relativePath) }
                try state.checkCancellation()
                try validateAncestors(of: node.relativePath, root: root, directories: directories)
                let url = root.appendingPathComponent(node.relativePath)
                try verify(node, at: url)
                let handle = try open(url, relative: node.relativePath)
                defer { try? handle.close() }
                var data = Data()
                while true {
                    try state.checkCancellation()
                    let remaining = state.limits.maximumPayloadBytes - payloadBytesRead
                    let count = min(32 * 1_024, remaining + 1) // Max50MiB, so this detection byte cannot overflow.
                    let chunk = try readChunk(handle, count: count, relative: node.relativePath)
                    if chunk.isEmpty { break }
                    try charge(&payloadBytesRead, chunk.count, limit: state.limits.maximumPayloadBytes, bound: .payloadBytes)
                    data.append(chunk)
                }
                guard data.count == node.size else { throw Failure(.sourceChanged, node.relativePath) }
                try verify(node, at: url)
                try validateAncestors(of: node.relativePath, root: root, directories: directories)
                consumed.insert(candidate.id)
                return data
            } catch { failed = error; throw error }
        }

        fileprivate func finish() throws {
            if let failed { throw failed }
            try state.checkCancellation()
            guard consumed.count == candidates.count else { throw Failure(.incompleteConsumption) }
            // Also recheck excluded regular files: one must not change from unrelated to Google unnoticed.
            for node in files {
                try state.checkCancellation()
                try validateAncestors(of: node.relativePath, root: root, directories: directories)
                try verify(node, at: root.appendingPathComponent(node.relativePath))
            }
            for path in directories.keys.sorted(by: ordered) {
                try state.checkCancellation()
                guard let expected = directories[path] else { throw Failure(.sourceChanged) }
                try verify(expected, at: path.isEmpty ? root : root.appendingPathComponent(path))
            }
        }
    }

    fileprivate struct Identity: Hashable { let device: UInt64; let inode: UInt64 }
    fileprivate struct Node: Equatable {
        let relativePath: String
        let identity: Identity
        let type: FileAttributeType
        let size: Int
        let modified: Date
        let links: UInt64
        let alias: Bool
        let hidden: Bool
        let package: Bool
    }
    fileprivate final class ScanState {
        let limits: Limits
        let checkCancellation: () throws -> Void
        var statistics = Statistics()
        var diagnostics: [Diagnostic] = []
        var exclusionCounts: [Exclusion: Int] = [:]
        init(limits: Limits, checkCancellation: @escaping () throws -> Void) {
            self.limits = limits; self.checkCancellation = checkCancellation
        }
        func exclude(_ kind: Exclusion, _ path: String) {
            exclusionCounts[kind, default: 0] += 1 // <=10,000 entries, validated ceiling.
            diagnostics.append(Diagnostic(kind: kind, relativePath: path))
            diagnostics.sort {
                $0.relativePath == $1.relativePath ? ordered($0.kind.rawValue, $1.kind.rawValue) : ordered($0.relativePath, $1.relativePath)
            }
            if diagnostics.count > limits.maximumDiagnostics { diagnostics.removeLast() }
        }
    }

    fileprivate static func ordered(_ a: String, _ b: String) -> Bool { a.utf8.lexicographicallyPrecedes(b.utf8) }
    fileprivate static func charge(_ value: inout Int, _ amount: Int, limit: Int, bound: Bound) throws {
        let next = value.addingReportingOverflow(amount)
        guard amount >= 0, !next.overflow, next.partialValue <= limit else { throw Failure(.limit(bound)) }
        value = next.partialValue
    }
    fileprivate static func relativePath(_ url: URL, under root: URL) throws -> String {
        let prefix = root.pathComponents, components = url.standardizedFileURL.pathComponents
        guard components.count > prefix.count, Array(components.prefix(prefix.count)) == prefix else { throw Failure(.sourceChanged) }
        let tail = components.dropFirst(prefix.count)
        guard tail.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }) else { throw Failure(.sourceChanged) }
        let path = tail.joined(separator: "/")
        guard path.utf8.count <= 4_096 else { throw Failure(.metadataUnavailable) }
        return path
    }
    fileprivate static func inspect(_ url: URL, relative: String) throws -> Node {
        let attributes: [FileAttributeKey: Any]
        do { attributes = try FileManager.default.attributesOfItem(atPath: url.path) }
        catch { throw Failure(.metadataUnavailable, relative) }
        guard let type = attributes[.type] as? FileAttributeType,
              let device = attributes[.systemNumber] as? NSNumber,
              let inode = attributes[.systemFileNumber] as? NSNumber,
              let size = attributes[.size] as? NSNumber, size.uint64Value <= UInt64(Int.max),
              let modified = attributes[.modificationDate] as? Date,
              let links = attributes[.referenceCount] as? NSNumber else { throw Failure(.metadataUnavailable, relative) }
        var alias = false, hidden = false, package = false
        if type == .typeRegular || type == .typeDirectory {
            var fresh = url
            fresh.removeAllCachedResourceValues()
            let values: URLResourceValues
            do { values = try fresh.resourceValues(forKeys: [.isAliasFileKey, .isHiddenKey, .isPackageKey]) }
            catch { throw Failure(.metadataUnavailable, relative) }
            guard let a = values.isAliasFile, let h = values.isHidden, let p = values.isPackage else { throw Failure(.metadataUnavailable, relative) }
            alias = a; hidden = h || url.lastPathComponent.hasPrefix("."); package = p
        }
        return Node(relativePath: relative, identity: Identity(device: device.uint64Value, inode: inode.uint64Value), type: type,
                    size: Int(size.uint64Value), modified: modified, links: links.uint64Value, alias: alias, hidden: hidden, package: package)
    }
    fileprivate static func verify(_ expected: Node, at url: URL) throws {
        let actual: Node
        do { actual = try inspect(url, relative: expected.relativePath) }
        catch { throw Failure(.sourceChanged, expected.relativePath) }
        guard actual == expected else { throw Failure(.sourceChanged, expected.relativePath) }
    }
    fileprivate static func validateAncestors(of path: String, root: URL, directories: [String: Node]) throws {
        guard let rootNode = directories[""] else { throw Failure(.sourceChanged) }
        try verify(rootNode, at: root)
        var prefix = ""
        for part in path.split(separator: "/").dropLast() {
            prefix = prefix.isEmpty ? String(part) : prefix + "/" + String(part)
            guard let node = directories[prefix], node.type == .typeDirectory else { throw Failure(.sourceChanged, path) }
            try verify(node, at: root.appendingPathComponent(prefix))
        }
    }
    fileprivate static func open(_ url: URL, relative: String) throws -> FileHandle {
        do { return try FileHandle(forReadingFrom: url) }
        catch { throw Failure(.readFailed, relative) }
    }
    fileprivate static func readChunk(_ handle: FileHandle, count: Int, relative: String) throws -> Data {
        do { return try handle.read(upToCount: count) ?? Data() }
        catch { throw Failure(.readFailed, relative) }
    }

    fileprivate enum ProbeOutcome { case candidate(Hint), excluded(Exclusion) }
    fileprivate static func probe(_ node: Node, at url: URL, state: ScanState) throws -> ProbeOutcome {
        if node.size == 0 { return .excluded(.nonJSON) }
        let handle = try open(url, relative: node.relativePath)
        defer { try? handle.close() }
        var buffer = Data()
        func append(upTo target: Int) throws {
            while buffer.count < target {
                try state.checkCancellation()
                let totalRemaining = state.limits.maximumTotalProbeBytes - state.statistics.probeBytesRead
                guard totalRemaining > 0 else { throw Failure(.limit(.probeTotalBytes), node.relativePath) }
                let chunk = try readChunk(handle, count: min(4_096, target - buffer.count, totalRemaining), relative: node.relativePath)
                guard !chunk.isEmpty else { throw Failure(.sourceChanged, node.relativePath) }
                try charge(&state.statistics.probeBytesRead, chunk.count, limit: state.limits.maximumTotalProbeBytes, bound: .probeTotalBytes)
                buffer.append(chunk)
            }
        }
        try append(upTo: min(16, node.size, state.limits.maximumProbeBytesPerFile))
        let header = [UInt8](buffer)
        let jsonLead: (UInt8) -> Bool = { $0 == 123 || $0 == 91 || EnvelopeProbe.whitespace($0) }
        let possibleUTF16 = header.count >= 2 && ((header[0] == 0 && jsonLead(header[1])) || (jsonLead(header[0]) && header[1] == 0))
        let possibleUTF32 = header.count >= 4 && header[0] == 0 && header[1] == 0 && header[2] == 0 && jsonLead(header[3])
        let byteOrderMarks: [[UInt8]] = [[0xff, 0xfe], [0xfe, 0xff], [0, 0, 0xfe, 0xff]]
        let partialBOM = byteOrderMarks.contains { header.count < $0.count && $0.starts(with: header) }
        let partialUTF32BE = header.count < 4 && header.allSatisfy({ $0 == 0 })
        if partialBOM || partialUTF32BE || header.starts(with: [0xff, 0xfe]) || header.starts(with: [0xfe, 0xff]) || header.starts(with: [0, 0, 0xfe, 0xff])
            || possibleUTF16 || possibleUTF32 { throw Failure(.indeterminate, node.relativePath) }
        let offset = header.starts(with: [0xef, 0xbb, 0xbf]) ? 3 : 0
        if let first = header.dropFirst(offset).first(where: { !EnvelopeProbe.whitespace($0) }), first != 123 && first != 91 {
            // A partial Unicode BOM is not confidently binary or a supported root.
            if first == 0xef { throw Failure(.indeterminate, node.relativePath) }
            return .excluded(.nonJSON)
        }
        // Small objects are inspected completely, so known backup precedence is retained in either key order.
        try append(upTo: min(4_096, node.size, state.limits.maximumProbeBytesPerFile))
        while true {
            try state.checkCancellation()
            var syntax = EnvelopeProbe(bytes: [UInt8](buffer), maximumDepth: state.limits.maximumJSONDepth)
            if let result = try syntax.classify(endOfFile: buffer.count == node.size, relative: node.relativePath) { return result }
            guard buffer.count < state.limits.maximumProbeBytesPerFile else { throw Failure(.limit(.probeFileBytes), node.relativePath) }
            try append(upTo: min(buffer.count + 4_096, node.size, state.limits.maximumProbeBytesPerFile))
        }
    }

    /// Structural prefix reader only. It does not decode location records or establish source authenticity.
    fileprivate struct EnvelopeProbe {
        enum Syntax: Error { case incomplete, invalid, depth }
        enum Role: Equatable { case rootObject, rootArrayElement, other }
        enum Kind { case object, array, scalar }
        let bytes: [UInt8]
        let maximumDepth: Int
        var index = 0
        var googleObject = false
        var invalidGoogleShape = false
        var deviceArray = false
        var backup = false
        static func whitespace(_ byte: UInt8) -> Bool { byte == 32 || byte == 9 || byte == 10 || byte == 13 }
        mutating func space() { while index < bytes.count && Self.whitespace(bytes[index]) { index += 1 } }
        mutating func peek() throws -> UInt8 { space(); guard index < bytes.count else { throw Syntax.incomplete }; return bytes[index] }
        mutating func take(_ expected: UInt8) throws {
            guard try peek() == expected else { throw Syntax.invalid }; index += 1
        }
        mutating func classify(endOfFile: Bool, relative: String) throws -> ProbeOutcome? {
            if bytes.starts(with: [0xef, 0xbb, 0xbf]) { index = 3 }
            do {
                let first = try peek()
                if first == 123 { try object(role: .rootObject, depth: 1) }
                else if first == 91 { try array(root: true, depth: 1) }
                else { return .excluded(.nonJSON) }
                space()
                if index != bytes.count { throw Syntax.invalid }
                if invalidGoogleShape && !backup { throw Syntax.invalid }
                return result() ?? .excluded(.nonGoogleJSON)
            } catch Syntax.incomplete {
                if backup { return .excluded(.backup) }
                if endOfFile { throw Failure(googleObject || deviceArray ? .malformedLikelyGoogle : .indeterminate, relative) }
                // Keep inspecting structurally within existing bounds for a later root backup marker.
                // A wrong-shaped Google field alone must never become a candidate prefix.
                return invalidGoogleShape ? nil : result()
            } catch Syntax.depth { throw Failure(.limit(.jsonDepth), relative) }
            catch {
                if backup { return .excluded(.backup) }
                throw Failure(googleObject || deviceArray ? .malformedLikelyGoogle : .indeterminate, relative)
            }
        }
        func result() -> ProbeOutcome? {
            if backup { return .excluded(.backup) }
            if googleObject { return .candidate(.googleObject) }
            if deviceArray { return .candidate(.deviceRootArray) }
            return nil
        }
        mutating func object(role: Role, depth: Int) throws {
            guard depth <= maximumDepth else { throw Syntax.depth }
            try take(123)
            if try peek() == 125 { index += 1; return }
            var start = false, end = false, body = false
            while true {
                let key = try string()
                try take(58)
                if role == .rootObject {
                    if key == "backupFormat" { backup = true }
                    if ["locations", "timelineObjects", "semanticSegments"].contains(key) {
                        googleObject = true
                        if try peek() != 91 { invalidGoogleShape = true }
                    }
                } else if role == .rootArrayElement {
                    start = start || key == "startTime"; end = end || key == "endTime"
                    body = body || ["visit", "activity", "timelinePath"].contains(key)
                    if body && (start || end) { deviceArray = true }
                }
                _ = try value(role: .other, depth: depth + 1)
                let separator = try peek(); index += 1
                if separator == 125 { return }
                guard separator == 44 else { throw Syntax.invalid }
            }
        }
        mutating func array(root: Bool, depth: Int) throws {
            guard depth <= maximumDepth else { throw Syntax.depth }
            try take(91)
            if try peek() == 93 { index += 1; return }
            while true {
                _ = try value(role: root ? .rootArrayElement : .other, depth: depth + 1)
                let separator = try peek(); index += 1
                if separator == 93 { return }
                guard separator == 44 else { throw Syntax.invalid }
            }
        }
        mutating func value(role: Role, depth: Int) throws -> Kind {
            guard depth <= maximumDepth else { throw Syntax.depth }
            switch try peek() {
            case 123: try object(role: role, depth: depth); return .object
            case 91: try array(root: false, depth: depth); return .array
            case 34: _ = try string(); return .scalar
            case 116: try literal(Array("true".utf8)); return .scalar
            case 102: try literal(Array("false".utf8)); return .scalar
            case 110: try literal(Array("null".utf8)); return .scalar
            default: try number(); return .scalar
            }
        }
        mutating func string() throws -> String {
            try take(34)
            let beginning = index - 1
            while index < bytes.count {
                let byte = bytes[index]; index += 1
                if byte == 34 {
                    do {
                        guard let text = try JSONSerialization.jsonObject(with: Data(bytes[beginning..<index]), options: [.fragmentsAllowed]) as? String else { throw Syntax.invalid }
                        return text
                    } catch { throw Syntax.invalid }
                }
                if byte < 32 { throw Syntax.invalid }
                if byte == 92 {
                    guard index < bytes.count else { throw Syntax.incomplete }
                    let escaped = bytes[index]; index += 1
                    if escaped == 117 {
                        for _ in 0..<4 {
                            guard index < bytes.count else { throw Syntax.incomplete }
                            let digit = bytes[index]; index += 1
                            guard (48...57).contains(digit) || (65...70).contains(digit) || (97...102).contains(digit) else { throw Syntax.invalid }
                        }
                    } else if ![34, 92, 47, 98, 102, 110, 114, 116].contains(escaped) { throw Syntax.invalid }
                }
            }
            throw Syntax.incomplete
        }
        mutating func literal(_ word: [UInt8]) throws {
            for byte in word {
                guard index < bytes.count else { throw Syntax.incomplete }
                guard bytes[index] == byte else { throw Syntax.invalid }; index += 1
            }
        }
        mutating func number() throws {
            if try peek() == 45 { index += 1 }
            guard index < bytes.count else { throw Syntax.incomplete }
            if bytes[index] == 48 { index += 1 }
            else {
                guard (49...57).contains(bytes[index]) else { throw Syntax.invalid }
                while index < bytes.count && (48...57).contains(bytes[index]) { index += 1 }
            }
            if index < bytes.count && bytes[index] == 46 {
                index += 1; try digits()
            }
            if index < bytes.count && (bytes[index] == 101 || bytes[index] == 69) {
                index += 1
                if index < bytes.count && (bytes[index] == 43 || bytes[index] == 45) { index += 1 }
                try digits()
            }
        }
        mutating func digits() throws {
            guard index < bytes.count else { throw Syntax.incomplete }
            guard (48...57).contains(bytes[index]) else { throw Syntax.invalid }
            while index < bytes.count && (48...57).contains(bytes[index]) { index += 1 }
        }
    }
}
