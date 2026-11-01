import SwiftUI

/// The inverted ink card at the top of Settings.
struct DiscordPromoBanner: View {
    @Environment(\.openURL) private var openURL
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let onDismiss: () -> Void

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(spacing: 12))

        layout {
            VStack(alignment: .leading, spacing: 2) {
                Text("JOIN THE COMMUNITY")
                    .font(TE.mono(.caption2))
                    .tracking(1.2)
                    .foregroundStyle(TE.onInkCardMuted)
                Text("Chat with us on Discord")
                    .font(TE.editorial(.title3))
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)

            HStack(spacing: 12) {
                Button {
                    if let discordInviteURL = IsoMeURLs.discordInvite {
                        openURL(discordInviteURL)
                    }
                } label: {
                    Text("Join")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(TE.inkCard)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 44)
                        .background(Capsule().fill(TE.onInkCard))
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Join the Discord community.")

                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.footnote.weight(.semibold))
                        .frame(width: 44, height: 44)
                        .overlay(Circle().strokeBorder(TE.onInkCard.opacity(0.35), lineWidth: 1))
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Dismiss Discord banner.")
            }
        }
        .foregroundStyle(TE.onInkCard)
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.inkCard))
        .accessibilityElement(children: .contain)
    }
}

#Preview {
    ZStack {
        TE.surface.ignoresSafeArea()
        DiscordPromoBanner(onDismiss: {})
            .padding()
    }
}
