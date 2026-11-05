import SwiftUI

/// The four destinations of the floating tab bar, plus Settings, which joins them on the open rail.
enum MainTab: Int, CaseIterable, Identifiable {
    // Raw values are the TabView tags. Map takes 0, the tag the map had before 2.0;
    // the others keep their 2.0 tags so launch arguments stay stable.
    case map = 0, atlas = 5, places = 6, archive = 1, settings = 2

    var id: Int { rawValue }

    /// The floating tab bar, in order. The map is home.
    static let primary: [MainTab] = [.map, .atlas, .places, .archive]

    var title: LocalizedStringKey {
        switch self {
        case .map: "Map"
        case .atlas: "Atlas"
        case .places: "Places"
        case .archive: "Archive"
        case .settings: "Settings"
        }
    }

    var systemImage: String {
        switch self {
        case .map: "map"
        case .atlas: "square.grid.2x2"
        case .places: "mappin.and.ellipse"
        case .archive: "archivebox"
        case .settings: "gearshape"
        }
    }
}

/// Where a launch (or the `--default-tab=N` debug argument) lands.
///
/// Launch numbers predate the map-first home and stay stable:
/// 0 Map, 1 Archive, 2 Settings (behind Archive), 3 Timeline (over the map),
/// 4 the old Today tab (now the map with its day sheet open), 5 Atlas, 6 Places.
struct MainTabLaunch: Equatable {
    var tab: MainTab
    /// The day-by-day timeline sheet opens over the map.
    var opensTimeline = false
    /// The map's day sheet starts expanded, where Today's dial and list now live.
    var expandsDaySheet = false

    static let home = MainTabLaunch(tab: .map)
    static let launchArgumentPrefix = "--default-tab="
    static let validLaunchNumbers = 0...6

    init(tab: MainTab, opensTimeline: Bool = false, expandsDaySheet: Bool = false) {
        self.tab = tab
        self.opensTimeline = opensTimeline
        self.expandsDaySheet = expandsDaySheet
    }

    init(launchNumber: Int) {
        switch launchNumber {
        case 0: self.init(tab: .map)
        case 1: self.init(tab: .archive)
        // Settings has no slot in the closed tab bar; it lives behind Archive there.
        case 2: self.init(tab: .archive)
        case 3: self.init(tab: .map, opensTimeline: true)
        case 4: self.init(tab: .map, expandsDaySheet: true)
        case 5: self.init(tab: .atlas)
        case 6: self.init(tab: .places)
        default: self = .home
        }
    }

    /// Reads `--default-tab=N`; anything missing or out of range opens the map.
    static func fromLaunchArguments(_ arguments: [String]) -> MainTabLaunch {
        guard let argument = arguments.first(where: { $0.hasPrefix(launchArgumentPrefix) }),
              let number = Int(argument.dropFirst(launchArgumentPrefix.count)),
              validLaunchNumbers.contains(number) else {
            return .home
        }
        return MainTabLaunch(launchNumber: number)
    }

    /// The launch for this process: debug builds honour `--default-tab=N`, release builds open the map.
    static var current: MainTabLaunch {
        #if DEBUG
        fromLaunchArguments(ProcessInfo.processInfo.arguments)
        #else
        .home
        #endif
    }
}

/// Lets a tab ask the floating tab bar to step aside (the map's replay takes the whole bottom edge).
struct HidesFloatingTabBarKey: PreferenceKey {
    static let defaultValue = false
    static func reduce(value: inout Bool, nextValue: () -> Bool) {
        value = value || nextValue()
    }
}

extension EnvironmentValues {
    /// Height the floating tab bar covers at the bottom of a tab drawn under it (the map); 0 elsewhere.
    @Entry var floatingTabBarInset: CGFloat = 0
}
