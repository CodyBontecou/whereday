import SwiftUI
import Photos
import CoreLocation

typealias PhotoThumbnailLoader = @MainActor (String, CGSize, PHImageContentMode) async -> UIImage?

@MainActor
private func loadPhotoThumbnail(_ identifier: String, _ size: CGSize, _ mode: PHImageContentMode) async -> UIImage? {
    await PhotoLibraryManager.shared.thumbnail(for: identifier, targetSize: size, contentMode: mode)
}

/// Shared by map, place-directory and nested browser presentations. Invalidating
/// this object clears the entire presentation chain, not just its first sheet.
@MainActor
@Observable
final class PhotoMomentDetailSelection {
    var photo: PhotoMoment?
    var cluster: PhotoMomentCluster?
    var showsPlaces = false
    var place: PhotoMomentCluster?
    var browserPhoto: PhotoMoment?

    func dismissAll() {
        photo = nil
        cluster = nil
        showsPlaces = false
        place = nil
        browserPhoto = nil
    }
}

/// Dots override images without changing the user's existing image/pin preference.
enum PhotoMapMarkerStyle: Equatable {
    static let imagesKey = "showPhotoMarkerImages"
    static let dotsKey = "showPhotoMarkerDots"

    case images, pins, dots

    static func resolve(showsImages: Bool, showsDots: Bool) -> Self {
        showsDots ? .dots : (showsImages ? .images : .pins)
    }
}

/// Resolve overlaps by distance to the visible dot, rather than annotation order.
/// Centers come from MapProxy at touch time, so zoom, rotation and pitch apply.
enum PhotoMapDotHitTesting {
    static let targetSize: CGFloat = 44

    static func mapPoint(localPoint: CGPoint, markerCenter: CGPoint) -> CGPoint {
        CGPoint(x: markerCenter.x + localPoint.x - targetSize / 2,
                y: markerCenter.y + localPoint.y - targetSize / 2)
    }

    static func targetIndex(at point: CGPoint, centers: [CGPoint?]) -> Int? {
        var winner: Int?
        var nearest = CGFloat.infinity
        for (index, center) in centers.enumerated() {
            guard let center else { continue }
            let dx = point.x - center.x
            let dy = point.y - center.y
            guard abs(dx) <= targetSize / 2, abs(dy) <= targetSize / 2 else { continue }
            let distance = dx * dx + dy * dy
            if distance < nearest {
                winner = index
                nearest = distance
            }
        }
        // Exact ties keep the first stable cluster. Accessibility activation
        // still acts on the focused marker, including coincident locations.
        return winner
    }
}

struct PhotoMapDot: View {
    let isSelected: Bool
    let isCluster: Bool

    var body: some View {
        Circle()
            .fill(TE.accent)
            .frame(width: isCluster ? 14 : 10, height: isCluster ? 14 : 10)
            .overlay {
                Circle().strokeBorder(.white, lineWidth: isSelected ? 3 : 2)
            }
            .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
            // Small visual, full rectangular touch target. No image view or task.
            .frame(width: PhotoMapDotHitTesting.targetSize, height: PhotoMapDotHitTesting.targetSize)
            .contentShape(Rectangle())
            .accessibilityHidden(true)
    }
}

struct PhotoThumbnailView: View {
    @Environment(\.displayScale) private var displayScale

    let assetLocalIdentifier: String
    let targetPointSize: CGSize
    var cornerRadius: CGFloat = 4
    var contentMode: ContentMode = .fill
    var loader: PhotoThumbnailLoader = loadPhotoThumbnail

    @State private var image: UIImage?
    @State private var hasAttemptedLoad = false

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
                    .frame(width: targetPointSize.width, height: targetPointSize.height)
                    .clipped()
            } else {
                RoundedRectangle(cornerRadius: cornerRadius)
                    .fill(TE.textMuted.opacity(0.12))
                    .overlay {
                        Image(systemName: hasAttemptedLoad ? "photo.badge.exclamationmark" : "photo")
                            .font(.title3.weight(.medium))
                            .foregroundStyle(TE.textMuted)
                    }
            }
        }
        .frame(width: targetPointSize.width, height: targetPointSize.height)
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        .task(id: taskID) {
            await loadThumbnail()
        }
    }

    private var taskID: String {
        let mode = contentMode == .fit ? "fit" : "fill"
        return "\(assetLocalIdentifier)-\(Int(targetPointSize.width))-\(Int(targetPointSize.height))-\(Int(displayScale * 100))-\(mode)"
    }

    private var photoKitContentMode: PHImageContentMode {
        contentMode == .fit ? .aspectFit : .aspectFill
    }

    private func loadThumbnail() async {
        guard !Task.isCancelled else { return }
        hasAttemptedLoad = false
        let pixelSize = CGSize(
            width: max(1, targetPointSize.width * displayScale),
            height: max(1, targetPointSize.height * displayScale)
        )
        let loaded = await loader(assetLocalIdentifier, pixelSize, photoKitContentMode)
        guard !Task.isCancelled else { return }
        image = loaded
        hasAttemptedLoad = true
    }
}

struct PhotoMomentCluster: Identifiable {
    let id: String
    let coordinate: CLLocationCoordinate2D
    let photos: [PhotoMoment]
    let isArea: Bool
    /// Area annotations retain whole place groups; they never repartition photos.
    let places: [PhotoMomentCluster]

    init(id: String, coordinate: CLLocationCoordinate2D, photos: [PhotoMoment], isArea: Bool = false, places: [PhotoMomentCluster] = []) {
        self.id = id
        self.coordinate = coordinate
        self.photos = PhotoMomentClusterBuilder.sorted(photos)
        self.isArea = isArea
        self.places = places
    }

    var count: Int { photos.count }
    var onlyPhoto: PhotoMoment? { photos.count == 1 ? photos[0] : nil }
    var representativePhoto: PhotoMoment? { photos.last }

    var sortedPhotos: [PhotoMoment] {
        photos
    }

    var coordinateText: String {
        String(format: "%.5f, %.5f", coordinate.latitude, coordinate.longitude)
    }

    var timeRangeText: String {
        let sorted = sortedPhotos
        guard let first = sorted.first, let last = sorted.last else { return "" }
        if Calendar.current.isDate(first.takenAt, equalTo: last.takenAt, toGranularity: .minute) {
            return first.takenAt.formatted(date: .abbreviated, time: .shortened)
        }
        return "\(first.takenAt.formatted(date: .abbreviated, time: .shortened)) – \(last.takenAt.formatted(date: .omitted, time: .shortened))"
    }

    var accessibilityLabel: String {
        isArea
            ? String(localized: "\(count) photos in this area")
            : String(localized: "\(count) photos taken here")
    }

    var accessibilityValue: String {
        [timeRangeText, coordinateText]
            .filter { !$0.isEmpty }
            .joined(separator: ". ")
    }
}

enum PhotoMomentClusterBuilder {
    static let defaultThresholdMeters: CLLocationDistance = 35
    static let maximumAnnotationCount = 500

    static func sorted(_ photos: [PhotoMoment]) -> [PhotoMoment] {
        photos.sorted { lhs, rhs in
            if lhs.takenAt == rhs.takenAt {
                return lhs.assetLocalIdentifier < rhs.assetLocalIdentifier
            }
            return lhs.takenAt < rhs.takenAt
        }
    }

    static func clusters(
        for photos: [PhotoMoment],
        thresholdMeters: CLLocationDistance = defaultThresholdMeters,
        maximumCount: Int = maximumAnnotationCount
    ) -> [PhotoMomentCluster] {
        annotations(for: placeClusters(for: photos, thresholdMeters: thresholdMeters), maximumCount: maximumCount)
    }

    /// Index centroids in metre-sized 3-D cells. Only neighbouring cells can
    /// contain a candidate within the threshold, including across the dateline.
    /// This avoids scanning every established place for every distinct photo.
    static func placeClusters(
        for photos: [PhotoMoment],
        thresholdMeters: CLLocationDistance = defaultThresholdMeters
    ) -> [PhotoMomentCluster] {
        precondition(thresholdMeters >= 0 && thresholdMeters.isFinite)
        let width = max(1, thresholdMeters * 1.02) // margin for ellipsoid/sphere distance differences
        var working: [WorkingCluster] = []
        var index: [SpatialCell: Set<Int>] = [:]
        for photo in sorted(photos) {
            let location = CLLocation(latitude: photo.latitude, longitude: photo.longitude)
            let cell = SpatialCell(coordinate: photo.coordinate, width: width)
            var nearest: (index: Int, distance: Double)?
            for neighbour in cell.neighbours {
                for candidate in index[neighbour] ?? [] {
                    let coordinate = working[candidate].coordinate
                    let distance = location.distance(from: CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude))
                    guard distance <= thresholdMeters else { continue }
                    if nearest == nil || distance < nearest!.distance || (distance == nearest!.distance && candidate < nearest!.index) {
                        nearest = (candidate, distance)
                    }
                }
            }
            if let nearest {
                let oldCell = SpatialCell(coordinate: working[nearest.index].coordinate, width: width)
                working[nearest.index].append(photo)
                let newCell = SpatialCell(coordinate: working[nearest.index].coordinate, width: width)
                if oldCell != newCell {
                    index[oldCell]?.remove(nearest.index)
                    if index[oldCell]?.isEmpty == true { index.removeValue(forKey: oldCell) }
                    index[newCell, default: []].insert(nearest.index)
                }
            } else {
                index[cell, default: []].insert(working.count)
                working.append(WorkingCluster(photo: photo))
            }
        }
        return working.map { cluster in
            PhotoMomentCluster(
                id: "\(cluster.photos[0].id.uuidString):\(cluster.photos.count)",
                coordinate: cluster.coordinate,
                photos: cluster.photos
            )
        }
    }

    /// Coarsen whole places, not individual photos. Every place is reachable in
    /// an area's paged directory AND the map's all-places directory, even when
    /// the area anchor is outside the current viewport. An anchor is an actual
    /// member place, not a fictitious centroid claiming a single shared location.
    static func annotations(for places: [PhotoMomentCluster], maximumCount: Int = maximumAnnotationCount) -> [PhotoMomentCluster] {
        precondition(maximumCount > 0)
        guard places.count > maximumCount else { return places }
        var width = 1.0 / 1_024
        while true {
            var cells: [Cell: [PhotoMomentCluster]] = [:]
            for place in places {
                let cell = width >= 360 ? Cell(row: 0, column: 0) : Cell(
                    row: Int(floor((place.coordinate.latitude + 90) / width)),
                    column: Int(floor((place.coordinate.longitude + 180) / width))
                )
                cells[cell, default: []].append(place)
            }
            if cells.count <= maximumCount {
                return cells.values.map { members in
                    let photos = sorted(members.flatMap(\.photos))
                    return PhotoMomentCluster(
                        id: "area:\(members[0].id):\(members.count):\(photos.count)",
                        coordinate: members[0].coordinate,
                        photos: photos,
                        isArea: true,
                        places: members
                    )
                }.sorted {
                    if $0.photos[0].takenAt == $1.photos[0].takenAt { return $0.id < $1.id }
                    return $0.photos[0].takenAt < $1.photos[0].takenAt
                }
            }
            width *= 2
        }
    }

    private struct Cell: Hashable {
        let row: Int
        let column: Int
    }

    private struct SpatialCell: Hashable {
        let x: Int
        let y: Int
        let z: Int

        init(coordinate: CLLocationCoordinate2D, width: Double) {
            let vector = PhotoMomentClusterBuilder.unitVector(coordinate)
            x = Int(floor(vector.x * 6_371_000 / width))
            y = Int(floor(vector.y * 6_371_000 / width))
            z = Int(floor(vector.z * 6_371_000 / width))
        }

        private init(x: Int, y: Int, z: Int) { self.x = x; self.y = y; self.z = z }

        var neighbours: [SpatialCell] {
            (-1...1).flatMap { dx in
                (-1...1).flatMap { dy in
                    (-1...1).map { dz in SpatialCell(x: x + dx, y: y + dy, z: z + dz) }
                }
            }
        }
    }

    private static func unitVector(_ coordinate: CLLocationCoordinate2D) -> (x: Double, y: Double, z: Double) {
        let latitude = coordinate.latitude * .pi / 180
        let longitude = coordinate.longitude * .pi / 180
        return (cos(latitude) * cos(longitude), cos(latitude) * sin(longitude), sin(latitude))
    }

    private struct WorkingCluster {
        var photos: [PhotoMoment]
        private var x: Double
        private var y: Double
        private var z: Double

        init(photo: PhotoMoment) {
            photos = [photo]
            let vector = PhotoMomentClusterBuilder.unitVector(photo.coordinate)
            x = vector.x; y = vector.y; z = vector.z
        }

        var coordinate: CLLocationCoordinate2D {
            CLLocationCoordinate2D(
                latitude: atan2(z, sqrt(x * x + y * y)) * 180 / .pi,
                longitude: atan2(y, x) * 180 / .pi
            )
        }

        mutating func append(_ photo: PhotoMoment) {
            photos.append(photo)
            let vector = PhotoMomentClusterBuilder.unitVector(photo.coordinate)
            x += vector.x; y += vector.y; z += vector.z
        }
    }
}

struct PhotoMomentMapMarker: View {
    let photo: PhotoMoment
    let isSelected: Bool
    let showsImage: Bool
    var showsDots = false
    var onDotTap: ((CGPoint) -> Void)? = nil
    var thumbnailLoader: PhotoThumbnailLoader = loadPhotoThumbnail
    let action: () -> Void

    func activate() { action() }

    func activateDot(at point: CGPoint) {
        if let onDotTap { onDotTap(point) } else { activate() }
    }

    private var style: PhotoMapMarkerStyle {
        .resolve(showsImages: showsImage, showsDots: showsDots)
    }

    var body: some View {
        Button(action: activate) {
            markerContent
                .scaleEffect(isSelected ? 1.06 : 1)
        }
        .buttonStyle(.plain)
        .highPriorityGesture(
            SpatialTapGesture().onEnded { activateDot(at: $0.location) },
            including: showsDots ? .all : .none
        )
        .zIndex(100)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(photo.accessibilityLabel)
        .accessibilityValue(photo.accessibilityValue)
        .accessibilityHint("Opens the photo.")
    }

    @ViewBuilder
    private var markerContent: some View {
        switch style {
        case .dots:
            PhotoMapDot(isSelected: isSelected, isCluster: false)
        case .images:
            imageMarker
        case .pins:
            compactMarker
        }
    }

    private var imageMarker: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .topTrailing) {
                PhotoThumbnailView(
                    assetLocalIdentifier: photo.assetLocalIdentifier,
                    targetPointSize: CGSize(width: 58, height: 58),
                    cornerRadius: 7,
                    loader: thumbnailLoader
                )
                .overlay {
                    RoundedRectangle(cornerRadius: 7)
                        .strokeBorder(.white.opacity(0.85), lineWidth: 1)
                }

                Image(systemName: "camera.fill")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(TE.surface)
                    .frame(width: 18, height: 18)
                    .background(Circle().fill(TE.accent))
                    .offset(x: 4, y: -4)
            }

            Text(photo.takenAt.formatted(date: .omitted, time: .shortened))
                .font(TE.mono(.caption2, weight: .semibold))
                .foregroundStyle(TE.textPrimary)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .padding(.top, 4)
        }
        .padding(6)
        .frame(width: 78)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(TE.card)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(isSelected ? TE.accent : TE.border, lineWidth: isSelected ? 2 : 1)
        }
        .shadow(color: .black.opacity(0.22), radius: 7, x: 0, y: 4)
        .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private var compactMarker: some View {
        ZStack(alignment: .bottom) {
            Circle()
                .fill(TE.card)
                .frame(width: 38, height: 38)
                .overlay {
                    Circle()
                        .strokeBorder(isSelected ? TE.accent : TE.border, lineWidth: isSelected ? 2 : 1)
                }
                .shadow(color: .black.opacity(0.22), radius: 7, x: 0, y: 4)

            Image(systemName: "camera.fill")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(TE.accent)
                .frame(width: 38, height: 38)
        }
        .frame(width: 44, height: 44)
        .contentShape(Circle())
    }
}

struct PhotoMomentClusterMapMarker: View {
    let cluster: PhotoMomentCluster
    let isSelected: Bool
    let showsImage: Bool
    var showsDots = false
    var onDotTap: ((CGPoint) -> Void)? = nil
    var thumbnailLoader: PhotoThumbnailLoader = loadPhotoThumbnail
    let action: () -> Void

    func activate() { action() }

    func activateDot(at point: CGPoint) {
        if let onDotTap { onDotTap(point) } else { activate() }
    }

    private var style: PhotoMapMarkerStyle {
        .resolve(showsImages: showsImage, showsDots: showsDots)
    }

    private var countText: String {
        cluster.count > 99 ? "99+" : "\(cluster.count)"
    }

    var body: some View {
        Button(action: activate) {
            markerContent
                .scaleEffect(isSelected ? 1.06 : 1)
        }
        .buttonStyle(.plain)
        .highPriorityGesture(
            SpatialTapGesture().onEnded { activateDot(at: $0.location) },
            including: showsDots ? .all : .none
        )
        .zIndex(100)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(cluster.accessibilityLabel)
        .accessibilityValue(cluster.accessibilityValue)
        .accessibilityHint(cluster.isArea ? "Opens the places and all photos in this area." : "Opens all photos taken at this place.")
    }

    private var previewPhotos: [PhotoMoment] {
        Array(cluster.sortedPhotos.suffix(3))
    }

    @ViewBuilder
    private var markerContent: some View {
        switch style {
        case .dots:
            PhotoMapDot(isSelected: isSelected, isCluster: true)
        case .images where !previewPhotos.isEmpty:
            imageMarker
        default:
            compactMarker
        }
    }

    private var imageMarker: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .topTrailing) {
                ZStack {
                    ForEach(Array(previewPhotos.enumerated()), id: \.element.id) { index, photo in
                        PhotoThumbnailView(
                            assetLocalIdentifier: photo.assetLocalIdentifier,
                            targetPointSize: CGSize(width: 58, height: 58),
                            cornerRadius: 7,
                            loader: thumbnailLoader
                        )
                        .overlay {
                            RoundedRectangle(cornerRadius: 7, style: .continuous)
                                .strokeBorder(.white.opacity(0.9), lineWidth: 1)
                        }
                        .shadow(color: .black.opacity(index == previewPhotos.count - 1 ? 0.16 : 0.08), radius: 3, y: 2)
                        .rotationEffect(.degrees(rotation(forPreviewIndex: index)))
                        .offset(offset(forPreviewIndex: index))
                        .zIndex(Double(index))
                    }
                }
                .frame(width: 78, height: 68)

                Text(countText)
                    .font(TE.mono(.caption2, weight: .black))
                    .foregroundStyle(TE.surface)
                    .monospacedDigit()
                    .padding(.horizontal, 6)
                    .frame(minWidth: 22, minHeight: 22)
                    .background(Capsule().fill(TE.accent))
                    .overlay {
                        Capsule().strokeBorder(.white.opacity(0.85), lineWidth: 1)
                    }
                    .offset(x: 6, y: -7)
                    .zIndex(10)
            }
            .frame(width: 82, height: 70)

            Text("\(cluster.count) photos")
                .font(TE.mono(.caption2, weight: .semibold))
                .foregroundStyle(TE.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .padding(.top, 2)
        }
        .padding(6)
        .frame(width: 94)
        .background(
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(TE.card)
        )
        .overlay {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .strokeBorder(isSelected ? TE.accent : TE.border, lineWidth: isSelected ? 2 : 1)
        }
        .shadow(color: .black.opacity(0.22), radius: 7, x: 0, y: 4)
        .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private func rotation(forPreviewIndex index: Int) -> Double {
        switch previewPhotos.count {
        case 1:
            return 0
        case 2:
            return index == 0 ? -8 : 5
        default:
            return [-10, 7, 0][index]
        }
    }

    private func offset(forPreviewIndex index: Int) -> CGSize {
        switch previewPhotos.count {
        case 1:
            return .zero
        case 2:
            return index == 0 ? CGSize(width: -9, height: -2) : CGSize(width: 8, height: 2)
        default:
            return [
                CGSize(width: -14, height: -2),
                CGSize(width: 12, height: 1),
                CGSize(width: 0, height: 5)
            ][index]
        }
    }

    private var compactMarker: some View {
        ZStack(alignment: .topTrailing) {
            Circle()
                .fill(TE.card)
                .frame(width: 42, height: 42)
                .overlay {
                    Circle()
                        .strokeBorder(isSelected ? TE.accent : TE.border, lineWidth: isSelected ? 2 : 1)
                }
                .shadow(color: .black.opacity(0.22), radius: 7, x: 0, y: 4)

            Image(systemName: "photo.stack.fill")
                .font(.subheadline.weight(.bold))
                .foregroundStyle(TE.accent)
                .frame(width: 42, height: 42)

            Text(countText)
                .font(TE.mono(.caption2, weight: .black))
                .foregroundStyle(TE.surface)
                .monospacedDigit()
                .padding(.horizontal, 5)
                .frame(minWidth: 20, minHeight: 20)
                .background(Capsule().fill(TE.accent))
                .offset(x: 7, y: -7)
        }
        .frame(width: 52, height: 52)
        .contentShape(Circle())
    }
}

struct PhotoMomentMiniMarker: View {
    let photo: PhotoMoment

    var body: some View {
        ZStack {
            Circle()
                .fill(TE.accent)
                .frame(width: 22, height: 22)
                .shadow(color: TE.accent.opacity(0.3), radius: 4, y: 2)

            Image(systemName: "camera.fill")
                .font(.caption2.weight(.bold))
                .foregroundStyle(TE.surface)
        }
        .frame(width: 32, height: 32)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(photo.accessibilityLabel)
        .accessibilityValue(photo.accessibilityValue)
    }
}

struct PhotoMomentQuickView: View {
    let photo: PhotoMoment
    @Environment(\.dismiss) private var dismiss
    @State private var isShowingFullPhoto = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    TESectionHeader(title: "PHOTO")

                    TECard {
                        VStack(spacing: 0) {
                            TERow {
                                Button {
                                    isShowingFullPhoto = true
                                } label: {
                                    VStack(spacing: 8) {
                                        PhotoThumbnailView(
                                            assetLocalIdentifier: photo.assetLocalIdentifier,
                                            targetPointSize: CGSize(width: 300, height: 360),
                                            cornerRadius: 6,
                                            contentMode: .fit
                                        )
                                        .background(TE.surfaceDark.opacity(0.05))
                                        .frame(maxWidth: .infinity)

                                        Label("View full photo", systemImage: "arrow.up.left.and.arrow.down.right")
                                            .font(TE.mono(.caption2, weight: .bold))
                                            .tracking(1.2)
                                            .foregroundStyle(TE.accent)
                                    }
                                    .frame(maxWidth: .infinity)
                                }
                                .buttonStyle(.plain)
                                .accessibilityHint("Opens this photo full screen.")
                            }

                            detailRow(label: "TAKEN", value: photo.formattedTakenTime)
                            detailRow(label: "SOURCE", value: photo.coordinateSource.displayName.uppercased())
                            detailRow(
                                label: "COORDS",
                                value: String(format: "%.5f, %.5f", photo.latitude, photo.longitude),
                                showDivider: false
                            )
                        }
                    }
                    .padding(.horizontal, 16)

                    TESectionFooter(text: "Whereday stores only this photo's local identifier, timestamp, and coordinates. The photo file stays in your Photos library.")
                }
                .padding(.bottom, 28)
            }
            .background(TE.surface.ignoresSafeArea())
            .navigationTitle("Photo")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .fullScreenCover(isPresented: $isShowingFullPhoto) {
                PhotoMomentFullScreenView(photo: photo)
            }
        }
    }

    private func detailRow(label: LocalizedStringKey, value: String, showDivider: Bool = true) -> some View {
        TERow(showDivider: showDivider) {
            HStack(spacing: 12) {
                Text(label)
                    .font(TE.mono(.caption, weight: .medium))
                    .tracking(1)
                    .foregroundStyle(TE.textMuted)

                Spacer()

                Text(value)
                    .font(TE.mono(.caption, weight: .semibold))
                    .foregroundStyle(TE.textPrimary)
                    .multilineTextAlignment(.trailing)
                    .monospacedDigit()
            }
        }
    }
}

/// Bounded metadata slice supplied to the lazy thumbnail grid. The full collection
/// remains available to the single-image full-screen browser.
struct PhotoMomentDetailPage {
    static let size = 60
    let photos: [PhotoMoment]
    let index: Int
    let count: Int

    init(photos: [PhotoMoment], index: Int) {
        count = max(1, (photos.count + Self.size - 1) / Self.size)
        self.index = min(max(index, 0), count - 1)
        let start = self.index * Self.size
        self.photos = Array(photos.dropFirst(start).prefix(Self.size))
    }

    static func nextPhotoIndex(from index: Int, by delta: Int, count: Int) -> Int {
        guard count > 0 else { return 0 }
        return ((index + delta) % count + count) % count
    }
}

@MainActor
@Observable
final class PhotoMomentGridState {
    let photos: [PhotoMoment]
    private(set) var pageIndex = 0
    var page: PhotoMomentDetailPage { PhotoMomentDetailPage(photos: photos, index: pageIndex) }

    init(photos: [PhotoMoment]) { self.photos = photos }

    func movePage(by delta: Int) {
        pageIndex = PhotoMomentDetailPage(photos: photos, index: page.index + delta).index
    }
}

/// Places are paged too: no eagerly rendered row for every distinct location.
@MainActor
@Observable
final class PhotoMomentPlaceDirectoryState {
    let places: [PhotoMomentCluster]
    private(set) var pageIndex = 0
    var pageCount: Int { max(1, (places.count + PhotoMomentDetailPage.size - 1) / PhotoMomentDetailPage.size) }
    var pagePlaces: [PhotoMomentCluster] {
        Array(places.dropFirst(pageIndex * PhotoMomentDetailPage.size).prefix(PhotoMomentDetailPage.size))
    }

    init(places: [PhotoMomentCluster]) { self.places = places }
    func movePage(by delta: Int) { pageIndex = min(max(pageIndex + delta, 0), pageCount - 1) }
}

/// "Moments": every matched photo place, by place or grouped by day, as on the Moments artboard.
struct PhotoMomentPlacesView: View {
    enum Grouping: Hashable, CaseIterable {
        case byPlace, byDay

        var title: LocalizedStringKey {
            switch self {
            case .byPlace: "By place"
            case .byDay: "By day"
            }
        }
    }

    let title: String
    @Bindable var selection: PhotoMomentDetailSelection
    /// Names a place after the visit it was taken during; nil falls back to its coordinates.
    let placeName: ((PhotoMomentCluster) -> String?)?
    var thumbnailLoader: PhotoThumbnailLoader = loadPhotoThumbnail
    @State private var directory: PhotoMomentPlaceDirectoryState
    @State private var grouping: Grouping = .byPlace
    @Environment(\.dismiss) private var dismiss

    init(
        places: [PhotoMomentCluster],
        title: String,
        selection: PhotoMomentDetailSelection,
        placeName: ((PhotoMomentCluster) -> String?)? = nil,
        thumbnailLoader: @escaping PhotoThumbnailLoader = loadPhotoThumbnail
    ) {
        self.title = title
        self.selection = selection
        self.placeName = placeName
        self.thumbnailLoader = thumbnailLoader
        _directory = State(initialValue: PhotoMomentPlaceDirectoryState(places: places))
    }

    /// The current page's places grouped by the day their first photo was taken, newest day first.
    private var dayGroups: [(day: Date, places: [PhotoMomentCluster])] {
        let calendar = Calendar.current
        let grouped = Dictionary(grouping: directory.pagePlaces) { place in
            calendar.startOfDay(for: place.sortedPhotos.first?.takenAt ?? .distantPast)
        }
        return grouped.keys.sorted(by: >).map { day in
            (day, grouped[day] ?? [])
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 14) {
                    header
                    groupingPicker

                    switch grouping {
                    case .byPlace:
                        ForEach(directory.pagePlaces) { place in
                            placeCard(place, timeStyle: .dayAndTime)
                        }
                    case .byDay:
                        ForEach(dayGroups, id: \.day) { group in
                            Text(dayTitle(group.day).uppercased())
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1.2)
                                .foregroundStyle(TE.textMuted)
                                .padding(.top, 6)
                                .accessibilityAddTraits(.isHeader)
                            ForEach(group.places) { place in
                                placeCard(place, timeStyle: .timeOnly)
                            }
                        }
                    }

                    if directory.pageCount > 1 {
                        pager
                    }

                    footer
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 24)
            }
            .foregroundStyle(TE.textPrimary)
            .background(TE.surface.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(TE.surface, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .fontWeight(.semibold)
                        .foregroundStyle(TE.inkRust)
                }
            }
            .sheet(item: $selection.place) { place in
                PhotoMomentClusterQuickView(cluster: place, selection: selection, thumbnailLoader: thumbnailLoader)
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("PHOTOS · MATCHED TO YOUR DAYS")
                .font(TE.mono(.caption2, weight: .semibold))
                .tracking(1.2)
                .foregroundStyle(TE.textMuted)
            Text(title)
                .font(TE.editorial(.largeTitle))
                .accessibilityAddTraits(.isHeader)
            Text(directory.places.count == 1 ? "1 place" : "\(directory.places.count) places")
                .font(.footnote)
                .foregroundStyle(TE.textMuted)
        }
        .padding(.top, 4)
    }

    private var groupingPicker: some View {
        HStack(spacing: 8) {
            ForEach(Grouping.allCases, id: \.self) { option in
                let isOn = grouping == option
                Button {
                    grouping = option
                } label: {
                    Text(option.title)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(isOn ? TE.onInkCard : TE.textPrimary)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 44)
                        .background(Capsule().fill(isOn ? TE.accent : TE.card))
                        .overlay(Capsule().strokeBorder(isOn ? Color.clear : TE.border, lineWidth: 1))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isOn ? .isSelected : [])
            }
        }
    }

    private enum TimeStyle { case dayAndTime, timeOnly }

    private func placeCard(_ place: PhotoMomentCluster, timeStyle: TimeStyle) -> some View {
        Button {
            selection.place = place
        } label: {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text(placeName?(place) ?? place.coordinateText)
                        .font(TE.editorial(.title3))
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text(placeCaption(place, timeStyle: timeStyle))
                        .font(TE.mono(.caption2, weight: .medium))
                        .foregroundStyle(TE.textMuted)
                        .multilineTextAlignment(.trailing)
                }
                thumbnailStrip(place)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
            .contentShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(placeName?(place).map { String(localized: "\(place.count) photos at \($0)") } ?? place.accessibilityLabel)
        .accessibilityValue(place.accessibilityValue)
        .accessibilityHint("Opens the complete photo collection at this place.")
        .accessibilityAddTraits(.isButton)
    }

    private static let stripLimit = 4

    private func thumbnailStrip(_ place: PhotoMomentCluster) -> some View {
        let photos = Array(place.sortedPhotos.prefix(Self.stripLimit))
        let overflow = place.count - photos.count
        return HStack(spacing: 6) {
            ForEach(Array(photos.enumerated()), id: \.element.id) { index, photo in
                PhotoThumbnailView(
                    assetLocalIdentifier: photo.assetLocalIdentifier,
                    targetPointSize: CGSize(width: 64, height: 64),
                    cornerRadius: 10,
                    loader: thumbnailLoader
                )
                .overlay {
                    if index == photos.count - 1, overflow > 0 {
                        RoundedRectangle(cornerRadius: 10, style: .continuous)
                            .fill(.black.opacity(0.45))
                        Text("+\(overflow)")
                            .font(TE.mono(.footnote, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                }
            }
        }
        .accessibilityHidden(true)
    }

    private func placeCaption(_ place: PhotoMomentCluster, timeStyle: TimeStyle) -> String {
        let count = place.count == 1 ? String(localized: "1 photo") : String(localized: "\(place.count) photos")
        guard let first = place.sortedPhotos.first else { return count }
        let time: String
        switch timeStyle {
        case .timeOnly:
            time = LocationMapView.clockText(first.takenAt)
        case .dayAndTime:
            let calendar = Calendar.current
            time = calendar.isDateInToday(first.takenAt)
                ? LocationMapView.clockText(first.takenAt)
                : first.takenAt.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
        }
        return "\(time) · \(count)"
    }

    private func dayTitle(_ day: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(day) { return String(localized: "Today") }
        if calendar.isDateInYesterday(day) { return String(localized: "Yesterday") }
        return day.formatted(.dateTime.weekday(.wide).month(.abbreviated).day())
    }

    private var pager: some View {
        HStack {
            Button("Previous page") { directory.movePage(by: -1) }
                .disabled(directory.pageIndex == 0)
            Spacer()
            Text("\(directory.pageIndex + 1) of \(directory.pageCount)")
                .monospacedDigit()
            Spacer()
            Button("Next page") { directory.movePage(by: 1) }
                .disabled(directory.pageIndex == directory.pageCount - 1)
        }
        .font(TE.mono(.caption, weight: .semibold))
        .foregroundStyle(TE.inkRust)
        .buttonStyle(.plain)
        .frame(minHeight: 44)
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Photos are read from this iPhone's library; only their time and place are kept.", systemImage: "lock")
            Text("Every place is listed here, even outside the visible map. Area annotations group places for rendering; they do not represent one shared location.")
        }
        .font(.caption)
        .foregroundStyle(TE.textMuted)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.top, 6)
    }
}

struct PhotoMomentClusterQuickView: View {
    let cluster: PhotoMomentCluster
    var thumbnailLoader: PhotoThumbnailLoader = loadPhotoThumbnail
    @Bindable var selection: PhotoMomentDetailSelection
    @State private var grid: PhotoMomentGridState
    @Environment(\.dismiss) private var dismiss

    init(
        cluster: PhotoMomentCluster,
        selection: PhotoMomentDetailSelection? = nil,
        grid: PhotoMomentGridState? = nil,
        thumbnailLoader: @escaping PhotoThumbnailLoader = loadPhotoThumbnail
    ) {
        self.cluster = cluster
        self.selection = selection ?? PhotoMomentDetailSelection()
        self.thumbnailLoader = thumbnailLoader
        _grid = State(initialValue: grid ?? PhotoMomentGridState(photos: cluster.sortedPhotos))
    }

    private var page: PhotoMomentDetailPage { grid.page }

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: 96), spacing: 12)]
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    TESectionHeader(title: cluster.isArea ? "PHOTOS IN THIS AREA" : "PHOTOS HERE")

                    TECard {
                        VStack(spacing: 0) {
                            detailRow(label: "COUNT", value: String(localized: "\(cluster.count) PHOTOS"))
                            detailRow(label: "TAKEN", value: cluster.timeRangeText.uppercased())
                            detailRow(label: "COORDS", value: cluster.coordinateText, showDivider: false)
                        }
                    }
                    .padding(.horizontal, 16)

                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(page.photos) { photo in
                            Button {
                                selection.browserPhoto = photo
                            } label: {
                                VStack(alignment: .leading, spacing: 7) {
                                    PhotoThumbnailView(
                                        assetLocalIdentifier: photo.assetLocalIdentifier,
                                        targetPointSize: CGSize(width: 96, height: 96),
                                        cornerRadius: 8,
                                        loader: thumbnailLoader
                                    )
                                    .overlay {
                                        RoundedRectangle(cornerRadius: 8, style: .continuous)
                                            .strokeBorder(TE.border, lineWidth: 1)
                                    }

                                    Text(photo.takenAt.formatted(date: .omitted, time: .shortened))
                                        .font(TE.mono(.caption2, weight: .semibold))
                                        .foregroundStyle(TE.textPrimary)
                                        .monospacedDigit()
                                        .lineLimit(1)
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(8)
                                .background(
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .fill(TE.card)
                                )
                                .overlay {
                                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                                        .strokeBorder(TE.border, lineWidth: 1)
                                }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(photo.accessibilityLabel)
                            .accessibilityValue(photo.accessibilityValue)
                            .accessibilityHint("Opens this photo full screen.")
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 14)

                    if page.count > 1 {
                        HStack {
                            Button("Previous page") { grid.movePage(by: -1) }
                                .disabled(page.index == 0)
                            Spacer()
                            Text("\(page.index + 1) of \(page.count)")
                                .monospacedDigit()
                            Spacer()
                            Button("Next page") { grid.movePage(by: 1) }
                                .disabled(page.index == page.count - 1)
                        }
                        .font(TE.mono(.caption, weight: .semibold))
                        .padding(16)
                    }

                    TESectionFooter(text: "Tap any thumbnail to view it full screen, then use the arrows to move through all photos in this group.")
                }
                .padding(.bottom, 28)
            }
            .background(TE.surface.ignoresSafeArea())
            .navigationTitle(cluster.isArea ? "Photos in This Area" : "Photos Here")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .fullScreenCover(item: $selection.browserPhoto) { photo in
                PhotoMomentFullScreenView(photo: photo, photos: grid.photos, loader: thumbnailLoader)
            }
        }
    }

    private func detailRow(label: LocalizedStringKey, value: String, showDivider: Bool = true) -> some View {
        TERow(showDivider: showDivider) {
            HStack(spacing: 12) {
                Text(label)
                    .font(TE.mono(.caption, weight: .medium))
                    .tracking(1)
                    .foregroundStyle(TE.textMuted)

                Spacer()

                Text(value)
                    .font(TE.mono(.caption, weight: .semibold))
                    .foregroundStyle(TE.textPrimary)
                    .multilineTextAlignment(.trailing)
                    .monospacedDigit()
            }
        }
    }
}

/// The same observable selection drives the real browser and executable hosted
/// request-spy tests. No second, test-only navigation arithmetic is required.
@MainActor
@Observable
final class PhotoMomentBrowserState {
    let photos: [PhotoMoment]
    private(set) var selectedIndex: Int
    var photo: PhotoMoment { photos[selectedIndex] }

    init(photo: PhotoMoment, photos: [PhotoMoment]? = nil) {
        let ordered = PhotoMomentClusterBuilder.sorted(photos ?? [photo])
        self.photos = ordered.isEmpty ? [photo] : ordered
        selectedIndex = self.photos.firstIndex { $0.id == photo.id } ?? 0
    }

    func move(by delta: Int) {
        selectedIndex = PhotoMomentDetailPage.nextPhotoIndex(from: selectedIndex, by: delta, count: photos.count)
    }
}

struct PhotoMomentFullScreenView: View {
    @State private var browser: PhotoMomentBrowserState
    var loader: PhotoThumbnailLoader = loadPhotoThumbnail
    var photos: [PhotoMoment] { browser.photos }

    @Environment(\.dismiss) private var dismiss
    @Environment(\.displayScale) private var displayScale
    @State private var image: UIImage?
    @State private var hasAttemptedLoad = false

    init(photo: PhotoMoment, photos: [PhotoMoment]? = nil, loader: @escaping PhotoThumbnailLoader = loadPhotoThumbnail) {
        _browser = State(initialValue: PhotoMomentBrowserState(photo: photo, photos: photos))
        self.loader = loader
    }

    init(browser: PhotoMomentBrowserState, loader: @escaping PhotoThumbnailLoader) {
        _browser = State(initialValue: browser)
        self.loader = loader
    }

    private var activeIndex: Int { browser.selectedIndex }
    private var photo: PhotoMoment { browser.photo }
    private var canNavigate: Bool { photos.count > 1 }

    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color.black.ignoresSafeArea()

                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .accessibilityLabel(photo.accessibilityLabel)
                        .accessibilityValue(photo.accessibilityValue)
                } else {
                    VStack(spacing: 12) {
                        if hasAttemptedLoad {
                            Image(systemName: "photo.badge.exclamationmark")
                                .font(.largeTitle.weight(.medium))
                            Text("Unable to load this photo")
                                .font(TE.mono(.caption, weight: .semibold))
                                .tracking(1.2)
                        } else {
                            ProgressView()
                                .tint(.white)
                            Text("Loading photo…")
                                .font(TE.mono(.caption, weight: .semibold))
                                .tracking(1.2)
                        }
                    }
                    .foregroundStyle(.white.opacity(0.86))
                }

                VStack(spacing: 0) {
                    HStack {
                        Spacer()

                        Button {
                            dismiss()
                        } label: {
                            Image(systemName: "xmark")
                                .font(.headline.weight(.bold))
                                .foregroundStyle(.white)
                                .frame(width: 42, height: 42)
                                .background(.black.opacity(0.55), in: Circle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Close photo")
                    }
                    .padding(.top, 16)
                    .padding(.horizontal, 16)

                    Spacer()

                    VStack(alignment: .leading, spacing: 12) {
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(photo.formattedTakenTime)
                                    .font(TE.mono(.caption, weight: .bold))
                                    .tracking(1.2)
                                    .foregroundStyle(.white)

                                Text(String(format: "%.5f, %.5f", photo.latitude, photo.longitude))
                                    .font(TE.mono(.caption2, weight: .medium))
                                    .foregroundStyle(.white.opacity(0.72))
                                    .monospacedDigit()
                            }

                            Spacer()

                            if canNavigate {
                                Text("\(activeIndex + 1) of \(photos.count)")
                                    .font(TE.mono(.caption2, weight: .bold))
                                    .foregroundStyle(.white.opacity(0.82))
                                    .monospacedDigit()
                            }
                        }

                        if canNavigate {
                            HStack(spacing: 12) {
                                navigationButton(systemName: "chevron.left", label: "Previous photo") {
                                    moveSelection(by: -1)
                                }

                                Spacer()

                                Text("Swipe or tap arrows")
                                    .font(TE.mono(.caption2, weight: .semibold))
                                    .tracking(1)
                                    .foregroundStyle(.white.opacity(0.66))

                                Spacer()

                                navigationButton(systemName: "chevron.right", label: "Next photo") {
                                    moveSelection(by: 1)
                                }
                            }
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(
                        LinearGradient(
                            colors: [.black.opacity(0.0), .black.opacity(0.72)],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                }
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 28)
                    .onEnded { value in
                        guard canNavigate,
                              abs(value.translation.width) > abs(value.translation.height),
                              abs(value.translation.width) > 44 else { return }
                        moveSelection(by: value.translation.width < 0 ? 1 : -1)
                    }
            )
            .task(id: imageTaskID(for: proxy.size)) {
                await loadImage(for: proxy.size)
            }
        }
    }

    private func navigationButton(
        systemName: String,
        label: LocalizedStringKey,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.headline.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 42, height: 42)
                .background(.white.opacity(0.16), in: Circle())
                .overlay {
                    Circle().strokeBorder(.white.opacity(0.22), lineWidth: 1)
                }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }

    private func moveSelection(by delta: Int) {
        guard canNavigate else { return }
        browser.move(by: delta)
    }

    private func imageTaskID(for size: CGSize) -> String {
        "\(photo.assetLocalIdentifier)-\(Int(size.width))-\(Int(size.height))-\(Int(displayScale * 100))"
    }

    private func loadImage(for size: CGSize) async {
        image = nil
        hasAttemptedLoad = false
        let pixelSize = CGSize(
            width: max(1, size.width * displayScale),
            height: max(1, size.height * displayScale)
        )
        let loadedImage = await loader(photo.assetLocalIdentifier, pixelSize, .aspectFit)
        guard !Task.isCancelled else { return }
        image = loadedImage
        hasAttemptedLoad = true
    }
}
