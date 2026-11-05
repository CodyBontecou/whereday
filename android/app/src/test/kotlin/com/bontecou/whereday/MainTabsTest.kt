package com.bontecou.whereday

import org.junit.Assert.*
import org.junit.Test

/** The map-first tab order and where launches, launcher shortcuts and links land. */
class MainTabsTest {
    @Test fun tabsAreMapAtlasPlacesArchiveThenSettings() {
        assertEquals(listOf("map","atlas","places","archive","settings"),MainTabs.order.map {it.id})
        assertEquals(listOf(R.string.shell_map,R.string.shell_atlas,R.string.shell_places,R.string.shell_archive,R.string.shell_settings),MainTabs.order.map {it.title})
    }

    @Test fun theAppOpensOnTheMap() {
        assertEquals(MainTab.map,MainTabs.launch)
        assertEquals(0,MainTabs.index(MainTabs.launch))
        // A restored index outside the bar (an older build's tab) falls back to the map.
        assertEquals(MainTab.map,MainTabs.at(9))
        assertEquals(MainTab.map,MainTabs.at(-1))
    }

    @Test fun launcherShortcutsMapToTheirDestinations() {
        assertEquals(MainTabs.Destination(MainTab.map,timeline=true),MainTabs.destination(LauncherAction.timeline))
        assertEquals(MainTabs.Destination(MainTab.archive),MainTabs.destination(LauncherAction.export))
        // Recording shortcuts confirm first and leave navigation to the confirmation.
        assertNull(MainTabs.destination(LauncherAction.start))
        assertNull(MainTabs.destination(LauncherAction.stop))
        LauncherAction.entries.forEach {action->MainTabs.destination(action)?.let {assertTrue(it.tab in MainTabs.order)}}
    }

    @Test fun linksAndClearsLandWhereTheyDidBefore() {
        assertEquals(MainTabs.Destination(MainTab.map,timeline=false),MainTabs.visitLink)
        assertEquals(MainTab.settings,MainTabs.afterClear.tab)
        assertEquals(4,MainTabs.index(MainTabs.afterClear.tab))
    }
}
