import SwiftUI
import StoreKit

struct PaywallView: View {
    @ObservedObject var storeManager: StoreManager
    var context: PaywallContext = .export
    var isRequired = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var family = false
    @State private var selectedOption: IsoMePurchaseOption = .individualYearly
    @State private var showSubscriptionManagement = false

    private var managing: Bool { context == .settings && storeManager.isPurchased }
    private var options: [IsoMePurchaseOption] {
        family ? [.familyWeekly, .familyYearly, .family] : [.individualWeekly, .individualYearly, .individual]
    }
    /// Only plans the App Store actually returned are offered.
    private var offeredOptions: [IsoMePurchaseOption] {
        options.filter { storeManager.product(for: $0) != nil }
    }
    private var usesTwoPanes: Bool { horizontalSizeClass == .regular && !dynamicTypeSize.isAccessibilitySize }

    var body: some View {
        Group {
            if usesTwoPanes {
                twoPaneLayout
            } else {
                singleColumnLayout
            }
        }
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .tint(TE.textPrimary)
        .manageSubscriptionsSheet(isPresented: $showSubscriptionManagement)
        .interactiveDismissDisabled(isRequired)
        .onChange(of: family) { _, value in
            switch selectedOption {
            case .individualWeekly, .familyWeekly: selectedOption = value ? .familyWeekly : .individualWeekly
            case .individual, .family: selectedOption = value ? .family : .individual
            default: selectedOption = value ? .familyYearly : .individualYearly
            }
            keepSelectionOffered()
        }
        .onChange(of: storeManager.productsByID.count) { _, _ in keepSelectionOffered() }
        .onAppear {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--review-family-paywall") {
                family = true
                selectedOption = .familyYearly
            }
            #endif
        }
        .task {
            await storeManager.loadProducts()
        }
    }

    /// If the chosen plan isn't on sale, fall back to yearly, then whatever is.
    private func keepSelectionOffered() {
        let offered = offeredOptions
        guard !offered.isEmpty, !offered.contains(selectedOption) else { return }
        selectedOption = offered.first(where: { $0 == .individualYearly || $0 == .familyYearly }) ?? offered[0]
    }

    // MARK: - Layouts

    private var singleColumnLayout: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    eyebrow
                    Spacer(minLength: 12)
                    closeButton
                }
                .padding(.horizontal, 20)
                .padding(.top, 12)

                VStack(alignment: .leading, spacing: 10) {
                    JourneyMapIllustration(ink: TE.textPrimary, paper: TE.card)
                        .frame(height: 96)
                        .accessibilityHidden(true)
                    headline
                        .font(TE.editorial(.largeTitle))
                    subheadline
                        .font(.subheadline)
                        .foregroundStyle(TE.textMuted)
                }
                .padding(.horizontal, 24)
                .padding(.top, 14)

                planContent
                    .padding(.horizontal, 20)
                    .padding(.top, 18)

                notes
                    .padding(.horizontal, 24)
                    .padding(.top, 18)
            }
            .padding(.bottom, 16)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .scrollIndicators(.hidden)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            footer
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .background(TE.surface.ignoresSafeArea(edges: .bottom))
        }
    }

    /// The wide layout: the promise on an ink page, the plans beside it.
    private var twoPaneLayout: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                Text("YOUR WHEREDAY PLAN")
                    .font(TE.mono(.caption2))
                    .tracking(1.5)
                    .foregroundStyle(TE.onInkCardMuted)
                Spacer(minLength: 16)
                JourneyMapIllustration(ink: TE.onInkCard, paper: TE.inkCard)
                    .frame(height: 150)
                    .accessibilityHidden(true)
                Spacer(minLength: 16)
                VStack(alignment: .leading, spacing: 10) {
                    headline
                        .font(TE.editorial(.largeTitle))
                    subheadline
                        .font(.subheadline)
                        .foregroundStyle(TE.onInkCardMuted)
                }
            }
            .foregroundStyle(TE.onInkCard)
            .padding(.horizontal, 36)
            .padding(.vertical, 40)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
            .background(TE.inkCard.ignoresSafeArea())

            VStack(spacing: 0) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack {
                            Spacer(minLength: 0)
                            closeButton
                        }
                        planContent
                            .padding(.top, 8)
                        notes
                            .padding(.top, 16)
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 20)
                }
                .scrollIndicators(.hidden)
                footer
            }
            .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Pieces

    private var eyebrow: some View {
        Text("YOUR WHEREDAY PLAN")
            .font(TE.mono(.caption2))
            .tracking(1.5)
            .foregroundStyle(TE.textMuted)
    }

    @ViewBuilder
    private var closeButton: some View {
        if !isRequired {
            BackPageCircleButton(systemImage: "xmark", label: "Close") { dismiss() }
        }
    }

    private var headline: Text {
        if managing {
            return Text("Your Whereday plan")
        }
        // The second line is set in italic, as on the printed page.
        let lines = String(localized: "A life of places.\nYours to keep.").split(separator: "\n", maxSplits: 1)
        guard lines.count == 2 else { return Text(String(localized: "A life of places.\nYours to keep.")) }
        return Text(verbatim: "\(lines[0])\n") + Text(verbatim: String(lines[1])).italic()
    }

    private var subheadline: Text {
        managing
            ? Text("Manage your purchase and Family Sharing.")
            : Text("Track your days. Revisit your places.\nKeep your history private and on-device.")
    }

    @ViewBuilder
    private var planContent: some View {
        VStack(alignment: .leading, spacing: 12) {
            if managing {
                activePlanCard
            } else {
                audiencePicker
                if offeredOptions.isEmpty {
                    unavailablePlans
                } else {
                    ForEach(offeredOptions) { option in
                        planCard(option)
                    }
                }
                if family {
                    HStack(alignment: .top, spacing: 10) {
                        Image(systemName: "person.3")
                            .foregroundStyle(TE.textMuted)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Share with up to 5 family members using Apple Family Sharing")
                            Text("Family Sharing shares access, not your location journal.")
                        }
                        .fixedSize(horizontal: false, vertical: true)
                    }
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
                    .accessibilityElement(children: .combine)
                }
                if selectedOption.isSubscription, storeManager.product(for: selectedOption) != nil {
                    Text("Cancel in Apple subscription settings at least 24 hours before renewal.")
                        .font(.caption)
                        .foregroundStyle(TE.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if let error = storeManager.purchaseError ?? storeManager.productLoadError {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(TE.inkRust)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var audiencePicker: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(spacing: 10))
        return layout {
            Text("WHO IS THIS FOR?")
                .font(TE.mono(.caption2))
                .tracking(1.2)
                .foregroundStyle(TE.textMuted)
            if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 0) }
            BackPageSegmented(
                label: "Who is this for?",
                options: [(false, "Individual"), (true, "Family")],
                selection: $family
            )
        }
    }

    private var unavailablePlans: some View {
        VStack(alignment: .leading, spacing: 10) {
            if storeManager.isLoadingProducts {
                ProgressView("Loading plans…")
                    .font(.footnote)
            } else {
                Button("Retry loading plans") { Task { await storeManager.loadProducts() } }
                    .buttonStyle(BackPagePillButtonStyle(kind: .outlined, minHeight: 48))
                    .disabled(storeManager.isLoading)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 88)
    }

    private func planCard(_ option: IsoMePurchaseOption) -> some View {
        let isSelected = selectedOption == option
        let trial = storeManager.trialEligibleProductIDs.contains(option.productID)
        let product = storeManager.product(for: option)
        return Button { selectedOption = option } label: {
            VStack(alignment: .leading, spacing: 4) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(verbatim: option.title)
                        .font(TE.editorial(.title3))
                    Spacer(minLength: 4)
                    if trial {
                        Text("7 DAYS FREE")
                            .font(TE.mono(.caption2))
                            .foregroundStyle(TE.card)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(RoundedRectangle(cornerRadius: 10).fill(TE.placeInks[1]))
                    } else if let product {
                        Text(verbatim: product.displayPrice)
                            .font(TE.mono(.footnote))
                            .monospacedDigit()
                    }
                }
                if let product {
                    Text(PaywallCopy.disclosure(for: option, displayPrice: product.displayPrice, hasTrial: trial))
                        .font(.footnote)
                        .foregroundStyle(TE.textMuted)
                }
            }
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 18)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
            .overlay(
                RoundedRectangle(cornerRadius: 18)
                    .strokeBorder(isSelected ? TE.placeInks[1] : TE.border, lineWidth: isSelected ? 2 : 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 18))
        }
        .buttonStyle(PaywallInkPressStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var activePlanCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(verbatim: storeManager.activePurchaseOption?.title ?? String(localized: "Active purchase"))
                .font(TE.editorial(.title3))
            if let expiration = storeManager.trackingExpiration, storeManager.activePurchaseOption?.isSubscription == true {
                Text("Current period ends \(expiration.formatted(date: .abbreviated, time: .omitted)).")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
            }
            if storeManager.canBuyFamilyUpgrade {
                Text("Add Apple Family Sharing to your lifetime purchase.")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
                if let product = storeManager.product(for: .familyUpgrade) {
                    Text("\(product.displayPrice), one-time purchase")
                        .font(TE.mono(.footnote))
                }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.placeInks[1], lineWidth: 2))
        .accessibilityElement(children: .combine)
    }

    private var notes: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Your existing records remain available to view and export if your subscription ends.")
            Text("Deleting the app or buying Lifetime does not cancel an existing subscription. Manage it in Apple Account settings.")
        }
        .font(.caption)
        .foregroundStyle(TE.textMuted)
        .fixedSize(horizontal: false, vertical: true)
    }

    private var footer: some View {
        VStack(spacing: 6) {
            if !managing {
                purchaseButton(selectedOption)
            } else if storeManager.canBuyFamilyUpgrade {
                purchaseButton(.familyUpgrade)
            } else if storeManager.activePurchaseOption?.isSubscription == true {
                Button("Manage subscription") { showSubscriptionManagement = true }
                    .buttonStyle(BackPagePillButtonStyle(kind: .filled, font: .body.weight(.semibold), minHeight: 56))
            }
            HStack(spacing: 4) {
                Button("Restore Purchases") { restore() }
                    .disabled(storeManager.isLoading)
                    .padding(.horizontal, 10)
                    .frame(minHeight: 44)
                if let url = IsoMeURLs.privacyPolicy {
                    Link("Privacy", destination: url)
                        .padding(.horizontal, 10)
                        .frame(minHeight: 44)
                }
                if let url = IsoMeURLs.termsOfService {
                    Link("Terms", destination: url)
                        .padding(.horizontal, 10)
                        .frame(minHeight: 44)
                }
            }
            .font(.footnote)
            .buttonStyle(.plain)
            .modifier(BackPageWrapIfNeeded())
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
        .padding(.bottom, 8)
    }

    private func purchaseButton(_ option: IsoMePurchaseOption) -> some View {
        Button {
            Task {
                let result = await storeManager.purchase(option)
                if result == .succeeded && !isRequired { dismiss() }
            }
        } label: {
            HStack(spacing: 10) {
                if storeManager.purchasingOption == option { ProgressView().tint(TE.surface) }
                Text(PaywallCopy.purchaseButtonTitle(for: option, hasTrial: storeManager.trialEligibleProductIDs.contains(option.productID)))
            }
        }
        .buttonStyle(BackPagePillButtonStyle(kind: .filled, font: .body.weight(.semibold), minHeight: 56))
        .accessibilityIdentifier("paywall-purchase")
        .disabled(storeManager.product(for: option) == nil || storeManager.isLoading)
    }

    private func restore() {
        Task {
            let result = await storeManager.restorePurchases()
            if result == .restored && !isRequired { dismiss() }
        }
    }
}

/// Complete localized sentences let translators choose grammar and price placement.
/// StoreKit's already-localized price is inserted verbatim; it is never parsed or converted.
nonisolated enum PaywallCopy {
    static func disclosure(for option: IsoMePurchaseOption, displayPrice: String, hasTrial: Bool, bundle: Bundle = .main) -> String {
        switch option {
        case .individualWeekly, .familyWeekly:
            return hasTrial
                ? String(localized: "7 days free, then \(displayPrice) per week, automatically renewing until canceled.", bundle: bundle)
                : String(localized: "\(displayPrice) per week, automatically renewing until canceled.", bundle: bundle)
        case .individualYearly, .familyYearly:
            return hasTrial
                ? String(localized: "7 days free, then \(displayPrice) per year, automatically renewing until canceled.", bundle: bundle)
                : String(localized: "\(displayPrice) per year, automatically renewing until canceled.", bundle: bundle)
        case .individual, .family, .familyUpgrade:
            return String(localized: "\(displayPrice) charged once to your Apple Account. No subscription.", bundle: bundle)
        }
    }

    static func purchaseButtonTitle(for option: IsoMePurchaseOption, hasTrial: Bool, bundle: Bundle = .main) -> String {
        if option == .familyUpgrade { return String(localized: "Upgrade to Family", bundle: bundle) }
        if !option.isSubscription { return String(localized: "Buy Lifetime", bundle: bundle) }
        return hasTrial ? String(localized: "Start 7-day free trial", bundle: bundle) : String(localized: "Subscribe", bundle: bundle)
    }
}

private struct PaywallInkPressStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.opacity(configuration.isPressed ? 0.7 : 1)
    }
}

/// Original vector artwork: a folded field map, a wandering route, and pen-hatched clouds.
/// Static by design, so the illustration never distracts from prices or requires motion.
// Shared with onboarding so the final step leads into the same illustrated world.
struct JourneyMapIllustration: View {
    let ink: Color
    let paper: Color

    var body: some View {
        Canvas { context, size in
            let scale = min(size.width / 350, size.height / 142)
            context.translateBy(x: (size.width - 350 * scale) / 2, y: 0)
            context.scaleBy(x: scale, y: scale)
            let stroke = StrokeStyle(lineWidth: 1.15, lineCap: .round, lineJoin: .round)

            // A slightly imperfect folded map, rather than a rounded app icon.
            var map = Path()
            map.move(to: CGPoint(x: 89, y: 39))
            for point in [CGPoint(x: 145, y: 28), CGPoint(x: 201, y: 42), CGPoint(x: 260, y: 30),
                          CGPoint(x: 278, y: 112), CGPoint(x: 217, y: 125), CGPoint(x: 160, y: 111),
                          CGPoint(x: 103, y: 124)] { map.addLine(to: point) }
            map.closeSubpath()
            context.fill(map, with: .color(paper))
            context.stroke(map, with: .color(ink), style: stroke)
            for (start, end) in [(CGPoint(x: 145, y: 28), CGPoint(x: 160, y: 111)),
                                 (CGPoint(x: 201, y: 42), CGPoint(x: 217, y: 125))] {
                var fold = Path()
                fold.move(to: start); fold.addLine(to: end)
                context.stroke(fold, with: .color(ink.opacity(0.35)), style: stroke)
            }
            var terrain = Path()
            terrain.move(to: CGPoint(x: 105, y: 96))
            terrain.addCurve(to: CGPoint(x: 266, y: 85), control1: CGPoint(x: 159, y: 47), control2: CGPoint(x: 208, y: 133))
            context.stroke(terrain, with: .color(ink.opacity(0.25)), style: stroke)
            var route = Path()
            route.move(to: CGPoint(x: 122, y: 99))
            route.addCurve(to: CGPoint(x: 188, y: 74), control1: CGPoint(x: 121, y: 48), control2: CGPoint(x: 198, y: 117))
            route.addCurve(to: CGPoint(x: 239, y: 60), control1: CGPoint(x: 176, y: 38), control2: CGPoint(x: 231, y: 96))
            context.stroke(route, with: .color(ink), style: StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [3, 4]))
            for point in [CGPoint(x: 122, y: 99), CGPoint(x: 239, y: 60)] {
                let dot = Path(ellipseIn: CGRect(x: point.x - 3, y: point.y - 3, width: 6, height: 6))
                context.fill(dot, with: .color(paper))
                context.stroke(dot, with: .color(ink), lineWidth: 1.5)
            }
            // Tiny mountain marks add the vocabulary of a travel journal.
            for point in [CGPoint(x: 120, y: 59), CGPoint(x: 133, y: 57), CGPoint(x: 235, y: 101)] {
                var mountain = Path()
                mountain.move(to: CGPoint(x: point.x - 5, y: point.y + 4))
                mountain.addLine(to: CGPoint(x: point.x, y: point.y - 5))
                mountain.addLine(to: CGPoint(x: point.x + 6, y: point.y + 4))
                context.stroke(mountain, with: .color(ink.opacity(0.65)), style: stroke)
            }

            cloud(context: &context, center: CGPoint(x: 55, y: 42), width: 94, height: 47, phase: 0)
            cloud(context: &context, center: CGPoint(x: 296, y: 96), width: 97, height: 49, phase: 2)
            cloud(context: &context, center: CGPoint(x: 281, y: 23), width: 52, height: 28, phase: 1)
            // Two little birds, drawn as pairs of pen strokes.
            for point in [CGPoint(x: 172, y: 12), CGPoint(x: 192, y: 6)] {
                var bird = Path()
                bird.move(to: CGPoint(x: point.x - 5, y: point.y - 2))
                bird.addQuadCurve(to: point, control: CGPoint(x: point.x - 1, y: point.y - 3))
                bird.addQuadCurve(to: CGPoint(x: point.x + 5, y: point.y - 3), control: CGPoint(x: point.x + 1, y: point.y - 4))
                context.stroke(bird, with: .color(ink), style: stroke)
            }
        }
    }

    private func cloud(context: inout GraphicsContext, center: CGPoint, width: CGFloat, height: CGFloat, phase: Double) {
        // Unequal, overlapping puffs, drawn with round curves instead of a regular wave.
        var puff = Path()
        puff.move(to: CGPoint(x: 12, y: 49))
        puff.addCurve(to: CGPoint(x: 9, y: 31), control1: CGPoint(x: -4, y: 48), control2: CGPoint(x: -1, y: 31))
        puff.addCurve(to: CGPoint(x: 23, y: 18), control1: CGPoint(x: 3, y: 17), control2: CGPoint(x: 15, y: 12))
        puff.addCurve(to: CGPoint(x: 43, y: 10), control1: CGPoint(x: 20, y: 0), control2: CGPoint(x: 36, y: -3))
        puff.addCurve(to: CGPoint(x: 65, y: 14), control1: CGPoint(x: 47, y: -3), control2: CGPoint(x: 63, y: 0))
        puff.addCurve(to: CGPoint(x: 84, y: 25), control1: CGPoint(x: 80, y: 4), control2: CGPoint(x: 91, y: 14))
        puff.addCurve(to: CGPoint(x: 94, y: 44), control1: CGPoint(x: 100, y: 20), control2: CGPoint(x: 106, y: 39))
        puff.addCurve(to: CGPoint(x: 77, y: 57), control1: CGPoint(x: 103, y: 58), control2: CGPoint(x: 86, y: 65))
        puff.addCurve(to: CGPoint(x: 54, y: 62), control1: CGPoint(x: 77, y: 73), control2: CGPoint(x: 59, y: 74))
        puff.addCurve(to: CGPoint(x: 31, y: 59), control1: CGPoint(x: 47, y: 76), control2: CGPoint(x: 29, y: 74))
        puff.addCurve(to: CGPoint(x: 12, y: 49), control1: CGPoint(x: 13, y: 68), control2: CGPoint(x: 7, y: 60))
        puff.closeSubpath()
        let transform = CGAffineTransform(translationX: center.x - width / 2, y: center.y - height / 2)
            .scaledBy(x: width / 100, y: height / 72)
        let outline = puff.applying(transform)
        context.fill(outline, with: .color(paper))
        var shading = context
        shading.clip(to: outline)
        for index in stride(from: -80, through: 100, by: 3) {
            let x = center.x + CGFloat(index)
            var hatch = Path()
            let edge = height * (0.12 + 0.07 * sin(Double(index) * 0.19 + phase))
            hatch.move(to: CGPoint(x: x, y: center.y + edge))
            hatch.addLine(to: CGPoint(x: x + 18, y: center.y + height * 0.65))
            shading.stroke(hatch, with: .color(ink.opacity(0.55)), lineWidth: 0.65)
        }
        context.stroke(outline, with: .color(ink), style: StrokeStyle(lineWidth: 1.1, lineCap: .round, lineJoin: .round))
    }
}

struct InkPaperGrain: View {
    let ink: Color

    var body: some View {
        Canvas { context, size in
            // Deterministic flecks, not random noise regenerated during selection changes.
            for index in 0..<1400 {
                let x = CGFloat((index * 127 + 17) % 997) / 997 * size.width
                let y = CGFloat((index * 233 + 43) % 991) / 991 * size.height
                context.fill(Path(ellipseIn: CGRect(x: x, y: y, width: 0.7, height: 0.7)), with: .color(ink.opacity(0.045)))
            }
        }
        .accessibilityHidden(true)
    }
}
