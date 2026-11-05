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

/** The floating tab bar, map first: Map · Atlas · Places · Archive, then Settings, which stays a tab on Android.
 *  Ids are the `navigation-<id>` test tags and the launch mapping's targets. */
internal enum class MainTab(val id:String,val title:Int,val icon:ImageVector,val selectedIcon:ImageVector) {
    map("map",R.string.shell_map,Icons.Outlined.Map,Icons.Filled.Map),
    atlas("atlas",R.string.shell_atlas,Icons.Outlined.GridView,Icons.Filled.GridView),
    places("places",R.string.shell_places,Icons.Outlined.Place,Icons.Filled.Place),
    archive("archive",R.string.shell_archive,Icons.Outlined.Archive,Icons.Filled.Archive),
    settings("settings",R.string.shell_settings,Icons.Outlined.Settings,Icons.Filled.Settings);
}

/** Where the app opens and where launcher actions and links land. */
internal object MainTabs {
    val order:List<MainTab> = MainTab.entries
    /** The map is home: every fresh launch opens on it. */
    val launch:MainTab = MainTab.map
    fun index(tab:MainTab)=order.indexOf(tab)
    fun at(index:Int)=order.getOrElse(index) {launch}

    /** The tab a launcher shortcut opens, and whether the day's timeline opens over the map. Recording actions stay where they are. */
    data class Destination(val tab:MainTab,val timeline:Boolean=false)
    fun destination(action:LauncherAction):Destination? = when(action) {
        LauncherAction.timeline->Destination(MainTab.map,timeline=true)
        LauncherAction.export->Destination(MainTab.archive)
        LauncherAction.start,LauncherAction.stop->null
    }
    /** Visit links and the manual-visit router always land on the map, with the timeline closed. */
    val visitLink=Destination(MainTab.map)
    /** Returning from a journal clear goes back to Settings, where it was started. */
    val afterClear=Destination(MainTab.settings)
}
