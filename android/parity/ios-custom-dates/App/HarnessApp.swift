import SwiftUI
@main struct DateParityApp: App {
    var body: some Scene { WindowGroup { Harness() } }
}
struct Harness: View {
    @State private var presented = true
    @State private var dates: ClosedRange<Date> = {
        let parse = ISO8601DateFormatter(); parse.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return parse.date(from: UserDefaults.standard.string(forKey: "first") ?? "2026-10-10T18:12:34.125Z")!...parse.date(from: UserDefaults.standard.string(forKey: "last") ?? "2026-10-12T20:34:56.875Z")!
    }()
    var body: some View {
        VStack {
            Text(String(format:"%.3f|%.3f",dates.lowerBound.timeIntervalSince1970,dates.upperBound.timeIntervalSince1970)).accessibilityIdentifier("result")
            Button("Open") { presented = true }
        }
        .sheet(isPresented: $presented) { DateRangeFilterSheet(dateRange: $dates, isPresented: $presented) }
        .environment(\.timeZone, TimeZone(identifier: UserDefaults.standard.string(forKey: "zone") ?? "UTC")!)
        .environment(\.locale, Locale(identifier: "en_US"))
    }
}
