import SwiftUI
import SwiftData
import CoreLocation

/// A shareable card summarizing one month.
struct MonthRecapView: View {
    let viewModel: LocationViewModel
    var month: Date = Date()

    @Environment(\.dismiss) private var dismiss
    @Environment(\.displayScale) private var displayScale
    @Environment(\.modelContext) private var modelContext
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    @ObservedObject private var localities = LocalityResolver.shared
    @State private var monthOffset = 0
    @State private var renderedCard: UIImage?
    @State private var monthPoints: [LocationPoint] = []
    @State private var loadedPointsMonth: Date?

    private var displayedMonth: Date {
        let calendar = Calendar.current
        let start = calendar.dateInterval(of: .month, for: month)?.start ?? month
        return calendar.date(byAdding: .month, value: monthOffset, to: start) ?? start
    }

    private var isCurrentOrLater: Bool {
        Calendar.current.compare(displayedMonth, to: Date(), toGranularity: .month) != .orderedAscending
    }

    var body: some View {
        let recap = MonthRecap(
            visits: viewModel.allVisits,
            points: loadedPointsMonth == displayedMonth ? monthPoints : [],
            month: displayedMonth,
            usesMetric: usesMetricDistanceUnits,
            locality: { localities.locality(latitude: $0, longitude: $1) }
        )
        ScrollView {
            VStack(spacing: 18) {
                HStack {
                    Button { monthOffset -= 1 } label: {
                        Image(systemName: "chevron.left").frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("Previous month")
                    Spacer()
                    Text(displayedMonth.formatted(.dateTime.month(.wide).year()))
                        .font(TE.editorial(.title3))
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button { monthOffset += 1 } label: {
                        Image(systemName: "chevron.right").frame(width: 44, height: 44)
                    }
                    .disabled(isCurrentOrLater)
                    .accessibilityLabel("Next month")
                }
                .buttonStyle(.plain)
                .foregroundStyle(TE.textPrimary)

                MonthRecapCard(recap: recap)
                    .environment(\.colorScheme, .dark)
                    .clipShape(RoundedRectangle(cornerRadius: 18))

                if let renderedCard {
                    let image = Image(uiImage: renderedCard)
                    ShareLink(item: image, preview: SharePreview(recap.shareTitle, image: image)) {
                        Label("Share recap", systemImage: "square.and.arrow.up")
                    }
                    .buttonStyle(PlacesInkButtonStyle(emphasis: .primary))
                } else {
                    ProgressView()
                        .frame(minHeight: 48)
                }

                Text("The shared image shows place names and totals only. It never includes map coordinates.")
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
        .background(TE.surface.ignoresSafeArea())
        .navigationTitle("Month Recap")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            }
        }
        .task(id: monthOffset) {
            loadPoints(for: displayedMonth)
            localities.request(recap.visitCoordinates)
        }
        .task(id: "\(monthOffset)-\(viewModel.allVisits.count)-\(monthPoints.count)-\(localities.revision)") {
            renderedCard = nil
            renderedCard = render(recap)
        }
        .tint(TE.accent)
    }

    /// Loads the month's kept route points, used for distance moved.
    private func loadPoints(for month: Date) {
        let interval = Calendar.current.dateInterval(of: .month, for: month) ?? DateInterval(start: month, duration: 0)
        let start = interval.start
        let end = interval.end
        var descriptor = FetchDescriptor<LocationPoint>(
            predicate: #Predicate { $0.timestamp >= start && $0.timestamp < end && !$0.isOutlier },
            sortBy: [SortDescriptor(\.timestamp)]
        )
        descriptor.propertiesToFetch = [\.latitude, \.longitude, \.timestamp]
        monthPoints = (try? modelContext.fetch(descriptor)) ?? []
        loadedPointsMonth = month
    }

    @MainActor
    private func render(_ recap: MonthRecap) -> UIImage? {
        let renderer = ImageRenderer(content:
            MonthRecapCard(recap: recap)
                .frame(width: 390)
                .environment(\.colorScheme, .dark)
                .environment(\.dynamicTypeSize, .large)
        )
        renderer.scale = max(displayScale, 2)
        return renderer.uiImage
    }
}

/// The card itself, drawn in the night palette so it reads the same wherever it's shared.
private struct MonthRecapCard: View {
    let recap: MonthRecap

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .firstTextBaseline) {
                Text("Whereday")
                    .font(TE.editorial(.title3))
                    .italic()
                Spacer()
                Text(recap.month.formatted(.dateTime.month(.wide).year()).uppercased())
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
            }

            (Text(recap.daysLine) + Text("\n") + Text(recap.placesLine) + Text("\n")
                + Text(recap.closingLine).foregroundStyle(TE.inkRust).italic())
                .font(WheredayFont.serif(size: 44, relativeTo: .largeTitle))
                .lineSpacing(-4)
                .fixedSize(horizontal: false, vertical: true)

            bars
                .frame(height: 60)

            HStack(alignment: .top, spacing: 8) {
                if recap.hasDistance {
                    stat(recap.movedText, label: "MOVED")
                } else {
                    stat(recap.visitCount.formatted(), label: "VISITS")
                }
                stat(recap.newPlaces.formatted(), label: "NEW PLACES")
                stat(recap.biggestDay.map { $0.formatted(.dateTime.month(.abbreviated).day()) } ?? "—", label: "BIGGEST DAY")
            }

            VStack(spacing: 0) {
                ForEach(Array(recap.topPlaces.enumerated()), id: \.offset) { index, place in
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(String(format: "%02d", index + 1))
                            .font(TE.mono(.caption2))
                            .foregroundStyle(TE.textMuted)
                            .frame(width: 22, alignment: .leading)
                        Text(place.name)
                            .font(TE.editorial(.title3))
                            .lineLimit(1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                        Text(PlaceRhythm.formatHours(place.duration))
                            .font(TE.mono(.caption))
                            .foregroundStyle(TE.textMuted)
                    }
                    .padding(.vertical, 8)
                    .overlay(alignment: .bottom) { Rectangle().fill(TE.border).frame(height: 1) }
                }
            }

            Text("Made on-device · no map coordinates included")
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
                .padding(.top, 8)
        }
        .foregroundStyle(TE.textPrimary)
        .padding(.horizontal, 28)
        .padding(.top, 36)
        .padding(.bottom, 28)
        .background(TE.surface)
        .accessibilityElement(children: .combine)
    }

    private var bars: some View {
        let values = recap.barValues
        let maximum = max(values.max() ?? 0, 1)
        let threshold = recap.highlightThreshold
        let monthName = recap.month.formatted(.dateTime.month(.wide))
        return HStack(alignment: .bottom, spacing: 0) {
            ForEach(Array(values.enumerated()), id: \.offset) { _, value in
                Capsule()
                    .fill(value > 0 && value >= threshold ? TE.liveDot : TE.border)
                    .frame(width: 6, height: max(6, 60 * value / maximum))
                    .frame(maxWidth: .infinity)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(recap.hasDistance
            ? String(localized: "Distance moved each day in \(monthName)")
            : String(localized: "Places visited each day in \(monthName)"))
    }

    private func stat(_ value: String, label: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(value)
                .font(TE.editorial(.title))
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// One month's totals, computed from recorded visits and route points.
struct MonthRecap {
    struct TopPlace {
        let name: String
        let duration: TimeInterval
    }

    let month: Date
    let dayCount: Int
    let recordedDays: Int
    let placeCount: Int
    let countryCount: Int
    let visitCount: Int
    let newPlaces: Int
    let biggestDay: Date?
    let visitsPerDay: [Int]
    /// Metres moved on each day of the month, from consecutive kept route points.
    let distancePerDay: [Double]
    let totalDistance: Double
    let cityCount: Int
    let visitCoordinates: [CLLocationCoordinate2D]
    let usesMetric: Bool
    let topPlaces: [TopPlace]

    init(
        visits: [Visit],
        points: [LocationPoint] = [],
        month: Date,
        usesMetric: Bool = true,
        locality: (Double, Double) -> String? = { _, _ in nil },
        now: Date = Date(),
        calendar: Calendar = .current
    ) {
        self.month = month
        self.usesMetric = usesMetric
        let interval = calendar.dateInterval(of: .month, for: month) ?? DateInterval(start: month, duration: 0)
        dayCount = calendar.range(of: .day, in: .month, for: month)?.count ?? 30
        let validVisits = visits.filter { visit in
            visit.arrivedAt <= now && (visit.departedAt ?? now) >= visit.arrivedAt
        }
        let inMonth = validVisits.filter { visit in
            let end = min(visit.departedAt ?? now, now)
            return visit.arrivedAt < interval.end && end > interval.start
        }
        visitCount = inMonth.count
        placeCount = Set(inMonth.map(\.displayName)).count
        countryCount = Set(inMonth.compactMap(\.countryCode)).count

        var perDay = Array(repeating: Set<String>(), count: dayCount)
        var durations: [String: TimeInterval] = [:]
        for visit in inMonth {
            let start = max(visit.arrivedAt, interval.start)
            let end = min(visit.departedAt ?? now, interval.end, now)
            durations[visit.displayName, default: 0] += max(0, end.timeIntervalSince(start))
            // Occupancy is [arrival, departure): a midnight departure belongs
            // only to the preceding day. Calendar arithmetic also handles DST.
            var dayStart = calendar.startOfDay(for: start)
            repeat {
                let day = calendar.component(.day, from: dayStart) - 1
                if perDay.indices.contains(day) { perDay[day].insert(visit.displayName) }
                guard let nextDay = calendar.date(byAdding: .day, value: 1, to: dayStart),
                      nextDay > dayStart else { break }
                dayStart = nextDay
            } while dayStart < end
        }
        let counts = perDay.map(\.count)
        visitsPerDay = counts

        var distances = Array(repeating: 0.0, count: dayCount)
        var pointDays = Set<Int>()
        let ordered = points.filter {
            $0.timestamp >= interval.start && $0.timestamp < interval.end
                && $0.timestamp <= now && !$0.isOutlier
        }.sorted { $0.timestamp < $1.timestamp }
        for (index, point) in ordered.enumerated() {
            let day = calendar.component(.day, from: point.timestamp) - 1
            guard distances.indices.contains(day) else { continue }
            pointDays.insert(day)
            guard index > 0 else { continue }
            let previous = ordered[index - 1]
            // Movement is attributed to the day it ends on, only within one day.
            guard calendar.isDate(previous.timestamp, inSameDayAs: point.timestamp) else { continue }
            distances[day] += previous.distance(to: point)
        }
        distancePerDay = distances
        totalDistance = distances.reduce(0, +)
        recordedDays = counts.indices.filter { counts[$0] > 0 || pointDays.contains($0) }.count

        let ranking: [Double] = totalDistance > 0 ? distances : counts.map(Double.init)
        if let best = ranking.indices.max(by: { ranking[$0] < ranking[$1] }), ranking[best] > 0 {
            biggestDay = calendar.date(byAdding: .day, value: best, to: interval.start)
        } else {
            biggestDay = nil
        }

        visitCoordinates = inMonth.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
        cityCount = Set(inMonth.compactMap { locality($0.latitude, $0.longitude) }).count
        topPlaces = durations.sorted { $0.value > $1.value }.prefix(3).map { TopPlace(name: $0.key, duration: $0.value) }

        // A place is new when its first-ever visit falls in this month.
        var firstSeen: [String: Date] = [:]
        for visit in validVisits {
            if let seen = firstSeen[visit.displayName], seen <= visit.arrivedAt { continue }
            firstSeen[visit.displayName] = visit.arrivedAt
        }
        newPlaces = firstSeen.values.filter { $0 >= interval.start && $0 < interval.end }.count
    }

    var hasDistance: Bool { totalDistance > 0 }

    /// Distance per day when route points exist, otherwise places visited per day.
    var barValues: [Double] {
        hasDistance ? distancePerDay : visitsPerDay.map(Double.init)
    }

    var movedText: String {
        DistanceFormatter.format(meters: totalDistance, usesMetric: usesMetric)
    }

    /// The top fifth of days are highlighted.
    var highlightThreshold: Double {
        let sorted = barValues.filter { $0 > 0 }.sorted(by: >)
        guard !sorted.isEmpty else { return .infinity }
        return sorted[min(sorted.count - 1, max(0, sorted.count / 5))]
    }

    var daysLine: String {
        recordedDays == 1 ? String(localized: "1 day,") : String(localized: "\(recordedDays) days,")
    }

    var placesLine: String {
        placeCount == 1 ? String(localized: "1 place,") : String(localized: "\(placeCount) places,")
    }

    var closingLine: String {
        if cityCount == 1 { return String(localized: "one city.") }
        if cityCount > 1 { return String(localized: "\(cityCount) cities.") }
        return switch countryCount {
        case 0: visitCount == 1 ? String(localized: "one visit.") : String(localized: "\(visitCount) visits.")
        case 1: String(localized: "one country.")
        default: String(localized: "\(countryCount) countries.")
        }
    }

    var shareTitle: String {
        String(localized: "Whereday · \(month.formatted(.dateTime.month(.wide).year()))")
    }
}
