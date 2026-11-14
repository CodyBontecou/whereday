package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@SdkSuppress(minSdkVersion=33)
class CurrentBusinessSuggestionTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val origin=Coordinate(18.0,-66.0)
    private val clock=AtomicLong(10)
    private var now by mutableLongStateOf(10)
    private var fix by mutableStateOf<CurrentVisitLocation?>(CurrentVisitLocation(origin,5.0,0))
    private var places by mutableStateOf<List<SavedPlace>?>(emptyList())
    private var available by mutableStateOf(true)
    private val allowed=MutableStateFlow(true)
    private val calls=AtomicInteger()
    private var oldLocales:LocaleList?=null
    private var selected:NearbyPlaceSuggestion?=null
    private var request:Pair<Coordinate,Double>?=null
    private val record=PlaceLookupRecord("Business QA","Synthetic street",18.0001,-66.0)
    private var load:suspend (Coordinate)->List<PlaceLookupRecord> = {listOf(record)}
    private val provider=object:PlaceLookupProvider {
        override suspend fun search(query:String,language:String)=error("No text lookup")
        override suspend fun reverse(coordinate:Coordinate,language:String)=error("No reverse lookup")
        override suspend fun nearby(coordinate:Coordinate,radiusMeters:Double,language:String,query:NearbyPlaceQuery):List<PlaceLookupRecord> {
            calls.incrementAndGet();request=coordinate to radiusMeters;assertEquals("es",language);return load(coordinate)
        }
    }
    @Before fun setup() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
    }
    @After fun cleanup() {oldLocales?.let {applyQaAppLocales(compose,it)}}
    private fun show() {
        val service=PlaceLookupService(provider,allowed,{"es"},minimumRequestIntervalMillis=0)
        compose.setContent {MaterialTheme {AlertDialog(onDismissRequest={},title={Text("QA")},text={
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CurrentBusinessSuggestion(fix,places,now,service,allowed,available,{places},{selected=it},{clock.get()})
            }
        },confirmButton={})}}
    }
    private fun waitText(text:String) {
        compose.waitUntil(5000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
    private fun saved()=SavedPlace("saved","Saved QA",origin,"Saved street",150.0)
    @Test fun oneFreshFixAutomaticallyUsesThe500MeterSingleSuggestionPath() {
        load={listOf(record,record.copy(name="Second QA",latitude=18.0002))}
        show();waitText("Business QA")
        compose.onNodeWithText("Second QA").assertDoesNotExist()
        assertEquals(origin to 500.0,request);assertEquals(1,calls.get());assertNull(selected)
        compose.onNodeWithTag("current-business-use").performScrollTo().performClick()
        compose.waitUntil(5000) {selected!=null}
        assertEquals(Coordinate(18.0001,-66.0),selected!!.coordinate)
        assertEquals("Synthetic street",selected!!.address)
    }
    @Test fun savedPlacesMustFinishLoadingAndMatchingSavedPlacesPreventNetworkLookup() {
        places=null;show();waitText("Comprobando los lugares guardados en este teléfono…")
        assertEquals(0,calls.get())
        compose.runOnIdle {places=listOf(saved())}
        compose.onNodeWithTag("current-business-section").assertDoesNotExist();assertEquals(0,calls.get())
        compose.runOnIdle {places=emptyList()}
        waitText("Business QA");assertEquals(1,calls.get())
    }
    @Test fun missingOldPoorAndFutureFixesAndAnUnconfiguredProviderNeverRequest() {
        fix=null;show();compose.waitForIdle();assertEquals(0,calls.get())
        for(value in listOf(CurrentVisitLocation(origin,101.0,0),CurrentVisitLocation(origin,5.0,11),CurrentVisitLocation(origin,5.0,-1))) {
            compose.runOnIdle {fix=value};compose.onNodeWithTag("current-business-section").assertDoesNotExist()
        }
        compose.runOnIdle {clock.set(300_001);now=300_001;fix=CurrentVisitLocation(origin,5.0,0)}
        compose.onNodeWithTag("current-business-section").assertDoesNotExist()
        compose.runOnIdle {available=false;clock.set(10);now=10;fix=CurrentVisitLocation(origin,5.0,0)}
        compose.onNodeWithTag("current-business-section").assertDoesNotExist();assertEquals(0,calls.get())
    }
    @Test fun disabledNetworkBlocksLookupAndInFlightOptOutCancelsIt() {
        val canceled=AtomicInteger();allowed.value=false
        load={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        show();waitText("Permite la geocodificación en red en Ajustes para buscar negocios cercanos.")
        assertEquals(0,calls.get());allowed.value=true
        compose.waitUntil(5000) {calls.get()==1};allowed.value=false
        compose.waitUntil(5000) {canceled.get()==1}
        compose.onNodeWithTag("current-business-use").assertDoesNotExist();assertNull(selected)
    }
    @Test fun newlyLoadedSavedPlaceCancelsAPendingBusinessRequest() {
        val canceled=AtomicInteger()
        load={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        show();compose.waitUntil(5000) {calls.get()==1}
        compose.runOnIdle {places=listOf(saved())}
        compose.waitUntil(5000) {canceled.get()==1}
        compose.onNodeWithTag("current-business-section").assertDoesNotExist();assertNull(selected)
    }
    @Test fun heldSelectionCannotIgnoreOptOutOrANewSavedPlace() {
        show();waitText("Business QA")
        val use=compose.onNodeWithTag("current-business-use").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {places=listOf(saved());use()};compose.waitForIdle()
        assertNull(selected)
        compose.runOnIdle {places=emptyList()};waitText("Business QA")
        val after=compose.onNodeWithTag("current-business-use").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        allowed.value=false;compose.runOnIdle {after()};compose.waitForIdle();assertNull(selected)
    }
    @Test fun expiryInvalidatesAHeldSelectionBeforeTheNextDisplayUpdate() {
        show();waitText("Business QA")
        val use=compose.onNodeWithTag("current-business-use").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {clock.set(300_001);use()};compose.waitForIdle();assertNull(selected)
        compose.runOnIdle {now=300_001}
        compose.onNodeWithTag("current-business-section").assertDoesNotExist()
    }
    @Test fun aReplacementFixCancelsThePreviousRequestAndUsesItsOwnOrigin() {
        val canceled=AtomicInteger();val next=Coordinate(18.001,-66.0)
        load={coordinate->if(coordinate==origin) try {awaitCancellation()} finally {canceled.incrementAndGet()}
            else listOf(record.copy(name="New origin QA",latitude=18.0011))}
        show();compose.waitUntil(5000) {calls.get()==1}
        compose.runOnIdle {fix=CurrentVisitLocation(next,5.0,1)}
        waitText("New origin QA");assertEquals(1,canceled.get());assertEquals(next to 500.0,request)
        compose.onNodeWithText("Business QA").assertDoesNotExist()
    }
    @Test fun backgroundCancelsAndRequiresAnExplicitRetryOnReturn() {
        val canceled=AtomicInteger()
        load={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        show();compose.waitUntil(5000) {calls.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5000) {canceled.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("current-business-retry").performScrollTo().assertIsEnabled()
        assertEquals(1,calls.get());load={listOf(record)}
        compose.onNodeWithTag("current-business-retry").performClick();waitText("Business QA")
        assertEquals(2,calls.get())
    }
    @Test fun emptyAndErrorStatesOfferRetryWithoutExposingProviderDetails() {
        load={throw IllegalStateException("Private QA provider detail")}
        show();waitText("No se pudo obtener el nombre del lugar actual. Puedes usar las coordenadas actuales.")
        compose.onNodeWithText("Private QA provider detail").assertDoesNotExist()
        load={emptyList()};compose.onNodeWithTag("current-business-retry").performScrollTo().performClick()
        waitText("No se encontró ningún negocio cercano. Puedes usar las coordenadas actuales o introducir el nombre del lugar.")
        assertEquals(8,calls.get());assertNull(selected)
    }
}
