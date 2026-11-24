import SwiftUI
import StoreKit

// The last onboarding step for someone who needs a plan: "Start my free week".
// Prices, periods and trial wording come from StoreKit through StoreManager and PaywallCopy.

extension StoreManager {
    /// True only while StoreKit reports an eligible seven-day free trial for this plan.
    func onboardingHasTrial(_ option: IsoMePurchaseOption) -> Bool {
        option.isSubscription && trialEligibleProductIDs.contains(option.productID)
    }

    func onboardingOffer(for option: IsoMePurchaseOption) -> OnboardingOffer {
        OnboardingOffer(option: option, displayPrice: product(for: option)?.displayPrice, hasTrial: onboardingHasTrial(option))
    }

    /// Plans the App Store actually returned, in the step's order.
    func onboardingOfferedPlans(family: Bool) -> [OnboardingPlanChoice] {
        OnboardingPlanChoice.allCases.filter { product(for: $0.option(family: family)) != nil }
    }

    /// The chosen plan when it's on sale; otherwise Yearly, then whatever is.
    func onboardingEffectivePlan(_ plan: OnboardingPlanChoice, family: Bool) -> OnboardingPlanChoice {
        let offered = onboardingOfferedPlans(family: family)
        if offered.contains(plan) || offered.isEmpty { return plan }
        return offered.contains(.yearly) ? .yearly : offered[0]
    }
}

// MARK: - Page

/// Eyebrow, headline, the personalised timeline and the inline plan chooser.
struct OnboardingFreeWeekPage: View {
    @ObservedObject var storeManager: StoreManager
    @Binding var plan: OnboardingPlanChoice
    let family: Bool
    let today: OnboardingTrialTimeline.Today
    let now: Date

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var effectivePlan: OnboardingPlanChoice { storeManager.onboardingEffectivePlan(plan, family: family) }
    private var option: IsoMePurchaseOption { effectivePlan.option(family: family) }
    private var offer: OnboardingOffer { storeManager.onboardingOffer(for: option) }

    /// Until StoreKit returns the plan, only what's already true: today and the evening recap.
    private var timelineEntries: [OnboardingTimelineEntry] {
        let recap = RecapReminderScheduler.shared.settings
        let entries = OnboardingTrialTimeline.entries(offer: offer, today: today,
                                                      recapTime: (recap.hour, recap.minute), now: now)
        guard storeManager.product(for: option) == nil else { return entries }
        return entries.filter { $0.kind == .today || $0.kind == .recap }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: OnboardingFreeWeekCopy.eyebrow(for: offer))
                    .font(TE.mono(.caption2))
                    .tracking(0.9)
                    .foregroundStyle(TE.inkRust)
                Text(verbatim: OnboardingFreeWeekCopy.headline(for: offer))
                    .font(WheredayFont.serif(size: 34, relativeTo: .largeTitle))
                    .lineSpacing(-2)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
            }

            OnboardingTrialTimelineView(entries: timelineEntries)

            planChooser
        }
    }

    @ViewBuilder
    private var planChooser: some View {
        let offered = storeManager.onboardingOfferedPlans(family: family)
        if offered.isEmpty {
            PaywallPlansUnavailable(storeManager: storeManager)
        } else {
            let layout = dynamicTypeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(spacing: 8))
                : AnyLayout(HStackLayout(alignment: .top, spacing: 8))
            layout {
                ForEach(offered) { choice in
                    quickPlanCard(choice)
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityElement(children: .contain)
            .accessibilityLabel(Text("Plan"))
        }
    }

    private func quickPlanCard(_ choice: OnboardingPlanChoice) -> some View {
        let option = choice.option(family: family)
        let isSelected = choice == effectivePlan
        let hasTrial = storeManager.onboardingHasTrial(option)
        return Button { plan = choice } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: PaywallCopy.planName(for: option))
                    .font(.subheadline.weight(.semibold))
                if let product = storeManager.product(for: option) {
                    Text(verbatim: product.displayPrice)
                        .font(TE.mono(.caption, weight: .medium))
                        .monospacedDigit()
                    Text(verbatim: option.billingCaption)
                        .font(.caption2)
                        .foregroundStyle(TE.textMuted)
                }
                if let tag = OnboardingFreeWeekCopy.planTag(for: option, hasTrial: hasTrial) {
                    Text(verbatim: tag)
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(TE.inkRust)
                }
            }
            .multilineTextAlignment(.leading)
            .fixedSize(horizontal: false, vertical: true)
            .padding(10)
            .frame(maxWidth: .infinity, minHeight: 64, maxHeight: .infinity, alignment: .topLeading)
            .background(RoundedRectangle(cornerRadius: 14).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 14)
                .strokeBorder(isSelected ? TE.textPrimary : TE.border, lineWidth: isSelected ? 2 : 1))
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("onboarding-plan-\(choice.rawValue)")
    }
}

// MARK: - Timeline

struct OnboardingTrialTimelineView: View {
    let entries: [OnboardingTimelineEntry]

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                row(entry, isFirst: index == 0, isLast: index == entries.count - 1)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.border, lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("What happens next"))
        .accessibilityIdentifier("onboarding-trial-timeline")
    }

    private func row(_ entry: OnboardingTimelineEntry, isFirst: Bool, isLast: Bool) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 0) {
                if isFirst {
                    Circle().fill(TE.placeInks[1]).frame(width: 14, height: 14).padding(.top, 3)
                } else {
                    Circle().strokeBorder(TE.textPrimary, lineWidth: 2).frame(width: 12, height: 12).padding(.top, 4)
                }
                if !isLast {
                    Rectangle().fill(TE.border).frame(width: 2).frame(minHeight: 12, maxHeight: .infinity).padding(.vertical, 3)
                }
            }
            .frame(width: 14)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: entry.when)
                    .font(TE.mono(.caption2))
                    .tracking(0.6)
                    .textCase(.uppercase)
                    .foregroundStyle(TE.textMuted)
                Text(verbatim: entry.text)
                    .font(.subheadline)
            }
            .fixedSize(horizontal: false, vertical: true)
            .padding(.bottom, isLast ? 0 : 12)
            Spacer(minLength: 0)
        }
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(verbatim: entry.accessibilityLabel))
        .accessibilityIdentifier("onboarding-trial-\(entry.kind.rawValue)")
    }
}

// MARK: - Controls

/// The purchase button, "All plans and Family", and the App Store disclosure beside them.
struct OnboardingFreeWeekControls: View {
    @ObservedObject var storeManager: StoreManager
    @ObservedObject var checkout: OnboardingCheckout
    let plan: OnboardingPlanChoice
    let family: Bool
    let now: Date
    let openPlans: () -> Void

    var body: some View {
        let effective = storeManager.onboardingEffectivePlan(plan, family: family)
        VStack(spacing: 4) {
            PaywallStoreMessage(storeManager: storeManager)
                .padding(.bottom, 4)
            OnboardingPurchaseButton(storeManager: storeManager, checkout: checkout,
                                     option: effective.option(family: family))
            Button(action: openPlans) {
                Text("All plans and Family")
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(TE.textPrimary)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(checkout.phase != .choosing)
            .accessibilityIdentifier("onboarding-all-plans")
            OnboardingFinePrint(storeManager: storeManager, option: effective.option(family: family), now: now)
            PaywallLegalLinks(isDisabled: storeManager.isLoading || checkout.phase != .choosing) {
                Task { await checkout.restorePurchases() }
            }
            .foregroundStyle(TE.textMuted)
        }
    }
}

struct OnboardingPurchaseButton: View {
    @ObservedObject var storeManager: StoreManager
    @ObservedObject var checkout: OnboardingCheckout
    let option: IsoMePurchaseOption
    var identifier = "onboarding-start-trial"

    var body: some View {
        let product = storeManager.product(for: option)
        let hasTrial = storeManager.onboardingHasTrial(option)
        Button {
            Task {
                await checkout.buy(option, startsTrial: hasTrial)
                if checkout.isDone { AppReviewPromptCoordinator.shared.recordSuccess(.purchase) }
            }
        } label: {
            HStack(spacing: 10) {
                if storeManager.purchasingOption == option { ProgressView().tint(TE.surface) }
                Text(verbatim: OnboardingFreeWeekCopy.primaryTitle(for: option, displayPrice: product?.displayPrice,
                                                                  hasTrial: hasTrial))
            }
        }
        .buttonStyle(TEActionButtonStyle(emphasis: .primary))
        .disabled(product == nil || storeManager.isLoading || checkout.phase != .choosing)
        .accessibilityIdentifier(identifier)
    }
}

/// Price, period, renewal and how to cancel, from StoreKit's product.
struct OnboardingFinePrint: View {
    @ObservedObject var storeManager: StoreManager
    let option: IsoMePurchaseOption
    let now: Date

    var body: some View {
        if let product = storeManager.product(for: option) {
            Text(verbatim: OnboardingFreeWeekCopy.finePrint(for: option, displayPrice: product.displayPrice,
                                                            hasTrial: storeManager.onboardingHasTrial(option), now: now))
                .font(.caption2)
                .foregroundStyle(TE.textMuted)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("onboarding-fine-print")
        }
    }
}

// MARK: - All plans sheet

struct OnboardingAllPlansSheet: View {
    @ObservedObject var storeManager: StoreManager
    @ObservedObject var checkout: OnboardingCheckout
    @Binding var plan: OnboardingPlanChoice
    @Binding var family: Bool
    let lead: OnboardingIntent
    let now: Date
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let effective = storeManager.onboardingEffectivePlan(plan, family: family)
        VStack(spacing: 0) {
            HStack {
                Text("All plans")
                    .font(WheredayFont.serif(size: 28, relativeTo: .title))
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("paywall-sheet")
                Spacer()
                BackPageCircleButton(systemImage: "xmark", label: "Close") { dismiss() }
                    .accessibilityIdentifier("paywall-close")
            }
            .padding(.horizontal, 24)
            .padding(.top, 20)

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    leadCard
                    BackPageSegmented(
                        label: "Who is this for?",
                        options: [(false, "Just me"), (true, "Family · up to 6")],
                        selection: $family,
                        identifier: { $0 ? "paywall-audience-family" : "paywall-audience-individual" }
                    )
                    .frame(maxWidth: .infinity, alignment: .leading)
                    plans(selected: effective)
                    if family { PaywallFamilyNote() }
                    Divider().overlay(TE.border)
                    PaywallIncludedList()
                    PaywallNotes()
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 14)
            }
            .scrollBounceBehavior(.basedOnSize)

            VStack(spacing: 6) {
                PaywallStoreMessage(storeManager: storeManager)
                OnboardingPurchaseButton(storeManager: storeManager, checkout: checkout,
                                         option: effective.option(family: family), identifier: "paywall-purchase")
                OnboardingFinePrint(storeManager: storeManager, option: effective.option(family: family), now: now)
            }
            .padding(.horizontal, 24)
            .padding(.top, 10)
            .padding(.bottom, 12)
        }
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .foregroundStyle(TE.textPrimary)
        .background(TE.surface.ignoresSafeArea())
        .presentationDragIndicator(.visible)
        .onChange(of: checkout.isDone) { _, isDone in
            if isDone { dismiss() }
        }
    }

    private var leadCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(leadKicker)
                .font(TE.mono(.caption2))
                .tracking(0.6)
                .textCase(.uppercase)
                .foregroundStyle(TE.onInkCardMuted)
            Text(leadTitle)
                .font(WheredayFont.serif(size: 20, relativeTo: .title3))
            Text(leadDetail)
                .font(.footnote)
                .foregroundStyle(TE.onInkCardMuted)
        }
        .fixedSize(horizontal: false, vertical: true)
        .foregroundStyle(TE.onInkCard)
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(TE.inkCard))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("paywall-lead-\(lead.rawValue)")
    }

    private var leadKicker: LocalizedStringKey {
        switch lead {
        case .notes: "Because you picked “Send it to my notes”"
        case .routes: "Because you picked “Record runs and hikes”"
        case .countries: "Because you picked “Count days in countries”"
        case .days: "Because you picked “Remember my days”"
        }
    }

    private var leadTitle: LocalizedStringKey {
        switch lead {
        case .notes: "A Markdown note for every day, in your vault."
        case .routes: "Every step, even with the phone at home."
        case .countries: "Every date abroad, counted for you."
        case .days: "Every day written down, without check-ins."
        }
    }

    private var leadDetail: LocalizedStringKey {
        switch lead {
        case .notes: "Scheduled exports write one dated file a day to a folder or Obsidian vault."
        case .routes: "Your Watch records the route; GPX exports open in Strava and gpx.studio."
        case .countries: "Country Days fills in from recordings; export the dates as CSV."
        case .days: "Visits and routes record in the background, on your iPhone only."
        }
    }

    @ViewBuilder
    private func plans(selected: OnboardingPlanChoice) -> some View {
        let offered = storeManager.onboardingOfferedPlans(family: family)
        if offered.isEmpty {
            PaywallPlansUnavailable(storeManager: storeManager)
        } else {
            VStack(spacing: 8) {
                ForEach(offered) { choice in
                    planCard(choice, isSelected: choice == selected)
                }
            }
        }
    }

    private func planCard(_ choice: OnboardingPlanChoice, isSelected: Bool) -> some View {
        let option = choice.option(family: family)
        let product = storeManager.product(for: option)
        let hasTrial = storeManager.onboardingHasTrial(option)
        return Button { plan = choice } label: {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 8) {
                        Text(verbatim: PaywallCopy.planName(for: option))
                            .font(.body.weight(.semibold))
                        if choice == .yearly {
                            Text("BEST VALUE").font(TE.mono(.caption2)).foregroundStyle(TE.inkRust)
                        } else if choice == .lifetime {
                            Text("ONE-TIME").font(TE.mono(.caption2)).foregroundStyle(TE.inkRust)
                        }
                    }
                    if let product {
                        Text(verbatim: PaywallCopy.planDetail(for: option, price: product.price, format: product.priceFormatStyle))
                            .font(.footnote)
                            .foregroundStyle(TE.textMuted)
                    }
                    if hasTrial {
                        Text("7 days free")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(TE.inkRust)
                    }
                }
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                if let product {
                    VStack(alignment: .trailing, spacing: 0) {
                        Text(verbatim: product.displayPrice)
                            .font(TE.mono(.subheadline, weight: .medium))
                            .monospacedDigit()
                        Text(verbatim: option.billingCaption)
                            .font(.caption2)
                            .foregroundStyle(TE.textMuted)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 16)
                .strokeBorder(isSelected ? TE.textPrimary : TE.border, lineWidth: isSelected ? 2 : 1))
            .contentShape(RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityIdentifier("paywall-plan-\(choice.rawValue)")
    }
}
