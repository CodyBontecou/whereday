import SwiftUI
import UIKit

/// Paper and blue ink tokens shared across Whereday's journal and utility screens.
enum TE {
    // MARK: - Colors
    /// Paper and ink adapt across sheets and controls in both appearances.
    static func adaptive(_ light: UIColor, _ dark: UIColor) -> Color {
        Color(uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark ? dark : light
        })
    }

    static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        adaptive(UIColor(hex: light), UIColor(hex: dark))
    }

    /// Navy ink on paper; amber in the night appearance.
    static let accent = adaptive(0x1E2B45, 0xE0B565)
    static let surface = adaptive(0xF3EFE6, 0x0F1626)
    static let surfaceDark = Color(red: 0.12, green: 0.12, blue: 0.12)
    static let card = adaptive(0xFBF9F4, 0x18213A)
    static let border = adaptive(0xD8D2C3, 0x2C3754)
    static let borderDark = Color(red: 0.25, green: 0.25, blue: 0.25)
    static let textPrimary = adaptive(0x1E2B45, 0xECE6D8)
    static let textMuted = adaptive(0x66645D, 0x9AA3B8)
    static let lcdGreen = Color(red: 0.0, green: 0.78, blue: 0.45)
    static let lcdBackground = adaptive(UIColor(red: 0.88, green: 0.90, blue: 0.85, alpha: 1),
                                        UIColor(red: 0.17, green: 0.21, blue: 0.17, alpha: 1))
    static let danger = Color(uiColor: .systemRed)
    static let warning = Color(uiColor: .systemOrange)
    static let success = Color(uiColor: .systemGreen)
    static let inkRust = adaptive(0xA8381F, 0xF0805F)

    // MARK: - Day dial
    /// Place inks cycle in this order so a day's places stay distinguishable on the dial.
    static let placeInks: [Color] = [
        adaptive(0x1E2B45, 0xECE6D8),
        adaptive(0xC2462A, 0xF0805F),
        adaptive(0xB7862B, 0xE0B565),
        adaptive(0x5E7F5A, 0x9DC097)
    ]
    static let dialTrack = adaptive(0xE4DED0, 0x222C46)
    static let dialTick = adaptive(0x9A9585, 0x4A5573)
    /// The inverted "you're at" card: navy on paper, amber at night.
    static let inkCard = adaptive(0x1E2B45, 0xE0B565)
    static let onInkCard = adaptive(0xF3EFE6, 0x0F1626)
    static let onInkCardMuted = adaptive(0xC9CEDA, 0x5A4A2A)
    static let liveDot = adaptive(0xF08A6B, 0xC2462A)

    // MARK: - Typography
    static func mono(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
        WheredayFont.mono(style, weight: weight)
    }

    static func editorial(_ style: Font.TextStyle) -> Font {
        WheredayFont.serif(style)
    }
}

private extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}

// MARK: - Reusable Components

/// Export-style ink actions with explicit label colors, independent of ancestor foreground styles.
struct TEActionButtonStyle: ButtonStyle {
    enum Emphasis {
        case primary, secondary, destructive
    }

    var emphasis: Emphasis = .secondary
    @Environment(\.isEnabled) private var isEnabled

    private var ink: Color {
        if !isEnabled { return TE.textMuted }
        return emphasis == .destructive ? TE.inkRust : TE.accent
    }

    func makeBody(configuration: Configuration) -> some View {
        let filled = emphasis == .primary && isEnabled

        configuration.label
            .font(.body.weight(.semibold))
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, minHeight: filled ? 52 : 44)
            .foregroundStyle(filled ? TE.surface : ink)
            // Opaque paper keeps the decorative artwork from reducing label contrast.
            .background {
                ZStack {
                    filled ? ink : TE.card
                    if configuration.isPressed {
                        filled ? Color.black.opacity(0.12) : ink.opacity(0.06)
                    }
                }
            }
            .overlay {
                RoundedRectangle(cornerRadius: 16)
                    .strokeBorder(filled ? ink : (emphasis == .destructive ? ink : TE.border), lineWidth: 1)
            }
            .clipShape(RoundedRectangle(cornerRadius: 16))
    }
}

/// Decorative ink is confined to its own layout space and never intercepts map gestures.
struct FieldNotesArtwork: View {
    enum Subject: String {
        case clouds = "JournalClouds"
        case street = "JournalStreet"
    }

    let subject: Subject
    var height: CGFloat = 140

    var body: some View {
        GeometryReader { geometry in
            if subject == .clouds {
                inkImage
                    .scaledToFill()
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .clipped()
                    .mask {
                        LinearGradient(
                            stops: [
                                .init(color: .clear, location: 0),
                                .init(color: .black, location: 0.08),
                                .init(color: .black, location: 0.88),
                                .init(color: .clear, location: 1)
                            ],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                        .mask {
                            LinearGradient(
                                stops: [
                                    .init(color: .clear, location: 0),
                                    .init(color: .black, location: 0.06),
                                    .init(color: .black, location: 0.94),
                                    .init(color: .clear, location: 1)
                                ],
                                startPoint: .leading,
                                endPoint: .trailing
                            )
                        }
                    }
            } else {
                inkImage
                    .scaledToFill()
                    .frame(width: geometry.size.width, height: geometry.size.height, alignment: .trailing)
                    .clipped()
            }
        }
        .frame(height: height)
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }

    private var inkImage: some View {
        Image(decorative: subject.rawValue)
            .renderingMode(.template)
            .resizable()
            .foregroundStyle(TE.accent)
            .accessibilityHidden(true)
    }
}

/// Stationary ink behind the journal UI, with no space reserved in the content layout.
struct FieldNotesBackground: View {
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                TE.surface

                VStack(spacing: 0) {
                    FieldNotesArtwork(subject: .clouds, height: min(geometry.size.height * 0.36, 280))
                        .opacity(0.28)

                    Spacer(minLength: 0)

                    FieldNotesArtwork(subject: .street, height: min(geometry.size.height * 0.48, 360))
                        .opacity(0.34)
                }
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
        .allowsHitTesting(false)
    }
}

/// TE-styled section header: uppercase, tracked, monospaced.
struct TESectionHeader: View {
    let title: LocalizedStringKey
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        Text(title)
            .font(TE.mono(.caption2, weight: .semibold))
            .tracking(dynamicTypeSize.isAccessibilitySize ? 1 : 2)
            .foregroundStyle(TE.textMuted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 6)
    }
}

/// A quiet paper surface for grouped controls.
struct TECard<Content: View>: View {
    let content: Content
    let paperOpacity: Double

    init(paperOpacity: Double = 1, @ViewBuilder content: () -> Content) {
        self.paperOpacity = paperOpacity
        self.content = content()
    }

    var body: some View {
        content
            .background(
                RoundedRectangle(cornerRadius: 10)
                    .fill(TE.card.opacity(paperOpacity))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10)
                    .strokeBorder(TE.border, lineWidth: 1)
            )
    }
}

/// A single row inside a TECard, with optional bottom divider.
struct TERow<Content: View>: View {
    let showDivider: Bool
    let content: Content

    init(showDivider: Bool = true, @ViewBuilder content: () -> Content) {
        self.showDivider = showDivider
        self.content = content()
    }

    var body: some View {
        VStack(spacing: 0) {
            content
                .padding(.horizontal, 16)
                .padding(.vertical, 13)

            if showDivider {
                Divider()
                    .background(TE.border)
                    .padding(.leading, 16)
            }
        }
    }
}

/// TE-styled toggle: cream-paper-friendly off track with stronger border,
/// solid accent on track. Gives clear contrast on TE.surface backgrounds.
struct TEToggleStyle: ToggleStyle {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .body) private var trackWidth: CGFloat = 50
    @ScaledMetric(relativeTo: .body) private var trackHeight: CGFloat = 30
    @ScaledMetric(relativeTo: .body) private var thumbInset: CGFloat = 2

    @ViewBuilder
    func makeBody(configuration: Configuration) -> some View {
        let isOn = configuration.isOn
        let thumbDiameter = trackHeight - thumbInset * 2

        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 12) {
                configuration.label
                    .fixedSize(horizontal: false, vertical: true)

                toggleTrack(isOn: isOn, thumbDiameter: thumbDiameter)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture {
                configuration.isOn.toggle()
            }
            .accessibilityRepresentation {
                Toggle(isOn: configuration.$isOn) { configuration.label }
            }
        } else {
            HStack(alignment: .center, spacing: 12) {
                configuration.label
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 12)
                toggleTrack(isOn: isOn, thumbDiameter: thumbDiameter)
            }
            .contentShape(Rectangle())
            .onTapGesture {
                configuration.isOn.toggle()
            }
            .accessibilityRepresentation {
                Toggle(isOn: configuration.$isOn) { configuration.label }
            }
        }
    }

    private func toggleTrack(isOn: Bool, thumbDiameter: CGFloat) -> some View {
        ZStack(alignment: isOn ? .trailing : .leading) {
            Capsule()
                .fill(isOn ? TE.accent : TE.border)
            Capsule()
                .strokeBorder(isOn ? TE.accent : TE.textMuted.opacity(0.35), lineWidth: 1)

            Circle()
                .fill(Color.white)
                .frame(width: thumbDiameter, height: thumbDiameter)
                .shadow(color: .black.opacity(0.18), radius: 1.5, x: 0, y: 1)
                .padding(.horizontal, thumbInset)
        }
        .frame(width: trackWidth, height: trackHeight)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.18), value: isOn)
    }
}

/// TE-styled section footer text.
struct TESectionFooter: View {
    let text: LocalizedStringKey

    var body: some View {
        Text(text)
            .font(TE.mono(.caption2, weight: .regular))
            .foregroundStyle(TE.textMuted)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.top, 4)
    }
}

/// Stored as a raw value so an unknown preference safely falls back to the system.
enum AppAppearance: String, CaseIterable, Identifiable {
    case system, light, dark
    var id: String { rawValue }
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
    var interfaceStyle: UIUserInterfaceStyle {
        switch self {
        case .system: .unspecified
        case .light: .light
        case .dark: .dark
        }
    }
    var title: LocalizedStringKey {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
    }
}

/// Apply the preference at the window so existing sheets also return to the
/// system appearance. Clearing SwiftUI's preferredColorScheme alone leaves a
/// presented sheet using its previous override until it is dismissed.
struct AppAppearanceWindowOverride: UIViewRepresentable {
    let appearance: AppAppearance

    func makeUIView(context: Context) -> AppearanceView {
        let view = AppearanceView()
        view.isUserInteractionEnabled = false
        view.appearance = appearance
        return view
    }

    func updateUIView(_ uiView: AppearanceView, context: Context) {
        uiView.appearance = appearance
    }

    final class AppearanceView: UIView {
        var appearance: AppAppearance = .system {
            didSet { applyAppearance() }
        }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            applyAppearance()
        }

        private func applyAppearance() {
            guard let window, window.overrideUserInterfaceStyle != appearance.interfaceStyle else { return }
            window.overrideUserInterfaceStyle = appearance.interfaceStyle
        }
    }
}

// MARK: - Illustrated onboarding and purchase flow

/// Kept separate from TE so the rest of the app retains its existing appearance.
enum InkPaperPalette {
    static let ink = TE.adaptive(
        UIColor(red: 0.14, green: 0.27, blue: 0.62, alpha: 1),
        UIColor(red: 0.58, green: 0.70, blue: 1, alpha: 1)
    )
    static let paper = TE.adaptive(
        UIColor(red: 0.98, green: 0.97, blue: 0.94, alpha: 1),
        UIColor(red: 0.09, green: 0.11, blue: 0.15, alpha: 1)
    )
    static let pencil = TE.adaptive(
        UIColor(red: 0.39, green: 0.39, blue: 0.38, alpha: 1),
        UIColor(red: 0.72, green: 0.73, blue: 0.76, alpha: 1)
    )
    static let wash = ink.opacity(0.045)
    static let border = ink.opacity(0.24)
}


/// Decorative ink sketches sit behind the flow, never reserving header space.
struct InkJournalBackground: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                InkPaperPalette.paper
                VStack(spacing: 0) {
                    sketch("JournalClouds")
                        .frame(height: min(geometry.size.height * 0.38, 320))
                        .opacity(dynamicTypeSize.isAccessibilitySize ? 0.15 : 0.30)
                        .mask(LinearGradient(stops: [.init(color: .black, location: 0), .init(color: .black, location: 0.6), .init(color: .clear, location: 1)], startPoint: .top, endPoint: .bottom))
                    Spacer(minLength: 0)
                    sketch("JournalStreet")
                        .frame(height: min(geometry.size.height * 0.48, 400))
                        .opacity(dynamicTypeSize.isAccessibilitySize ? 0.16 : 0.42)
                }
                InkPaperGrain(ink: InkPaperPalette.ink)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }

    private func sketch(_ name: String) -> some View {
        GeometryReader { geometry in
            Image(decorative: name)
                .renderingMode(.template)
                .resizable()
                .scaledToFill()
                .foregroundStyle(InkPaperPalette.ink)
                .frame(width: geometry.size.width, height: geometry.size.height)
                .clipped()
        }
    }
}


/// On the inner display, forms and their bottom action stay together on one page.
/// The second page is only background artwork, with no extra header or controls.
struct JournalFlowPresentation: ViewModifier {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass

    @ViewBuilder
    func body(content: Content) -> some View {
        // Runtime availability alone cannot hide a type from an older SDK.
        #if compiler(>=6.4)
        if #available(iOS 27.1, *) {
            ArrangementView {
                content
            } secondary: {
                GeometryReader { geometry in
                    ZStack {
                        InkPaperPalette.paper
                        VStack(spacing: 0) {
                            FieldNotesArtwork(subject: .clouds, height: geometry.size.height * 0.36)
                                .opacity(0.35)
                            Spacer(minLength: 0)
                            FieldNotesArtwork(subject: .street, height: geometry.size.height * 0.60)
                                .opacity(0.60)
                        }
                    }
                }
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .accessibilityHidden(true)
            }
            .arrangementViewStyle(.split.axes(horizontalSizeClass == .regular ? Axis.Set.horizontal.union(.vertical) : Axis.Set()))
        } else {
            content
        }
        #else
        content
        #endif
    }
}
