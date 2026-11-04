import SwiftUI
import StoreKit
import SwiftData

/// The paywall's carousel pages, in swipe order.
private enum PaywallPage: Hashable {
    case promise, freeWeek, plans
}

struct PaywallView: View {
    @ObservedObject var storeManager: StoreManager
    var context: PaywallContext = .export
    var isRequired = false
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var family = false
    @State private var selectedOption: IsoMePurchaseOption = .individualYearly
    @State private var showSubscriptionManagement = false
    @State private var page: PaywallPage = .promise
    @Environment(\.modelContext) private var modelContext
    /// Ink index per day for the promise page's grid: recorded days when there are any, else nil.
    @State private var planDays: [Int?]?

    private var managing: Bool { context == .settings && storeManager.isPurchased }
    /// Yearly and Lifetime are the choices on offer. Weekly is kept only for someone already
    /// subscribed to Weekly, so their current plan stays visible and purchasable from here.
    private var options: [IsoMePurchaseOption] {
        let weekly: IsoMePurchaseOption = family ? .familyWeekly : .individualWeekly
        let base: [IsoMePurchaseOption] = family ? [.familyYearly, .family] : [.individualYearly, .individual]
        return isSubscribedToWeekly ? [weekly] + base : base
    }
    private var isSubscribedToWeekly: Bool {
        let active = storeManager.activePurchaseOption
        return active == .individualWeekly || active == .familyWeekly
    }
    /// Only plans the App Store actually returned are offered.
    private var offeredOptions: [IsoMePurchaseOption] {
        options.filter { storeManager.product(for: $0) != nil }
    }
    private var yearlyOption: IsoMePurchaseOption { family ? .familyYearly : .individualYearly }
    /// The free-week page only appears while the yearly plan really offers a trial.
    private var pages: [PaywallPage] {
        storeManager.trialEligibleProductIDs.contains(yearlyOption.productID)
            ? [.promise, .freeWeek, .plans] : [.promise, .plans]
    }
    /// Opened from an export or webhook, the first page leads with what that unlocks.
    private var leadsWithExport: Bool { context == .export || context == .webhook }

    var body: some View {
        Group {
            if managing {
                managingLayout
            } else {
                carouselLayout
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
        .onChange(of: pages) { _, pages in
            if !pages.contains(page) { page = .plans }
        }
        .onAppear {
            #if DEBUG
            let arguments = ProcessInfo.processInfo.arguments
            if arguments.contains("--review-family-paywall") {
                family = true
                selectedOption = .familyYearly
            }
            if arguments.contains("--review-paywall-plans") { page = .plans }
            #endif
        }
        .task {
            await storeManager.loadProducts()
        }
        .task {
            guard planDays == nil else { return }
            planDays = PlanDayGrid.recordedDays(in: modelContext)
        }
    }

    /// If the chosen plan isn't on sale, fall back to yearly, then whatever is.
    private func keepSelectionOffered() {
        let offered = offeredOptions
        guard !offered.isEmpty, !offered.contains(selectedOption) else { return }
        selectedOption = offered.first(where: { $0 == .individualYearly || $0 == .familyYearly }) ?? offered[0]
    }

    // MARK: - Layouts

    /// One story per page, swiped through, with the price and purchase button pinned below every page.
    private var carouselLayout: some View {
        VStack(spacing: 0) {
            header
                .padding(.horizontal, 20)
                .padding(.top, 12)
            TabView(selection: $page) {
                ForEach(pages, id: \.self) { page in
                    ScrollView {
                        pageContent(page)
                            .padding(.horizontal, 24)
                            .padding(.top, 14)
                            .padding(.bottom, 16)
                            .frame(maxWidth: 560, alignment: .leading)
                            .frame(maxWidth: .infinity)
                    }
                    .scrollIndicators(.hidden)
                    .tag(page)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            footer
                .frame(maxWidth: 560)
                .frame(maxWidth: .infinity)
                .background(TE.surface.ignoresSafeArea(edges: .bottom))
        }
    }

    /// Settings, for someone who already has a plan: what they own, and what they can still add.
    private var managingLayout: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                    .padding(.horizontal, 20)
                    .padding(.top, 12)

                VStack(alignment: .leading, spacing: 10) {
                    JourneyMapIllustration(ink: TE.textPrimary, paper: TE.card)
                        .frame(height: 96)
                        .accessibilityHidden(true)
                    Text("Your Whereday plan")
                        .font(TE.editorial(.largeTitle))
                    Text("Manage your purchase and Family Sharing.")
                        .font(.subheadline)
                        .foregroundStyle(TE.textMuted)
                }
                .padding(.horizontal, 24)
                .padding(.top, 14)

                activePlanCard
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

    // MARK: - Pages

    @ViewBuilder
    private func pageContent(_ page: PaywallPage) -> some View {
        switch page {
        case .promise:
            if leadsWithExport { exportPromise } else { promise }
        case .freeWeek:
            freeWeek
        case .plans:
            plans
        }
    }

    private var promise: some View {
        VStack(alignment: .leading, spacing: 18) {
            PlanDayGrid(days: planDays)
                .frame(height: 84)
                .padding(14)
                .frame(maxWidth: .infinity)
                .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
                .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
            italicSecondLine(String(localized: "A life of places.\nYours to keep."))
                .font(TE.editorial(.largeTitle))
            VStack(alignment: .leading, spacing: 14) {
                PaywallBenefitRow(systemImage: "clock", title: "Every day, recorded for you",
                                  detail: "Visits and routes log in the background. No check-ins.")
                PaywallBenefitRow(systemImage: "map", title: "Revisit any place, any date",
                                  detail: "Map, timeline and calendar of where you've been.")
                PaywallBenefitRow(systemImage: "lock", title: "Private, on your iPhone",
                                  detail: "No account. Export to GPX, CSV, Markdown and more.")
            }
        }
    }

    private static let exportFormats = ["GPX", "GeoJSON", "KML", "CSV", "JSON", "Markdown", "OwnTracks", "Overland"]
    private static let exportFormatInks = [0, 1, 0, 3, 3, 0, 2, 2]

    private var exportPromise: some View {
        VStack(alignment: .leading, spacing: 18) {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8),
                                     count: dynamicTypeSize.isAccessibilitySize ? 2 : 4), spacing: 8) {
                ForEach(Self.exportFormats.indices, id: \.self) { index in
                    Text(verbatim: Self.exportFormats[index])
                        .font(TE.mono(.caption, weight: .medium))
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .frame(maxWidth: .infinity, minHeight: 34)
                        .overlay(RoundedRectangle(cornerRadius: 10)
                            .strokeBorder(TE.placeInks[Self.exportFormatInks[index]], lineWidth: 1))
                }
            }
            .padding(16)
            .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
            .accessibilityElement(children: .combine)
            italicSecondLine(String(localized: "Take your history\nanywhere."))
                .font(TE.editorial(.largeTitle))
            VStack(alignment: .leading, spacing: 14) {
                PaywallBenefitRow(systemImage: "square.and.arrow.down", title: "Every format, filtered your way",
                                  detail: "Pick dates, fields and accuracy. Visits, routes or outings.")
                PaywallBenefitRow(systemImage: "calendar.badge.clock", title: "A file a day, saved for you",
                                  detail: "Scheduled exports land in your folder or Obsidian vault.")
                PaywallBenefitRow(systemImage: "link", title: "Webhooks and Shortcuts",
                                  detail: "Send each day to your own server or automation.")
            }
        }
    }

    private var freeWeek: some View {
        VStack(alignment: .leading, spacing: 20) {
            if let review = PaywallReview.featured {
                PaywallReviewCard(review: review)
            }
            Text("How your *free week* works")
                .font(TE.editorial(.largeTitle))
                .accessibilityAddTraits(.isHeader)
            VStack(alignment: .leading, spacing: 0) {
                PaywallTrialStep(systemImage: "checkmark", ink: TE.textPrimary, isDone: true,
                                 title: Text("Today"),
                                 detail: Text("Everything unlocks. Recording starts with your first walk out the door."))
                PaywallTrialStep(systemImage: "bell", ink: TE.placeInks[3],
                                 title: Text("Day 5 · A reminder"),
                                 detail: Text("We'll notify you two days before the trial ends."))
                PaywallTrialStep(systemImage: "calendar", ink: TE.inkRust, isLast: true,
                                 title: Text("Day 7 · Your plan begins"),
                                 detail: Text(verbatim: renewalDetail))
            }
        }
    }

    private var renewalDetail: String {
        if let product = storeManager.product(for: yearlyOption) {
            return String(localized: "\(product.displayPrice) for the year. Cancel before then in Apple settings and you won't be charged.")
        }
        return String(localized: "Your yearly plan begins. Cancel before then in Apple settings and you won't be charged.")
    }

    private var plans: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Choose your *plan*")
                .font(TE.editorial(.largeTitle))
                .accessibilityAddTraits(.isHeader)
            audiencePicker
            if offeredOptions.isEmpty {
                unavailablePlans
            } else {
                let sideBySide = offeredOptions.count == 2 && !dynamicTypeSize.isAccessibilitySize
                let layout = sideBySide
                    ? AnyLayout(HStackLayout(alignment: .top, spacing: 12))
                    : AnyLayout(VStackLayout(spacing: 12))
                layout {
                    ForEach(offeredOptions) { option in
                        planCard(option)
                    }
                }
                .fixedSize(horizontal: false, vertical: true)
            }
            includedList
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
            notes
        }
    }

    private var includedList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("EVERY PLAN INCLUDES")
                .font(TE.mono(.caption2))
                .tracking(1.2)
                .foregroundStyle(TE.textMuted)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12, alignment: .leading)],
                      alignment: .leading, spacing: 8) {
                includedItem("Automatic recording")
                includedItem("All 8 export formats")
                includedItem("Scheduled exports")
                includedItem("Webhooks")
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func includedItem(_ title: LocalizedStringKey) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "checkmark")
                .font(.caption.weight(.bold))
                .foregroundStyle(TE.placeInks[3])
                .accessibilityHidden(true)
            Text(title)
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Pieces

    private var header: some View {
        HStack {
            Text(leadsWithExport && !managing ? "EXPORT IS PART OF YOUR PLAN" : "YOUR WHEREDAY PLAN")
                .font(TE.mono(.caption2))
                .tracking(1.5)
                .foregroundStyle(TE.textMuted)
            Spacer(minLength: 12)
            closeButton
        }
        .frame(minHeight: 44)
    }

    @ViewBuilder
    private var closeButton: some View {
        if !isRequired {
            BackPageCircleButton(systemImage: "xmark", label: "Close") { dismiss() }
        }
    }

    /// A two-line headline whose second line is set in italic, as on the printed page.
    private func italicSecondLine(_ localized: String) -> Text {
        let lines = localized.split(separator: "\n", maxSplits: 1)
        guard lines.count == 2 else { return Text(verbatim: localized) }
        return Text(verbatim: "\(lines[0])\n") + Text(verbatim: String(lines[1])).italic()
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
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .top, spacing: 6) {
                    planBadge(option, hasTrial: trial)
                    Spacer(minLength: 4)
                    Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(isSelected ? TE.inkRust : TE.border)
                        .accessibilityHidden(true)
                }
                Text(verbatim: PaywallCopy.planName(for: option))
                    .font(TE.editorial(.title2))
                    .padding(.top, 2)
                if let product {
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(verbatim: product.displayPrice)
                            .font(TE.mono(.title3, weight: .medium))
                            .monospacedDigit()
                        Text(verbatim: option.billingCaption)
                            .font(.footnote)
                            .foregroundStyle(TE.textMuted)
                    }
                    Text(verbatim: PaywallCopy.planDetail(for: option, price: product.price, format: product.priceFormatStyle))
                        .font(.caption)
                        .foregroundStyle(TE.textMuted)
                }
            }
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            .padding(14)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
            .overlay(
                RoundedRectangle(cornerRadius: 18)
                    .strokeBorder(isSelected ? TE.inkRust : TE.border, lineWidth: isSelected ? 2 : 1)
            )
            .contentShape(RoundedRectangle(cornerRadius: 18))
        }
        .buttonStyle(PaywallInkPressStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    @ViewBuilder
    private func planBadge(_ option: IsoMePurchaseOption, hasTrial: Bool) -> some View {
        if hasTrial {
            Text("7 DAYS FREE")
                .font(TE.mono(.caption2, weight: .medium))
                .foregroundStyle(TE.card)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(RoundedRectangle(cornerRadius: 10).fill(TE.inkRust))
        } else if !option.isSubscription {
            Text("ONE-TIME")
                .font(TE.mono(.caption2, weight: .medium))
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(TE.border, lineWidth: 1))
        }
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
        VStack(spacing: 8) {
            if !managing {
                PaywallPageDots(count: pages.count, index: pages.firstIndex(of: page) ?? 0) { index in
                    withAnimation { page = pages[index] }
                }
                if let product = storeManager.product(for: selectedOption) {
                    Text(verbatim: PaywallCopy.disclosure(
                        for: selectedOption,
                        displayPrice: product.displayPrice,
                        hasTrial: storeManager.trialEligibleProductIDs.contains(selectedOption.productID)))
                        .font(.caption)
                        .foregroundStyle(TE.textMuted)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if let error = storeManager.purchaseError ?? storeManager.productLoadError {
                Text(error)
                    .font(.footnote)
                    .foregroundStyle(TE.inkRust)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
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

    /// The short name on a plan card; the Individual/Family switch above it says who it's for.
    static func planName(for option: IsoMePurchaseOption, bundle: Bundle = .main) -> String {
        switch option {
        case .individualWeekly, .familyWeekly: return String(localized: "Weekly", bundle: bundle)
        case .individualYearly, .familyYearly: return String(localized: "Yearly", bundle: bundle)
        case .individual, .family: return String(localized: "Lifetime", bundle: bundle)
        case .familyUpgrade: return String(localized: "Family Upgrade", bundle: bundle)
        }
    }

    /// The line under a plan card's price. Yearly shows its monthly equivalent, in the storefront's currency format.
    static func planDetail(for option: IsoMePurchaseOption, price: Decimal, format: Decimal.FormatStyle.Currency,
                           bundle: Bundle = .main) -> String {
        switch option {
        case .individualYearly, .familyYearly:
            let monthly = (price / 12).formatted(format)
            return String(localized: "\(monthly) a month, billed yearly", bundle: bundle)
        case .individualWeekly, .familyWeekly:
            return String(localized: "Renews every week.", bundle: bundle)
        case .individual, .family, .familyUpgrade:
            return String(localized: "One-time purchase. Yours for good.", bundle: bundle)
        }
    }
}

/// An App Store review quoted with the reviewer's permission, shown verbatim in its own language.
struct PaywallReview {
    let quote: String
    let reviewer: String
    let rating: Int

    /// Empty until real, permitted quotes are added; the free-week page then leads with the first.
    static let all: [PaywallReview] = []
    static var featured: PaywallReview? { all.first }
}

private struct PaywallReviewCard: View {
    let review: PaywallReview

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 3) {
                ForEach(0..<5, id: \.self) { index in
                    Image(systemName: index < review.rating ? "star.fill" : "star")
                        .font(.caption)
                        .foregroundStyle(TE.placeInks[2])
                }
            }
            .accessibilityElement()
            .accessibilityLabel(Text("\(review.rating) out of 5 stars"))
            Text(verbatim: "“\(review.quote)”")
                .font(TE.editorial(.title3))
                .italic()
                .fixedSize(horizontal: false, vertical: true)
            Text(verbatim: review.reviewer.uppercased())
                .font(TE.mono(.caption2))
                .tracking(0.6)
                .foregroundStyle(TE.textMuted)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .accessibilityElement(children: .combine)
    }
}

private struct PaywallBenefitRow: View {
    let systemImage: String
    let title: LocalizedStringKey
    let detail: LocalizedStringKey

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: systemImage)
                .font(.body)
                .frame(width: 34, height: 34)
                .background(RoundedRectangle(cornerRadius: 10).fill(TE.card))
                .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(TE.border, lineWidth: 1))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.body.weight(.semibold))
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(TE.textMuted)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .combine)
    }
}

/// One stop on the free-week timeline, joined to the next by a rule in its ink.
private struct PaywallTrialStep: View {
    let systemImage: String
    let ink: Color
    var isDone = false
    var isLast = false
    let title: Text
    let detail: Text

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            VStack(spacing: 0) {
                Image(systemName: systemImage)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(isDone ? TE.surface : ink)
                    .frame(width: 28, height: 28)
                    .background(Circle().fill(isDone ? ink : TE.card))
                    .overlay(Circle().strokeBorder(ink, lineWidth: isDone ? 0 : 2))
                if !isLast {
                    Rectangle()
                        .fill(isDone ? ink : TE.border)
                        .frame(width: 2)
                        .frame(minHeight: 22, maxHeight: .infinity)
                }
            }
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                title
                    .font(.body.weight(.semibold))
                detail
                    .font(.subheadline)
                    .foregroundStyle(TE.textMuted)
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 3)
            .padding(.bottom, isLast ? 0 : 18)
        }
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .combine)
    }
}

/// The carousel's position: tap a dot to jump, or swipe up and down on it with VoiceOver.
private struct PaywallPageDots: View {
    let count: Int
    let index: Int
    let select: (Int) -> Void

    var body: some View {
        HStack(spacing: 6) {
            ForEach(0..<count, id: \.self) { dot in
                Capsule()
                    .fill(dot == index ? TE.textPrimary : TE.border)
                    .frame(width: dot == index ? 18 : 6, height: 6)
                    .frame(minWidth: 18, minHeight: 24)
                    .contentShape(Rectangle())
                    .onTapGesture { select(dot) }
            }
        }
        .animation(.easeOut(duration: 0.2), value: index)
        .accessibilityElement()
        .accessibilityLabel(Text("Page \(index + 1) of \(count)"))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment where index + 1 < count: select(index + 1)
            case .decrement where index > 0: select(index - 1)
            default: break
            }
        }
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

/// Half a year of days on the plan page: 26 weeks of cells, each in the ink of the place you spent
/// most of that day at. Without recorded days it draws a fixed illustrative pattern instead.
struct PlanDayGrid: View {
    static let dayCount = 182
    private static let rows = 7

    /// One entry per day, oldest first: an ink index, or nil for a day with nothing recorded.
    /// A nil array means there is no recorded history to show.
    let days: [Int?]?

    /// The place inks, drawn on the paper card of the paywall's first page.
    private static let inks: [Color] = TE.placeInks
    private static let empty = TE.dialTrack

    var body: some View {
        let cells = days ?? Self.illustrativeDays
        Canvas { context, size in
            let columns = Int(ceil(Double(cells.count) / Double(Self.rows)))
            let gap: CGFloat = 3
            let side = max(2, min((size.width - gap * CGFloat(columns - 1)) / CGFloat(columns),
                                  (size.height - gap * CGFloat(Self.rows - 1)) / CGFloat(Self.rows)))
            let gridWidth = side * CGFloat(columns) + gap * CGFloat(columns - 1)
            let originX = (size.width - gridWidth) / 2
            for (index, ink) in cells.enumerated() {
                let column = index / Self.rows
                let row = index % Self.rows
                let rect = CGRect(x: originX + CGFloat(column) * (side + gap), y: CGFloat(row) * (side + gap),
                                  width: side, height: side)
                let color = ink.map { Self.inks[$0 % Self.inks.count] } ?? Self.empty
                context.fill(Path(roundedRect: rect, cornerRadius: side * 0.22), with: .color(color))
            }
        }
        .accessibilityElement()
        .accessibilityAddTraits(.isImage)
        .accessibilityLabel(days == nil
            ? Text("An illustration of half a year of days, each colored by place")
            : Text("Your last 26 weeks, each day colored by the place you spent most time at"))
        .accessibilityValue(days.map { days in
            Text("\(days.compactMap { $0 }.count) of \(days.count) days recorded")
        } ?? Text(""))
    }

    /// The artboard's pattern: mostly one ink, accents of the others, and a quiet stretch at the end.
    static let illustrativeDays: [Int?] = (0..<dayCount).map { index in
        if index > 152 || (index * 7) % 23 == 3 { return nil }
        guard ((index / 9) * 5 + index) % 13 >= 9 else { return 0 }
        return [0, 0, 1, 2, 3][index % 5]
    }

    /// Each of the last 182 days in the ink of its longest-stay saved place, in the library's ink order.
    /// Returns nil when nothing was recorded in that window.
    @MainActor
    static func recordedDays(in context: ModelContext, now: Date = Date(), calendar: Calendar = .current) -> [Int?]? {
        let today = calendar.startOfDay(for: now)
        guard let start = calendar.date(byAdding: .day, value: -(dayCount - 1), to: today) else { return nil }
        let visitDescriptor = FetchDescriptor<Visit>(
            predicate: #Predicate { $0.arrivedAt >= start },
            sortBy: [SortDescriptor(\.arrivedAt, order: .reverse)]
        )
        var placeDescriptor = FetchDescriptor<SavedPlace>()
        placeDescriptor.sortBy = [SortDescriptor(\.name, order: .forward)]
        guard let visits = try? context.fetch(visitDescriptor), !visits.isEmpty else { return nil }
        let places = (try? context.fetch(placeDescriptor)) ?? []
        let index = PlaceVisitIndex(places: places, visits: visits)

        // Seconds per day per ink; visits outside every saved place count toward the first ink.
        var time = Array(repeating: [Int: TimeInterval](), count: dayCount)
        func add(_ visit: Visit, ink: Int) {
            let day = calendar.dateComponents([.day], from: start, to: calendar.startOfDay(for: visit.arrivedAt)).day ?? -1
            guard time.indices.contains(day) else { return }
            let end = min(visit.departedAt ?? now, now)
            time[day][ink, default: 0] += max(60, end.timeIntervalSince(visit.arrivedAt))
        }
        for (offset, place) in places.enumerated() {
            for visit in index.visits(for: place) { add(visit, ink: offset % inks.count) }
        }
        for visit in index.unmatched { add(visit, ink: 0) }
        return time.map { day in day.max { $0.value < $1.value }?.key }
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
