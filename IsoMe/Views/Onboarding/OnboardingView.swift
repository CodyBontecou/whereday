import SwiftUI
import CoreLocation
import MapKit
import Photos

/// The ten first-run screens, in order.
enum OnboardingStep: Int, CaseIterable {
    case cover, intent, scrub, pen, permission, stamp, backfill, glance, export, tonight
}

/// Names offered for the first stamped place.
enum OnboardingPlaceChoice: String, CaseIterable, Identifiable {
    case home, work, cafe, gym, other

    var id: String { rawValue }

    var kind: SavedPlaceKind {
        switch self {
        case .home: .home
        case .work: .work
        case .cafe: .cafe
        case .gym: .gym
        case .other: .other
        }
    }

    var chipTitle: String {
        self == .other ? String(localized: "Somewhere else") : kind.label
    }
}

extension RecordingDetail {
    /// Stable key for onboarding accessibility identifiers.
    var onboardingKey: String {
        switch self {
        case .places: "places"
        case .routes: "routes"
        case .everyStep: "everyStep"
        }
    }
}

/// First run, "Page One": learn to read a page, then write the first line of yours.
struct OnboardingView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let viewModel: LocationViewModel
    let onComplete: (_ startTracking: Bool, _ opensImport: Bool) -> Void

    @ObservedObject private var locationManager: LocationManager
    @ObservedObject private var storeManager: StoreManager
    @StateObject private var checkout: OnboardingCheckout

    @State private var step: OnboardingStep
    @State private var intents: Set<OnboardingIntent>
    @State private var scrubHour = 9.25
    @State private var recordingDetail: RecordingDetail
    @State private var placeChoice: OnboardingPlaceChoice?
    @State private var customPlaceName = ""
    @State private var stampFix: CLLocation?
    @State private var stampFixTimedOut = false
    @State private var stampedPlaceName: String?
    @State private var photoDayCounts: [Int]?
    @State private var isConfiguringAutomaticPhotoSync = false
    @State private var exportSample: OnboardingExportSample?
    /// The "Evening recap" toggle: Every evening when on, Off when off. Applied only on the last button.
    @State private var recapEnabled = true
    @State private var selectedPlan: OnboardingPlanChoice = .yearly
    @State private var familyPlan = false
    @State private var showsAllPlans = false
    /// Fixed when the last step appears, so its dates don't shift while someone reads them.
    @State private var checkoutNow = Date()
    @State private var isFinishing = false
    @State private var liveActivitiesAllowed = LiveActivityManager.shared.areActivitiesEnabled
    @AppStorage(LocationViewModel.automaticPhotoSyncEnabledKey) private var automaticPhotoSyncEnabled = false
    @FocusState private var customNameFocused: Bool

    private static var stepCount: Int { OnboardingStep.allCases.count }

    init(viewModel: LocationViewModel, storeManager: StoreManager = .shared,
         onComplete: @escaping (_ startTracking: Bool, _ opensImport: Bool) -> Void) {
        self.viewModel = viewModel
        self.onComplete = onComplete
        _locationManager = ObservedObject(initialValue: viewModel.locationManager)
        _storeManager = ObservedObject(initialValue: storeManager)
        _checkout = StateObject(wrappedValue: OnboardingCheckout(store: storeManager))
        _step = State(initialValue: Self.initialStepFromLaunchArguments())
        let intents = OnboardingIntentStore.load()
        _intents = State(initialValue: intents)
        _recordingDetail = State(initialValue: intents.contains(.routes) ? .everyStep : .routes)
    }

    private static func initialStepFromLaunchArguments() -> OnboardingStep {
        #if DEBUG
        let prefix = "--onboarding-page="
        if let argument = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix(prefix) }),
           let page = Int(argument.dropFirst(prefix.count)),
           let step = OnboardingStep(rawValue: page) {
            return step
        }
        #endif
        return .cover
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if showsTrialDone {
                // Paid: there is no going back to the offer.
                Color.clear.frame(height: 44).padding(.top, 12)
            } else {
                header
                    .padding(.top, 12)
            }

            GeometryReader { geometry in
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        page
                        if controlsScrollWithPage {
                            controls
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 20)
                    .frame(minHeight: geometry.size.height, alignment: .center)
                }
                .scrollBounceBehavior(.basedOnSize)
                .scrollDismissesKeyboard(.interactively)
            }
            // The stamp map is a UIKit view; keep it from drawing over the header while scrolled.
            .clipped()
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: step)

            if !controlsScrollWithPage {
                controls
                    .padding(.top, 12)
            }
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 16)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .foregroundStyle(TE.textPrimary)
        .background(TE.surface.ignoresSafeArea())
        .modifier(JournalFlowPresentation())
        .tint(TE.accent)
        .onChange(of: locationManager.authorizationStatus) { _, newStatus in
            if step == .permission, newStatus == .authorizedAlways {
                go(to: .stamp)
            }
        }
        .onChange(of: locationManager.currentLocation) { _, location in
            captureStampFix(location)
        }
        .onChange(of: step) { _, newStep in
            prepare(newStep)
        }
        .onAppear { prepare(step) }
        .onChange(of: checkout.isDone) { _, isDone in
            if isDone { showsAllPlans = false }
        }
        .sheet(isPresented: $showsAllPlans) {
            OnboardingAllPlansSheet(storeManager: storeManager, checkout: checkout,
                                    plan: $selectedPlan, family: $familyPlan,
                                    lead: OnboardingPlanLead.intent(for: intents), now: checkoutNow)
        }
    }

    /// Who the last step asks to pay: see `OnboardingAccessRouting`.
    private var finalRoute: OnboardingFinalStepRoute {
        OnboardingAccessRouting.route(
            hasResolvedEntitlements: storeManager.hasResolvedEntitlements,
            legacy: storeManager.hasLegacyTrackingAccess,
            purchased: storeManager.isPurchased,
            expiration: storeManager.trackingExpiration
        )
    }

    private var showsTrialDone: Bool { step == .tonight && checkout.isDone }
    private var showsFreeWeek: Bool { step == .tonight && !checkout.isDone && finalRoute == .freeWeek }

    /// At accessibility text sizes the purchase controls and their disclosure scroll with the page,
    /// so the timeline and plans aren't squeezed out.
    private var controlsScrollWithPage: Bool { showsFreeWeek && dynamicTypeSize.isAccessibilitySize }

    @ViewBuilder
    private var page: some View {
        switch step {
        case .cover: coverPage
        case .intent: intentPage
        case .scrub: scrubPage
        case .pen: penPage
        case .permission: permissionPage
        case .stamp: stampPage
        case .backfill: backfillPage
        case .glance: glancePage
        case .export: exportPage
        case .tonight:
            if checkout.isDone {
                trialDonePage
            } else {
                switch finalRoute {
                case .startDay: tonightPage
                case .checking: checkingPage
                case .freeWeek:
                    OnboardingFreeWeekPage(storeManager: storeManager, plan: $selectedPlan, family: familyPlan,
                                           today: timelineToday, now: checkoutNow)
                }
            }
        }
    }

    // MARK: Header

    private var header: some View {
        HStack {
            if step == .cover {
                Text("Whereday")
                    .font(TE.editorial(.title3).italic())
            } else {
                Button(action: goBack) {
                    Label("Back", systemImage: "chevron.left")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(TE.textMuted)
                        .frame(minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("onboarding-back")
            }
            Spacer()
            stepIndicators
        }
        .frame(minHeight: 44)
    }

    private var stepIndicators: some View {
        HStack(spacing: 6) {
            ForEach(OnboardingStep.allCases, id: \.self) { item in
                Capsule()
                    .fill(item == step ? TE.placeInks[1] : TE.border)
                    .frame(width: item == step ? 20 : 6, height: 6)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Step \(step.rawValue + 1) of \(Self.stepCount)"))
        // The identifier names the current step, so tests can wait for a page.
        .accessibilityIdentifier("onboarding-step-\(step.rawValue)")
    }

    // MARK: Shared pieces

    private func eyebrow(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(TE.mono(.caption2))
            .tracking(0.9)
            .foregroundStyle(TE.inkRust)
    }

    private func headline(_ text: LocalizedStringKey, size: CGFloat = 34) -> some View {
        Text(text)
            .font(WheredayFont.serif(size: size, relativeTo: .largeTitle))
            .lineSpacing(-2)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
    }

    private func titleBlock(_ eyebrowText: LocalizedStringKey, _ title: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            eyebrow(eyebrowText)
            headline(title)
        }
    }

    private func note(_ text: Text) -> some View {
        text
            .font(.footnote)
            .foregroundStyle(TE.textMuted)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func card<Content: View>(selected: Bool = false, @ViewBuilder content: () -> Content) -> some View {
        content()
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(selected ? TE.textPrimary : TE.border, lineWidth: selected ? 2 : 1))
            .contentShape(RoundedRectangle(cornerRadius: 16))
    }

    private func chip(_ title: String, isSelected: Bool, identifier: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .foregroundStyle(isSelected ? TE.surface : TE.textPrimary)
                .padding(.horizontal, 16)
                .frame(minHeight: 44)
                .background(Capsule().fill(isSelected ? TE.textPrimary : TE.card))
                .overlay(Capsule().strokeBorder(isSelected ? TE.textPrimary : TE.border, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier(identifier)
    }

    // MARK: 1 · Cover

    private var coverPage: some View {
        VStack(alignment: .leading, spacing: 28) {
            OnboardingCoverDial()
                .frame(width: 248, height: 248)
                .frame(maxWidth: .infinity)
            VStack(alignment: .leading, spacing: 10) {
                headline("Every day\nis a page.", size: 42)
                Text("Whereday quietly writes down where you go. In the next minute you’ll learn to read a page, then write the first line of yours.")
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: 2 · Intent

    private var intentPage: some View {
        VStack(alignment: .leading, spacing: 16) {
            titleBlock("PICK ANY · SHAPES YOUR SETUP", "What should\nit remember for you?")
            VStack(spacing: 8) {
                ForEach(OnboardingIntent.allCases) { intent in
                    intentRow(intent)
                }
            }
            note(intentNote)
        }
    }

    private func intentRow(_ intent: OnboardingIntent) -> some View {
        let isOn = intents.contains(intent)
        return Button {
            toggle(intent)
        } label: {
            card(selected: isOn) {
                HStack(spacing: 14) {
                    RoundedRectangle(cornerRadius: 12)
                        .fill(intentInk(intent))
                        .frame(width: 40, height: 40)
                        .overlay {
                            Text(verbatim: intentGlyph(intent))
                                .font(.custom(WheredayFont.serifFamily, fixedSize: 20))
                                .foregroundStyle(TE.surface)
                        }
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(intentTitle(intent)).font(.body.weight(.semibold))
                        Text(intentSummary(intent)).font(.footnote).foregroundStyle(TE.textMuted)
                    }
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    Image(systemName: isOn ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(isOn ? TE.textPrimary : TE.dialTick)
                        .accessibilityHidden(true)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isOn ? .isSelected : [])
        .accessibilityIdentifier("onboarding-intent-\(intent.rawValue)")
    }

    private func intentTitle(_ intent: OnboardingIntent) -> LocalizedStringKey {
        switch intent {
        case .days: "Remember my days"
        case .countries: "Count days in countries"
        case .routes: "Record runs and hikes"
        case .notes: "Send it to my notes"
        }
    }

    private func intentSummary(_ intent: OnboardingIntent) -> LocalizedStringKey {
        switch intent {
        case .days: "Look back at any day, place or trip."
        case .countries: "Travel days, residency, visas."
        case .routes: "Every step, with or without the phone."
        case .notes: "Daily Markdown for Obsidian and friends."
        }
    }

    private func intentInk(_ intent: OnboardingIntent) -> Color {
        switch intent {
        case .days: TE.placeInks[0]
        case .countries: TE.placeInks[1]
        case .routes: TE.placeInks[3]
        case .notes: TE.placeInks[2]
        }
    }

    private func intentGlyph(_ intent: OnboardingIntent) -> String {
        switch intent {
        case .days: "D"
        case .countries: "C"
        case .routes: "R"
        case .notes: "N"
        }
    }

    private var intentNote: Text {
        switch intents.count {
        case 0: Text("Pick at least one, or skip and set things up later.")
        case 1: Text("We’ll set up 1 thing for you. Everything else stays one tap away.")
        default: Text("We’ll set up \(intents.count) things for you. Everything else stays one tap away.")
        }
    }

    private func toggle(_ intent: OnboardingIntent) {
        if intents.contains(intent) {
            intents.remove(intent)
            if intent == .routes { recordingDetail = .routes }
        } else {
            intents.insert(intent)
            if intent == .routes { recordingDetail = .everyStep }
        }
    }

    // MARK: 3 · Scrub

    private var scrubPage: some View {
        let stop = OnboardingSampleStop.stop(at: scrubHour)
        let time = ClockTimeFormatter.string(from: OnboardingScrubDial.date(for: scrubHour))
        return VStack(alignment: .leading, spacing: 18) {
            titleBlock("HOW TO READ A PAGE · SAMPLE DAY", "Drag through a day.")
            OnboardingScrubDial(hour: $scrubHour)
                .frame(width: 232, height: 232)
                .frame(maxWidth: .infinity)
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(stopInk(stop))
                    .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(TE.onInkCard, lineWidth: 1.5))
                    .frame(width: 14, height: 14)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 0) {
                    Text(stop.name)
                        .font(WheredayFont.serif(size: 22, relativeTo: .title2))
                    Text(stop.detail)
                        .font(.footnote)
                        .foregroundStyle(TE.onInkCardMuted)
                }
                Spacer(minLength: 0)
            }
            .foregroundStyle(TE.onInkCard)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 16).fill(TE.inkCard))
            .accessibilityElement(children: .combine)
            .accessibilityLabel(Text("You were at \(String(localized: stop.name)), \(String(localized: stop.detail))"))
            VStack(alignment: .leading, spacing: 4) {
                Text("TIME OF DAY")
                    .font(TE.mono(.caption2))
                    .tracking(0.7)
                    .foregroundStyle(TE.textMuted)
                    .accessibilityHidden(true)
                Slider(value: $scrubHour, in: 0...23.75, step: 0.25) {
                    Text("Time of day")
                }
                .tint(TE.placeInks[1])
                .accessibilityValue(Text("\(time), \(String(localized: stop.name))"))
                .accessibilityIdentifier("onboarding-scrub")
                note(Text("Each colour is a place. Gaps are travel. Midnight sits at the top."))
            }
        }
    }

    private func stopInk(_ stop: OnboardingSampleStop) -> Color {
        switch stop.kind {
        case let .place(ink): TE.placeInks[ink]
        case .travel: TE.dialTick
        }
    }

    // MARK: 4 · Pen

    private var penPage: some View {
        VStack(alignment: .leading, spacing: 16) {
            titleBlock("RECORDING DETAIL", "Pick a pen.")
            OnboardingPenPreview(detail: recordingDetail)
            VStack(spacing: 8) {
                ForEach(RecordingDetail.allCases) { detail in
                    Button {
                        recordingDetail = detail
                    } label: {
                        card(selected: recordingDetail == detail) {
                            VStack(alignment: .leading, spacing: 3) {
                                HStack(alignment: .firstTextBaseline, spacing: 8) {
                                    Text(detail.title).font(.body.weight(.semibold))
                                    Spacer(minLength: 0)
                                    Text(penTag(detail))
                                        .font(TE.mono(.caption2))
                                        .foregroundStyle(TE.inkRust)
                                }
                                Text(penSummary(detail)).font(.footnote).foregroundStyle(TE.textMuted)
                            }
                            .multilineTextAlignment(.leading)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(recordingDetail == detail ? .isSelected : [])
                    .accessibilityIdentifier("onboarding-pen-\(detail.onboardingKey)")
                }
            }
        }
    }

    private func penTag(_ detail: RecordingDetail) -> LocalizedStringKey {
        switch detail {
        case .places: "100 m"
        case .routes: "RECOMMENDED"
        case .everyStep: "5 m · 2 h"
        }
    }

    private func penSummary(_ detail: RecordingDetail) -> LocalizedStringKey {
        switch detail {
        case .places: "Lightest on battery. Where you stopped, roughly how you got there."
        case .routes: "Every visit and the streets between them. 25 m detail."
        case .everyStep: "For hikes and runs. Turns itself off after 2 hours."
        }
    }

    // MARK: 5 · Permission

    private var permissionPage: some View {
        VStack(alignment: .leading, spacing: 18) {
            titleBlock("LOCATION ACCESS", "Let it write while\nit’s in your pocket.")
            VStack(alignment: .leading, spacing: 12) {
                comparisonRow("Always", "the whole day", parts: [
                    (35, TE.placeInks[0]), (4, TE.placeInks[1]), (31, TE.placeInks[2]), (6, TE.placeInks[3]), (18, TE.placeInks[0])
                ], track: nil)
                comparisonRow("While using", "only when it’s open", parts: [
                    (36, nil), (3, TE.placeInks[1]), (40, nil), (2, TE.placeInks[3]), (19, nil)
                ], track: TE.dialTrack)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.border, lineWidth: 1))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("With Always, the whole day is recorded. With While Using, only the moments the app is open."))
            VStack(spacing: 0) {
                Divider().overlay(TE.border)
                privacyRow("01", "No account. Nothing to sign up for.")
                privacyRow("02", "No server keeps a copy of where you’ve been.")
                privacyRow("03", "Export anytime in open formats.")
            }
            Text(permissionNote)
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func comparisonRow(_ title: LocalizedStringKey, _ detail: LocalizedStringKey,
                               parts: [(weight: Double, ink: Color?)], track: Color?) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title).font(.subheadline.weight(.semibold))
                Spacer()
                Text(detail).font(.subheadline).foregroundStyle(TE.textMuted)
            }
            OnboardingProportionBar(parts: parts, track: track)
        }
    }

    private func privacyRow(_ number: String, _ text: LocalizedStringKey) -> some View {
        VStack(spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(verbatim: number)
                    .font(TE.mono(.caption))
                    .foregroundStyle(TE.inkRust)
                    .frame(width: 22, alignment: .leading)
                    .accessibilityHidden(true)
                Text(text)
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 11)
            Divider().overlay(TE.border)
        }
    }

    private var permissionNote: LocalizedStringKey {
        switch locationManager.authorizationStatus {
        case .authorizedWhenInUse: "Allow Always so Whereday records when it's closed. You can change this anytime."
        case .denied, .restricted: "Location access is off. Turn it on in iPhone Settings → Whereday → Location."
        case .authorizedAlways: "You can change this anytime."
        default: "iOS asks twice: “While Using” first, then “Always” later. Choose Always the second time."
        }
    }

    // MARK: 6 · Stamp

    private var stampPage: some View {
        VStack(alignment: .leading, spacing: 16) {
            titleBlock("FIRST STAMP · JUST NOW", "You’re here.")
            stampMap
            if stampFix != nil {
                VStack(alignment: .leading, spacing: 10) {
                    Text("What is this place?").font(.body.weight(.semibold))
                    OnboardingFlowLayout(spacing: 8) {
                        ForEach(OnboardingPlaceChoice.allCases) { choice in
                            chip(choice.chipTitle, isSelected: placeChoice == choice,
                                 identifier: "onboarding-place-\(choice.rawValue)") {
                                placeChoice = placeChoice == choice ? nil : choice
                                customNameFocused = placeChoice == .other
                            }
                        }
                    }
                    if placeChoice == .other {
                        TextField("Name this place", text: $customPlaceName)
                            .textInputAutocapitalization(.words)
                            .submitLabel(.done)
                            .focused($customNameFocused)
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .background(RoundedRectangle(cornerRadius: 12).fill(TE.card))
                            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(TE.border, lineWidth: 1))
                            .accessibilityIdentifier("onboarding-place-name")
                    }
                    note(Text(placeChoice == nil
                              ? "Name it once, and every visit here is labeled automatically."
                              : "Next time you’re here, the visit is named for you."))
                }
            } else {
                note(Text(stampWaiting
                          ? "Finding where you are…"
                          : "We’ll stamp your first place once location is on. You can name places anytime from the map."))
            }
        }
    }

    /// Still waiting for a first fix, with permission to get one.
    private var stampWaiting: Bool {
        locationManager.hasLocationPermission && stampFix == nil && !stampFixTimedOut
    }

    @ViewBuilder
    private var stampMap: some View {
        Group {
            if let stampFix {
                OnboardingStampMap(coordinate: stampFix.coordinate)
            } else {
                ZStack {
                    TE.dialTrack.opacity(0.6)
                    if stampWaiting {
                        ProgressView()
                    } else {
                        Image(systemName: "location.slash")
                            .font(.title)
                            .foregroundStyle(TE.textMuted)
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Text(stampWaiting ? "Finding where you are" : "Location is off"))
            }
        }
        .frame(height: 210)
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.border, lineWidth: 1))
    }

    /// The name that will be saved for the stamped place, if any.
    private var pendingPlaceName: String? {
        guard stampFix != nil, let placeChoice else { return nil }
        let name = placeChoice == .other
            ? customPlaceName.trimmingCharacters(in: .whitespacesAndNewlines)
            : placeChoice.kind.label
        return name.isEmpty ? nil : name
    }

    private func captureStampFix(_ location: CLLocation?) {
        guard step == .stamp || step == .permission, stampFix == nil, let location,
              location.horizontalAccuracy >= 0,
              abs(location.timestamp.timeIntervalSinceNow) < 600 else { return }
        stampFix = location
    }

    private func requestStampFix() {
        guard stampFix == nil, locationManager.hasLocationPermission else { return }
        stampFixTimedOut = false
        captureStampFix(locationManager.currentLocation)
        guard stampFix == nil else { return }
        locationManager.requestCurrentLocationFix()
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(20))
            if stampFix == nil { stampFixTimedOut = true }
        }
    }

    private func saveStamp() {
        guard let name = pendingPlaceName, let fix = stampFix, let placeChoice else { return }
        let draft = SavedPlaceDraft(
            name: name,
            latitude: fix.coordinate.latitude,
            longitude: fix.coordinate.longitude,
            radiusMeters: LocationViewModel.defaultSavedPlaceRadiusMeters,
            kind: placeChoice.kind
        )
        do {
            try viewModel.saveManagedPlace(draft)
            stampedPlaceName = name
        } catch {
            LogManager.shared.warning("Onboarding couldn't save the first place · \(error.localizedDescription)", category: .general)
        }
    }

    private var stampedTitle: String {
        stampedPlaceName ?? String(localized: "Your first place")
    }

    // MARK: 7 · Backfill

    private var backfillPage: some View {
        let counts = photoDayCounts ?? Array(repeating: 0, count: PhotoDayHistogram.dayCount)
        let showsPhotos = photoDayCounts != nil
        let found = PhotoDayHistogram.daysFound(in: counts)
        return VStack(alignment: .leading, spacing: 16) {
            titleBlock("DON’T START ON A BLANK PAGE", "Fill earlier pages.")
            Text("Your photos already know where you’ve been. Whereday pins them to days and places. They never leave your library.")
                .font(.subheadline)
                .foregroundStyle(TE.textMuted)
                .fixedSize(horizontal: false, vertical: true)
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .firstTextBaseline) {
                    Text("LAST 12 WEEKS")
                        .font(TE.mono(.caption2))
                        .tracking(0.7)
                        .foregroundStyle(TE.textMuted)
                    Spacer()
                    Group {
                        if !showsPhotos {
                            Text("Today only")
                        } else if found == 1 {
                            Text("1 day found")
                        } else {
                            Text("\(found) days found")
                        }
                    }
                    .font(TE.mono(.caption, weight: .medium))
                }
                OnboardingHeatmap(counts: counts, showsPhotos: showsPhotos)
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.border, lineWidth: 1))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("Last 12 weeks"))
            .accessibilityValue(showsPhotos
                                ? Text("\(found) days with geotagged photos")
                                : Text("Only today is marked"))
            .accessibilityIdentifier("onboarding-heatmap")

            Toggle(isOn: Binding(
                get: { automaticPhotoSyncEnabled },
                set: { handleAutomaticPhotoSyncToggle($0) }
            )) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Match photos to places").font(.body.weight(.semibold))
                    Text("Asks for Photos access. You can turn it off later.")
                        .font(.footnote)
                        .foregroundStyle(TE.textMuted)
                }
            }
            .toggleStyle(TEToggleStyle())
            .disabled(isConfiguringAutomaticPhotoSync)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.border, lineWidth: 1))
            .accessibilityIdentifier("onboarding-photos-toggle")

            Button {
                completeOnboarding(opensImport: true)
            } label: {
                Text("Import a Google Timeline or Whereday backup")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(TE.inkRust)
                    .multilineTextAlignment(.leading)
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("onboarding-backfill-import")
        }
    }

    private func handleAutomaticPhotoSyncToggle(_ isEnabled: Bool) {
        automaticPhotoSyncEnabled = isEnabled

        guard isEnabled else {
            viewModel.setAutomaticPhotoSyncEnabled(false)
            photoDayCounts = nil
            return
        }

        Task { @MainActor in
            isConfiguringAutomaticPhotoSync = true
            await viewModel.requestPhotoLibraryAccessAndStartAutomaticSync()
            automaticPhotoSyncEnabled = LocationViewModel.isAutomaticPhotoSyncEnabled && viewModel.photoLibraryAccessState.canRead
            isConfiguringAutomaticPhotoSync = false
            await loadPhotoDays()
        }
    }

    /// Counts real days with geotagged photos, off the main thread. Nothing leaves the device.
    private func loadPhotoDays() async {
        guard automaticPhotoSyncEnabled, PhotoLibraryManager.shared.authorizationState.canRead else {
            photoDayCounts = nil
            return
        }
        let today = Date()
        photoDayCounts = await Task.detached(priority: .userInitiated) {
            OnboardingPhotoDays.counts(today: today)
        }.value
    }

    // MARK: 8 · Glance

    private var glancePage: some View {
        VStack(alignment: .leading, spacing: 16) {
            titleBlock("LOCK SCREEN · APPLE WATCH", "Check without\nopening it.")
            OnboardingLiveActivityCard(placeTitle: stampedPlaceName.map { String(localized: "Leaving \($0)") }
                                       ?? String(localized: "Recording route"))
            card {
                HStack(spacing: 14) {
                    OnboardingWatchFace()
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Leave the phone at home").font(.body.weight(.semibold))
                        Text("Your Watch records the run and hands the points to your iPhone when they’re back together.")
                            .font(.footnote)
                            .foregroundStyle(TE.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .accessibilityElement(children: .combine)
            if !liveActivitiesAllowed {
                note(Text("Live Activities are turned off for Whereday. Turn them on in iPhone Settings → Whereday to see recording on your Lock Screen."))
            }
        }
    }

    // MARK: 9 · Export

    private var selectedExportSample: OnboardingExportSample {
        exportSample ?? .preferred(for: intents)
    }

    private var exportPage: some View {
        let sample = selectedExportSample
        return VStack(alignment: .leading, spacing: 16) {
            titleBlock("OPEN FORMATS · NO LOCK-IN", "Your history,\nyour files.")
            OnboardingFlowLayout(spacing: 8) {
                ForEach(OnboardingExportSample.allCases) { format in
                    chip(format.label, isSelected: sample == format, identifier: "onboarding-format-\(format.rawValue)") {
                        exportSample = format
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Text("Export format"))
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text(verbatim: sample.fileName)
                        .font(TE.mono(.caption, weight: .medium))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    Spacer(minLength: 8)
                    Text("SAMPLE DAY")
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.textMuted)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                Divider().overlay(TE.border)
                Text(verbatim: sample.body)
                    .font(TE.mono(.caption))
                    .lineSpacing(4)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, minHeight: 128, alignment: .topLeading)
                    .padding(14)
            }
            .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.border, lineWidth: 1))
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("onboarding-export-preview")
            note(Text("\(Text(formatNote(sample))) Schedule it daily to a folder, or export once. Exports are part of the one-time unlock; recording isn’t."))
        }
    }

    private func formatNote(_ sample: OnboardingExportSample) -> LocalizedStringKey {
        switch sample {
        case .markdown: "One note per day, with dated folders like {year}/{month}."
        case .gpx: "Opens in Strava, gpx.studio and most mapping tools."
        case .csv: "Drop it into Numbers, Sheets or a script."
        case .json: "A full backup you can import again on a new phone."
        }
    }

    // MARK: 10 · Start my day (access already in place)

    private var tonightPage: some View {
        VStack(alignment: .leading, spacing: 18) {
            titleBlock("YOUR FIRST DAY", "Recording starts\nnow.")
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .firstTextBaseline) {
                    Text(Date.now.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()))
                        .font(WheredayFont.serif(size: 24, relativeTo: .title2))
                    Spacer()
                    Text("DRAFTING")
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.inkRust)
                }
                HStack(spacing: 10) {
                    Circle().fill(TE.textPrimary).frame(width: 10, height: 10)
                    Text("\(stampedTitle) · since this morning").font(.subheadline)
                }
                ForEach([170.0, 120.0], id: \.self) { width in
                    HStack(spacing: 10) {
                        Circle()
                            .strokeBorder(TE.dialTick, style: StrokeStyle(lineWidth: 1.5, dash: [2, 2]))
                            .frame(width: 10, height: 10)
                        Capsule().fill(TE.dialTrack).frame(maxWidth: width).frame(height: 10)
                    }
                    .accessibilityHidden(true)
                }
                note(Text("Places, distance and photos fill in as you go."))
            }
            .padding(20)
            .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.dialTick, style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
            .accessibilityElement(children: .combine)

            recapToggle
        }
    }

    /// Every evening at the configured time (9 PM unless Settings › Reminders says otherwise).
    private var recapToggle: some View {
        let settings = RecapReminderScheduler.shared.settings
        let time = RecapTimeFormatter.string(hour: settings.hour, minute: settings.minute)
        let now = Date.now
        let startsTonight = RecapSchedule.nextFire(after: now, frequency: .daily, hour: settings.hour, minute: settings.minute)
            .map { RecapSchedule.startsToday(fire: $0, now: now) } ?? true
        return Toggle(isOn: $recapEnabled) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Evening recap").font(.body.weight(.semibold))
                Text(startsTonight
                     ? "Every night at \(time), starting tonight. Change it in Settings › Reminders."
                     : "Every night at \(time), starting tomorrow. Change it in Settings › Reminders.")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .toggleStyle(TEToggleStyle())
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.border, lineWidth: 1))
        .accessibilityIdentifier("onboarding-recap-toggle")
    }

    // MARK: 10 · Free week (for someone who needs a plan)

    /// What onboarding really set up; missing values drop their clause.
    private var timelineToday: OnboardingTrialTimeline.Today {
        OnboardingTrialTimeline.Today(
            placeName: stampedPlaceName,
            earlierPhotoDays: OnboardingTrialTimeline.earlierPhotoDays(in: photoDayCounts),
            canRecord: locationManager.hasLocationPermission
        )
    }

    private var checkingPage: some View {
        VStack(alignment: .leading, spacing: 16) {
            titleBlock("YOUR FREE WEEK", "Recording starts\nnow, on us.")
            ProgressView("Checking purchases…")
                .font(.footnote)
                .frame(maxWidth: .infinity, minHeight: 88)
        }
    }

    /// After a purchase, trial start or restore: the only place notifications are asked for.
    private var trialDonePage: some View {
        VStack(alignment: .leading, spacing: 18) {
            ZStack {
                Circle().fill(TE.textPrimary)
                Image(systemName: "checkmark")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundStyle(TE.surface)
            }
            .frame(width: 56, height: 56)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: trialDoneKicker)
                    .font(TE.mono(.caption2))
                    .tracking(0.9)
                    .foregroundStyle(TE.inkRust)
                headline("You’re in.", size: 36)
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("onboarding-trial-done")
            Text(locationManager.hasLocationPermission
                 ? "Recording starts now. Go about your day; Whereday writes it down."
                 : "Allow location in iPhone Settings and Whereday starts writing your days.")
                .foregroundStyle(TE.textMuted)
                .fixedSize(horizontal: false, vertical: true)
            recapToggle
        }
    }

    private var trialDoneKicker: String {
        guard case let .done(completion) = checkout.phase else { return "" }
        switch completion {
        case let .trial(ends):
            let date = OnboardingFreeWeekCopy.shortDate(ends ?? OnboardingFreeWeekCopy.trialEnd(from: checkoutNow))
            return String(localized: "Free until \(date)").uppercased()
        case .subscription: return String(localized: "Your plan is active").uppercased()
        case .lifetime: return String(localized: "Lifetime · purchased").uppercased()
        case .restored: return String(localized: "Purchase restored").uppercased()
        }
    }

    // MARK: Controls

    @ViewBuilder
    private var controls: some View {
        if showsFreeWeek {
            OnboardingFreeWeekControls(storeManager: storeManager, checkout: checkout, plan: selectedPlan,
                                       family: familyPlan, now: checkoutNow) {
                showsAllPlans = true
            }
        } else {
            standardControls
        }
    }

    private var standardControls: some View {
        VStack(spacing: 6) {
            Button(action: handlePrimaryAction) {
                Text(primaryTitle)
            }
            .buttonStyle(TEActionButtonStyle(emphasis: .primary))
            .disabled(isFinishing || (step == .backfill && isConfiguringAutomaticPhotoSync)
                      || (step == .tonight && !checkout.isDone && finalRoute == .checking))
            .accessibilityIdentifier("onboarding-next")

            if let secondaryTitle {
                Button(action: handleSecondaryAction) {
                    Text(secondaryTitle)
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(TE.textMuted)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier(step == .cover ? "onboarding-import" : "onboarding-skip")
            }
        }
    }

    private var primaryTitle: LocalizedStringKey {
        switch step {
        case .cover: return "Start my journal"
        case .intent: return intents.isEmpty ? "Skip" : "Continue"
        case .scrub: return "Got it"
        case .pen: return "Use this pen"
        case .permission:
            switch locationManager.authorizationStatus {
            case .notDetermined: return "Allow location"
            case .authorizedWhenInUse: return "Allow Always"
            case .denied, .restricted: return "Open iPhone Settings"
            default: return "Continue"
            }
        case .stamp:
            if let name = pendingPlaceName { return "Save “\(name)”" }
            return stampFix == nil && !stampWaiting ? "Continue" : "Skip for now"
        case .backfill, .export: return "Continue"
        case .glance: return liveActivitiesAllowed ? "Show Live Activity" : "Continue"
        case .tonight:
            if checkout.isDone { return "Open today’s map" }
            return finalRoute == .checking ? "Start my free week" : "Start my day"
        }
    }

    private var secondaryTitle: LocalizedStringKey? {
        switch step {
        case .cover: return "I already have data — import"
        case .permission:
            switch locationManager.authorizationStatus {
            case .authorizedAlways: return nil
            case .authorizedWhenInUse: return "Continue without Always"
            default: return "Not now"
            }
        case .backfill: return "Skip — start fresh"
        case .glance: return "Not now"
        case .export: return "Set up later"
        default: return nil
        }
    }

    private func handlePrimaryAction() {
        switch step {
        case .intent:
            OnboardingIntentStore.save(intents)
            advance()
        case .pen:
            recordingDetail.apply(to: locationManager)
            advance()
        case .permission:
            recordingDetail.apply(to: locationManager)
            switch locationManager.authorizationStatus {
            case .notDetermined: locationManager.requestWhenInUseAuthorization()
            case .authorizedWhenInUse: locationManager.requestAlwaysAuthorization()
            case .denied, .restricted: openSettings()
            default: advance()
            }
        case .stamp:
            saveStamp()
            advance()
        case .glance:
            locationManager.setLiveActivityEnabled(true)
            advance()
        case .tonight:
            if checkout.isDone {
                finishAfterPurchase()
            } else if finalRoute == .startDay {
                finish()
            }
        default:
            advance()
        }
    }

    private func handleSecondaryAction() {
        switch step {
        case .cover:
            OnboardingIntentStore.save(intents)
            onComplete(false, true)
        default:
            // "Not now", "Skip — start fresh", "Set up later": move on without changing anything.
            advance()
        }
    }

    private func advance() {
        guard let next = OnboardingStep(rawValue: step.rawValue + 1) else { return }
        go(to: next)
    }

    private func goBack() {
        guard let previous = OnboardingStep(rawValue: step.rawValue - 1) else { return }
        go(to: previous)
    }

    private func go(to newStep: OnboardingStep) {
        customNameFocused = false
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) { step = newStep }
    }

    /// Work a screen needs when it appears.
    private func prepare(_ step: OnboardingStep) {
        switch step {
        case .stamp:
            requestStampFix()
        case .backfill:
            Task { await loadPhotoDays() }
        case .glance:
            liveActivitiesAllowed = LiveActivityManager.shared.areActivitiesEnabled
        case .tonight:
            checkoutNow = Date()
            if storeManager.productsByID.isEmpty {
                Task { await storeManager.loadProducts() }
            }
        default:
            break
        }
    }

    private func completeOnboarding(opensImport: Bool) {
        OnboardingIntentStore.save(intents)
        ExportPreferencesStore.seedFromOnboarding(intents: intents)
        onComplete(locationManager.hasLocationPermission, opensImport)
    }

    private func finish() {
        guard !isFinishing else { return }
        isFinishing = true
        let recapOn = recapEnabled
        Task { @MainActor in
            // Asks for notification permission only when the recap is on, and only now.
            await RecapReminderScheduler.shared.applyOnboardingChoice(enabled: recapOn)
            completeOnboarding(opensImport: false)
        }
    }

    /// The "You're in" action: notifications are asked for and scheduled only now, after the purchase.
    private func finishAfterPurchase() {
        guard !isFinishing else { return }
        isFinishing = true
        let recapOn = recapEnabled
        Task { @MainActor in
            await checkout.finish(recapEnabled: recapOn)
            completeOnboarding(opensImport: false)
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

// MARK: - Services

/// Reads only creation dates and whether a photo has a location; never uploads anything.
enum OnboardingPhotoDays {
    static func counts(today: Date, calendar: Calendar = .current) -> [Int] {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(
            format: "creationDate >= %@",
            PhotoDayHistogram.startDate(today: today, calendar: calendar) as NSDate
        )
        options.includeAssetSourceTypes = [.typeUserLibrary, .typeCloudShared, .typeiTunesSynced]
        let result = PHAsset.fetchAssets(with: .image, options: options)
        var dates: [Date] = []
        dates.reserveCapacity(result.count)
        result.enumerateObjects { asset, _, _ in
            guard let date = asset.creationDate,
                  let location = asset.location,
                  CLLocationCoordinate2DIsValid(location.coordinate) else { return }
            dates.append(date)
        }
        return PhotoDayHistogram.counts(for: dates, today: today, calendar: calendar)
    }
}

// MARK: - Small views

/// A still map of the first fix with a pulsing pin.
private struct OnboardingStampMap: View {
    let coordinate: CLLocationCoordinate2D
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pulsing = false

    var body: some View {
        Map(initialPosition: .region(MKCoordinateRegion(center: coordinate, latitudinalMeters: 450, longitudinalMeters: 450)),
            interactionModes: []) {
            Annotation("", coordinate: coordinate, anchor: .center) {
                ZStack {
                    Circle()
                        .fill(TE.placeInks[1].opacity(0.1))
                        .overlay(Circle().strokeBorder(TE.placeInks[1].opacity(0.4), lineWidth: 1))
                        .frame(width: 80, height: 80)
                    if !reduceMotion {
                        Circle()
                            .fill(TE.placeInks[1])
                            .frame(width: 32, height: 32)
                            .scaleEffect(pulsing ? 2.2 : 0.4)
                            .opacity(pulsing ? 0 : 0.6)
                            .animation(.easeOut(duration: 2).repeatForever(autoreverses: false), value: pulsing)
                    }
                    Circle()
                        .fill(TE.placeInks[1])
                        .overlay(Circle().strokeBorder(TE.card, lineWidth: 3))
                        .frame(width: 18, height: 18)
                }
                .onAppear { pulsing = true }
            }
        }
        .mapStyle(.standard(pointsOfInterest: .excludingAll))
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Map with a pin at your current location"))
        .accessibilityIdentifier("onboarding-stamp-map")
    }
}

/// Wraps chips onto as many lines as they need.
struct OnboardingFlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(width: proposal.width ?? .infinity, subviews: subviews)
        let height = rows.last.map { $0.y + $0.height } ?? 0
        let width = rows.map(\.width).max() ?? 0
        return CGSize(width: proposal.width ?? width, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let rows = arrange(width: bounds.width, subviews: subviews)
        for row in rows {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: bounds.minY + row.y), proposal: ProposedViewSize(size))
                x += size.width + spacing
            }
        }
    }

    private struct Row {
        var indices: [Int] = []
        var y: CGFloat = 0
        var width: CGFloat = 0
        var height: CGFloat = 0
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> [Row] {
        var rows: [Row] = []
        var current = Row()
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            let needed = current.indices.isEmpty ? size.width : current.width + spacing + size.width
            if needed > width, !current.indices.isEmpty {
                rows.append(current)
                current = Row(y: current.y + current.height + spacing)
            }
            current.width = current.indices.isEmpty ? size.width : current.width + spacing + size.width
            current.indices.append(index)
            current.height = max(current.height, size.height)
        }
        if !current.indices.isEmpty { rows.append(current) }
        return rows
    }
}
