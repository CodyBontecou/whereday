import SwiftUI
/// System stand-ins for the app's bundled faces; typography does not affect the applied bounds.
enum WheredayFont {
    static func mono(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font { .system(style, design: .monospaced).weight(weight) }
    static func serif(_ style: Font.TextStyle) -> Font { .system(style, design: .serif) }
}
