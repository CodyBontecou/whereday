import SwiftUI
import CoreLocation

/// What a map search can land on.
enum MapSearchResult: Identifiable {
    case place(SavedPlace)
    case visit(Visit)

    var id: String {
        switch self {
        case .place(let place): "place-\(place.id.uuidString)"
        case .visit(let visit): "visit-\(visit.id.uuidString)"
        }
    }
}

/// Searches saved places and visits by name or address; a result is focused on the map.
struct MapSearchSheet: View {
    @Environment(\.dismiss) private var dismiss
    let viewModel: LocationViewModel
    let onSelect: (MapSearchResult) -> Void
    @State private var query = ""
    @FocusState private var isFocused: Bool

    private var trimmed: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }

    private var places: [SavedPlace] {
        let all = viewModel.savedPlaces.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        guard !trimmed.isEmpty else { return all }
        return all.filter { $0.name.localizedCaseInsensitiveContains(trimmed) || ($0.address?.localizedCaseInsensitiveContains(trimmed) ?? false) }
    }

    /// The latest visits that match, one per place name, newest first.
    private var visits: [Visit] {
        var seen = Set<String>()
        let matching = viewModel.allVisits
            .sorted { $0.arrivedAt > $1.arrivedAt }
            .filter { visit in
                trimmed.isEmpty || visit.displayName.localizedCaseInsensitiveContains(trimmed)
                    || (visit.address?.localizedCaseInsensitiveContains(trimmed) ?? false)
            }
        return Array(matching.filter { seen.insert($0.displayName).inserted }.prefix(40))
    }

    var body: some View {
        NavigationStack {
            List {
                if !places.isEmpty {
                    Section {
                        ForEach(places) { place in
                            Button { choose(.place(place)) } label: {
                                resultRow(title: place.name, detail: place.address ?? "", systemImage: "star")
                            }
                        }
                    } header: { sectionHeader("SAVED PLACES") }
                }
                if !visits.isEmpty {
                    Section {
                        ForEach(visits) { visit in
                            Button { choose(.visit(visit)) } label: {
                                resultRow(
                                    title: visit.displayName,
                                    detail: visit.arrivedAt.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day().year()),
                                    systemImage: "mappin"
                                )
                            }
                        }
                    } header: { sectionHeader("VISITS") }
                }
                if places.isEmpty && visits.isEmpty {
                    Text(trimmed.isEmpty ? "Nothing saved yet." : "Nothing matches “\(trimmed)”.")
                        .font(TE.editorial(.body))
                        .foregroundStyle(TE.textMuted)
                        .listRowBackground(Color.clear)
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(TE.surface.ignoresSafeArea())
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: Text("Places and visits"))
            .navigationTitle(Text("Search the map"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func choose(_ result: MapSearchResult) {
        dismiss()
        onSelect(result)
    }

    private func sectionHeader(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .font(TE.mono(.caption2, weight: .medium))
            .tracking(1.4)
            .foregroundStyle(TE.textMuted)
    }

    private func resultRow(title: String, detail: String, systemImage: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .foregroundStyle(TE.textMuted)
                .frame(width: 22)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(TE.editorial(.title3))
                    .foregroundStyle(TE.textPrimary)
                if !detail.isEmpty {
                    Text(detail)
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.textMuted)
                        .lineLimit(1)
                }
            }
        }
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }
}
