import SwiftUI
import MapKit

// Moved from MapView.swift: the tapped-visit sheet (board M5).

/// A tapped visit (board M5): its status, name and times, the nearest places numbered to match their pins
/// on the map ("Where were you?"), and Not here / Details / Confirm.
struct VisitQuickView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Bindable var visit: Visit
    @Bindable var viewModel: LocationViewModel
    /// Nearby places the map has already pinned (numbered 1, 2, 3); nil looks them up here instead.
    var candidates: [NearbyPlaceSuggestion]? = nil
    @State private var checkoutError: String?
    @State private var nameText: String = ""
    @State private var showingPlaceSearch = false
    @FocusState private var isNameFieldFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    header
                    whereWereYou
                    actions

                    if let notes = visit.notes, !notes.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("NOTE")
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1.2)
                                .foregroundStyle(TE.textMuted)
                            Text(notes)
                                .font(TE.editorial(.body))
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(TE.card))
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 18)
                .padding(.bottom, 30)
            }
            .foregroundStyle(TE.textPrimary)
            .background(TE.surface.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .onAppear {
                nameText = visit.displayName
            }
            .alert("Unable to Check Out", isPresented: Binding(
                get: { checkoutError != nil },
                set: { if !$0 { checkoutError = nil } }
            )) {
                Button("OK", role: .cancel) { checkoutError = nil }
            } message: {
                Text(checkoutError ?? "")
            }
            .sheet(isPresented: $showingPlaceSearch) {
                PlaceSelectionView(
                    initialRegion: MKCoordinateRegion(center: visit.coordinate, latitudinalMeters: 1_000, longitudinalMeters: 1_000),
                    onSelect: correctVisit(with:)
                )
                .presentationDetents([.large])
            }
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") {
                        isNameFieldFocused = false
                        saveName()
                    }
                }
            }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 8) {
                statusBadge
                Spacer(minLength: 0)
                if visit.hasCustomName {
                    Button("Reset") {
                        resetName()
                    }
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(TE.inkRust)
                    .frame(minHeight: 44)
                    .buttonStyle(.plain)
                    .accessibilityHint("Restores the automatically detected visit name.")
                }
                Button {
                    saveName()
                    dismiss()
                } label: {
                    Image(systemName: "xmark")
                        .font(.footnote.weight(.semibold))
                        .frame(width: 40, height: 40)
                        .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                        .frame(width: 44, height: 44)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close")
            }

            TextField("Visit name", text: $nameText, axis: .vertical)
                .font(WheredayFont.serif(size: 26, relativeTo: .title))
                .textFieldStyle(.plain)
                .submitLabel(.done)
                .focused($isNameFieldFocused)
                .onSubmit(saveName)
                .onChange(of: isNameFieldFocused) { _, focused in
                    if !focused {
                        saveName()
                    }
                }
                .accessibilityLabel("Name")

            Text(timeLine)
                .font(TE.mono(.caption, weight: .medium))
                .tracking(1)
                .foregroundStyle(TE.textMuted)
                .accessibilityLabel(Text("\(dayWord), \(timeRange). Duration \(visit.formattedDuration)"))

            if let address = visit.address, !address.isEmpty, address != visit.displayName {
                Text(address)
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
            }

            if visit.hasCustomName, visit.automaticDisplayName != visit.displayName {
                Text("Detected as \(visit.automaticDisplayName)")
                    .font(.caption)
                    .foregroundStyle(TE.textMuted)
            }
        }
    }

    /// "DETECTED · NOT CONFIRMED" in ochre while it is still a guess.
    private var statusBadge: some View {
        let isUnconfirmed = visit.confirmationStatus == .unconfirmed
        return HStack(spacing: 6) {
            if viewModel.isCurrentVisit(visit) {
                Circle().fill(TE.liveDot).frame(width: 7, height: 7)
                    .accessibilityHidden(true)
            }
            Text(statusCaption)
                .font(TE.mono(.caption2, weight: .semibold))
                .tracking(1)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(isUnconfirmed ? TE.adaptive(0x8A5A12, 0xE0B565) : TE.textMuted)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 6).fill(isUnconfirmed ? TE.adaptive(0xF1E3C6, 0x2E2A22) : TE.dialTrack))
        .padding(.top, 10)
    }

    @ViewBuilder
    private var whereWereYou: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("WHERE WERE YOU?")
                .font(TE.mono(.caption2, weight: .semibold))
                .tracking(1.2)
                .foregroundStyle(TE.textMuted)
                .accessibilityAddTraits(.isHeader)
            if let candidates, !candidates.isEmpty {
                ForEach(Array(candidates.enumerated()), id: \.element.id) { index, candidate in
                    candidateRow(candidate, number: index + 1)
                }
            } else {
                NearbyVisitNameSuggestionSection(visit: visit, selectedName: visit.displayName, showsControls: false) { suggestion in
                    correctVisit(with: suggestion)
                }
            }
        }
    }

    private func candidateRow(_ candidate: NearbyPlaceSuggestion, number: Int) -> some View {
        let isSelected = candidate.name == visit.displayName
        return Button {
            correctVisit(with: candidate)
        } label: {
            HStack(spacing: 10) {
                Text(number.formatted())
                    .font(.system(size: 11, weight: .semibold))
                    .frame(width: 20, height: 20)
                    .overlay(Circle().strokeBorder(TE.textPrimary, lineWidth: 1.5))
                    .accessibilityHidden(true)
                Text(candidate.name)
                    .font(.system(size: 14, weight: .medium))
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(candidate.distanceLabel)
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
            }
            .foregroundStyle(TE.textPrimary)
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .background(RoundedRectangle(cornerRadius: 14).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(isSelected ? TE.textPrimary : TE.border, lineWidth: isSelected ? 2 : 1))
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Correct visit to \(candidate.name)")
        .accessibilityValue(candidate.distanceLabel)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    @ViewBuilder
    private var actions: some View {
        let isUnconfirmed = visit.confirmationStatus == .unconfirmed
        if dynamicTypeSize.isAccessibilitySize {
            VStack(spacing: 8) {
                if isUnconfirmed { confirmButton }
                detailsButton(filled: !isUnconfirmed)
                notHereButton
            }
        } else {
            // Not here · Details · Confirm, the confirm button a little wider (1 : 1 : 1.4).
            GeometryReader { geometry in
                let unit: CGFloat = (geometry.size.width - 16) / (isUnconfirmed ? CGFloat(3.4) : CGFloat(2))
                HStack(spacing: 8) {
                    notHereButton.frame(width: unit)
                    detailsButton(filled: !isUnconfirmed).frame(width: unit)
                    if isUnconfirmed { confirmButton.frame(width: unit * 1.4) }
                }
            }
            .frame(height: 46)
        }

        if visit.source == .manual, visit.departedAt == nil {
            Button {
                if !viewModel.checkoutVisit(visit) {
                    checkoutError = viewModel.visitMutationError?.localizedDescription
                }
            } label: {
                actionLabel("Check Out", filled: false)
            }
            .buttonStyle(.plain)
            .disabled(visit.arrivedAt > Date())
            .accessibilityHint("Ends this manual visit at the current time.")
        }
    }

    private var notHereButton: some View {
        Button {
            saveName()
            showingPlaceSearch = true
        } label: {
            actionLabel("Not here", filled: false)
        }
        .buttonStyle(.plain)
        .accessibilityHint("Search Apple Maps or drop a pin to correct this visit.")
    }

    private func detailsButton(filled: Bool) -> some View {
        NavigationLink {
            VisitDetailView(visit: visit, viewModel: viewModel)
        } label: {
            actionLabel("Details", filled: filled)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("More Details")
    }

    private var confirmButton: some View {
        Button {
            saveName()
            viewModel.confirmVisit(visit)
        } label: {
            actionLabel("Confirm", filled: true)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Confirm Place")
    }

    private func actionLabel(_ title: LocalizedStringKey, filled: Bool) -> some View {
        Text(title)
            .font(.subheadline.weight(filled ? .semibold : .medium))
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .foregroundStyle(filled ? MapHomeStyle.invertedText : TE.textPrimary)
            .frame(maxWidth: .infinity, minHeight: 46)
            .background(Capsule().fill(filled ? MapHomeStyle.invertedFill : TE.card))
            .overlay(Capsule().strokeBorder(filled ? Color.clear : TE.border, lineWidth: 1))
            .contentShape(Capsule())
    }

    /// "DETECTED · NOT CONFIRMED", in the artboard's words, from the visit's source and status.
    private var statusCaption: String {
        var parts = [visit.source.displayName, visit.confirmationStatus.displayName]
        if viewModel.isCurrentVisit(visit) {
            parts.insert(String(localized: "Now"), at: 0)
        }
        return parts.joined(separator: " · ").uppercased()
    }

    private var dayWord: String {
        let calendar = Calendar.current
        if calendar.isDateInToday(visit.arrivedAt) { return String(localized: "Today") }
        if calendar.isDateInYesterday(visit.arrivedAt) { return String(localized: "Yesterday") }
        return visit.arrivedAt.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
    }

    private var timeRange: String {
        let start = LocationMapView.clockText(visit.arrivedAt)
        let end = visit.departedAt.map(LocationMapView.clockText) ?? String(localized: "now")
        return "\(start) – \(end)"
    }

    /// "TODAY · 08:40 – 09:25 · 45 MIN".
    private var timeLine: String {
        "\(dayWord) · \(timeRange) · \(visit.formattedDuration)".uppercased()
    }

    private func saveName() {
        viewModel.updateVisitName(visit, customName: nameText)
        nameText = visit.displayName
    }

    private func resetName() {
        viewModel.clearVisitName(visit)
        nameText = visit.displayName
    }

    private func correctVisit(with suggestion: NearbyPlaceSuggestion) {
        isNameFieldFocused = false
        viewModel.correctVisit(
            visit,
            name: suggestion.name,
            address: suggestion.address,
            coordinate: suggestion.coordinate,
            placeSource: .appleMaps,
            distanceMeters: suggestion.distanceMeters
        )
        nameText = visit.displayName
    }

    /// A searched place or dropped pin; an unnamed pin keeps the visit's current name.
    private func correctVisit(with selection: ManualLocationSelection) {
        isNameFieldFocused = false
        let trimmedName = selection.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let original = CLLocation(latitude: visit.latitude, longitude: visit.longitude)
        let chosen = CLLocation(latitude: selection.coordinate.latitude, longitude: selection.coordinate.longitude)
        viewModel.correctVisit(
            visit,
            name: trimmedName.isEmpty ? visit.displayName : trimmedName,
            address: selection.address,
            coordinate: selection.coordinate,
            placeSource: selection.visitPlaceSource,
            distanceMeters: original.distance(from: chosen)
        )
        nameText = visit.displayName
    }
}

