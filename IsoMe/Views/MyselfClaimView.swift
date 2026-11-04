import SwiftUI
import StoreKit

/// Build-time rollout switch. Keep the claim implementation available for a future release.
enum MyselfClaimFeature {
    static let isEnabled = false
}

/// Sends purchase proof only after the user chooses to claim access. No health or location data is sent.
struct MyselfClaimView: View {
    let app: String
    @Environment(\.openURL) private var openURL
    @Environment(\.dismiss) private var dismiss
    @State private var busy = false
    @State private var errorMessage: String?
    @State private var openedClaim = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top) {
                    BackPageHeader(caption: "A GIFT WITH YOUR PURCHASE", title: "Claim free myself.md access")
                    BackPageCircleButton(systemImage: "xmark", label: "Close") { dismiss() }
                }
                .padding(.leading, 24)
                .padding(.trailing, 20)
                .padding(.top, 20)

                Text("Your existing purchase includes complimentary lifetime access to myself.md.")
                    .font(.subheadline)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 24)
                    .padding(.top, 14)

                BackPageCard {
                    step(1, title: "Verify your purchase",
                         detail: "We’ll send your App Store purchase proof to myself.md to verify eligibility. Your health and location data stay on your device.",
                         fill: openedClaim ? TE.placeInks[1] : TE.textPrimary, ink: TE.surface)
                    BackPageDivider()
                    step(2, title: "Choose the account that gets it",
                         detail: "Then sign in to choose the myself.md account that receives access. Each original purchase can be linked to one account. Family-shared access alone does not qualify; the purchaser can claim.",
                         fill: openedClaim ? TE.textPrimary : TE.dialTrack, ink: openedClaim ? TE.surface : TE.textPrimary)
                }
                .padding(.horizontal, 16)
                .padding(.top, 20)

                if let errorMessage {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("COULDN'T VERIFY")
                            .font(TE.mono(.caption2))
                            .tracking(1.2)
                            .foregroundStyle(TE.inkRust)
                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundStyle(TE.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
                    .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.placeInks[1], lineWidth: 1.5))
                    .padding(.horizontal, 16)
                    .padding(.top, 14)
                    .accessibilityElement(children: .combine)
                    .accessibilityAddTraits(.updatesFrequently)
                } else if openedClaim {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("VERIFIED")
                            .font(TE.mono(.caption2))
                            .tracking(1.2)
                            .foregroundStyle(TE.onInkCardMuted)
                        Text("Finish signing in on myself.md, then sign in with the same account in the myself.md app on iOS or Android.")
                            .font(.footnote)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .foregroundStyle(TE.onInkCard)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 14)
                    .background(RoundedRectangle(cornerRadius: 18).fill(TE.inkCard))
                    .padding(.horizontal, 16)
                    .padding(.top, 14)
                    .accessibilityElement(children: .combine)
                }
            }
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 6) {
                Button {
                    Task { await claim() }
                } label: {
                    HStack(spacing: 10) {
                        if busy {
                            ProgressView().tint(TE.surface)
                            Text("Verifying purchase…")
                        } else if errorMessage != nil {
                            Text("Try again")
                        } else {
                            Text(openedClaim ? "Open claim again" : "Verify purchase and continue")
                        }
                    }
                }
                .buttonStyle(BackPagePillButtonStyle(kind: .filled, font: .body.weight(.semibold), minHeight: 56))
                .disabled(busy)

                Button("Not now") { dismiss() }
                    .font(.subheadline)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .buttonStyle(.plain)
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
            .padding(.bottom, 12)
            .background(TE.surface.ignoresSafeArea(edges: .bottom))
        }
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
    }

    private func step(_ number: Int, title: LocalizedStringKey, detail: LocalizedStringKey, fill: Color, ink: Color) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text(verbatim: "\(number)")
                .font(TE.editorial(.subheadline))
                .foregroundStyle(ink)
                .frame(width: 28, height: 28)
                .background(Circle().fill(fill))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.footnote.weight(.semibold))
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
            }
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .accessibilityElement(children: .combine)
    }

    @MainActor
    private func claim() async {
        guard MyselfClaimFeature.isEnabled else { return }
        busy = true
        errorMessage = nil
        defer { busy = false }
        do {
            // Explicit user action may ask the App Store to authenticate; background launches never do.
            try await AppStore.sync()
            let proof = try await Self.purchaseProof(app: app)
            let configured = Bundle.main.object(forInfoDictionaryKey: "MyselfClaimServerURL") as? String ?? "https://myself.md"
            guard let server = URL(string: configured), server.scheme == "https", server.user == nil,
                  server.password == nil, server.path.isEmpty || server.path == "/", server.query == nil, server.fragment == nil else {
                throw ClaimError.message("The myself.md claim service is not configured correctly.")
            }
            var request = URLRequest(url: server.appendingPathComponent("api/migration/proof"))
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.timeoutInterval = 30
            request.httpBody = try JSONEncoder().encode(proof)
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let response = response as? HTTPURLResponse else { throw ClaimError.message("The claim service did not respond.") }
            let result = try JSONDecoder().decode(ClaimResponse.self, from: data)
            guard response.statusCode == 200, let raw = result.claimUrl, let url = URL(string: raw),
                  url.scheme == server.scheme, url.host == server.host, url.port == server.port,
                  url.user == nil, url.password == nil, url.path == "/claim", url.query == nil,
                  let ticket = url.fragment, ticket.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil else {
                throw ClaimError.message(result.error ?? "Your purchase could not be verified. Please try again.")
            }
            openURL(url)
            openedClaim = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    @MainActor
    private static func purchaseProof(app: String) async throws -> PurchaseProof {
        let products: Set<String> = app == "health.md"
            ? ["com.codybontecou.obsidianhealth.unlock", "com.codybontecou.obsidianhealth.unlock.family"]
            : ["com.bontecou.isome.lifetime.individual", "com.bontecou.isome.lifetime"]
        for await result in Transaction.currentEntitlements {
            if case .verified(let transaction) = result,
               products.contains(transaction.productID), transaction.revocationDate == nil,
               transaction.ownershipType == .purchased {
                return PurchaseProof(app: app, kind: "iap", proof: result.jwsRepresentation)
            }
        }
        if app == "health.md" {
            let result = try await AppTransaction.refresh()
            if case .verified(let transaction) = result,
               transaction.originalPurchaseDate < Date(timeIntervalSince1970: 1777161600) {
                return PurchaseProof(app: app, kind: "app", proof: result.jwsRepresentation)
            }
        }
        throw ClaimError.message("No qualifying purchase was found. Use the Apple Account that bought this app or its lifetime unlock, then try again.")
    }

    private struct PurchaseProof: Encodable {
        let app: String
        let store = "ios"
        let kind: String
        let proof: String
    }
    private struct ClaimResponse: Decodable {
        var claimUrl: String?
        var error: String?
    }
    private enum ClaimError: LocalizedError {
        case message(String)
        var errorDescription: String? { if case .message(let text) = self { return text }; return nil }
    }
}
