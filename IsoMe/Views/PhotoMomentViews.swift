import SwiftUI
import Photos
import CoreLocation

struct PhotoThumbnailView: View {
    @Environment(\.displayScale) private var displayScale

    let assetLocalIdentifier: String
    let targetPointSize: CGSize
    var cornerRadius: CGFloat = 4
    var contentMode: ContentMode = .fill

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
        hasAttemptedLoad = false
        let pixelSize = CGSize(
            width: max(1, targetPointSize.width * displayScale),
            height: max(1, targetPointSize.height * displayScale)
        )
        image = await PhotoLibraryManager.shared.thumbnail(
            for: assetLocalIdentifier,
            targetSize: pixelSize,
            contentMode: photoKitContentMode
        )
        hasAttemptedLoad = true
    }
}

struct PhotoMomentCluster: Identifiable {
    let id: String
    let coordinate: CLLocationCoordinate2D
    let photos: [PhotoMoment]

    var count: Int { photos.count }
    var onlyPhoto: PhotoMoment? { photos.count == 1 ? photos[0] : nil }
    var representativePhoto: PhotoMoment? { photos.last }

    var sortedPhotos: [PhotoMoment] {
        PhotoMomentClusterBuilder.sorted(photos)
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
        String(localized: "\(count) photos taken here")
    }

    var accessibilityValue: String {
        [timeRangeText, coordinateText]
            .filter { !$0.isEmpty }
            .joined(separator: ". ")
    }
}

enum PhotoMomentClusterBuilder {
    static let defaultThresholdMeters: CLLocationDistance = 35

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
        thresholdMeters: CLLocationDistance = defaultThresholdMeters
    ) -> [PhotoMomentCluster] {
        guard !photos.isEmpty else { return [] }

        var workingClusters: [WorkingCluster] = []

        for photo in sorted(photos) {
            let photoLocation = CLLocation(latitude: photo.latitude, longitude: photo.longitude)
            let nearestCluster = workingClusters.indices
                .map { index -> (index: Int, distance: CLLocationDistance) in
                    let clusterCoordinate = workingClusters[index].coordinate
                    let clusterLocation = CLLocation(
                        latitude: clusterCoordinate.latitude,
                        longitude: clusterCoordinate.longitude
                    )
                    return (index, photoLocation.distance(from: clusterLocation))
                }
                .min { lhs, rhs in lhs.distance < rhs.distance }

            if let nearestCluster, nearestCluster.distance <= thresholdMeters {
                workingClusters[nearestCluster.index].append(photo)
            } else {
                workingClusters.append(WorkingCluster(photo: photo))
            }
        }

        return workingClusters
            .map { cluster in
                let photos = sorted(cluster.photos)
                return PhotoMomentCluster(
                    id: photos.map { $0.id.uuidString }.joined(separator: ","),
                    coordinate: cluster.coordinate,
                    photos: photos
                )
            }
            .sorted { lhs, rhs in
                guard let lhsDate = lhs.sortedPhotos.first?.takenAt,
                      let rhsDate = rhs.sortedPhotos.first?.takenAt else {
                    return lhs.id < rhs.id
                }
                if lhsDate == rhsDate { return lhs.id < rhs.id }
                return lhsDate < rhsDate
            }
    }

    private struct WorkingCluster {
        var photos: [PhotoMoment]
        private var latitudeTotal: Double
        private var longitudeTotal: Double

        init(photo: PhotoMoment) {
            self.photos = [photo]
            self.latitudeTotal = photo.latitude
            self.longitudeTotal = photo.longitude
        }

        var coordinate: CLLocationCoordinate2D {
            let count = max(photos.count, 1)
            return CLLocationCoordinate2D(
                latitude: latitudeTotal / Double(count),
                longitude: longitudeTotal / Double(count)
            )
        }

        mutating func append(_ photo: PhotoMoment) {
            photos.append(photo)
            latitudeTotal += photo.latitude
            longitudeTotal += photo.longitude
        }
    }
}

struct PhotoMomentMapMarker: View {
    let photo: PhotoMoment
    let isSelected: Bool
    let showsImage: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            markerContent
                .scaleEffect(isSelected ? 1.06 : 1)
        }
        .buttonStyle(.plain)
        .zIndex(100)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(photo.accessibilityLabel)
        .accessibilityValue(photo.accessibilityValue)
        .accessibilityHint("Opens the photo.")
    }

    @ViewBuilder
    private var markerContent: some View {
        if showsImage {
            imageMarker
        } else {
            compactMarker
        }
    }

    private var imageMarker: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .topTrailing) {
                PhotoThumbnailView(
                    assetLocalIdentifier: photo.assetLocalIdentifier,
                    targetPointSize: CGSize(width: 58, height: 58),
                    cornerRadius: 7
                )
                .overlay {
                    RoundedRectangle(cornerRadius: 7)
                        .strokeBorder(.white.opacity(0.85), lineWidth: 1)
                }

                Image(systemName: "camera.fill")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(.white)
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
    let action: () -> Void

    private var countText: String {
        cluster.count > 99 ? "99+" : "\(cluster.count)"
    }

    var body: some View {
        Button(action: action) {
            markerContent
                .scaleEffect(isSelected ? 1.06 : 1)
        }
        .buttonStyle(.plain)
        .zIndex(100)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(cluster.accessibilityLabel)
        .accessibilityValue(cluster.accessibilityValue)
        .accessibilityHint("Opens all photos taken at this place.")
    }

    private var previewPhotos: [PhotoMoment] {
        Array(cluster.sortedPhotos.suffix(3))
    }

    @ViewBuilder
    private var markerContent: some View {
        if showsImage, !previewPhotos.isEmpty {
            imageMarker
        } else {
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
                            cornerRadius: 7
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
                    .foregroundStyle(.white)
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
                .foregroundStyle(.white)
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
                .foregroundStyle(.white)
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

struct PhotoMomentClusterQuickView: View {
    let cluster: PhotoMomentCluster

    @Environment(\.dismiss) private var dismiss
    @State private var selectedPhoto: PhotoMoment?

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: 96), spacing: 12)]
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    TESectionHeader(title: "PHOTOS HERE")

                    TECard {
                        VStack(spacing: 0) {
                            detailRow(label: "COUNT", value: String(localized: "\(cluster.count) PHOTOS"))
                            detailRow(label: "TAKEN", value: cluster.timeRangeText.uppercased())
                            detailRow(label: "COORDS", value: cluster.coordinateText, showDivider: false)
                        }
                    }
                    .padding(.horizontal, 16)

                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(cluster.sortedPhotos) { photo in
                            Button {
                                selectedPhoto = photo
                            } label: {
                                VStack(alignment: .leading, spacing: 7) {
                                    PhotoThumbnailView(
                                        assetLocalIdentifier: photo.assetLocalIdentifier,
                                        targetPointSize: CGSize(width: 96, height: 96),
                                        cornerRadius: 8
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

                    TESectionFooter(text: "Tap any thumbnail to view it full screen, then use the arrows to move through the photos from this place.")
                }
                .padding(.bottom, 28)
            }
            .background(TE.surface.ignoresSafeArea())
            .navigationTitle("Photos Here")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
            .fullScreenCover(item: $selectedPhoto) { photo in
                PhotoMomentFullScreenView(photo: photo, photos: cluster.sortedPhotos)
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

struct PhotoMomentFullScreenView: View {
    let photos: [PhotoMoment]

    @Environment(\.dismiss) private var dismiss
    @Environment(\.displayScale) private var displayScale
    @State private var selectedIndex: Int
    @State private var image: UIImage?
    @State private var hasAttemptedLoad = false

    init(photo: PhotoMoment, photos: [PhotoMoment]? = nil) {
        let resolvedPhotos = PhotoMomentClusterBuilder.sorted(photos ?? [photo])
        let nonEmptyPhotos = resolvedPhotos.isEmpty ? [photo] : resolvedPhotos
        self.photos = nonEmptyPhotos
        _selectedIndex = State(initialValue: nonEmptyPhotos.firstIndex { $0.id == photo.id } ?? 0)
    }

    private var activeIndex: Int {
        min(max(selectedIndex, 0), photos.count - 1)
    }

    private var photo: PhotoMoment {
        photos[activeIndex]
    }

    private var canNavigate: Bool {
        photos.count > 1
    }

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
        selectedIndex = (activeIndex + delta + photos.count) % photos.count
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
        let loadedImage = await PhotoLibraryManager.shared.thumbnail(
            for: photo.assetLocalIdentifier,
            targetSize: pixelSize,
            contentMode: .aspectFit
        )
        guard !Task.isCancelled else { return }
        image = loadedImage
        hasAttemptedLoad = true
    }
}
