import XCTest
import CoreLocation
import SwiftData
import SwiftUI
import Photos
import UIKit
@testable import IsoMe

@MainActor
final class PhotoMomentMembershipTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_700_000_000)

    func test501SamePlaceHasCompleteCountPagesAndFullScreenMembership() throws {
        let photos = fixture(count: 501)
        let cluster = try XCTUnwrap(PhotoMomentClusterBuilder.clusters(for: Array(photos.reversed())).first)
        XCTAssertEqual(cluster.count, 501)
        XCTAssertFalse(cluster.isArea)
        XCTAssertEqual(cluster.accessibilityLabel, "501 photos taken here")
        XCTAssertEqual(cluster.sortedPhotos.map(\.id), photos.map(\.id))

        var pagedIDs: [UUID] = []
        let pageCount = PhotoMomentDetailPage(photos: cluster.sortedPhotos, index: 0).count
        XCTAssertEqual(pageCount, 9)
        for index in 0..<pageCount {
            let page = PhotoMomentDetailPage(photos: cluster.sortedPhotos, index: index)
            XCTAssertLessThanOrEqual(page.photos.count, 60)
            pagedIDs += page.photos.map(\.id)
        }
        XCTAssertEqual(pagedIDs, photos.map(\.id))
        XCTAssertEqual(PhotoMomentDetailPage(photos: cluster.sortedPhotos, index: 8).photos.count, 21)

        // This is the same initializer used when tapping any grid page, including
        // the last page. It must receive all members, not just that page's slice.
        let browser = PhotoMomentFullScreenView(photo: photos[500], photos: cluster.sortedPhotos)
        XCTAssertEqual(browser.photos.map(\.id), photos.map(\.id))
        let state = PhotoMomentBrowserState(photo: photos[500], photos: cluster.sortedPhotos)
        XCTAssertEqual(state.photo.id, photos[500].id)
        state.move(by: 1)
        XCTAssertEqual(state.photo.id, photos[0].id)
        var visited: [UUID] = []
        for _ in 0..<state.photos.count {
            visited.append(state.photo.id)
            state.move(by: 1)
        }
        XCTAssertEqual(visited, photos.map(\.id))
        XCTAssertEqual(state.selectedIndex, 0)
        state.move(by: -1)
        XCTAssertEqual(state.photo.id, photos[500].id)
    }

    func testAbove500AcrossMultiplePlacesPreservesEachPlaceAndStableOrder() {
        let first = fixture(count: 1_001)
        let second = fixture(count: 701, latitude: 38, prefix: "second")
        let clusters = PhotoMomentClusterBuilder.clusters(for: Array((first + second).reversed()))
        XCTAssertEqual(clusters.count, 2)
        XCTAssertEqual(Set(clusters.map(\.count)), Set([1_001, 701]))
        XCTAssertTrue(clusters.allSatisfy { !$0.isArea })
        assertCompleteMembership(clusters, photos: first + second)
        XCTAssertEqual(
            clusters.map(\.id),
            PhotoMomentClusterBuilder.clusters(for: first + second).map(\.id)
        )
    }

    func testMoreThan500DistinctPlacesCoarsensAnnotationsWithoutDroppingMembers() {
        let photos: [PhotoMoment] = (0..<1_001).map { (index: Int) -> PhotoMoment in
            let identifier = "place-\(index)"
            let takenAt = start.addingTimeInterval(Double(index))
            let latitude: Double = 37.0 + Double(index / 100) * 0.01
            let longitude: Double = -122.0 + Double(index % 100) * 0.01
            return PhotoMoment(assetLocalIdentifier: identifier, takenAt: takenAt, latitude: latitude, longitude: longitude)
        }
        let clusters = PhotoMomentClusterBuilder.clusters(for: photos)
        XCTAssertLessThanOrEqual(clusters.count, 500)
        XCTAssertTrue(clusters.contains { $0.isArea && $0.count > 1 })
        assertCompleteMembership(clusters, photos: photos)
        let one = PhotoMomentClusterBuilder.clusters(for: photos, maximumCount: 1)
        XCTAssertEqual(one.count, 1)
        XCTAssertEqual(one.first?.count, photos.count)
    }

    func testDateRangeReloadAndAuthorizationClearOnlyPresentationNotStoredMetadata() throws {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self])
        let container = try ModelContainer(
            for: schema,
            configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)]
        )
        let context = container.mainContext
        let first = fixture(count: 501)
        let second = fixture(count: 601, latitude: 38, prefix: "tomorrow", offset: 86_400)
        for photo in first + second { context.insert(photo) }
        try context.save()

        var access = PhotoLibraryAccessState.authorized
        let manager = makeIsolatedLocationManager()
        manager.isTrackingEnabled = false
        let viewModel = LocationViewModel(
            modelContext: context,
            locationManager: manager,
            photoAuthorizationProvider: { access },
            accessiblePhotoIdentifiersProvider: { Set($0) }
        )
        let firstRange = start...start.addingTimeInterval(500)
        viewModel.mapDateRange = firstRange
        viewModel.loadMapPhotoMoments()
        XCTAssertEqual(viewModel.mapPhotoMomentCount, 501)
        assertCompleteMembership(viewModel.mapPhotoMomentClusters, photos: first)
        XCTAssertEqual(viewModel.mapPhotoMoments.count, 501)

        let secondRange = start.addingTimeInterval(86_400)...start.addingTimeInterval(87_000)
        viewModel.mapDateRange = secondRange
        viewModel.loadMapPhotoMoments()
        XCTAssertEqual(viewModel.mapPhotoMomentCount, 601)
        assertCompleteMembership(viewModel.mapPhotoMomentClusters, photos: second)
        XCTAssertTrue(viewModel.mapPhotoMoments.allSatisfy { secondRange.contains($0.takenAt) })

        viewModel.mapDateRange = start.addingTimeInterval(-100)...start.addingTimeInterval(-1)
        viewModel.loadMapPhotoMoments()
        XCTAssertEqual(viewModel.mapPhotoMomentCount, 0)
        XCTAssertTrue(viewModel.mapPhotoMomentClusters.isEmpty)
        viewModel.loadMapPhotoMoments(in: firstRange)
        XCTAssertEqual(viewModel.mapPhotoMomentClusters.first?.count, 501)

        access = .denied
        viewModel.loadMapPhotoMoments(in: firstRange)
        XCTAssertTrue(viewModel.mapPhotoMoments.isEmpty)
        XCTAssertTrue(viewModel.mapPhotoMomentClusters.isEmpty)
        XCTAssertEqual(viewModel.mapPhotoMomentCount, 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<PhotoMoment>()), 1_102)
        XCTAssertEqual(Set(try context.fetch(FetchDescriptor<PhotoMoment>()).map(\.id)), Set((first + second).map(\.id)))

        access = .limited
        viewModel.loadMapPhotoMoments(in: secondRange)
        assertCompleteMembership(viewModel.mapPhotoMomentClusters, photos: second)
    }

    func testDetailPageClampsEmptyAndOutOfBoundsRequests() {
        let empty = PhotoMomentDetailPage(photos: [], index: 10)
        XCTAssertEqual(empty.count, 1)
        XCTAssertEqual(empty.index, 0)
        XCTAssertTrue(empty.photos.isEmpty)
        let photos = fixture(count: 61)
        XCTAssertEqual(PhotoMomentDetailPage(photos: photos, index: -10).photos.count, 60)
        XCTAssertEqual(PhotoMomentDetailPage(photos: photos, index: 10).photos.map(\.id), [photos[60].id])
        XCTAssertEqual(PhotoMomentDetailPage.nextPhotoIndex(from: 0, by: 1, count: 0), 0)
    }

    func testMixedOverflowKeepsBoundaryPlaceIndivisibleAndDirectoryReachesEveryPlace() throws {
        // 38 + 90 == 128: this place straddles the old area's power-of-two
        // latitude boundaries, while its two sides are only about two metres apart.
        let dense = fixture(count: 501, latitude: 38 - 0.00001)
        for photo in dense.suffix(250) { photo.latitude = 38 + 0.00001 }
        let distinct: [PhotoMoment] = (0..<601).map { (index: Int) -> PhotoMoment in
            let identifier = "distinct-\(index)"
            let takenAt = start.addingTimeInterval(1_000.0 + Double(index))
            let latitude: Double = -60.0 + Double(index / 100) * 10.0
            let longitude: Double = -170.0 + Double(index % 100) * 3.0
            return PhotoMoment(assetLocalIdentifier: identifier, takenAt: takenAt, latitude: latitude, longitude: longitude)
        }
        let photos: [PhotoMoment] = dense + distinct
        let places: [PhotoMomentCluster] = PhotoMomentClusterBuilder.placeClusters(for: Array(photos.reversed()))
        XCTAssertEqual(places.count, 602)
        let densePlace: PhotoMomentCluster = try XCTUnwrap(places.first { $0.count == 501 })
        XCTAssertEqual(densePlace.photos.map(\.id), dense.map(\.id))
        let areas: [PhotoMomentCluster] = PhotoMomentClusterBuilder.annotations(for: places)
        XCTAssertLessThanOrEqual(areas.count, 500)
        XCTAssertTrue(areas.allSatisfy(\.isArea))
        assertCompleteMembership(areas, photos: photos)
        let retainedPlaces = areas.flatMap(\.places)
        XCTAssertEqual(Set(retainedPlaces.map(\.id)), Set(places.map(\.id)))
        XCTAssertEqual(retainedPlaces.filter { $0.id == densePlace.id }.count, 1)
        let retainedDensePlace = try XCTUnwrap(retainedPlaces.first { $0.id == densePlace.id })
        XCTAssertEqual(retainedDensePlace.photos.map(\.id), dense.map(\.id))
        XCTAssertTrue(areas.allSatisfy { $0.accessibilityLabel.contains("in this area") })
        XCTAssertTrue(areas.allSatisfy { area in
            area.places.contains { $0.coordinate.latitude == area.coordinate.latitude && $0.coordinate.longitude == area.coordinate.longitude }
        })
        // The same paged state used by the always-reachable all-places sheet.
        let directory = PhotoMomentPlaceDirectoryState(places: places)
        var reachable: [String] = []
        for _ in 0..<directory.pageCount {
            XCTAssertLessThanOrEqual(directory.pagePlaces.count, 60)
            reachable += directory.pagePlaces.map(\.id)
            directory.movePage(by: 1)
        }
        XCTAssertEqual(reachable, places.map(\.id))
        XCTAssertEqual(reachable.first, places.first?.id)
        XCTAssertEqual(reachable.last, places.last?.id)
        let fallback = PhotoMomentClusterBuilder.annotations(for: places, maximumCount: 1)
        XCTAssertEqual(fallback.count, 1)
        XCTAssertEqual(fallback[0].places.map(\.id), places.map(\.id))
        XCTAssertEqual(fallback[0].photos.count, photos.count)
    }

    func testSpatialIndexGroupsAcrossDatelineAndTracksMovingCentroids() {
        let photos = fixture(count: 3, latitude: 0)
        photos[0].longitude = 179.99999
        photos[1].longitude = -179.99999
        photos[2].longitude = 179.99998
        let places = PhotoMomentClusterBuilder.placeClusters(for: photos)
        XCTAssertEqual(places.count, 1)
        XCTAssertEqual(places[0].count, 3)
        XCTAssertGreaterThan(abs(places[0].coordinate.longitude), 179.99)
        let moving = fixture(count: 200)
        for (index, photo) in moving.enumerated() {
            photo.latitude += Double(index % 20) * 0.00001
        }
        XCTAssertEqual(PhotoMomentClusterBuilder.placeClusters(for: moving).first?.count, 200)
    }

    func testSameRangeMembershipAndMetadataRevisionsDismissNestedDetailsButUnchangedReloadDoesNot() throws {
        let container = try makeContainer()
        // SwiftData's context must not outlive its container during this fixture.
        defer { withExtendedLifetime(container) {} }
        let context = container.mainContext
        let photos = fixture(count: 501)
        for photo in photos { context.insert(photo) }
        try context.save()
        var identifiers = Set(photos.map(\.assetLocalIdentifier))
        let viewModel = makeViewModel(context: context, access: { .authorized }, identifiers: { _ in identifiers })
        viewModel.mapDateRange = start...start.addingTimeInterval(600)
        viewModel.loadMapPhotoMoments()
        selectEveryDetailLevel(viewModel)
        let revision = viewModel.photoPresentationRevision
        viewModel.loadMapPhotoMoments()
        XCTAssertEqual(viewModel.photoPresentationRevision, revision)
        XCTAssertNotNil(viewModel.photoDetails.browserPhoto)
        XCTAssertTrue(viewModel.photoDetails.showsPlaces)
        photos[0].lastSyncedAt = Date() // not a relevant display change
        viewModel.loadMapPhotoMoments()
        XCTAssertEqual(viewModel.photoPresentationRevision, revision)
        XCTAssertNotNil(viewModel.photoDetails.browserPhoto)

        let added = fixture(count: 1, prefix: "added", offset: 501)[0]
        context.insert(added)
        identifiers.insert(added.assetLocalIdentifier)
        try context.save()
        viewModel.loadMapPhotoMoments()
        XCTAssertEqual(viewModel.mapPhotoMomentCount, 502)
        XCTAssertEqual(viewModel.photoPresentationRevision, revision + 1)
        assertDetailsDismissed(viewModel)

        selectEveryDetailLevel(viewModel)
        photos[0].latitude = 40 // same SwiftData object, new grouping/coordinate
        try context.save()
        viewModel.loadMapPhotoMoments()
        XCTAssertEqual(viewModel.photoPresentationRevision, revision + 2)
        assertDetailsDismissed(viewModel)
        selectEveryDetailLevel(viewModel)
        viewModel.mapDateRange = start...start.addingTimeInterval(100)
        assertDetailsDismissed(viewModel)
    }

    func testLimitedSelectionObserverFiltersPresentationAndDismissesAllLevelsWithoutDeletingMetadata() async throws {
        let key = LocationViewModel.automaticPhotoSyncEnabledKey
        let previous = UserDefaults.standard.object(forKey: key)
        UserDefaults.standard.set(false, forKey: key)
        defer {
            if let previous { UserDefaults.standard.set(previous, forKey: key) }
            else { UserDefaults.standard.removeObject(forKey: key) }
        }
        let container = try makeContainer()
        defer { withExtendedLifetime(container) {} }
        let context = container.mainContext
        let photos = fixture(count: 501)
        for photo in photos { context.insert(photo) }
        try context.save()
        let original = metadataSnapshot(photos)
        var access = PhotoLibraryAccessState.authorized
        var identifiers = Set(photos.map(\.assetLocalIdentifier))
        var onRefresh: (() -> Void)?
        let viewModel = makeViewModel(context: context, access: { access }, identifiers: { _ in
            onRefresh?()
            return identifiers
        })
        viewModel.mapDateRange = start...start.addingTimeInterval(500)
        viewModel.loadMapPhotoMoments()
        selectEveryDetailLevel(viewModel)
        let revision = viewModel.photoPresentationRevision
        access = .limited // even the same ID set must invalidate on access reduction
        viewModel.loadMapPhotoMoments()
        XCTAssertEqual(viewModel.photoPresentationRevision, revision + 1)
        assertDetailsDismissed(viewModel)
        XCTAssertEqual(viewModel.mapPhotoMomentCount, 501)

        selectEveryDetailLevel(viewModel)
        identifiers = Set(photos.suffix(21).map(\.assetLocalIdentifier))
        let refreshed = expectation(description: "Existing PHChange notification refreshes accessible IDs with sync off")
        onRefresh = { refreshed.fulfill() }
        NotificationCenter.default.post(name: .photoLibraryDidChange, object: nil)
        await fulfillment(of: [refreshed], timeout: 5)
        onRefresh = nil
        XCTAssertEqual(viewModel.photoPresentationRevision, revision + 2)
        assertDetailsDismissed(viewModel)
        XCTAssertEqual(viewModel.mapPhotoMoments.count, 501)
        XCTAssertEqual(viewModel.mapPhotoMomentCount, 21)
        XCTAssertEqual(viewModel.mapAccessiblePhotoMoments.map(\.id), photos.suffix(21).map(\.id))
        assertCompleteMembership(viewModel.mapPhotoMomentClusters, photos: Array(photos.suffix(21)))
        XCTAssertEqual(viewModel.photosInDateRange(viewModel.mapDateRange).map(\.id), photos.suffix(21).map(\.id))

        let unchanged = viewModel.photoPresentationRevision
        selectEveryDetailLevel(viewModel)
        viewModel.loadMapPhotoMoments()
        XCTAssertEqual(viewModel.photoPresentationRevision, unchanged)
        XCTAssertNotNil(viewModel.photoDetails.browserPhoto)
        identifiers = []
        viewModel.loadMapPhotoMoments()
        XCTAssertTrue(viewModel.mapPhotoMomentClusters.isEmpty)
        assertDetailsDismissed(viewModel)
        access = .denied
        viewModel.loadMapPhotoMoments()
        assertDetailsDismissed(viewModel)
        access = .authorized
        identifiers = Set(photos.map(\.assetLocalIdentifier))
        viewModel.loadMapPhotoMoments()
        XCTAssertEqual(viewModel.mapPhotoMomentCount, 501)
        XCTAssertEqual(metadataSnapshot(try context.fetch(FetchDescriptor<PhotoMoment>())), original)
    }

    func testHostedBrowserRequestsActualLastFirstAndPreviousSelections() async throws {
        let photos = fixture(count: 501)
        let browser = PhotoMomentBrowserState(photo: photos[500], photos: photos)
        var requested: [String] = []
        var expectedID = photos[500].assetLocalIdentifier
        var loaded = expectation(description: "Positive hosted rendering control: last image requested")
        loaded.assertForOverFulfill = false
        let loader: PhotoThumbnailLoader = { identifier, size, mode in
            requested.append(identifier)
            XCTAssertGreaterThan(size.width, 0)
            XCTAssertEqual(mode, .aspectFit)
            if identifier == expectedID { loaded.fulfill() }
            return nil
        }
        let host = UIHostingController(rootView: PhotoMomentFullScreenView(browser: browser, loader: loader))
        let window = hostWindow(host)
        defer { window.isHidden = true; window.rootViewController = nil }
        await fulfillment(of: [loaded], timeout: 5)
        XCTAssertEqual(browser.photo.id, photos[500].id)
        for (delta, target) in [(1, 0), (-1, 500), (-1, 499)] {
            expectedID = photos[target].assetLocalIdentifier
            loaded = expectation(description: "Rendered browser requests selected ID \(target)")
            loaded.assertForOverFulfill = false
            // Exactly the observable transition called by the view's arrows/swipe.
            browser.move(by: delta)
            await fulfillment(of: [loaded], timeout: 5)
            XCTAssertEqual(browser.photo.id, photos[target].id)
            XCTAssertEqual(requested.last, expectedID)
        }
        XCTAssertEqual(Set(requested), Set([photos[0], photos[499], photos[500]].map(\.assetLocalIdentifier)))
    }

    func testHostedGridRequestsOnlyCurrentBoundedPageWithPositiveRenderingControl() async throws {
        let photos = fixture(count: 501)
        let cluster = try XCTUnwrap(PhotoMomentClusterBuilder.clusters(for: photos).first)
        let grid = PhotoMomentGridState(photos: cluster.sortedPhotos)
        var requested = Set<String>()
        var expectedID = photos[0].assetLocalIdentifier
        var loaded = expectation(description: "Positive hosted grid rendering control: first thumbnail requested")
        loaded.assertForOverFulfill = false
        let loader: PhotoThumbnailLoader = { identifier, _, _ in
            requested.insert(identifier)
            if identifier == expectedID { loaded.fulfill() }
            return nil
        }
        let host = UIHostingController(rootView: PhotoMomentClusterQuickView(cluster: cluster, grid: grid, thumbnailLoader: loader))
        let window = hostWindow(host)
        defer { window.isHidden = true; window.rootViewController = nil }
        await fulfillment(of: [loaded], timeout: 5)
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertFalse(requested.isEmpty)
        XCTAssertTrue(requested.isSubset(of: Set(photos.prefix(60).map(\.assetLocalIdentifier))))
        XCTAssertLessThanOrEqual(requested.count, 60)
        requested = []
        expectedID = photos[480].assetLocalIdentifier
        loaded = expectation(description: "Last page is actually rendered and requests its first thumbnail")
        loaded.assertForOverFulfill = false
        for _ in 0..<8 { grid.movePage(by: 1) }
        await fulfillment(of: [loaded], timeout: 5)
        try await Task.sleep(nanoseconds: 200_000_000)
        XCTAssertEqual(grid.page.index, 8)
        XCTAssertEqual(grid.page.photos.count, 21)
        XCTAssertEqual(grid.page.photos.last?.id, photos.last?.id)
        XCTAssertFalse(requested.isEmpty)
        XCTAssertTrue(requested.isSubset(of: Set(photos.suffix(21).map(\.assetLocalIdentifier))))
        XCTAssertLessThanOrEqual(requested.count, 21)
        XCTAssertEqual(grid.photos.map(\.id), photos.map(\.id))
    }

    private func makeContainer() throws -> ModelContainer {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self])
        return try ModelContainer(for: schema, configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)])
    }

    private func makeViewModel(
        context: ModelContext,
        access: @escaping @MainActor () -> PhotoLibraryAccessState,
        identifiers: @escaping @MainActor ([String]) -> Set<String>
    ) -> LocationViewModel {
        let manager = makeIsolatedLocationManager()
        manager.isTrackingEnabled = false
        return LocationViewModel(modelContext: context, locationManager: manager, photoAuthorizationProvider: access, accessiblePhotoIdentifiersProvider: identifiers)
    }

    private func selectEveryDetailLevel(_ viewModel: LocationViewModel) {
        viewModel.photoDetails.photo = viewModel.mapAccessiblePhotoMoments.first
        viewModel.photoDetails.cluster = viewModel.mapPhotoMomentClusters.first
        viewModel.photoDetails.showsPlaces = true
        viewModel.photoDetails.place = viewModel.mapPhotoPlaces.first
        viewModel.photoDetails.browserPhoto = viewModel.mapAccessiblePhotoMoments.last
    }

    private func assertDetailsDismissed(_ viewModel: LocationViewModel, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertNil(viewModel.photoDetails.photo, file: file, line: line)
        XCTAssertNil(viewModel.photoDetails.cluster, file: file, line: line)
        XCTAssertFalse(viewModel.photoDetails.showsPlaces, file: file, line: line)
        XCTAssertNil(viewModel.photoDetails.place, file: file, line: line)
        XCTAssertNil(viewModel.photoDetails.browserPhoto, file: file, line: line)
    }

    private func metadataSnapshot(_ photos: [PhotoMoment]) -> [String] {
        photos.map { "\($0.id)|\($0.assetLocalIdentifier)|\($0.takenAt.timeIntervalSince1970)|\($0.latitude)|\($0.longitude)|\($0.coordinateSourceRawValue)|\($0.lastSyncedAt.timeIntervalSince1970)" }.sorted()
    }

    private func hostWindow<Content: View>(_ host: UIHostingController<Content>) -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = host
        window.makeKeyAndVisible()
        host.view.layoutIfNeeded()
        return window
    }

    private func fixture(
        count: Int, latitude: Double = 37, prefix: String = "photo", offset: TimeInterval = 0
    ) -> [PhotoMoment] {
        (0..<count).map { index in
            PhotoMoment(
                assetLocalIdentifier: "\(prefix)-\(index)",
                takenAt: start.addingTimeInterval(offset + Double(index)),
                latitude: latitude,
                longitude: -122
            )
        }
    }

    private func assertCompleteMembership(
        _ clusters: [PhotoMomentCluster], photos: [PhotoMoment],
        file: StaticString = #filePath, line: UInt = #line
    ) {
        let members = clusters.flatMap(\.photos)
        XCTAssertEqual(members.count, photos.count, file: file, line: line)
        XCTAssertEqual(Set(members.map(\.id)), Set(photos.map(\.id)), file: file, line: line)
    }
}
