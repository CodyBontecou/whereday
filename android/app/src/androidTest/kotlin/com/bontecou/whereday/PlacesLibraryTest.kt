package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.time.temporal.ChronoUnit

class PlacesLibraryTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private val now=Instant.now().truncatedTo(ChronoUnit.HOURS)
    private fun ago(hours:Long)=now.minus(hours,ChronoUnit.HOURS)
    private fun yesterday(hour:Int)=java.time.LocalDate.now().minusDays(1).atTime(hour,0).atZone(java.time.ZoneId.systemDefault()).toInstant()
    private val cafe=SavedPlace("cafe","Cafe Luna",Coordinate(40.70,-74.00),radius=100.0)
    private val home=SavedPlace("home","Zinnia House",Coordinate(40.80,-74.00),"9 Home Rd",radius=100.0,kind=SavedPlaceKind.home)
    private val studio=SavedPlace("studio","Studio",Coordinate(40.90,-74.00),radius=100.0)

    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(target)
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        listOf(cafe,home,studio).forEach {graph.history.dao.put(PlaceRow(it))}
        // Cafe: three visits; Studio: one, the most recent; Home: two visits yesterday.
        val visits=listOf(Visit("c1",cafe.coordinate,ago(80),ago(79)),Visit("c2",cafe.coordinate,ago(60),ago(59)),Visit("c3",cafe.coordinate,ago(40),ago(39)),
            Visit("s1",studio.coordinate,ago(3),ago(2)),Visit("h1",home.coordinate,yesterday(9),yesterday(10)),Visit("h2",home.coordinate,yesterday(11),yesterday(12)),
            // An unsaved bakery visited three times.
            Visit("b1",Coordinate(41.5,-74.0),ago(70),ago(69),name="Bakery"),Visit("b2",Coordinate(41.5,-74.0),ago(50),ago(49),name="Bakery"),
            Visit("b3",Coordinate(41.5,-74.0),ago(30),ago(29),name="Bakery",address="4 Rye St"))
        visits.forEach {graph.history.dao.put(VisitRow(it))}
    }
    @After fun cleanup()=runBlocking {graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}};Unit}

    private fun show(width:Int?=null) {
        val places=graph.history.dao.observePlaces().map {rows->rows.map {it.value}}
        compose.setContent {WheredayTheme(false) {
            val current by places.collectAsState(initial=emptyList())
            Box(if(width==null) Modifier.fillMaxSize() else Modifier.requiredWidth(width.dp).fillMaxHeight()) {SavedPlacesScreen(graph,current) {}}
        }}
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("saved-place-row").fetchSemanticsNodes().size==3}
    }
    private fun rowNames()=compose.onAllNodesWithTag("saved-place-row").fetchSemanticsNodes().map {node->
        node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text
    }

    @Test fun homeIsPinnedAndFiltersReorderTheLibrary() {
        show()
        compose.onNodeWithTag("places-kicker").assertTextEquals("3 SAVED · 4 VISITED")
        assertEquals(listOf("Zinnia House","Cafe Luna","Studio"),rowNames())
        compose.onNode(hasTestTag("saved-place-row") and hasText("Zinnia House")).assertTextContains("9 Home Rd",substring=true).assertTextContains("1 day",substring=true)
        compose.onNode(hasTestTag("saved-place-row") and hasText("Cafe Luna")).assertTextContains("3 visits",substring=true)
        compose.onNodeWithTag("places-filter-recent").performClick()
        compose.waitUntil(5_000) {rowNames()==listOf("Zinnia House","Studio","Cafe Luna")}
        compose.onNodeWithTag("places-filter-alphabetical").performClick()
        compose.waitUntil(5_000) {rowNames()==listOf("Zinnia House","Cafe Luna","Studio")}
        compose.onNodeWithTag("saved-places-search").performTextInput("stu")
        compose.waitUntil(5_000) {rowNames()==listOf("Studio")}
    }

    @Test fun anUnsavedSpotOpensItsDetailAndSavingItBecomesTheSavedPlace():Unit=runBlocking {
        show()
        compose.onNodeWithTag("places-filter-unsaved").assertTextContains("Unnamed · 1",substring=true)
        compose.onNodeWithTag("places-name-spots").performScrollTo().assertTextContains("Name 1 place").performClick()
        compose.onNodeWithTag("places-suggestion").assertTextContains("Bakery",substring=true).assertTextContains("3 visits",substring=true).assertTextContains("UNSAVED",substring=true).performClick()
        compose.onNodeWithTag("place-unsaved-detail").assertExists()
        compose.onNode(hasText("3") and hasAnyAncestor(hasTestTag("place-detail-stats"))).assertExists()
        compose.onNodeWithTag("place-unsaved-save").performScrollTo().performClick()
        compose.onNodeWithTag("saved-name").assertTextContains("Bakery",substring=true)
        compose.onNodeWithTag("saved-address").assertTextContains("4 Rye St",substring=true)
        compose.onNodeWithTag("saved-editor-save").performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("place-detail-kicker").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("place-detail-name").assertTextEquals("Bakery")
        val saved=graph.history.dao.places().map {it.value}.single {it.name=="Bakery"}
        assertEquals(41.5,saved.coordinate.latitude,1e-9);assertEquals("4 Rye St",saved.address)
        compose.onNodeWithTag("place-detail-back").performClick()
        compose.waitUntil(5_000) {compose.onAllNodesWithTag("places-filter-unsaved").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("places-kicker").assertTextEquals("4 SAVED · 4 VISITED")
    }

    @Test fun wideWindowsShowTheListBesideTheSelectedPlace() {
        show(900)
        // With nothing picked, the first place in the list fills the detail pane.
        compose.onNodeWithTag("place-detail-name").assertTextEquals("Zinnia House")
        compose.onNodeWithTag("place-detail-back").assertDoesNotExist()
        compose.onNode(hasTestTag("saved-place-row") and hasText("Cafe Luna")).performClick()
        compose.onNodeWithTag("place-detail-name").assertTextEquals("Cafe Luna")
        compose.onNode(hasTestTag("saved-place-row") and hasText("Cafe Luna")).assertIsSelected()
        compose.onNodeWithTag("places-filter-unsaved").performClick()
        compose.onNodeWithTag("places-suggestion").performClick()
        compose.onNodeWithTag("place-unsaved-detail").assertExists()
        compose.onNodeWithTag("saved-places-content").assertExists()
    }
}
