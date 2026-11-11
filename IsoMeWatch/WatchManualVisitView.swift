import SwiftUI

struct WatchManualVisitView: View {
    @ObservedObject var client: WatchManualVisitClient
    @Environment(\.dismiss) private var dismiss
    @State private var selectedPlaceID: UUID?
    @State private var selectedVisitID: UUID?
    @State private var name = ""
    @State private var allowOverlap = false
    @State private var locating = false
    @State private var locationError: String?
    @State private var locationTask: Task<Void, Never>?

    private var manualVisits: [WatchVisitSummary] {
        (client.queue.snapshot?.status.openVisits ?? []).filter { $0.source == "manual" }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("From iPhone") {
                    if let snapshot = client.queue.snapshot {
                        if snapshot.status.openVisits.isEmpty { Text("No open visits on iPhone.") }
                        ForEach(snapshot.status.openVisits) { visit in
                            VStack(alignment: .leading) {
                                Text(visit.name)
                                Text("\(sourceLabel(visit.source)) · \(confirmationLabel(visit.confirmationStatus))")
                                    .font(.caption2).foregroundStyle(.secondary)
                                Text(visit.arrivedAt, style: .time).font(.caption2)
                            }
                        }
                        Text(snapshot.status.generatedAt, style: .relative).font(.caption2).foregroundStyle(.secondary)
                        if snapshot.status.openManualVisitCount > manualVisits.count {
                            Text("Open iso.me on iPhone to see more visits.").font(.caption2)
                        }
                    } else {
                        Text("Open iso.me on iPhone to refresh places and visits.")
                    }
                }
                Section("Check In") {
                    Picker("Place", selection: $selectedPlaceID) {
                        Text("Current location").tag(nil as UUID?)
                        ForEach(client.queue.snapshot?.savedPlaces ?? []) { place in
                            Text(place.name).tag(Optional(place.id))
                        }
                    }
                    .accessibilityIdentifier("watchManualPlace")
                    if selectedPlaceID == nil {
                        TextField("Place Name", text: $name)
                            .accessibilityIdentifier("watchManualPlaceName")
                    }
                    Toggle("Allow Overlap", isOn: $allowOverlap)
                        .accessibilityIdentifier("watchManualAllowOverlap")
                    Text("Enable only when you want to keep overlapping visits.").font(.caption2).foregroundStyle(.secondary)
                    Button {
                        locationError = nil
                        if let selectedPlaceID {
                            client.checkIn(name: name, savedPlaceID: selectedPlaceID, location: nil, allowOverlap: allowOverlap)
                        } else {
                            locating = true
                            let tappedAt = Date(), requestedName = name, requestedOverlap = allowOverlap
                            locationTask = Task {
                                defer { locating = false }
                                do {
                                    let location = try await WatchManualLocation().location()
                                    try Task.checkCancellation()
                                    client.checkIn(name: requestedName, savedPlaceID: nil, location: location,
                                        allowOverlap: requestedOverlap, at: tappedAt)
                                } catch is CancellationError { /* Dismissing cancels an unfinished location request. */ }
                                catch { locationError = error.localizedDescription }
                            }
                        }
                    } label: {
                        if locating { ProgressView() } else { Label("Check In", systemImage: "mappin.and.ellipse") }
                    }
                    .disabled(locating || client.queue.pendingCheckIn != nil || (selectedPlaceID == nil && name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty))
                    .accessibilityIdentifier("watchManualCheckIn")
                    if let locationError { Text(locationError).font(.caption2).foregroundStyle(.red) }
                }
                Section("Check Out") {
                    if let pending = client.queue.pendingCheckIn {
                        Button("Check Out Queued Check In") {
                            client.checkOut(visitID: pending.id, dependsOn: pending.id)
                        }
                        .accessibilityIdentifier("watchManualQueuedCheckout")
                        Text("Both commands keep the times you tapped, even while offline.").font(.caption2).foregroundStyle(.secondary)
                    }
                    if !manualVisits.isEmpty {
                        Picker("Visit", selection: $selectedVisitID) {
                            Text("Choose Visit").tag(nil as UUID?)
                            ForEach(manualVisits) { visit in
                                Text("\(visit.name) · \(visit.arrivedAt.formatted(date: .abbreviated, time: .shortened))")
                                    .tag(Optional(visit.id))
                            }
                        }
                        Button("Check Out") { client.checkOut(visitID: selectedVisitID) }
                            .disabled(selectedVisitID == nil)
                            .accessibilityIdentifier("watchManualCheckOut")
                    } else if client.queue.pendingCheckIn == nil {
                        Text("There is no open manual visit to check out.")
                    }
                }
                Section("Commands") {
                    ForEach(Array(client.queue.entries.suffix(3).reversed())) { entry in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(entry.command.kind == .checkIn ? "Check In" : "Check Out").font(.headline)
                            Text(entry.isPending ? "Pending iPhone confirmation" : (entry.result?.outcome == .succeeded ? "Completed" : "Failed"))
                                .font(.caption).foregroundStyle(entry.result?.outcome == .failed ? .red : .secondary)
                            if let message = entry.result?.message { Text(message).font(.caption2) }
                        }
                    }
                    if let error = client.errorMessage { Text(error).font(.caption2).foregroundStyle(.red) }
                    Button("Refresh / Retry Pending") { client.sync() }
                        .accessibilityIdentifier("watchManualRetry")
                    Text("Delivery is not completion. Queued commands remain here until iPhone confirms the save.")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Manual Visits")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
            .task { client.sync() }
            .onDisappear { locationTask?.cancel() }
            .onChange(of: manualVisits.map(\.id), initial: true) { _, ids in
                if let selectedVisitID, !ids.contains(selectedVisitID) { self.selectedVisitID = nil }
                if selectedVisitID == nil, ids.count == 1 { selectedVisitID = ids[0] }
            }
        }
    }

    private func sourceLabel(_ source: String) -> String {
        switch source {
        case "manual": return String(localized: "Manual")
        case "imported": return String(localized: "Imported")
        default: return String(localized: "Automatic")
        }
    }
    private func confirmationLabel(_ status: String) -> String {
        switch status {
        case "confirmed": return String(localized: "Confirmed")
        case "corrected": return String(localized: "Corrected")
        default: return String(localized: "Unconfirmed")
        }
    }
}
