import SwiftUI

// Map annotations for the map home (boards M1, M3, M5, M6).

private extension View {
    /// The paper halo the boards draw behind map labels so they read over streets.
    func paperHalo() -> some View {
        shadow(color: TE.surface, radius: 0.6)
            .shadow(color: TE.surface, radius: 1.2)
            .shadow(color: TE.surface, radius: 2)
    }
}

/// A visit: a filled dot in its place's ink with a paper ring, its name in serif italic and how long it lasted.
/// The current stay pulses in vermilion with "NOW · 30 MIN". Unconfirmed stays keep a dashed ring, so they
/// still read as a guess (`VisitMarkerState`).
struct MapVisitPin: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let visit: Visit
    let name: String
    let ink: Color
    let durationText: String
    let isSelected: Bool
    let isCurrent: Bool
    var isDimmed = false
    var showsLabel = true
    @State private var pulses = false

    private var state: VisitMarkerState { VisitMarkerState(visit: visit) }
    private var dotInk: Color { isCurrent ? MapHomeStyle.vermilion : ink }

    var body: some View {
        ZStack {
            if isCurrent {
                Circle().fill(MapHomeStyle.vermilion.opacity(0.16))
                    .frame(width: 52, height: 52)
                    .scaleEffect(pulses ? 1.15 : 0.9)
                    .animation(reduceMotion ? nil : .easeInOut(duration: 1.4).repeatForever(autoreverses: true), value: pulses)
                Circle().fill(MapHomeStyle.vermilion.opacity(0.22))
                    .frame(width: 32, height: 32)
            } else if isSelected {
                Circle()
                    .fill(ink.opacity(0.16))
                    .overlay(Circle().strokeBorder(ink, lineWidth: 1.5))
                    .frame(width: 44, height: 44)
            }

            Circle()
                .fill(dotInk)
                .frame(width: isCurrent ? 16 : 22, height: isCurrent ? 16 : 22)
                .overlay {
                    if state.isTentative && !isCurrent {
                        Circle().strokeBorder(TE.card, style: StrokeStyle(lineWidth: 3, dash: [3, 2.5]))
                    } else {
                        Circle().strokeBorder(TE.card, lineWidth: isCurrent ? 3 : 3.5)
                    }
                }
                .overlay(alignment: .topTrailing) {
                    if state == .corrected || state == .manual {
                        Image(systemName: state.symbol)
                            .font(.system(size: 7, weight: .bold))
                            .foregroundStyle(TE.card)
                            .frame(width: 12, height: 12)
                            .background(Circle().fill(TE.textPrimary))
                            .offset(x: 5, y: -5)
                    }
                }
        }
        .frame(width: 44, height: 44)
        .overlay(alignment: .leading) {
            if showsLabel {
                VStack(alignment: .leading, spacing: 0) {
                    Text(name)
                        .font(WheredayFont.serif(size: 15, relativeTo: .subheadline).italic())
                        .foregroundStyle(TE.textPrimary)
                    Text(durationText)
                        .font(WheredayFont.mono(size: 9, weight: isCurrent ? .semibold : .regular, relativeTo: .caption2))
                        .foregroundStyle(isCurrent ? MapHomeStyle.vermilion : MapHomeStyle.secondaryText)
                }
                .lineLimit(1)
                .paperHalo()
                .fixedSize()
                .offset(x: 40)
                .allowsHitTesting(false)
            }
        }
        .opacity(isDimmed ? 0.45 : 1)
        .contentShape(Circle())
        .onAppear { pulses = isCurrent && !reduceMotion }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(isCurrent ? "Current visit at \(name)" : "Visit at \(name)")
        .accessibilityValue([visit.confirmationStatus.displayName, visit.source.displayName, visit.accessibilityValue].joined(separator: ". "))
        .accessibilityHint(visit.accessibilityHint)
        .accessibilityAddTraits(.isButton)
    }
}

/// Where the range begins: a small ink diamond; tap for the start time.
struct MapStartDiamond: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let timestamp: Date
    @State private var showingTooltip = false

    var body: some View {
        Button {
            withAnimation(reduceMotion ? nil : .spring(duration: 0.2)) { showingTooltip.toggle() }
        } label: {
            Rectangle()
                .fill(TE.textPrimary)
                .frame(width: 10, height: 10)
                .overlay(Rectangle().stroke(TE.card, lineWidth: 2))
                .rotationEffect(.degrees(45))
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) {
            if showingTooltip {
                MapAnnotationTooltipOverlay {
                    PathEndpointTooltip(label: "START", timestamp: timestamp)
                }
            }
        }
        .accessibilityLabel("Path start")
        .accessibilityValue(timestamp.formatted(date: .abbreviated, time: .shortened))
        .accessibilityHint(showingTooltip ? "Hides the start time." : "Shows the start time.")
    }
}

/// "08:12" at the start of a main leg of the route.
struct MapTimeChip: View {
    let text: String

    var body: some View {
        Text(text)
            .font(WheredayFont.mono(size: 9.5, relativeTo: .caption2))
            .foregroundStyle(MapHomeStyle.secondaryText)
            .padding(.horizontal, 6)
            .frame(minWidth: 40, minHeight: 17)
            .background(Capsule().fill(TE.card.opacity(0.92)))
            .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
            .fixedSize()
            .allowsHitTesting(false)
            .accessibilityLabel(Text("Left at \(text)"))
    }
}

/// A place in a multi-day range: sized by, and numbered with, the days it was visited.
struct MapCountBubble: View {
    let name: String
    let dayCount: Int
    let ink: Color
    let diameter: Double
    var isDimmed = false

    var body: some View {
        Text(dayCount.formatted())
            .font(.system(size: 9.5, weight: .semibold))
            .monospacedDigit()
            .foregroundStyle(TE.card)
            .frame(width: diameter, height: diameter)
            .background(Circle().fill(ink))
            .overlay(Circle().strokeBorder(TE.card, lineWidth: 2.5))
            .frame(minWidth: 44, minHeight: 44)
            .opacity(isDimmed ? 0.5 : 1)
            .contentShape(Circle())
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(name))
            .accessibilityValue(dayCount == 1 ? Text("Visited on 1 day") : Text("Visited on \(dayCount) days"))
            .accessibilityAddTraits(.isButton)
    }
}

/// A numbered "Where were you?" candidate near a tapped visit; the number matches the sheet's list.
struct MapCandidatePin: View {
    let number: Int
    let name: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(number.formatted())
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(isSelected ? TE.card : TE.textPrimary)
                .frame(width: 18, height: 18)
                .background(Circle().fill(isSelected ? TE.textPrimary : TE.card))
                .overlay(Circle().strokeBorder(TE.textPrimary, lineWidth: 1.5))
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("\(number). \(name)"))
        .accessibilityHint("Renames the visit to this place.")
    }
}
