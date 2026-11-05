package com.bontecou.whereday

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/** The four destinations of the floating tab bar, plus Settings, which joins them only on the wide navigation rail
 *  (iOS MainTab). On the phone bar Settings lives behind Archive. Ids are the `navigation-<id>` test tags. */
internal enum class MainTab(val id:String,val title:Int,val icon:ImageVector,val selectedIcon:ImageVector) {
    map("map",R.string.shell_map,Icons.Outlined.Map,Icons.Filled.Map),
    atlas("atlas",R.string.shell_atlas,Icons.Outlined.GridView,Icons.Filled.GridView),
    places("places",R.string.shell_places,Icons.Outlined.Place,Icons.Filled.Place),
    archive("archive",R.string.shell_archive,Icons.Outlined.Archive,Icons.Filled.Archive),
    settings("settings",R.string.shell_settings,Icons.Outlined.Settings,Icons.Filled.Settings);
}

/** Where the app opens and where launcher actions and links land (iOS MainTabLaunch). */
internal object MainTabs {
    /** The floating tab bar, in order. The map is home. */
    val bar:List<MainTab> = listOf(MainTab.map,MainTab.atlas,MainTab.places,MainTab.archive)
    /** The wide navigation rail: the bar's four, then Settings below a rule. */
    val rail:List<MainTab> = bar+MainTab.settings
    /** The map is home: every fresh launch opens on it. */
    val launch:MainTab = MainTab.map
    /** Windows at least this wide show the rail instead of the floating bar (iOS: regular width). */
    const val railMinWidthDp=600
    fun index(tab:MainTab)=rail.indexOf(tab)
    fun at(index:Int)=rail.getOrElse(index) {launch}

    /** A destination: the tab, and whether the day's timeline opens over the map or Settings opens behind Archive. */
    data class Destination(val tab:MainTab,val timeline:Boolean=false,val settings:Boolean=false)
    /** Settings has no slot in the closed tab bar: there it opens behind Archive; on the rail it is its own tab. */
    fun resolve(destination:Destination,usesRail:Boolean):Destination = when {
        usesRail && (destination.tab==MainTab.settings || destination.settings)->Destination(MainTab.settings)
        !usesRail && destination.tab==MainTab.settings->Destination(MainTab.archive,settings=true)
        else->destination
    }
    /** When the rail closes back into the bar with Settings selected, the bar falls back to Archive, as on iOS. */
    fun onBar(tab:MainTab):MainTab = if(tab==MainTab.settings) MainTab.archive else tab

    /** The tab a launcher shortcut opens. Recording actions stay where they are. */
    fun destination(action:LauncherAction):Destination? = when(action) {
        LauncherAction.timeline->Destination(MainTab.map,timeline=true)
        LauncherAction.export->Destination(MainTab.archive)
        LauncherAction.start,LauncherAction.stop->null
    }
    /** Visit links and the manual-visit router always land on the map, with the timeline closed. */
    val visitLink=Destination(MainTab.map)
    /** Returning from a journal clear goes back to Settings, where it was started (behind Archive on the bar). */
    val afterClear=Destination(MainTab.settings)
}
