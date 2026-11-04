import SwiftUI
import MapKit

/// A map view that displays a session's recorded path in navy ink, with a green
/// start dot and a red end square as on the Route artboard
struct SessionPathMapView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let points: [LocationPoint]
    let replaySnapshot: RouteReplaySnapshot?
    let roadSnappedRoute: RoadSnappedRoute?
    let photoMoments: [PhotoMoment]
    let showsStraightLineSegments: Bool
    let showsRawPathFallback: Bool
    @State private var cameraPosition: MapCameraPosition = .automatic

    init(
        points: [LocationPoint],
        replaySnapshot: RouteReplaySnapshot? = nil,
        roadSnappedRoute: RoadSnappedRoute? = nil,
        photoMoments: [PhotoMoment] = [],
        showsStraightLineSegments: Bool = false,
        showsRawPathFallback: Bool = true
    ) {
        self.points = points
        self.replaySnapshot = replaySnapshot
        self.roadSnappedRoute = roadSnappedRoute
        self.photoMoments = photoMoments
        self.showsStraightLineSegments = showsStraightLineSegments
        self.showsRawPathFallback = showsRawPathFallback
    }
    
    var body: some View {
        Map(position: $cameraPosition) {
            // Draw road-snapped route segments when available. During replay,
            // only draw the route up to the current playhead so scrubbing mirrors
            // the main map without drawing straight chords between sparse dots.
            if let roadSnappedRoute, roadSnappedRoute.hasSnappedSegments {
                ForEach(displayedRoadSegments(from: roadSnappedRoute)) { segment in
                    MapPolyline(coordinates: segment.coordinates)
                        .stroke(
                            TE.textPrimary,
                            style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
                        )
                }
            } else if showsRawPathFallback, displayedPoints.count >= 2 {
                MapPolyline(coordinates: displayedPoints.map { $0.coordinate })
                    .stroke(
                        TE.textPrimary,
                        style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
                    )
            }
            
            // Start marker (first point)
            if let firstPoint = points.first {
                Annotation(String(localized: "Path start"), coordinate: firstPoint.coordinate) {
                    StartMarker(
                        accessibilityLabel: String(localized: "Path start"),
                        accessibilityValue: firstPoint.accessibilityValue
                    )
                }
            }

            if let replaySnapshot {
                Annotation(String(localized: "Route replay position"), coordinate: replaySnapshot.currentPoint.coordinate) {
                    RouteReplayMarker(snapshot: replaySnapshot)
                }
            } else if let lastPoint = points.last, points.count > 1 {
                // End/Current marker (last point)
                Annotation(String(localized: "Current location"), coordinate: lastPoint.coordinate) {
                    CurrentLocationMarker(
                        accessibilityLabel: String(localized: "Current location"),
                        accessibilityValue: lastPoint.accessibilityValue
                    )
                }
            }
            
            // Intermediate point markers (show every nth point for clarity)
            ForEach(intermediatePoints) { point in
                Annotation("", coordinate: point.coordinate) {
                    Circle()
                        .fill(TE.surface)
                        .frame(width: 6, height: 6)
                        .overlay(Circle().strokeBorder(TE.textPrimary, lineWidth: 1.5))
                        .accessibilityHidden(true)
                }
            }

            ForEach(photoMoments) { photo in
                Annotation("Photo", coordinate: photo.coordinate) {
                    PhotoMomentMiniMarker(photo: photo)
                }
            }
        }
        .mapStyle(.standard(elevation: .flat, emphasis: .muted, pointsOfInterest: .excludingAll))
        .mapControls {
            // Minimal controls for the mini map
        }
        .onChange(of: points.count) { _, _ in
            updateCameraPosition()
        }
        .onChange(of: roadSnappedRoute?.sourceFingerprint) { _, _ in
            updateCameraPosition()
        }
        .onChange(of: photoMoments.count) { _, _ in
            updateCameraPosition()
        }
        .onAppear {
            updateCameraPosition()
        }
        .accessibilityLabel("Session path map")
        .accessibilityValue(sessionAccessibilitySummary)
    }
    
    private var displayedPoints: [LocationPoint] {
        replaySnapshot?.visiblePoints ?? points
    }

    private func displayedRoadSegments(from route: RoadSnappedRoute) -> [RoadSnappedRouteSegment] {
        let segments: [RoadSnappedRouteSegment]
        if let replaySnapshot {
            segments = route.segments(upTo: replaySnapshot.index)
        } else {
            segments = route.segments
        }

        guard !showsStraightLineSegments else { return segments }
        return segments.filter(\.isSnapped)
    }

    private var cameraCoordinates: [CLLocationCoordinate2D] {
        if let roadSnappedRoute, roadSnappedRoute.hasSnappedSegments {
            let routeCoordinates = roadSnappedRoute.segments.flatMap(\.coordinates)
            if !routeCoordinates.isEmpty { return routeCoordinates }
        }

        return points.map { $0.coordinate } + photoMoments.map { $0.coordinate }
    }

    /// Returns intermediate points (every 10th point, excluding first and last)
    private var intermediatePoints: [LocationPoint] {
        guard displayedPoints.count > 2 else { return [] }
        
        let step = max(1, displayedPoints.count / 10) // Show roughly 10 intermediate markers
        var result: [LocationPoint] = []
        
        for i in stride(from: step, to: displayedPoints.count - 1, by: step) {
            result.append(displayedPoints[i])
        }
        
        return result
    }
    
    private func updateCameraPosition() {
        let coordinates = cameraCoordinates
        guard !coordinates.isEmpty else { return }
        
        let region = MKCoordinateRegion(coordinates: coordinates, padding: 1.3)
        
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.3)) {
            cameraPosition = .region(region)
        }
    }

    private var sessionAccessibilitySummary: String {
        if let replaySnapshot {
            return String(localized: "Route replay. \(replaySnapshot.accessibilitySummary)")
        }

        guard let first = points.first else {
            return String(localized: "No path points.")
        }

        var parts = ["\(points.count) \(points.count == 1 ? "point" : "points")"]
        if !photoMoments.isEmpty {
            parts.append("\(photoMoments.count) \(photoMoments.count == 1 ? "matched photo" : "matched photos")")
        }
        parts.append("Started \(first.accessibilityTimestamp)")

        if let last = points.last, points.count > 1 {
            parts.append("Ended \(last.accessibilityTimestamp)")
            parts.append("Distance \(formattedDistance(totalDistance))")
        }

        return parts.joined(separator: ". ")
    }

    private var totalDistance: Double {
        guard points.count > 1 else { return 0 }
        var distance: Double = 0
        for index in 1..<points.count {
            distance += points[index - 1].distance(to: points[index])
        }
        return distance
    }

    private func formattedDistance(_ meters: Double) -> String {
        let formatter = MeasurementFormatter()
        formatter.unitStyle = .long
        formatter.unitOptions = .providedUnit
        formatter.numberFormatter.maximumFractionDigits = meters >= 1_000 ? 1 : 0
        let distance = meters >= 1_000
            ? Measurement(value: meters / 1_000, unit: UnitLength.kilometers)
            : Measurement(value: meters, unit: UnitLength.meters)
        return formatter.string(from: distance)
    }
}

// MARK: - Marker Views

struct StartMarker: View {
    var accessibilityLabel = String(localized: "Path start")
    var accessibilityValue = ""

    var body: some View {
        Circle()
            .fill(TE.placeInks[3])
            .frame(width: 16, height: 16)
            .overlay(Circle().strokeBorder(TE.surface, lineWidth: 3))
            .shadow(color: .black.opacity(0.18), radius: 2, y: 1)
            .frame(width: 44, height: 44)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(accessibilityLabel)
            .accessibilityValue(accessibilityValue)
    }
}

/// The path's latest point: a red ink square, pulsing softly while the session is live.
struct CurrentLocationMarker: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var accessibilityLabel = String(localized: "Current location")
    var accessibilityValue = ""
    @State private var isPulsing = false
    
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 5, style: .continuous)
                .fill(TE.liveDot.opacity(0.35))
                .frame(width: 28, height: 28)
                .scaleEffect(isPulsing ? 1.4 : 1.0)
                .opacity(isPulsing ? 0 : 0.6)
                .accessibilityHidden(true)

            RoundedRectangle(cornerRadius: 3, style: .continuous)
                .fill(TE.placeInks[1])
                .frame(width: 18, height: 18)
                .overlay(
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .strokeBorder(TE.surface, lineWidth: 3)
                )
                .shadow(color: .black.opacity(0.18), radius: 2, y: 1)
                .accessibilityHidden(true)
        }
        .frame(width: 44, height: 44)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityValue(accessibilityValue)
        .onAppear {
            updatePulseAnimation(reduceMotion: reduceMotion)
        }
        .onChange(of: reduceMotion) { _, shouldReduceMotion in
            updatePulseAnimation(reduceMotion: shouldReduceMotion)
        }
    }

    private func updatePulseAnimation(reduceMotion: Bool) {
        guard !reduceMotion else {
            isPulsing = false
            return
        }

        isPulsing = false
        withAnimation(
            .easeInOut(duration: 1.5)
            .repeatForever(autoreverses: false)
        ) {
            isPulsing = true
        }
    }
}

// MARK: - MKCoordinateRegion Extension

extension MKCoordinateRegion {
    init(coordinates: [CLLocationCoordinate2D], padding: Double = 1.5) {
        guard !coordinates.isEmpty else {
            self = MKCoordinateRegion()
            return
        }
        
        var minLat = coordinates[0].latitude
        var maxLat = coordinates[0].latitude
        var minLon = coordinates[0].longitude
        var maxLon = coordinates[0].longitude
        
        for coordinate in coordinates {
            minLat = min(minLat, coordinate.latitude)
            maxLat = max(maxLat, coordinate.latitude)
            minLon = min(minLon, coordinate.longitude)
            maxLon = max(maxLon, coordinate.longitude)
        }
        
        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2,
            longitude: (minLon + maxLon) / 2
        )
        
        let latDelta = max(0.005, (maxLat - minLat) * padding)
        let lonDelta = max(0.005, (maxLon - minLon) * padding)
        
        let span = MKCoordinateSpan(
            latitudeDelta: latDelta,
            longitudeDelta: lonDelta
        )
        
        self = MKCoordinateRegion(center: center, span: span)
    }
}

#Preview {
    SessionPathMapView(points: [
        LocationPoint(latitude: 37.7749, longitude: -122.4194, timestamp: Date().addingTimeInterval(-300), horizontalAccuracy: 5),
        LocationPoint(latitude: 37.7755, longitude: -122.4180, timestamp: Date().addingTimeInterval(-240), horizontalAccuracy: 5),
        LocationPoint(latitude: 37.7760, longitude: -122.4170, timestamp: Date().addingTimeInterval(-180), horizontalAccuracy: 5),
        LocationPoint(latitude: 37.7770, longitude: -122.4160, timestamp: Date().addingTimeInterval(-120), horizontalAccuracy: 5),
        LocationPoint(latitude: 37.7780, longitude: -122.4150, timestamp: Date(), horizontalAccuracy: 5)
    ])
    .frame(height: 300)
}
