import SwiftUI

/// A shareable card summarizing one month.
struct MonthRecapView: View {
    let viewModel: LocationViewModel
    var month: Date = Date()

    @Environment(\.dismiss) private var dismiss
    @Environment(\.displayScale) private var displayScale
    @State private var monthOffset = 0
    @State private var renderedCard: UIImage?

    private var displayedMonth: Date {
        let calendar = Calendar.current
        let start = calendar.dateInterval(of: .month, for: month)?.start ?? month
        return calendar.date(byAdding: .month, value: monthOffset, to: start) ?? start
    }

    private var isCurrentOrLater: Bool {
        Calendar.current.compare(displayedMonth, to: Date(), toGranularity: .month) != .orderedAscending
    }

    var body: some View {
        let recap = MonthRecap(visits: viewModel.allVisits, month: displayedMonth)
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
        .task(id: "\(monthOffset)-\(viewModel.allVisits.count)") {
            renderedCard = nil
            renderedCard = render(recap)
        }
        .tint(TE.accent)
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
                .font(.system(size: 44, design: .serif))
                .lineSpacing(-4)
                .fixedSize(horizontal: false, vertical: true)

            bars
                .frame(height: 60)

            HStack(alignment: .top, spacing: 8) {
                stat(recap.visitCount.formatted(), label: "VISITS")
                stat(recap.newPlaces.formatted(), label: "NEW PLACES")
                stat(recap.biggestDay.map { $0.formatted(.dateTime.month(.abbreviated).day()) } ?? "—", label: "BUSIEST DAY")
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
        let maximum = max(recap.visitsPerDay.max() ?? 0, 1)
        let threshold = recap.highlightThreshold
        return HStack(alignment: .bottom, spacing: 0) {
            ForEach(Array(recap.visitsPerDay.enumerated()), id: \.offset) { _, count in
                Capsule()
                    .fill(count > 0 && count >= threshold ? TE.liveDot : TE.border)
                    .frame(width: 6, height: max(6, 60 * Double(count) / Double(maximum)))
                    .frame(maxWidth: .infinity)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Places visited each day")
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

/// One month's totals, computed from recorded visits.
private struct MonthRecap {
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
    let topPlaces: [TopPlace]

    init(visits: [Visit], month: Date, now: Date = Date(), calendar: Calendar = .current) {
        self.month = month
        let interval = calendar.dateInterval(of: .month, for: month) ?? DateInterval(start: month, duration: 0)
        dayCount = calendar.range(of: .day, in: .month, for: month)?.count ?? 30
        let inMonth = visits.filter { visit in
            let end = visit.departedAt ?? now
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
            let day = calendar.component(.day, from: start) - 1
            if perDay.indices.contains(day) { perDay[day].insert(visit.displayName) }
        }
        let counts = perDay.map(\.count)
        visitsPerDay = counts
        recordedDays = counts.filter { $0 > 0 }.count
        if let best = counts.indices.max(by: { counts[$0] < counts[$1] }), counts[best] > 0 {
            biggestDay = calendar.date(byAdding: .day, value: best, to: interval.start)
        } else {
            biggestDay = nil
        }
        topPlaces = durations.sorted { $0.value > $1.value }.prefix(3).map { TopPlace(name: $0.key, duration: $0.value) }

        // A place is new when its first-ever visit falls in this month.
        var firstSeen: [String: Date] = [:]
        for visit in visits {
            if let seen = firstSeen[visit.displayName], seen <= visit.arrivedAt { continue }
            firstSeen[visit.displayName] = visit.arrivedAt
        }
        newPlaces = firstSeen.values.filter { interval.contains($0) }.count
    }

    /// The top fifth of days by places visited are highlighted.
    var highlightThreshold: Int {
        let sorted = visitsPerDay.filter { $0 > 0 }.sorted(by: >)
        guard !sorted.isEmpty else { return .max }
        return sorted[min(sorted.count - 1, max(0, sorted.count / 5))]
    }

    var daysLine: String {
        recordedDays == 1 ? String(localized: "1 day,") : String(localized: "\(recordedDays) days,")
    }

    var placesLine: String {
        placeCount == 1 ? String(localized: "1 place,") : String(localized: "\(placeCount) places,")
    }

    var closingLine: String {
        switch countryCount {
        case 0: visitCount == 1 ? String(localized: "one visit.") : String(localized: "\(visitCount) visits.")
        case 1: String(localized: "one country.")
        default: String(localized: "\(countryCount) countries.")
        }
    }

    var shareTitle: String {
        String(localized: "Whereday · \(month.formatted(.dateTime.month(.wide).year()))")
    }
}
