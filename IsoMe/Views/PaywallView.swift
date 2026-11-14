import SwiftUI
import StoreKit

struct PaywallView: View {
    @ObservedObject var storeManager: StoreManager
    var context: OnboardingAnalyticsPaywallContext = .export
    var isRequired = false
    @Environment(\.dismiss) private var dismiss
    @State private var family = false
    @State private var selectedOption: IsoMePurchaseOption = .individualYearly
    @State private var didTrackShown = false
    @State private var showSubscriptionManagement = false
    private var ink: Color { InkPaperPalette.ink }
    private var paper: Color { InkPaperPalette.paper }
    private var pencil: Color { InkPaperPalette.pencil }

    private var managing: Bool { context == .settings && storeManager.isPurchased }
    private var options: [IsoMePurchaseOption] {
        family ? [.familyWeekly, .familyYearly, .family] : [.individualWeekly, .individualYearly, .individual]
    }
    private var hasTrial: Bool { storeManager.trialEligibleProductIDs.contains(selectedOption.productID) }
    private var analytics: OnboardingAnalyticsClient { .shared }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    VStack(alignment: .leading, spacing: 14) {
                        Text(managing ? String(localized: "Your Whereday plan") : String(localized: "A life of places.\nYours to keep."))
                            .font(.system(.largeTitle, design: .serif).weight(.medium))
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                        Text(managing ? String(localized: "Manage your purchase and Family Sharing.") : String(localized: "Track your days. Revisit your places.\nKeep your history private and on-device."))
                            .font(.subheadline).foregroundStyle(pencil)
                            .multilineTextAlignment(.leading)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.bottom, 8)
                    if managing {
                        Text(storeManager.activePurchaseOption?.title ?? String(localized: "Active purchase")).font(.headline)
                        if storeManager.canBuyFamilyUpgrade {
                            Text("Add Apple Family Sharing to your lifetime purchase.")
                            if let product = storeManager.product(for: .familyUpgrade) {
                                Text("\(product.displayPrice), one-time purchase").font(.headline)
                            }
                        }
                    } else {
                        Picker("Who is this for?", selection: $family) {
                            Text("Individual").tag(false)
                            Text("Family").tag(true)
                        }.pickerStyle(.segmented).frame(minHeight: 44)
                        if family {
                            Label("Share with up to 5 family members using Apple Family Sharing", systemImage: "person.3.fill")
                                .font(.subheadline).foregroundStyle(pencil)
                        }
                        VStack(spacing: 10) {
                            ForEach(options) { option in
                                planRow(option)
                            }
                        }
                        if let product = storeManager.product(for: selectedOption) {
                            VStack(spacing: 4) {
                                Text(PaywallCopy.disclosure(for: selectedOption, displayPrice: product.displayPrice, hasTrial: hasTrial))
                                if selectedOption.isSubscription {
                                    Text("Cancel in Apple subscription settings at least 24 hours before renewal.")
                                }
                            }
                            .font(.footnote).foregroundStyle(pencil).multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(12)
                            .frame(maxWidth: .infinity)
                            .background(paper.opacity(0.96), in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                    if let error = storeManager.purchaseError ?? storeManager.productLoadError {
                        Text(error).font(.footnote).foregroundStyle(.red).multilineTextAlignment(.center)
                    }
                    if !managing && storeManager.product(for: selectedOption) == nil {
                        Button("Retry loading plans") { Task { await storeManager.loadProducts() } }
                            .disabled(storeManager.isLoading)
                    }
                    Button { restore() } label: {
                        Text("Restore Purchases").frame(minHeight: 44)
                    }
                    .padding(.horizontal, 16)
                    .background(paper.opacity(0.96), in: Capsule())
                    .disabled(storeManager.isLoading)
                    HStack(spacing: 24) {
                        if let url = IsoMeURLs.privacyPolicy {
                            Link(destination: url) { Text("Privacy").frame(minHeight: 44) }
                        }
                        if let url = IsoMeURLs.termsOfService {
                            Link(destination: url) { Text("Terms").frame(minHeight: 44) }
                        }
                    }
                    .font(.footnote)
                    .padding(.horizontal, 16)
                    .background(paper.opacity(0.96), in: Capsule())
                    Text("Your existing records remain available to view and export if your subscription ends.")
                        .font(.caption).foregroundStyle(pencil).multilineTextAlignment(.center)
                        .padding(12)
                        .background(paper.opacity(0.96), in: RoundedRectangle(cornerRadius: 12))
                }
                .padding(.horizontal, 24).padding(.top, isRequired ? 52 : 24).padding(.bottom, 24)
                .frame(maxWidth: 560).frame(maxWidth: .infinity)
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                purchaseFooter
            }
            .background {
                InkJournalBackground()
            }
            .tint(ink)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar(isRequired ? .hidden : .visible, for: .navigationBar)
            .toolbar {
                if !isRequired {
                    ToolbarItem(placement: .topBarTrailing) { Button("Done") { dismiss() } }
                }
            }
            .manageSubscriptionsSheet(isPresented: $showSubscriptionManagement)
        }
        .modifier(JournalFlowPresentation())
        .interactiveDismissDisabled(isRequired)
        .onChange(of: family) { _, value in
            switch selectedOption {
            case .individualWeekly, .familyWeekly: selectedOption = value ? .familyWeekly : .individualWeekly
            case .individual, .family: selectedOption = value ? .family : .individual
            default: selectedOption = value ? .familyYearly : .individualYearly
            }
        }
        .onAppear {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--review-family-paywall") {
                family = true
                selectedOption = .familyYearly
            }
            #endif
        }
        .task {
            if !didTrackShown {
                didTrackShown = true
                analytics.trackPaywallShown(context: context)
            }
            await storeManager.loadProducts()
        }
    }

    @ViewBuilder
    private var purchaseFooter: some View {
        if !managing || storeManager.canBuyFamilyUpgrade || storeManager.activePurchaseOption?.isSubscription == true {
            VStack(spacing: 0) {
                if !managing {
                    purchaseButton(selectedOption)
                } else if storeManager.canBuyFamilyUpgrade {
                    purchaseButton(.familyUpgrade)
                } else {
                    Button("Manage subscription") { showSubscriptionManagement = true }
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                        .foregroundStyle(paper)
                        .background(ink, in: RoundedRectangle(cornerRadius: 12))
                        .buttonStyle(PaywallInkPressStyle())
                }
            }
            .frame(maxWidth: 512)
            .padding(.horizontal, 24)
            .padding(.top, 12)
            .padding(.bottom, 16)
            .frame(maxWidth: .infinity)
            .background(paper.ignoresSafeArea(edges: .bottom))
        }
    }

    private func planRow(_ option: IsoMePurchaseOption) -> some View {
        Button { selectedOption = option } label: {
            HStack(spacing: 12) {
                Image(systemName: selectedOption == option ? "checkmark.circle.fill" : "circle")
                    .foregroundStyle(selectedOption == option ? ink : pencil)
                VStack(alignment: .leading, spacing: 4) {
                    Text(option.title).font(.headline.weight(.medium))
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)
                    if storeManager.trialEligibleProductIDs.contains(option.productID) {
                        Text("7 days free").font(.caption).foregroundStyle(pencil)
                    } else if !option.isSubscription {
                        Text("One-time purchase").font(.caption).foregroundStyle(pencil)
                    }
                }
                Spacer(minLength: 4)
                if let product = storeManager.product(for: option) {
                    VStack(alignment: .trailing) {
                        Text(product.displayPrice).font(.headline).monospacedDigit()
                        Text(option.billingCaption).font(.caption).foregroundStyle(pencil)
                    }
                } else {
                    Text("Unavailable").font(.caption).foregroundStyle(pencil)
                }
            }
            .foregroundStyle(.primary).padding(.horizontal, 16).padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 68)
            .background(paper.opacity(0.96), in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).fill(ink.opacity(selectedOption == option ? 0.05 : 0)).allowsHitTesting(false))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(ink.opacity(selectedOption == option ? 1 : 0.24), lineWidth: selectedOption == option ? 1.5 : 0.75))
        }
        .buttonStyle(PaywallInkPressStyle())
        .accessibilityAddTraits(selectedOption == option ? .isSelected : [])
    }

    private func purchaseButton(_ option: IsoMePurchaseOption) -> some View {
        Button {
            analytics.trackPurchaseStarted(context: context, productId: option.analyticsProductID)
            Task {
                let result = await storeManager.purchase(option)
                let outcome: OnboardingAnalyticsPurchaseOutcome
                switch result {
                case .succeeded: outcome = .succeeded
                case .cancelled: outcome = .cancelled
                case .pending: outcome = .pending
                case .failed: outcome = .failed
                }
                analytics.trackPurchaseFinished(outcome: outcome, context: context, productId: option.analyticsProductID)
                if result == .succeeded && !isRequired { dismiss() }
            }
        } label: {
            HStack {
                Spacer()
                if storeManager.purchasingOption == option { ProgressView().tint(paper) }
                Text(PaywallCopy.purchaseButtonTitle(for: option, hasTrial: hasTrial))
                    .font(.headline)
                Spacer()
            }.padding(.vertical, 17)
                .foregroundStyle(paper)
                .background(ink, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(PaywallInkPressStyle())
        .accessibilityIdentifier("paywall-purchase")
        .opacity(storeManager.product(for: option) == nil || storeManager.isLoading ? 0.5 : 1)
        .disabled(storeManager.product(for: option) == nil || storeManager.isLoading)
    }

    private func restore() {
        analytics.trackRestoreStarted(context: context)
        Task {
            let result = await storeManager.restorePurchases()
            analytics.trackRestoreFinished(outcome: result == .restored ? .restored : (result == .notFound ? .notFound : .failed), context: context)
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
