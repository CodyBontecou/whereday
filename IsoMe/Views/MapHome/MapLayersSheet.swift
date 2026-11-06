import SwiftUI

extension LayerToggleHelp {
    static let visitSuggestions = LayerToggleHelp(
        id: "visit-suggestions",
        title: "“Are you here?”",
        message: "When you stop somewhere new, the map suggests the nearest saved place or Apple Maps place so you can save the visit in one tap."
    )

    static let gpsOutliers = LayerToggleHelp(
        id: "gps-outliers",
        title: "GPS outliers",
        message: "Points far from the rest of the route, usually from a weak signal, are left off the map. Turn this on to draw them too."
    )
}

/// "What the map shows" (board M4): every layer of the old map, with its help, kept between launches.
struct MapLayersSheet: View {
    @Environment(\.dismiss) private var dismiss
    let hasSessionPoints: Bool

    @AppStorage(MapLayerPreferences.visitsKey) private var visits = true
    @AppStorage(MapLayerPreferences.travelPathKey) private var travelPath = true
    @AppStorage(MapLayerPreferences.snapToRoadsKey) private var snapToRoads = true
    @AppStorage(MapLayerPreferences.straightGapsKey) private var straightGaps = false
    @AppStorage(MapLayerPreferences.pointMarkersKey) private var gpsPoints = false
    @AppStorage(MapLayerPreferences.startEndKey) private var startEnd = true
    @AppStorage(MapLayerPreferences.sessionPathKey) private var liveSession = false
    @AppStorage(MapLayerPreferences.photosKey) private var photos = false
    @AppStorage(MapLayerPreferences.photoImagesKey) private var photoImages = true
    @AppStorage(MapLayerPreferences.photoDotsKey) private var photoDots = false
    @AppStorage(MapLayerPreferences.suggestionsKey) private var suggestions = false
    @AppStorage(MapLayerPreferences.showOutliersKey) private var showOutliers = false
    @AppStorage(MapLayerSettings.minimumStayMinutesKey) private var minimumStayMinutes = 0
    @State private var activeHelp: LayerToggleHelp?

    var body: some View {
        VStack(spacing: 0) {
            header
            ScrollView {
                VStack(spacing: 0) {
                    row("Visits", subtitle: visitsSubtitle, systemImage: "mappin.circle.fill", help: .visitMarkers, isOn: $visits)
                    if visits {
                        subRow {
                            Text("Shortest stop")
                                .font(.subheadline)
                            Spacer(minLength: 8)
                            Picker("Shortest stop", selection: $minimumStayMinutes) {
                                ForEach(MapLayerSettings.minimumStayOptions, id: \.self) { option in
                                    if option == 0 { Text("Any length").tag(option) } else { Text("\(option) min").tag(option) }
                                }
                            }
                            .labelsHidden()
                            .tint(TE.textPrimary)
                        }
                    }
                    divider

                    row("Travel path", subtitle: "Your trail for the chosen days", systemImage: "point.topleft.down.to.point.bottomright.curvepath",
                        help: .travelPath, isOn: $travelPath, subHelps: [.roadMatchedPath, .straightLinePathGaps])
                    if travelPath {
                        subRow {
                            subChip("Match roads", isOn: $snapToRoads, help: .roadMatchedPath)
                            subChip("Show straight gaps", isOn: $straightGaps, help: .straightLinePathGaps)
                        }
                    }
                    divider

                    row("GPS points", subtitle: "Tap one for its time and accuracy", systemImage: "smallcircle.filled.circle",
                        help: .pointMarkers, isOn: $gpsPoints, subHelps: [.gpsOutliers])
                    if gpsPoints || travelPath {
                        subRow {
                            subChip("Show outliers", isOn: $showOutliers, help: .gpsOutliers)
                            Spacer(minLength: 0)
                        }
                    }
                    divider

                    row("Start & end", subtitle: "Where the range begins and the latest point", systemImage: "flag.fill",
                        help: .startEndMarkers, isOn: $startEnd)
                    divider

                    row("Live session", subtitle: hasSessionPoints ? "The route being recorded right now" : "Shown while recording",
                        systemImage: "waveform.path.ecg", help: .activeSessionPath, isOn: $liveSession)
                    divider

                    row("Photos", subtitle: "Matched by time and place, never copied", systemImage: "camera.fill",
                        help: .photoMarkers, isOn: $photos)
                    if photos {
                        subRow {
                            Picker("Photo markers", selection: photoDisplay) {
                                Text("Images").tag(PhotoMarkerDisplay.images)
                                Text("Pins").tag(PhotoMarkerDisplay.pins)
                                Text("Dots").tag(PhotoMarkerDisplay.dots)
                            }
                            .pickerStyle(.segmented)
                        }
                    }
                    divider

                    row("“Are you here?”", subtitle: "Suggests a name when you stop somewhere new", systemImage: "questionmark.bubble",
                        help: .visitSuggestions, isOn: $suggestions)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 30)
            }
        }
        .foregroundStyle(TE.textPrimary)
        .background(TE.surface.ignoresSafeArea())
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        #if DEBUG
        .onAppear {
            if ProcessInfo.processInfo.arguments.contains("--demo-road-layer-help") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { activeHelp = .roadMatchedPath }
            }
        }
        #endif
    }

    private var header: some View {
        HStack(alignment: .bottom) {
            VStack(alignment: .leading, spacing: 2) {
                Text("WHAT THE MAP SHOWS")
                    .font(WheredayFont.mono(size: 10, weight: .medium, relativeTo: .caption2))
                    .tracking(1.4)
                    .foregroundStyle(TE.textMuted)
                Text("Layers")
                    .font(WheredayFont.serif(size: 28, relativeTo: .title))
                    .accessibilityAddTraits(.isHeader)
            }
            Spacer()
            Button { dismiss() } label: {
                Text("Done")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(MapHomeStyle.invertedText)
                    .padding(.horizontal, 18)
                    .frame(minHeight: 40)
                    .background(Capsule().fill(MapHomeStyle.invertedFill))
                    .frame(minHeight: 44)
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 20)
        .padding(.top, 22)
        .padding(.bottom, 8)
    }

    private var visitsSubtitle: LocalizedStringKey {
        minimumStayMinutes > 0 ? "Stops longer than \(minimumStayMinutes) min, coloured by place" : "Every stop, coloured by place"
    }

    private var photoDisplay: Binding<PhotoMarkerDisplay> {
        Binding(
            get: { PhotoMarkerDisplay(showsImages: photoImages, showsDots: photoDots) },
            set: { choice in
                let flags = choice.storedFlags(previousImages: photoImages)
                photoImages = flags.images
                photoDots = flags.dots
            }
        )
    }

    private var divider: some View {
        Rectangle().fill(TE.dialTrack).frame(height: 1)
    }

    private func row(_ title: LocalizedStringKey, subtitle: LocalizedStringKey, systemImage: String,
                     help: LayerToggleHelp, isOn: Binding<Bool>, subHelps: [LayerToggleHelp] = []) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .font(.system(size: 16))
                .foregroundStyle(TE.textPrimary)
                .frame(width: 36, height: 36)
                .background(RoundedRectangle(cornerRadius: 10).fill(TE.adaptive(0xECE6D8, 0x222C46)))
                .accessibilityHidden(true)
            Toggle(isOn: isOn) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.system(size: 15, weight: .medium))
                    Text(subtitle)
                        .font(.system(size: 12))
                        .foregroundStyle(MapHomeStyle.secondaryText)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .toggleStyle(TEToggleStyle())
            helpButton(help, subHelps: subHelps, systemImage: systemImage, isOn: isOn.wrappedValue)
        }
        .padding(.vertical, 10)
    }

    private func subRow<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: 8, content: content)
            .padding(.leading, 48)
            .padding(.bottom, 10)
    }

    private func subChip(_ title: LocalizedStringKey, isOn: Binding<Bool>, help: LayerToggleHelp) -> some View {
        Button { isOn.wrappedValue.toggle() } label: {
            HStack(spacing: 6) {
                Image(systemName: isOn.wrappedValue ? "checkmark" : "plus")
                    .font(.system(size: 11, weight: .semibold))
                    .accessibilityHidden(true)
                Text(title)
                    .font(.system(size: 13, weight: .medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(isOn.wrappedValue ? MapHomeStyle.invertedText : TE.textPrimary)
            .padding(.horizontal, 12)
            .frame(minHeight: 32)
            .background(Capsule().fill(isOn.wrappedValue ? MapHomeStyle.invertedFill : TE.card))
            .overlay(Capsule().strokeBorder(isOn.wrappedValue ? Color.clear : TE.border, lineWidth: 1))
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn.wrappedValue ? .isSelected : [])
        .accessibilityHint(Text(help.message))
        .onLongPressGesture { activeHelp = help }
    }

    /// The row's help; a sub-switch's help (shown on long press, or by the demo) opens from the same button.
    private func helpButton(_ help: LayerToggleHelp, subHelps: [LayerToggleHelp], systemImage: String, isOn: Bool) -> some View {
        let shown = ([help] + subHelps).first { $0.id == activeHelp?.id }
        return Button { activeHelp = help } label: {
            Image(systemName: "info.circle")
                .font(.body)
                .foregroundStyle(TE.textMuted)
                .frame(width: 32, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(String(localized: "About \(String(localized: help.title))"))
        .popover(isPresented: Binding(
            get: { shown != nil },
            set: { if !$0, shown != nil { activeHelp = nil } }
        )) {
            LayerToggleHelpPopover(systemImage: systemImage, help: shown ?? help, isOn: isOn)
                .presentationCompactAdaptation(.popover)
        }
    }
}
