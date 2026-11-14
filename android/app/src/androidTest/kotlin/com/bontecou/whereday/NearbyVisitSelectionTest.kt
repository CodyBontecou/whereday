package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

@SdkSuppress(minSdkVersion=33)
class NearbyVisitSelectionTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private var oldLocales:LocaleList?=null
    private var oldNetwork:Boolean?=null
    private val origin=Coordinate(18.0,-66.0)
    private val point=Coordinate(18.0001,-66.0)
    private val now=Instant.ofEpochMilli(System.currentTimeMillis())
    private val original=Visit("nearby-original",origin,now.minusSeconds(3600),now.minusSeconds(600),"Detected QA","Old street",detectedCoordinate=origin,detectedName="Detected QA",placeSource="coreLocationGeocode",placeDistanceMeters=90.0)
    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target)
        oldNetwork=graph.preferences.settings.first().networkGeocoding
        graph.preferences.update {it.copy(networkGeocoding=true)}
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        Unit
    }
    @After fun cleanup()=runBlocking {
        oldLocales?.let {applyQaAppLocales(compose,it)}
        if(::graph.isInitialized) {
            graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
            oldNetwork?.let {value->graph.preferences.update {it.copy(networkGeocoding=value)}}
        }
        Unit
    }
    private fun screen(visit:Visit?):StateRestorationTester {
        val allowed=graph.preferences.settings.map {it.networkGeocoding}
        val provider=object:PlaceLookupProvider {
            override suspend fun search(query:String,language:String)=error("No text search")
            override suspend fun reverse(coordinate:Coordinate,language:String)=error("No reverse lookup")
            override suspend fun nearby(coordinate:Coordinate,radiusMeters:Double,language:String,query:NearbyPlaceQuery)=listOf(PlaceLookupRecord("Business QA","New street",point.latitude,point.longitude))
        }
        val service=PlaceLookupService(provider,allowed,{"es"},minimumRequestIntervalMillis=0)
        return StateRestorationTester(compose).also {tester->tester.setContent {MaterialTheme {
            var showing by rememberSaveable {mutableStateOf(true)}
            if(showing) VisitEditor(graph,visit,{showing=false},{value,reuse,remember,overlap->graph.history.saveVisit(value,visit,reuse,remember,overlap)},null,nearbyLookup=service,nearbyAvailable=true)
        }}}
    }
    private fun choose() {
        compose.onNodeWithTag("visit-nearby").performScrollTo().performClick()
        compose.onNodeWithTag("nearby-search").performScrollTo().performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("Business QA").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Usar este negocio").performScrollTo().performClick()
        compose.onNodeWithTag("visit-name").performScrollTo().assertTextContains("Business QA")
    }
    @Test fun selectedBusinessSurvivesDraftRestorationAndSavesItsAuditDistance()=runBlocking {
        graph.history.dao.put(VisitRow(original))
        val tester=screen(original);choose()
        assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("visit-name").assertTextContains("Business QA")
        compose.onNodeWithTag("visit-notes").performScrollTo().performTextInput("Keep this note")
        compose.onNodeWithText("Guardar").performClick()
        compose.waitUntil(5000) {runBlocking {graph.history.dao.visit(original.id)?.value?.placeSource=="geoapify"}}
        val saved=graph.history.dao.visit(original.id)!!.value
        assertEquals(point,saved.coordinate);assertEquals("Business QA",saved.name);assertEquals("New street",saved.address)
        assertEquals(origin.distanceTo(point),saved.placeDistanceMeters!!,.001)
        assertEquals(origin,saved.originalCoordinate);assertEquals(original.name,saved.originalName);assertEquals(original.address,saved.originalAddress)
        assertEquals(origin,saved.detectedCoordinate);assertEquals("Detected QA",saved.detectedName)
        assertEquals(Confirmation.corrected,saved.confirmation);assertEquals("Keep this note",saved.notes)
        assertEquals(1L,graph.history.dao.totalVisitCount())
    }
    @Test fun identicalBusinessSelectionRetainsExplicitIntentAcrossRestoration()=runBlocking {
        val same=original.copy(coordinate=point,name="Business QA",address="New street")
        graph.history.dao.put(VisitRow(same))
        val tester=screen(same);choose();tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithText("¿Descartar los cambios de la visita?").assertIsDisplayed()
        compose.onNodeWithText("Seguir editando").performClick()
        assertEquals(same,graph.history.dao.visit(same.id)!!.value)
        compose.onNodeWithText("Guardar").performClick()
        compose.waitUntil(5000) {runBlocking {graph.history.dao.visit(same.id)?.value?.placeSource=="geoapify"}}
        val saved=graph.history.dao.visit(same.id)!!.value
        assertEquals(point,saved.coordinate);assertEquals(0.0,saved.placeDistanceMeters!!,0.0)
        assertEquals(Confirmation.corrected,saved.confirmation);assertEquals(point,saved.originalCoordinate)
    }
    @Test fun manuallyReplacingSelectedMetadataDropsTheBusinessProvenance()=runBlocking {
        val tester=screen(null)
        compose.onNodeWithTag("visit-latitude").performScrollTo().performTextInput("18")
        compose.onNodeWithTag("visit-longitude").performScrollTo().performTextInput("-66")
        choose()
        compose.onNodeWithTag("visit-name").performTextReplacement("My manual name")
        compose.onNodeWithTag("visit-latitude").performScrollTo().performTextReplacement("18.0002")
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Guardar").performClick()
        compose.waitUntil(5000) {runBlocking {graph.history.dao.totalVisitCount()==1L}}
        val saved=graph.history.dao.rangeVisits(Instant.EPOCH,Instant.now().plusSeconds(1)).single().value
        assertEquals("My manual name",saved.name);assertEquals(Coordinate(18.0002,-66.0),saved.coordinate)
        assertEquals("userEntered",saved.placeSource);assertNull(saved.placeDistanceMeters)
    }
    @Test fun undoRemovesProviderProvenanceOnlyAfterConfirmation()=runBlocking {
        val corrected=original.correct(point,"Business QA","New street",now).copy(placeSource="geoapify",placeDistanceMeters=origin.distanceTo(point))
        graph.history.dao.put(VisitRow(corrected));screen(corrected)
        compose.onNodeWithText("Deshacer corrección").performScrollTo().performClick()
        assertEquals(corrected,graph.history.dao.visit(original.id)!!.value)
        compose.onNodeWithText("Restaurar lugar").performClick()
        compose.waitUntil(5000) {runBlocking {graph.history.dao.visit(original.id)?.value?.confirmation==Confirmation.unconfirmed}}
        val restored=graph.history.dao.visit(original.id)!!.value
        assertEquals(origin,restored.coordinate);assertEquals(original.name,restored.name);assertEquals(original.address,restored.address)
        assertNull(restored.placeSource);assertNull(restored.placeDistanceMeters)
    }
}
