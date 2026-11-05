import SwiftUI
import MapKit
import SwiftData

/// Confirm or correct a visit: its name, times, the nearby place it really was, photos and a note.
struct VisitDetailView: View {
    @Bindable var visit: Visit
    @Bindable var viewModel: LocationViewModel
    var showsMap = true
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var showingDeleteConfirmation = false
    @State private var nameText: String = ""
    @State private var notesText: String = ""
    @State private var arrivedAt: Date = Date()
    @State private var departedAt: Date = Date()
    @State private var isStillHere = false
    @State private var timeValidationMessage: String?
    @State private var showingOverlapConfirmation = false
    @State private var checkoutError: String?
    @State private var calendarDraft: CalendarEventDraft?
    @State private var showingPlaceSearch = false
    @State private var showingSplit = false
    @State private var photoMoments: [PhotoMoment] = []
    @State private var selectedPhotoMoment: PhotoMoment?
    @FocusState private var focusedField: FocusedField?

    private enum FocusedField: Hashable {
        case name
        case notes
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                titleSection
                    .padding(.horizontal, 24)
                    .padding(.top, 8)

                if showsMap {
                    mapSection
                        .padding(.horizontal, 16)
                        .padding(.top, 16)
                }

                timeInfoSection
                    .padding(.horizontal, 24)
                    .padding(.top, 16)

                placeChoiceSection
                    .padding(.horizontal, 24)
                    .padding(.top, 20)

                if !photoMoments.isEmpty {
                    photosSection
                        .padding(.top, 20)
                }

                notesSection
                    .padding(.horizontal, 24)
                    .padding(.top, 18)

                secondaryActions
                    .padding(.horizontal, 16)
                    .padding(.top, 18)

                // Coordinates (for debugging/reference)
                Text(String(format: "%.6f, %.6f", visit.latitude, visit.longitude))
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
                    .textSelection(.enabled)
                    .padding(.horizontal, 24)
                    .padding(.top, 16)
            }
            .padding(.bottom, 16)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom) {
            actionBar
        }
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .toolbarBackground(.hidden, for: .navigationBar)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            syncEditableStateFromVisit()
        }
        .task(id: photoTaskKey) {
            loadPhotoMoments()
        }
        .alert("Delete Visit?", isPresented: $showingDeleteConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                viewModel.deleteVisit(visit)
                dismiss()
            }
        } message: {
            Text("This visit will be permanently deleted.")
        }
        .alert("Overlapping Visit", isPresented: $showingOverlapConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Save Anyway") { saveTimes(allowOverlap: true) }
        } message: {
            Text(viewModel.visitMutationError?.localizedDescription ?? "")
        }
        .alert("Unable to Check Out", isPresented: Binding(
            get: { checkoutError != nil },
            set: { if !$0 { checkoutError = nil } }
        )) {
            Button("OK", role: .cancel) { checkoutError = nil }
        } message: {
            Text(checkoutError ?? "")
        }
        .sheet(item: $calendarDraft) { draft in
            CalendarEventReviewSheet(draft: draft)
        }
        .sheet(isPresented: $showingPlaceSearch) {
            PlaceSelectionView(
                initialRegion: MKCoordinateRegion(
                    center: visit.coordinate,
                    latitudinalMeters: 1_000,
                    longitudinalMeters: 1_000
                ),
                onSelect: correctVisit(with:)
            )
            .presentationDetents([.large])
        }
        .sheet(isPresented: $showingSplit) {
            SplitVisitSheet(visit: visit, viewModel: viewModel) {
                syncEditableStateFromVisit()
            }
            .presentationDetents([.large])
        }
        .sheet(item: $selectedPhotoMoment) { photo in
            PhotoMomentQuickView(photo: photo)
                .presentationDetents([.medium, .large])
        }
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") {
                    focusedField = nil
                    saveName()
                    saveNotes()
                }
            }
        }
    }

    // MARK: - Sections

    private var titleSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .center, spacing: 8) {
                statusChip

                if viewModel.isCurrentVisit(visit) {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(TE.liveDot)
                            .frame(width: 8, height: 8)
                            .accessibilityHidden(true)
                        Text("NOW")
                            .font(TE.mono(.caption2))
                            .tracking(1.2)
                            .foregroundStyle(TE.inkRust)
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Current visit")
                }

                Spacer(minLength: 8)

                Button(action: openInMaps) {
                    Image(systemName: "arrow.up.right")
                        .font(.system(size: 17, weight: .regular))
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(TE.card))
                        .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Open visit in Maps")
                .accessibilityValue(visit.displayName)
                .accessibilityHint("Opens this location in your default navigation app when supported.")
            }

            TextField("Visit name", text: $nameText, axis: .vertical)
                .font(TE.editorial(.title))
                .textFieldStyle(.plain)
                .submitLabel(.done)
                .focused($focusedField, equals: .name)
                .onSubmit(saveName)
                .onChange(of: nameText) { _, newValue in
                    // Vertical fields insert a newline on Return; treat it as submit.
                    if newValue.contains("\n") {
                        nameText = newValue.replacingOccurrences(of: "\n", with: "")
                        focusedField = nil
                    }
                }
                .onChange(of: focusedField) { oldValue, newValue in
                    if oldValue == .name && newValue != .name {
                        saveName()
                    }
                }
                .accessibilityLabel("Name")

            Text(subtitleText)
                .font(TE.mono(.caption))
                .foregroundStyle(TE.textMuted)
                .fixedSize(horizontal: false, vertical: true)

            if visit.hasCustomName, visit.automaticDisplayName != visit.displayName {
                detectedAsLine(String(localized: "Detected as \(visit.automaticDisplayName)"))
            }

            if let originalName = visit.originalLocationName,
               visit.confirmationStatus == .corrected,
               originalName != visit.displayName {
                detectedAsLine(String(localized: "Originally detected as \(originalName)"))
            }
        }
    }

    private func detectedAsLine(_ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(text)
                .font(TE.editorial(.subheadline).italic())
                .foregroundStyle(TE.textMuted)
                .fixedSize(horizontal: false, vertical: true)

            if visit.hasCustomName {
                Button("Reset") {
                    resetName()
                }
                .font(.footnote.weight(.medium))
                .foregroundStyle(TE.inkRust)
                .frame(minHeight: 44)
                .buttonStyle(.plain)
                .accessibilityLabel("Reset name")
                .accessibilityHint("Restores the automatically detected visit name.")
            }
        }
    }

    private var statusChip: some View {
        Text(statusChipText)
            .font(TE.mono(.caption2))
            .tracking(1.2)
            .foregroundStyle(TE.textPrimary)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(RoundedRectangle(cornerRadius: 6).fill(statusTint.opacity(0.22)))
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityLabel("Visit status: \(visit.confirmationStatus.displayName), source: \(visit.source.displayName)")
    }

    private var statusChipText: String {
        let source: String = switch visit.source {
        case .automatic: String(localized: "Detected")
        case .manual: String(localized: "Added by you")
        case .imported: String(localized: "Imported")
        }
        let status: String = switch visit.confirmationStatus {
        case .unconfirmed: String(localized: "Not confirmed")
        case .confirmed: String(localized: "Confirmed")
        case .corrected: String(localized: "Corrected")
        }
        return "\(source) · \(status)".uppercased()
    }

    private var statusTint: Color {
        switch visit.confirmationStatus {
        case .unconfirmed: return TE.placeInks[2]
        case .confirmed: return TE.placeInks[3]
        case .corrected: return TE.placeInks[0]
        }
    }

    private var subtitleText: String {
        var parts: [String] = []
        if let address = visit.address, !address.isEmpty, address != visit.displayName {
            parts.append(address)
        }
        let calendar = Calendar.current
        if calendar.isDateInToday(visit.arrivedAt) {
            parts.append(String(localized: "Today"))
        } else if calendar.isDateInYesterday(visit.arrivedAt) {
            parts.append(String(localized: "Yesterday"))
        } else {
            parts.append(visit.arrivedAt.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day().year()))
        }
        return parts.joined(separator: " · ")
    }

    private var mapSection: some View {
        Map(initialPosition: .region(MKCoordinateRegion(
            center: visit.coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.005, longitudeDelta: 0.005)
        ))) {
            Marker(visit.displayName, coordinate: visit.coordinate)
                .tint(viewModel.isCurrentVisit(visit) ? TE.inkRust : TE.accent)
        }
        .frame(height: 160)
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .accessibilityLabel("Visit map")
        .accessibilityValue("\(visit.accessibilityLabel). \(visit.accessibilityValue)")
    }

    // MARK: Times

    /// Same-day stays edit as two time fields plus a day; overnight stays need full dates.
    private var editsAsSingleDay: Bool {
        isStillHere || Calendar.current.isDate(arrivedAt, inSameDayAs: departedAt)
    }

    private var timeInfoSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            if editsAsSingleDay && !dynamicTypeSize.isAccessibilitySize {
                HStack(alignment: .bottom, spacing: 10) {
                    timeBox("ARRIVED") {
                        DatePicker("Arrived", selection: $arrivedAt, displayedComponents: .hourAndMinute)
                            .labelsHidden()
                    }
                    timeBox("LEFT") {
                        if isStillHere {
                            Text("still here")
                                .font(TE.editorial(.body).italic())
                                .foregroundStyle(TE.textMuted)
                        } else {
                            DatePicker("Departed", selection: $departedAt, displayedComponents: .hourAndMinute)
                                .labelsHidden()
                        }
                    }
                    durationText
                        .padding(.bottom, 10)
                }
            } else {
                fullDateRow("ARRIVED", title: "Arrived", selection: $arrivedAt)
                if !isStillHere {
                    fullDateRow("LEFT", title: "Departed", selection: $departedAt)
                }
                HStack {
                    Text("DURATION")
                        .font(TE.mono(.caption2))
                        .tracking(1.2)
                        .foregroundStyle(TE.textMuted)
                    Spacer()
                    durationText
                }
            }

            if editsAsSingleDay {
                HStack(spacing: 12) {
                    Text("DAY")
                        .font(TE.mono(.caption2))
                        .tracking(1.2)
                        .foregroundStyle(TE.textMuted)
                    Spacer(minLength: 8)
                    DatePicker("Day", selection: visitDay, displayedComponents: .date)
                        .labelsHidden()
                }
                .frame(minHeight: 44)
            }

            Toggle(isOn: $isStillHere) {
                Text("Still here")
                    .font(.subheadline)
            }
            .toggleStyle(TEToggleStyle())

            if let timeValidationMessage {
                Text(timeValidationMessage)
                    .font(.footnote)
                    .foregroundStyle(TE.inkRust)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if hasUnsavedTimeChanges {
                Button {
                    saveTimes()
                } label: {
                    Label("Save Time Changes", systemImage: "clock.badge.checkmark")
                }
                .buttonStyle(VisitPillButtonStyle(kind: .primary))
                .disabled(timeValidationMessage != nil)
            }
        }
        .tint(TE.accent)
        .onChange(of: arrivedAt) { _, _ in validateEditedTimes() }
        .onChange(of: departedAt) { _, _ in validateEditedTimes() }
        .onChange(of: isStillHere) { _, _ in validateEditedTimes() }
    }

    private var durationText: some View {
        Text(editedDurationText)
            .font(TE.editorial(.title2))
            .monospacedDigit()
            .accessibilityLabel("Duration \(editedDurationText)")
    }

    private func timeBox<Content: View>(_ label: LocalizedStringKey, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(TE.mono(.caption2))
                .tracking(1.2)
                .foregroundStyle(TE.textMuted)
                .accessibilityHidden(true)
            content()
                .padding(.horizontal, 8)
                .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 12).fill(TE.card))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(TE.border, lineWidth: 1))
        }
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func fullDateRow(_ label: LocalizedStringKey, title: LocalizedStringKey, selection: Binding<Date>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label)
                .font(TE.mono(.caption2))
                .tracking(1.2)
                .foregroundStyle(TE.textMuted)
                .accessibilityHidden(true)
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 8) {
                        DatePicker(title, selection: selection, displayedComponents: .date)
                            .labelsHidden()
                        DatePicker(title, selection: selection, displayedComponents: .hourAndMinute)
                            .labelsHidden()
                    }
                } else {
                    DatePicker(title, selection: selection, displayedComponents: [.date, .hourAndMinute])
                        .labelsHidden()
                }
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 12).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(TE.border, lineWidth: 1))
        }
    }

    /// Moving the day keeps the times of day and shifts arrival and departure together.
    private var visitDay: Binding<Date> {
        Binding(
            get: { arrivedAt },
            set: { newDay in
                let calendar = Calendar.current
                let dayOffset = calendar.dateComponents(
                    [.day],
                    from: calendar.startOfDay(for: arrivedAt),
                    to: calendar.startOfDay(for: newDay)
                ).day ?? 0
                guard dayOffset != 0 else { return }
                arrivedAt = calendar.date(byAdding: .day, value: dayOffset, to: arrivedAt) ?? arrivedAt
                if !isStillHere {
                    departedAt = calendar.date(byAdding: .day, value: dayOffset, to: departedAt) ?? departedAt
                }
            }
        )
    }

    // MARK: Place

    private var placeChoiceSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text("WHERE WERE YOU?")
                    .font(TE.mono(.caption2))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                Text("nearest first")
                    .font(TE.editorial(.subheadline).italic())
                    .foregroundStyle(TE.textMuted)
            }

            NearbyVisitNameSuggestionSection(
                visit: visit,
                selectedName: visit.displayName,
                showsControls: false
            ) { suggestion in
                correctVisit(with: suggestion)
            }

            let layout = dynamicTypeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(spacing: 8))
                : AnyLayout(HStackLayout(spacing: 8))
            layout {
                Button("Search…") {
                    focusedField = nil
                    showingPlaceSearch = true
                }
                .buttonStyle(VisitPillButtonStyle())
                .accessibilityLabel("Search for the place")
                .accessibilityHint("Search Apple Maps or drop a pin to correct this visit.")

                Button("Not here") {
                    focusedField = .name
                }
                .buttonStyle(VisitPillButtonStyle())
                .accessibilityHint("None of these places. Type the name yourself.")
            }
        }
    }

    // MARK: Photos and notes

    private var photosSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("PHOTOS FROM THIS VISIT")
                .font(TE.mono(.caption2))
                .tracking(1.5)
                .foregroundStyle(TE.textMuted)
                .accessibilityAddTraits(.isHeader)
                .padding(.horizontal, 24)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(photoMoments) { photo in
                        Button {
                            selectedPhotoMoment = photo
                        } label: {
                            PhotoThumbnailView(
                                assetLocalIdentifier: photo.assetLocalIdentifier,
                                targetPointSize: CGSize(width: 72, height: 72),
                                cornerRadius: 10
                            )
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(photo.accessibilityLabel)
                        .accessibilityValue(photo.accessibilityValue)
                    }
                }
                .padding(.horizontal, 24)
            }
        }
    }

    private var notesSection: some View {
        TextField("Add a note — who you met, what you had…", text: $notesText, axis: .vertical)
            .font(TE.editorial(.body).italic())
            .textFieldStyle(.plain)
            .lineLimit(2...6)
            .focused($focusedField, equals: .notes)
            .onChange(of: focusedField) { oldValue, newValue in
                if oldValue == .notes && newValue != .notes {
                    saveNotes()
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: 12).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(TE.border, lineWidth: 1))
            .accessibilityLabel("Notes")
    }

    // MARK: Actions

    @ViewBuilder
    private var secondaryActions: some View {
        let showsCheckOut = visit.source == .manual && visit.departedAt == nil
        let showsUndo = visit.confirmationStatus == .corrected && visit.originalCoordinate != nil
        let showsSplit = ManualVisitStore.canSplit(visit)
        if showsCheckOut || showsUndo || showsSplit {
            VStack(spacing: 8) {
                if showsSplit {
                    Button {
                        focusedField = nil
                        showingSplit = true
                    } label: {
                        Label("Split Visit", systemImage: "scissors")
                    }
                    .buttonStyle(VisitPillButtonStyle())
                    .disabled(hasUnsavedTimeChanges)
                    .accessibilityHint("Splits this stop into two visits, for places next door to each other.")
                }

                if showsCheckOut {
                    Button {
                        if viewModel.checkoutVisit(visit) {
                            syncEditableStateFromVisit()
                        } else {
                            checkoutError = viewModel.visitMutationError?.localizedDescription
                        }
                    } label: {
                        Label("Check Out", systemImage: "rectangle.portrait.and.arrow.right")
                    }
                    .buttonStyle(VisitPillButtonStyle())
                    .disabled(visit.arrivedAt > Date())
                    .accessibilityHint("Ends this manual visit at the current time.")
                }

                if showsUndo {
                    Button {
                        undoCorrection()
                    } label: {
                        Label("Undo Correction", systemImage: "arrow.uturn.backward")
                    }
                    .buttonStyle(VisitPillButtonStyle())
                    .accessibilityHint("Restores the originally detected visit location.")
                }
            }
        }
    }

    private var actionBar: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 8))
            : AnyLayout(HStackLayout(spacing: 8))
        return layout {
            Button("Calendar") {
                calendarDraft = CalendarEventDraft(visit: visit, notes: notesText)
            }
            .buttonStyle(VisitPillButtonStyle())
            .accessibilityLabel("Add Event to Calendar")

            Button("Delete", role: .destructive) {
                showingDeleteConfirmation = true
            }
            .buttonStyle(VisitPillButtonStyle(kind: .destructive))
            .accessibilityLabel("Delete Visit")
            .accessibilityHint("Deletes this visit after confirmation.")

            if visit.confirmationStatus == .unconfirmed {
                Button("Confirm") {
                    confirmVisit()
                }
                .buttonStyle(VisitPillButtonStyle(kind: .primary))
                .accessibilityLabel("Confirm Place")
                .accessibilityHint("Marks this visit as reviewed and correct.")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background(TE.surface)
    }

    private var editedDeparture: Date? {
        isStillHere ? nil : departedAt
    }

    private var hasUnsavedTimeChanges: Bool {
        abs(arrivedAt.timeIntervalSince(visit.arrivedAt)) > 0.5 || editedDepartureChanged
    }

    private var editedDepartureChanged: Bool {
        switch (editedDeparture, visit.departedAt) {
        case (nil, nil): return false
        case let (lhs?, rhs?): return abs(lhs.timeIntervalSince(rhs)) > 0.5
        default: return true
        }
    }

    private var editedDurationText: String {
        let end = editedDeparture ?? Date()
        let seconds = max(0, end.timeIntervalSince(arrivedAt))
        return VisitDetailDurationFormatter.format(seconds)
    }

    private var photoTaskKey: String {
        "\(visit.id.uuidString)-\(visit.arrivedAt.timeIntervalSince1970)-\(visit.departedAt?.timeIntervalSince1970 ?? 0)"
    }

    private func loadPhotoMoments() {
        let end = max(visit.arrivedAt, visit.departedAt ?? Date())
        photoMoments = viewModel.photosInDateRange(visit.arrivedAt...end)
    }

    private func syncEditableStateFromVisit() {
        nameText = visit.displayName
        notesText = visit.notes ?? ""
        arrivedAt = visit.arrivedAt
        departedAt = visit.departedAt ?? Date()
        isStillHere = visit.departedAt == nil
        validateEditedTimes()
    }

    private func validateEditedTimes() {
        if !isStillHere, departedAt < arrivedAt {
            timeValidationMessage = String(localized: "Departure must be after arrival.")
        } else {
            timeValidationMessage = nil
        }
    }

    private func saveTimes(allowOverlap: Bool = false) {
        validateEditedTimes()
        guard timeValidationMessage == nil else { return }
        guard viewModel.updateVisitTimes(visit, arrivedAt: arrivedAt, departedAt: editedDeparture, allowOverlap: allowOverlap) else {
            if case .overlap = viewModel.visitMutationError {
                showingOverlapConfirmation = true
            } else {
                timeValidationMessage = viewModel.visitMutationError?.localizedDescription
            }
            return
        }
        syncEditableStateFromVisit()
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
        focusedField = nil
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
        focusedField = nil
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

    private func confirmVisit() {
        focusedField = nil
        saveName()
        saveNotes()
        viewModel.confirmVisit(visit)
    }

    private func undoCorrection() {
        focusedField = nil
        viewModel.undoVisitCorrection(visit)
        nameText = visit.displayName
    }

    private func saveNotes() {
        viewModel.updateVisitNotes(visit, notes: notesText)
    }

    private func openInMaps() {
        let coordinate = visit.coordinate
        let name = visit.displayName
        Task { @MainActor in
            await VisitMapOpener.open(coordinate: coordinate, name: name)
        }
    }
}

/// The visit screen's 48pt rounded actions: outlined paper, rust for delete, filled ink to confirm.
struct VisitPillButtonStyle: ButtonStyle {
    enum Kind {
        case standard, destructive, primary
    }

    var kind: Kind = .standard
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let filled = kind == .primary && isEnabled
        configuration.label
            .font(.subheadline.weight(kind == .primary ? .semibold : .medium))
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, minHeight: 48)
            .foregroundStyle(foreground(filled: filled))
            .background {
                RoundedRectangle(cornerRadius: 14)
                    .fill(background(filled: filled))
            }
            .overlay {
                if !filled {
                    RoundedRectangle(cornerRadius: 14)
                        .strokeBorder(kind == .destructive ? TE.inkRust.opacity(0.35) : TE.border, lineWidth: 1)
                }
            }
            .opacity(configuration.isPressed ? 0.75 : 1)
            .contentShape(RoundedRectangle(cornerRadius: 14))
    }

    private func foreground(filled: Bool) -> Color {
        if !isEnabled { return TE.textMuted }
        if filled { return TE.surface }
        return kind == .destructive ? TE.inkRust : TE.textPrimary
    }

    private func background(filled: Bool) -> Color {
        if filled { return TE.accent }
        return kind == .destructive ? Color.clear : TE.card
    }
}

private enum VisitDetailDurationFormatter {
    static func format(_ seconds: TimeInterval) -> String {
        let minutes = max(0, Int(seconds / 60))
        if minutes < 60 { return String(localized: "\(minutes) min") }
        let hours = minutes / 60
        let remainingMinutes = minutes % 60
        return remainingMinutes == 0
            ? String(localized: "\(hours)h")
            : String(localized: "\(hours)h \(remainingMinutes)m")
    }
}

struct NearbyVisitNameSuggestionSection: View {
    let visit: Visit
    /// Highlights the suggestion the visit is already named after.
    var selectedName: String?
    /// The visit screen supplies its own header and search, so it hides these.
    var showsControls = true
    /// What choosing a suggestion does, for VoiceOver.
    var accessibilityAction: (String) -> String = { String(localized: "Correct visit to \($0)") }
    let onSelect: (NearbyPlaceSuggestion) -> Void

    init(
        visit: Visit,
        selectedName: String? = nil,
        showsControls: Bool = true,
        accessibilityAction: ((String) -> String)? = nil,
        onSelect: @escaping (NearbyPlaceSuggestion) -> Void
    ) {
        self.visit = visit
        self.selectedName = selectedName
        self.showsControls = showsControls
        if let accessibilityAction { self.accessibilityAction = accessibilityAction }
        self.onSelect = onSelect
    }

    @AppStorage("allowNetworkGeocoding") private var allowNetworkGeocoding = true
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    @State private var suggestions: [NearbyPlaceSuggestion] = []
    @State private var isLoading = false
    @State private var hasSearched = false
    @State private var loadFailed = false
    @State private var businessSearchText = ""

    private var normalizedBusinessSearchText: String {
        businessSearchText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var filteredSuggestions: [NearbyPlaceSuggestion] {
        let searchText = normalizedBusinessSearchText
        guard !searchText.isEmpty else { return suggestions }

        return suggestions.filter { suggestion in
            suggestion.name.localizedCaseInsensitiveContains(searchText) ||
            (suggestion.address?.localizedCaseInsensitiveContains(searchText) ?? false)
        }
    }

    private var suggestionTaskKey: String {
        String(format: "%@-%.5f-%.5f-%d", visit.id.uuidString, visit.latitude, visit.longitude, allowNetworkGeocoding ? 1 : 0)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if showsControls {
                HStack(spacing: 6) {
                    Label("Nearby businesses", systemImage: "building.2")
                        .font(TE.mono(.caption2, weight: .semibold))
                        .tracking(1)
                        .foregroundStyle(TE.textMuted)

                    Spacer()

                    if isLoading {
                        ProgressView()
                            .controlSize(.mini)
                            .accessibilityLabel("Loading nearby businesses")
                    }
                }

                searchField
                    .disabled(suggestions.isEmpty)
                    .opacity(suggestions.isEmpty ? 0.65 : 1)
            }

            if !allowNetworkGeocoding {
                Text(NetworkGeocodingPolicy.LookupError.disabled.localizedDescription)
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            } else if suggestions.isEmpty {
                emptyState
            } else if filteredSuggestions.isEmpty {
                filteredEmptyState
            } else {
                suggestionList
            }
        }
        .padding(.top, showsControls ? 6 : 0)
        .task(id: suggestionTaskKey) {
            await loadSuggestions(force: true)
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.caption)
                .foregroundStyle(TE.textMuted)
                .accessibilityHidden(true)

            TextField("Filter businesses", text: $businessSearchText)
                .font(.subheadline)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .submitLabel(.search)

            if !businessSearchText.isEmpty {
                Button {
                    businessSearchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(TE.textMuted)
                        .frame(minWidth: 44, minHeight: 44)
                        .accessibilityLabel("Clear business filter")
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 44)
        .background(RoundedRectangle(cornerRadius: 12).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(TE.border, lineWidth: 1))
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 6) {
            if loadFailed {
                Text("Couldn’t load nearby businesses.")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
            } else if isLoading || !hasSearched {
                Text("Looking for places near this visit…")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
            } else {
                Text("No nearby businesses found. Try again, or edit the visit name manually.")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
            }

            if loadFailed || (hasSearched && suggestions.isEmpty && !isLoading) {
                Button {
                    Task { await loadSuggestions(force: true) }
                } label: {
                    Label("Try nearby search again", systemImage: "arrow.clockwise")
                        .font(.footnote)
                }
                .buttonStyle(.borderless)
                .foregroundStyle(TE.inkRust)
                .frame(minHeight: 44)
            }
        }
    }

    private var filteredEmptyState: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("No nearby businesses match “\(normalizedBusinessSearchText)”.")
                .font(.footnote)
                .foregroundStyle(TE.textMuted)

            Button {
                businessSearchText = ""
            } label: {
                Label("Clear filter", systemImage: "xmark.circle")
                    .font(.footnote)
            }
            .buttonStyle(.borderless)
                .foregroundStyle(TE.inkRust)
                .frame(minHeight: 44)
        }
    }

    private var suggestionList: some View {
        VStack(spacing: 8) {
            if !showsControls, isLoading {
                ProgressView()
                    .controlSize(.small)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityLabel("Loading nearby businesses")
            }

            ForEach(filteredSuggestions) { suggestion in
                let isSelected = suggestion.name == selectedName
                Button {
                    onSelect(suggestion)
                } label: {
                    HStack(spacing: 10) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(suggestion.name)
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(TE.textPrimary)
                                .multilineTextAlignment(.leading)

                            if showsControls, let address = suggestion.address, !address.isEmpty {
                                Text(address)
                                    .font(.caption)
                                    .foregroundStyle(TE.textMuted)
                                    .lineLimit(1)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        Text(suggestion.distanceLabel(usesMetric: usesMetricDistanceUnits))
                            .font(TE.mono(.caption2))
                            .foregroundStyle(TE.textMuted)
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .frame(minHeight: 48)
                    .background(RoundedRectangle(cornerRadius: 14).fill(TE.card))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14)
                            .strokeBorder(isSelected ? TE.textPrimary : TE.border, lineWidth: isSelected ? 2 : 1)
                    )
                    .contentShape(RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(accessibilityAction(suggestion.name))
                .accessibilityValue(suggestion.distanceLabel(usesMetric: usesMetricDistanceUnits))
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
    }

    @MainActor
    private func loadSuggestions(force: Bool = false) async {
        guard allowNetworkGeocoding else {
            suggestions = []
            hasSearched = false
            isLoading = false
            return
        }
        guard force || (!isLoading && !hasSearched) else { return }

        let coordinate = visit.coordinate
        if force {
            suggestions = []
            businessSearchText = ""
            hasSearched = false
        }
        isLoading = true
        loadFailed = false
        defer {
            isLoading = false
            hasSearched = true
        }

        do {
            let nearbySuggestions = try await NearbyPlaceSearchService.shared.suggestions(
                near: coordinate,
                limit: 6
            )

            guard !Task.isCancelled else { return }
            guard allowNetworkGeocoding else { return }
            suggestions = nearbySuggestions
        } catch is CancellationError {
            // Ignore cancellation from SwiftUI task lifecycle changes.
        } catch {
            suggestions = []
            loadFailed = true
        }
    }
}

/// Splits one detected stop into two back-to-back visits: pick when you moved on and where you went.
struct SplitVisitSheet: View {
    let visit: Visit
    @Bindable var viewModel: LocationViewModel
    var onSplit: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var splitAt: Date
    @State private var choice: Choice?
    @State private var showingPlaceSearch = false
    @State private var errorMessage: String?

    struct Choice {
        var name: String
        var address: String?
        var coordinate: CLLocationCoordinate2D
        var placeSource: VisitPlaceSource
        var distanceMeters: Double?
    }

    init(visit: Visit, viewModel: LocationViewModel, onSplit: @escaping () -> Void = {}) {
        self.visit = visit
        self.viewModel = viewModel
        self.onSplit = onSplit
        let end = visit.departedAt ?? visit.arrivedAt
        // Start in the middle, rounded to the minute like the time pickers.
        let midpoint = visit.arrivedAt.addingTimeInterval(end.timeIntervalSince(visit.arrivedAt) / 2)
        let rounded = Date(timeIntervalSinceReferenceDate: (midpoint.timeIntervalSinceReferenceDate / 60).rounded() * 60)
        let range = ManualVisitStore.splitRange(for: visit)
        _splitAt = State(initialValue: range.map { min(max(rounded, $0.lowerBound), $0.upperBound) } ?? midpoint)
    }

    private var range: ClosedRange<Date> {
        ManualVisitStore.splitRange(for: visit) ?? visit.arrivedAt...visit.arrivedAt
    }

    private var spansDays: Bool {
        !Calendar.current.isDate(visit.arrivedAt, inSameDayAs: visit.departedAt ?? visit.arrivedAt)
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Split visit")
                            .font(TE.editorial(.title))
                            .accessibilityAddTraits(.isHeader)
                        Text("Places next door can be detected as one stop. Choose when you moved on and where you went next.")
                            .font(TE.editorial(.subheadline).italic())
                            .foregroundStyle(TE.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        sectionLabel("MOVED ON AT")
                        DatePicker(
                            "Moved on at",
                            selection: $splitAt,
                            in: range,
                            displayedComponents: spansDays ? [.date, .hourAndMinute] : .hourAndMinute
                        )
                        .labelsHidden()
                        .padding(.horizontal, 8)
                        .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 12).fill(TE.card))
                        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(TE.border, lineWidth: 1))
                    }

                    VStack(spacing: 8) {
                        partRow(index: 1, name: visit.displayName, start: visit.arrivedAt, end: splitAt)
                        partRow(index: 2, name: choice?.name, start: splitAt, end: visit.departedAt ?? splitAt)
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        HStack(alignment: .firstTextBaseline) {
                            sectionLabel("WHERE DID YOU GO NEXT?")
                            Spacer(minLength: 8)
                            Text("nearest first")
                                .font(TE.editorial(.subheadline).italic())
                                .foregroundStyle(TE.textMuted)
                        }

                        NearbyVisitNameSuggestionSection(
                            visit: visit,
                            selectedName: choice?.name,
                            showsControls: false,
                            accessibilityAction: { String(localized: "Second visit at \($0)") }
                        ) { suggestion in
                            choose(Choice(
                                name: suggestion.name,
                                address: suggestion.address,
                                coordinate: suggestion.coordinate,
                                placeSource: .appleMaps,
                                distanceMeters: suggestion.distanceMeters
                            ))
                        }

                        Button("Search…") {
                            showingPlaceSearch = true
                        }
                        .buttonStyle(VisitPillButtonStyle())
                        .accessibilityLabel("Search for the second place")
                        .accessibilityHint("Search Apple Maps or drop a pin.")
                    }

                    if let errorMessage {
                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundStyle(TE.inkRust)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 8)
            }
            .safeAreaInset(edge: .bottom) {
                Button("Split Visit") { split() }
                    .buttonStyle(VisitPillButtonStyle(kind: .primary))
                    .disabled(choice == nil)
                    .accessibilityHint("Saves two visits, one for each place.")
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(TE.surface)
            }
            .background(TE.surface.ignoresSafeArea())
            .foregroundStyle(TE.textPrimary)
            .tint(TE.accent)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .sheet(isPresented: $showingPlaceSearch) {
                PlaceSelectionView(
                    initialRegion: MKCoordinateRegion(
                        center: visit.coordinate,
                        latitudinalMeters: 1_000,
                        longitudinalMeters: 1_000
                    ),
                    onSelect: choose(_:)
                )
                .presentationDetents([.large])
            }
        }
    }

    private func sectionLabel(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(TE.mono(.caption2))
            .tracking(1.5)
            .foregroundStyle(TE.textMuted)
            .accessibilityAddTraits(.isHeader)
    }

    private func partRow(index: Int, name: String?, start: Date, end: Date) -> some View {
        let duration = VisitDetailDurationFormatter.format(max(0, end.timeIntervalSince(start)))
        let times = "\(start.formatted(date: .omitted, time: .shortened)) – \(end.formatted(date: .omitted, time: .shortened))"
        return HStack(alignment: .firstTextBaseline, spacing: 10) {
            Text(String(index))
                .font(TE.mono(.caption))
                .foregroundStyle(TE.textMuted)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(name ?? String(localized: "Choose a place below"))
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(name == nil ? TE.textMuted : TE.textPrimary)
                Text(times)
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(duration)
                .font(TE.editorial(.body))
                .monospacedDigit()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 14).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(TE.border, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }

    private func choose(_ choice: Choice) {
        errorMessage = nil
        self.choice = choice
    }

    /// A searched place or dropped pin; an unnamed pin cannot name the second visit.
    private func choose(_ selection: ManualLocationSelection) {
        let name = selection.name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else {
            errorMessage = String(localized: "Name the place to split the visit.")
            return
        }
        let original = CLLocation(latitude: visit.latitude, longitude: visit.longitude)
        let chosen = CLLocation(latitude: selection.coordinate.latitude, longitude: selection.coordinate.longitude)
        choose(Choice(
            name: name,
            address: selection.address,
            coordinate: selection.coordinate,
            placeSource: selection.visitPlaceSource,
            distanceMeters: original.distance(from: chosen)
        ))
    }

    private func split() {
        guard let choice else { return }
        let second = viewModel.splitVisit(
            visit,
            at: splitAt,
            secondPlaceName: choice.name,
            address: choice.address,
            coordinate: choice.coordinate,
            placeSource: choice.placeSource,
            distanceMeters: choice.distanceMeters
        )
        guard second != nil else {
            errorMessage = viewModel.visitMutationError?.localizedDescription
            return
        }
        onSplit()
        dismiss()
    }
}

#Preview {
    NavigationStack {
        VisitDetailView(
            visit: Visit.preview,
            viewModel: LocationViewModel(
                modelContext: try! ModelContainer(for: Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self).mainContext,
                locationManager: LocationManager()
            )
        )
    }
}
