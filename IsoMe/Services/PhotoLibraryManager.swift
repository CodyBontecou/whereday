import Foundation
import Photos
import UIKit
import CoreLocation

enum PhotoLibraryAccessState: String, Equatable {
    case notDetermined
    case authorized
    case limited
    case denied
    case restricted
    case unavailable

    var canRead: Bool {
        self == .authorized || self == .limited
    }

    var isLimited: Bool {
        self == .limited
    }

    var statusLabel: String {
        switch self {
        case .notDetermined:
            return String(localized: "Not Connected")
        case .authorized:
            return String(localized: "Connected")
        case .limited:
            return String(localized: "Limited")
        case .denied:
            return String(localized: "Denied")
        case .restricted:
            return String(localized: "Restricted")
        case .unavailable:
            return String(localized: "Unavailable")
        }
    }

    var explanation: String {
        switch self {
        case .notDetermined:
            return String(localized: "Connect Photos to show pictures on your map and outings. Whereday stores only local photo metadata.")
        case .authorized:
            return String(localized: "Photos from your library can appear on the map using photo GPS or nearby Whereday route/visit matches. Photo files stay in Photos.")
        case .limited:
            return String(localized: "Only photos you selected for Whereday can appear. Add more in iOS Photos privacy settings.")
        case .denied:
            return String(localized: "Photo access is off. Enable it in Settings to show photo pins on the map.")
        case .restricted:
            return String(localized: "Photo access is restricted on this device.")
        case .unavailable:
            return String(localized: "Photos are unavailable on this device.")
        }
    }

    static func from(_ status: PHAuthorizationStatus) -> PhotoLibraryAccessState {
        switch status {
        case .notDetermined:
            return .notDetermined
        case .authorized:
            return .authorized
        case .limited:
            return .limited
        case .denied:
            return .denied
        case .restricted:
            return .restricted
        @unknown default:
            return .unavailable
        }
    }
}

@MainActor
final class PhotoLibraryManager: NSObject, PHPhotoLibraryChangeObserver {
    static let shared = PhotoLibraryManager()

    private let imageManager = PHCachingImageManager()
    private var isObservingChanges = false

    var authorizationState: PhotoLibraryAccessState {
        PhotoLibraryAccessState.from(PHPhotoLibrary.authorizationStatus(for: .readWrite))
    }

    func requestAuthorization() async -> PhotoLibraryAccessState {
        let current = authorizationState
        guard current == .notDetermined else {
            startObservingChangesIfNeeded()
            return current
        }

        let status = await withCheckedContinuation { continuation in
            PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
                continuation.resume(returning: status)
            }
        }

        let state = PhotoLibraryAccessState.from(status)
        if state.canRead {
            startObservingChangesIfNeeded()
        }
        return state
    }

    func startObservingChangesIfNeeded() {
        guard authorizationState.canRead, !isObservingChanges else { return }
        PHPhotoLibrary.shared().register(self)
        isObservingChanges = true
    }

    func fetchPhotoMetadata(in range: ClosedRange<Date>) -> [PhotoAssetLibraryMetadata] {
        guard authorizationState.canRead else { return [] }
        startObservingChangesIfNeeded()

        let fetchOptions = PHFetchOptions()
        fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]
        fetchOptions.predicate = NSPredicate(
            format: "creationDate >= %@ AND creationDate <= %@",
            range.lowerBound as NSDate,
            range.upperBound as NSDate
        )

        let result = PHAsset.fetchAssets(with: .image, options: fetchOptions)
        var moments: [PhotoAssetLibraryMetadata] = []
        moments.reserveCapacity(result.count)

        result.enumerateObjects { asset, _, _ in
            guard let takenAt = asset.creationDate else { return }

            let coordinate = asset.location.flatMap { location in
                CLLocationCoordinate2DIsValid(location.coordinate) ? location.coordinate : nil
            }

            moments.append(PhotoAssetLibraryMetadata(
                assetLocalIdentifier: asset.localIdentifier,
                takenAt: takenAt,
                latitude: coordinate?.latitude,
                longitude: coordinate?.longitude
            ))
        }

        return moments
    }

    func thumbnail(
        for assetLocalIdentifier: String,
        targetSize: CGSize,
        contentMode: PHImageContentMode = .aspectFill
    ) async -> UIImage? {
        guard authorizationState.canRead else { return nil }

        let result = PHAsset.fetchAssets(withLocalIdentifiers: [assetLocalIdentifier], options: nil)
        guard let asset = result.firstObject else { return nil }

        let options = PHImageRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .highQualityFormat
        options.resizeMode = .exact

        return await withCheckedContinuation { continuation in
            var didResume = false
            imageManager.requestImage(
                for: asset,
                targetSize: targetSize,
                contentMode: contentMode,
                options: options
            ) { image, _ in
                guard !didResume else { return }
                didResume = true
                continuation.resume(returning: image)
            }
        }
    }

    nonisolated func photoLibraryDidChange(_ changeInstance: PHChange) {
        Task { @MainActor in
            NotificationCenter.default.post(name: .photoLibraryDidChange, object: nil)
        }
    }
}

extension Notification.Name {
    static let photoLibraryDidChange = Notification.Name("photoLibraryDidChange")
}
