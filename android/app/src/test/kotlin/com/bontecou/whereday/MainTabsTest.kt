package com.bontecou.whereday

import com.bontecou.whereday.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** The map-first tabs (iOS MainTab / MainTabLaunch): four on the bar, Settings joining them on the wide rail. */
class MainTabsTest {
    @Test fun barIsMapAtlasPlacesArchiveAndTheRailAddsSettings() {
        assertEquals(listOf("map","atlas","places","archive"),MainTabs.bar.map {it.id})
        assertEquals(listOf("map","atlas","places","archive","settings"),MainTabs.rail.map {it.id})
        assertEquals(listOf(R.string.shell_map,R.string.shell_atlas,R.string.shell_places,R.string.shell_archive),MainTabs.bar.map {it.title})
        assertEquals(600,MainTabs.railMinWidthDp)
    }

    @Test fun theAppOpensOnTheMap() {
        assertEquals(MainTab.map,MainTabs.launch)
        assertEquals(0,MainTabs.index(MainTabs.launch))
        assertEquals(MainTab.map,MainTabs.at(9));assertEquals(MainTab.map,MainTabs.at(-1))
    }

    @Test fun settingsLivesBehindArchiveOnTheBarAndIsItsOwnTabOnTheRail() {
        val settings=MainTabs.Destination(MainTab.settings)
        assertEquals(MainTabs.Destination(MainTab.archive,settings=true),MainTabs.resolve(settings,usesRail=false))
        assertEquals(MainTabs.Destination(MainTab.settings),MainTabs.resolve(settings,usesRail=true))
        // Settings opened behind Archive becomes the rail's Settings tab when the window widens.
        assertEquals(MainTabs.Destination(MainTab.settings),MainTabs.resolve(MainTabs.Destination(MainTab.archive,settings=true),usesRail=true))
        // Shrinking from the rail with Settings selected falls back to Archive, as on iOS.
        assertEquals(MainTab.archive,MainTabs.onBar(MainTab.settings))
        MainTabs.bar.forEach {assertEquals(it,MainTabs.onBar(it))}
        // Other destinations are the same either way.
        for(tab in MainTabs.bar) for(rail in listOf(true,false)) assertEquals(MainTabs.Destination(tab),MainTabs.resolve(MainTabs.Destination(tab),rail))
    }

    @Test fun launcherShortcutsMapToTheirDestinations() {
        assertEquals(MainTabs.Destination(MainTab.map,timeline=true),MainTabs.destination(LauncherAction.timeline))
        assertEquals(MainTabs.Destination(MainTab.archive),MainTabs.destination(LauncherAction.export))
        assertNull(MainTabs.destination(LauncherAction.start))
        assertNull(MainTabs.destination(LauncherAction.stop))
    }

    @Test fun linksAndClearsLandWhereTheyDidBefore() {
        assertEquals(MainTabs.Destination(MainTab.map),MainTabs.visitLink)
        // After a journal clear the app returns to Settings: behind Archive on the bar, the Settings tab on the rail.
        assertEquals(MainTabs.Destination(MainTab.archive,settings=true),MainTabs.resolve(MainTabs.afterClear,usesRail=false))
        assertEquals(MainTabs.Destination(MainTab.settings),MainTabs.resolve(MainTabs.afterClear,usesRail=true))
    }

    private val visit=Visit("v",Coordinate(37.0,-122.0),Instant.parse("2026-11-05T08:00:00Z"),Instant.parse("2026-11-05T09:00:00Z"),name="Ferry Building",address="1 Ferry Bldg",
        confirmation=Confirmation.unconfirmed,placeSource="automatic")

    @Test fun notHereCorrectsTheVisitToThePickedPlace() {
        val now=Instant.parse("2026-11-05T10:00:00Z")
        val picked=PlaceLocationSelection(Coordinate(37.001,-122.001),replaceMetadata=true,name="Blue Bottle Coffee",address="1 Ferry Plaza",placeSource="geoapify")
        val corrected=correctedVisit(visit,picked,now)
        assertEquals("Blue Bottle Coffee",corrected.name);assertEquals("1 Ferry Plaza",corrected.address)
        assertEquals(picked.coordinate,corrected.coordinate);assertEquals(Confirmation.corrected,corrected.confirmation)
        assertEquals("geoapify",corrected.placeSource);assertEquals(visit.coordinate,corrected.originalCoordinate);assertEquals("Ferry Building",corrected.originalName)
        assertEquals(visit.id,corrected.id);assertEquals(visit.arrivedAt,corrected.arrivedAt)
    }

    @Test fun aMovedPinAloneKeepsTheNameAndAddress() {
        val pin=PlaceLocationSelection(Coordinate(37.0005,-122.0005))
        val corrected=correctedVisit(visit,pin,Instant.parse("2026-11-05T10:00:00Z"))
        assertEquals("Ferry Building",corrected.name);assertEquals("1 Ferry Bldg",corrected.address)
        assertEquals(pin.coordinate,corrected.coordinate);assertEquals("userEntered",corrected.placeSource)
    }
}
