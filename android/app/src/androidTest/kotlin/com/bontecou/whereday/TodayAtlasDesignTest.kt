package com.bontecou.whereday

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.*
import org.junit.Assert.*

class TodayAtlasDesignTest {
    @get:Rule val compose=createComposeRule()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private val zone=ZoneId.systemDefault()
    private val today=LocalDate.of(2026,11,2)
    private val midnight=today.atStartOfDay(zone).toInstant()
    private val now=midnight.plus(Duration.ofHours(15))
    private fun at(h:Double)=midnight.plusSeconds((h*3600).toLong())
    private val cafe=Coordinate(40.70,-74.00);private val office=Coordinate(40.73,-74.00)
    private val home=Visit(coordinate=cafe,arrivedAt=midnight.minus(Duration.ofHours(3)),departedAt=at(8.0),name="Cafe Luna")
    private val work=Visit(coordinate=office,arrivedAt=at(9.0),name="Studio")
    private val points=listOf(TrackPoint(coordinate=cafe,time=at(8.0),accuracy=5.0),TrackPoint(coordinate=Coordinate(40.715,-74.0),time=at(8.5),accuracy=5.0),TrackPoint(coordinate=office,time=at(9.0),accuracy=5.0))
    private fun inputs(listening:TodayListening=TodayListening.full,visits:List<Visit> = listOf(home,work),last:Pair<LocalDate,List<TodayDay.Stay>>?=null)=TodayInputs(now,zone,
        TodayDay.build(visits,points,now,zone,{it.name ?: "Visit"},marksUnrecordedGaps=listening==TodayListening.idle),3_400.0,3,midnight.minus(Duration.ofDays(9)),listening,null,last)
    private fun show(value:TodayInputs,dark:Boolean=false,scale:Float?=null,opened:MutableList<Visit> = mutableListOf(),replays:MutableList<Unit> = mutableListOf(),access:MutableList<TodayListening> = mutableListOf()) {
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides if(scale==null) density else Density(density.density,scale)) {
                WheredayTheme(dark) {TodayContent(value,true,{replays+=Unit},{},{opened+=it},{},{access+=it},{},{})}
            }
        }
    }

    @Test fun dayDialStatsCurrentPlaceAndDaySoFarMatchTheIosLayout() {
        val opened=mutableListOf<Visit>();val replays=mutableListOf<Unit>()
        show(inputs(),opened=opened,replays=replays)
        compose.onNodeWithTag("today-screen").assertExists()
        compose.onNodeWithText("MON · NOV 2 · DAY 10",substring=true,ignoreCase=true).assertExists()
        compose.onNodeWithTag("today-dial").assertContentDescriptionContains("24-hour dial: Cafe Luna from",substring=true).performClick()
        assertEquals(1,replays.size)
        compose.onNodeWithTag("today-moved").assertTextContains("3.4 km",substring=true).assertTextContains("MOVED",substring=true)
        compose.onNodeWithTag("today-away").assertTextContains("1h 00m",substring=true)
        compose.onNodeWithTag("today-points").assertTextContains("3",substring=true)
        compose.onNodeWithTag("today-access").assertDoesNotExist()
        compose.onNode(hasText("Studio") and hasAnyAncestor(hasTestTag("today-current"))).assertExists()
        compose.onNode(hasText("YOU'RE AT · SINCE",substring=true) and hasAnyAncestor(hasTestTag("today-current"))).assertExists()
        compose.onNodeWithText("Walk · 3.3 km · 1h 00m").assertExists()
        compose.onAllNodesWithTag("today-stay").assertCountEquals(2)[0].assertTextContains("Cafe Luna",substring=true).assertTextContains("8h 00m",substring=true).performClick()
        compose.onNodeWithTag("today-rename").performClick()
        assertEquals(listOf(home.id,work.id),opened.map {it.id})
    }

    @Test fun blankDayAccessStatesAndLastRecordedDayFollowListening() {
        var current by mutableStateOf(inputs(visits=emptyList()))
        val access=mutableListOf<TodayListening>()
        compose.setContent {WheredayTheme(false) {TodayContent(current,true,{},{},{},{},{access+=it},{},{})}}
        compose.onNodeWithTag("today-blank-dial").assertContentDescriptionEquals("Empty 24-hour dial")
        compose.onNodeWithText("The page is blank for now.").assertExists();compose.onNodeWithTag("today-import").assertHasClickAction()
        compose.runOnIdle {current=inputs(TodayListening.idle,visits=listOf(Visit(coordinate=cafe,arrivedAt=at(10.0),departedAt=at(11.0),name="Late")))}
        compose.onNodeWithText("THE DAY SO FAR · WHILE OPEN").assertExists()
        compose.onNodeWithTag("today-unrecorded").assertTextContains("Not recorded",substring=true).assertTextContains("4h 00m",substring=true)
        compose.onNodeWithTag("today-access-action").performScrollTo().performClick()
        compose.onNodeWithTag("today-log-stop").performScrollTo().assertHasClickAction()
        val earlier=TodayDay.Stay(home,"Cafe Luna",at(-20.0),at(-18.0),false,0)
        compose.runOnIdle {current=inputs(TodayListening.off,visits=emptyList(),last=today.minusDays(1) to listOf(earlier))}
        compose.onNodeWithText("LOCATION ACCESS IS OFF").assertExists()
        compose.onNodeWithText("LAST RECORDED · YESTERDAY").assertExists()
        compose.onNodeWithTag("today-dial").assertDoesNotExist()
        compose.onNodeWithTag("today-access-action").performScrollTo().performClick()
        assertEquals(listOf(TodayListening.idle,TodayListening.off),access)
    }

    @Test fun largeTextReplacesTheDialWithReplayAndStackedNumbersInBothAppearances() {
        val replays=mutableListOf<Unit>()
        show(inputs(),dark=true,scale=1.8f,replays=replays)
        compose.onNodeWithTag("today-dial").assertDoesNotExist()
        compose.onNodeWithText("MON · NOV 2",substring=true,ignoreCase=true).assertExists()
        compose.onNodeWithText("DAY 10",substring=true,ignoreCase=true).assertDoesNotExist()
        compose.onNodeWithText("The day so far").performScrollTo().assertExists()
        compose.onNodeWithTag("today-replay").performScrollTo().performClick()
        compose.onAllNodesWithText("after a 3.3 km walk",substring=true).assertCountEquals(1)
        assertEquals(1,replays.size)
    }

    @Test fun atlasGridCountriesAndGapsFollowCountryDays() {
        val year=today.year
        val presences=listOf(CountryPresence("US",today.minusDays(2),true,false),CountryPresence("US",today.minusDays(1),true,false),CountryPresence("MX",today.minusDays(1),true,false),CountryPresence("CA",today,false,true))
        val countries=mutableListOf<String>();var openedDays=0
        compose.setContent {WheredayTheme(false) {AtlasContent(presences,today,false,{},{countries+=it},{openedDays++})}}
        compose.onNodeWithTag("atlas-year").assertTextEquals(year.toString())
        compose.onNodeWithTag("atlas-next-year").assertIsNotEnabled();compose.onNodeWithTag("atlas-previous-year").assertIsNotEnabled()
        compose.onNodeWithTag("atlas-month-$year-10").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"United States, 1 day; 30 days with no record"))
        compose.onNodeWithTag("atlas-month-$year-12").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Ahead"))
        compose.onAllNodesWithTag("atlas-country").assertCountEquals(3)[0].assertTextContains("United States",substring=true).assertTextContains("2 days",substring=true).performClick()
        compose.onNodeWithTag("atlas-gaps").assertTextEquals("Travel days count in both countries.")
        compose.onNodeWithTag("atlas-range-all").performClick()
        compose.onNodeWithTag("atlas-year").assertTextEquals("All years")
        compose.onNodeWithTag("atlas-country-days").performScrollTo().performClick()
        assertEquals(listOf("US"),countries);assertEquals(1,openedDays)
    }

    @Test fun emptyAtlasOffersCountryIdentification() {
        var opened=0
        compose.setContent {WheredayTheme(true) {AtlasContent(emptyList(),today,false,{},{},{opened++})}}
        compose.onNode(hasText("No country days yet") and hasAnyAncestor(hasTestTag("atlas-empty"))).assertExists()
        compose.onNodeWithText("Identify countries").performScrollTo().performClick()
        assertEquals(1,opened)
    }
}
