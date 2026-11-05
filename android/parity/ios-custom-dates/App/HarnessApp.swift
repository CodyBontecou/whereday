import SwiftUI
// The UI test sets each case's zone through TZ, which Calendar.current reads at launch.
@main struct DateParityApp: App {
    var body: some Scene { WindowGroup { Harness() } }
}
struct Harness: View {
    @State private var presented = true
    @State private var applied = 0
    @State private var dates: ClosedRange<Date> = {
        let parse = ISO8601DateFormatter(); parse.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let first = UserDefaults.standard.string(forKey: "first"), let last = UserDefaults.standard.string(forKey: "last") else {
            return Calendar.current.startOfDay(for: Date())...Date()
        }
        return parse.date(from: first)!...parse.date(from: last)!
    }()
    var body: some View {
        VStack {
            // Native reference-date seconds, printed round-trip exact (the closed upper bound is a nextDown).
            Text(verbatim: "\(applied)|\(dates.lowerBound.timeIntervalSinceReferenceDate)|\(dates.upperBound.timeIntervalSinceReferenceDate)").accessibilityIdentifier("result")
            Button("Open") { presented = true }
        }
        .sheet(isPresented: $presented) {
            DateRangeFilterSheet(dateRange: $dates, isPresented: $presented, onApply: { _ in applied += 1 })
        }
    }
}
