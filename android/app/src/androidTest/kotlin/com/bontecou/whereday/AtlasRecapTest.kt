package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.*
import org.junit.Assert.*

/** The side pane lies beyond a phone's width, so its controls are driven by their semantics action. */
private fun SemanticsNodeInteraction.click()=performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)

class AtlasRecapTest {
    @get:Rule val compose=createComposeRule()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private val zone=ZoneId.systemDefault()
    private val november=YearMonth.of(2026,11)
    private fun at(day:Int,hour:Int)=november.atDay(day).atTime(hour,0).atZone(zone).toInstant()
    private val visits=listOf(Visit("a",Coordinate(40.0,-74.0),at(2,9),at(2,17),name="Studio",countryCode="US"),Visit("b",Coordinate(40.0,-74.0),at(2,18),at(2,19),name="Cafe",countryCode="US"),
        Visit("c",Coordinate(40.0,-74.0),at(5,8),at(5,9),name="Gym",countryCode="US"))

    @Test fun recapCardReadsDaysPlacesAndTotalsAndStepsMonths() {
        val recap=MonthRecap(visits,null,november,zone,{it.name ?: "Visit"},at(20,12))
        var month by mutableStateOf(november);var done=0
        compose.setContent {WheredayTheme(false) {MonthRecapContent(if(month==november) recap else null,month,month<november,true,{month=month.minusMonths(1)},{month=month.plusMonths(1)}) {done++}}}
        compose.onNodeWithTag("recap-headline").assertTextEquals("2 days,\n3 places,\none country.")
        compose.onNodeWithTag("recap-bars").assertContentDescriptionEquals("Places visited each day in November")
        compose.onAllNodesWithTag("recap-stat").assertCountEquals(3)
        compose.onAllNodesWithTag("recap-stat")[0].assertTextContains("3",substring=true).assertTextContains("VISITS",substring=true)
        compose.onAllNodesWithTag("recap-stat")[1].assertTextContains("NEW PLACES",substring=true)
        compose.onAllNodesWithTag("recap-top-place").assertCountEquals(3)[0].assertTextContains("01",substring=true).assertTextContains("Studio",substring=true).assertTextContains("8h",substring=true)
        compose.onNodeWithTag("recap-share").assertHasClickAction()
        compose.onNodeWithTag("recap-next").assertIsNotEnabled()
        compose.onNodeWithTag("recap-previous").performClick()
        compose.onNodeWithTag("recap-month").assertTextContains("October",substring=true)
        compose.onNodeWithTag("recap-next").assertIsEnabled()
        compose.onNodeWithTag("recap-done").performClick()
        compose.runOnIdle {assertEquals(1,done)}
    }

    @Test fun wideAtlasShowsTheSelectedDayBesideTheCalendar() {
        val today=LocalDate.of(2026,11,6)
        val presences=listOf(CountryPresence("PT",today.minusDays(1),true,false),CountryPresence("ES",today.minusDays(1),true,false))
        var selected by mutableStateOf<LocalDate?>(null);var recaps=0;val opened=mutableListOf<String>();var added=0
        compose.setContent {WheredayTheme(false) {Box(Modifier.requiredWidth(900.dp).fillMaxHeight()) {
            AtlasContent(presences,today,false,{},{opened+=it},{added++},onRecap={recaps++},selectedDay=selected,onSelectDay={selected=it},
                dayDetail=AtlasDayDetail(today.minusDays(1),listOf("Alfama","LX Factory"),14_200.0),metric=true)
        }}}
        compose.onNodeWithTag("atlas-selected-kicker").assertTextContains("SELECTED · FRI, NOV 6",substring=true)
        compose.onNodeWithTag("atlas-selected-title").assertTextEquals("No record")
        compose.onNodeWithTag("atlas-selected-action").assertTextContains("Add a stay",substring=true).click()
        compose.runOnIdle {selected=today.minusDays(1)}
        compose.onNodeWithTag("atlas-selected-title").assertTextContains("Spain",substring=true).assertTextContains("Portugal",substring=true).assertTextContains(" and ",substring=true)
        compose.onNodeWithTag("atlas-selected-route").assertTextEquals("Alfama → LX Factory · 14.2 km")
        compose.onNodeWithTag("atlas-selected-action").assertTextContains("Open the day",substring=true).click()
        compose.onNodeWithTag("atlas-recap").assertTextContains("November recap",substring=true).click()
        compose.runOnIdle {assertEquals(1,added);assertEquals(1,opened.size);assertTrue(opened.single() in setOf("ES","PT"));assertEquals(1,recaps)}
    }
}
