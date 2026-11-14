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

    static let accent = adaptive(UIColor(red: 0.208, green: 0.310, blue: 0.525, alpha: 1),
                                 UIColor(red: 0.69, green: 0.77, blue: 0.95, alpha: 1))
    static let surface = adaptive(UIColor(red: 0.969, green: 0.957, blue: 0.922, alpha: 1),
                                  UIColor(red: 0.106, green: 0.125, blue: 0.169, alpha: 1))
    static let surfaceDark = Color(red: 0.12, green: 0.12, blue: 0.12)
    static let card = adaptive(UIColor(red: 1, green: 0.992, blue: 0.965, alpha: 1),
                              UIColor(red: 0.145, green: 0.18, blue: 0.235, alpha: 1))
    static let border = adaptive(UIColor(red: 0.847, green: 0.843, blue: 0.804, alpha: 1),
                                 UIColor(red: 0.267, green: 0.302, blue: 0.365, alpha: 1))
    static let borderDark = Color(red: 0.25, green: 0.25, blue: 0.25)
    static let textPrimary = adaptive(UIColor(red: 0.141, green: 0.231, blue: 0.357, alpha: 1),
                                      UIColor(red: 0.95, green: 0.95, blue: 0.93, alpha: 1))
    static let textMuted = adaptive(UIColor(red: 0.44, green: 0.43, blue: 0.41, alpha: 1),
                                    UIColor(red: 0.68, green: 0.67, blue: 0.64, alpha: 1))
    static let lcdGreen = Color(red: 0.0, green: 0.78, blue: 0.45)
    static let lcdBackground = adaptive(UIColor(red: 0.88, green: 0.90, blue: 0.85, alpha: 1),
                                        UIColor(red: 0.17, green: 0.21, blue: 0.17, alpha: 1))
    static let danger = Color(uiColor: .systemRed)
    static let warning = Color(uiColor: .systemOrange)
    static let success = Color(uiColor: .systemGreen)
    static let inkRust = adaptive(UIColor(red: 0.64, green: 0.28, blue: 0.20, alpha: 1),
                                  UIColor(red: 0.93, green: 0.67, blue: 0.59, alpha: 1))

    // MARK: - Typography
    static func mono(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
        Font.system(style, design: .monospaced).weight(weight)
    }

    static func editorial(_ style: Font.TextStyle) -> Font {
        Font.system(style, design: .serif)
    }
}

// MARK: - Reusable Components

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
    var title: LocalizedStringKey {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
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
    }
}
