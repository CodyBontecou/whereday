package com.bontecou.whereday

import android.Manifest
import android.app.LocaleManager
import android.os.LocaleList
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
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
import java.util.concurrent.atomic.AtomicInteger

@SdkSuppress(minSdkVersion=33)
class CurrentLocationFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private var oldLocales:LocaleList?=null
    private var oldNetwork:Boolean?=null
    private val origin=Coordinate(18.0,-66.0)
    private val business=Coordinate(18.0001,-66.0)
    private val gpsCalls=AtomicInteger()
    private val placeCalls=AtomicInteger()
    private var request:Pair<Coordinate,Double>?=null
    private var locate:suspend ()->CurrentVisitLocation? = {fresh()}
    private val source=CurrentVisitLocationSource {gpsCalls.incrementAndGet();locate()}
    private fun fresh(point:Coordinate=origin)=CurrentVisitLocation(point,5.0,SystemClock.elapsedRealtime())
    private val provider=object:PlaceLookupProvider {
        override suspend fun search(query:String,language:String)=error("No text lookup")
        override suspend fun reverse(coordinate:Coordinate,language:String)=error("No reverse lookup")
        override suspend fun nearby(coordinate:Coordinate,radiusMeters:Double,language:String,query:NearbyPlaceQuery):List<PlaceLookupRecord> {
            placeCalls.incrementAndGet();request=coordinate to radiusMeters
            return listOf(PlaceLookupRecord("Current business QA","Business street",business.latitude,business.longitude))
        }
    }
    @Before fun setup()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val target=instrumentation.targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        // Disposable QA permissions only. Every location request below uses the injected fake source.
        // Run this class on its own installation: grants outlive individual tests, and revoking
        // them here would terminate the instrumentation target rather than restore isolation.
        instrumentation.uiAutomation.grantRuntimePermission(target.packageName,Manifest.permission.ACCESS_COARSE_LOCATION)
        instrumentation.uiAutomation.grantRuntimePermission(target.packageName,Manifest.permission.ACCESS_FINE_LOCATION)
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target);oldNetwork=graph.preferences.settings.first().networkGeocoding
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
    private fun editor() {
        val service=PlaceLookupService(provider,graph.preferences.settings.map {it.networkGeocoding},{"es"},minimumRequestIntervalMillis=0)
        compose.setContent {MaterialTheme {
            var showing by remember {mutableStateOf(true)}
            if(showing) VisitEditor(graph,null,{showing=false},{value,reuse,remember,overlap->graph.history.saveVisit(value,null,reuse,remember,overlap)},null,
                mode=ManualVisitMode.current,nearbyLookup=service,nearbyAvailable=true,currentLocationSource=source)
        }}
    }
    private fun openLocation() {compose.onNodeWithText("Buscar ubicación actual").performScrollTo().performClick()}
    private fun requestLocation() {compose.onNodeWithTag("current-location-request").performScrollTo().performClick()}
    private fun waitText(text:String) {
        compose.waitUntil(5000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
    @Test fun requestedFixSuggestsABusinessAndOnlyExplicitSelectionAndSaveWriteAVisit()=runBlocking {
        editor()
        compose.onNodeWithTag("visit-name").performTextInput("Typed name QA")
        compose.onNodeWithTag("visit-address").performTextInput("Typed address QA")
        openLocation()
        val disclosure=InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.current_location_business_disclosure)
        compose.onNodeWithText(disclosure).performScrollTo().assertIsDisplayed()
        assertEquals(0,gpsCalls.get());assertEquals(0,placeCalls.get())
        compose.onNodeWithTag("current-location-use").assertIsNotEnabled()
        requestLocation();waitText("Current business QA")
        assertEquals(origin to 500.0,request);assertEquals(1,placeCalls.get())
        assertEquals(0L,graph.history.dao.totalVisitCount())
        if(InstrumentationRegistry.getArguments().getString("captureCurrentPlace")=="true") {
            if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(compose.activity.resources.configuration.fontScale>=1.8f)
            compose.onNodeWithTag("current-business-use").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("current-location-use").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithTag("current-location-close").assertIsDisplayed().assertIsEnabled()
            capture()
        }
        compose.onNodeWithTag("current-business-use").performScrollTo().performClick()
        compose.onNodeWithTag("visit-name").performScrollTo().assertTextContains("Current business QA")
        compose.onNodeWithTag("visit-address").performScrollTo().assertTextContains("Business street")
        assertEquals(0L,graph.history.dao.totalVisitCount())
        compose.onNodeWithText("Guardar").performClick()
        compose.waitUntil(5000) {runBlocking {graph.history.dao.totalVisitCount()==1L}}
        val saved=graph.history.dao.rangeVisits(Instant.EPOCH,Instant.now().plusSeconds(1)).single().value
        assertEquals(business,saved.coordinate);assertEquals("geoapify",saved.placeSource)
        assertEquals(origin.distanceTo(business),saved.placeDistanceMeters!!,.001)
        assertNull(saved.departedAt);assertEquals(Confirmation.confirmed,saved.confirmation)
        assertFalse(graph.history.dao.recording()?.requested==true)
    }
    @Test fun savedPlacePriorityUsesTheLocalPlaceAndMakesNoBusinessRequest()=runBlocking {
        val saved=SavedPlace("current-saved","Saved current QA",business,"Saved address",150.0)
        graph.history.dao.put(PlaceRow(saved));editor();openLocation();requestLocation()
        waitText("Saved current QA");assertEquals(0,placeCalls.get())
        compose.onNodeWithTag("use-current-saved-place").performScrollTo().performClick()
        compose.onNodeWithTag("visit-name").performScrollTo().assertTextContains(saved.name)
        compose.onNodeWithText("Guardar").performClick()
        compose.waitUntil(5000) {runBlocking {graph.history.dao.totalVisitCount()==1L}}
        val visit=graph.history.dao.rangeVisits(Instant.EPOCH,Instant.now().plusSeconds(1)).single().value
        assertEquals(saved.coordinate,visit.coordinate);assertEquals(saved.address,visit.address)
        assertEquals("userEntered",visit.placeSource);assertNull(visit.placeDistanceMeters)
        assertEquals(saved,graph.history.dao.places().single().value);assertEquals(0,placeCalls.get())
    }
    @Test fun unavailableLookupKeepsGpsOfflineAndSourceErrorsAreLocalizedWithoutPrivateDetails() {
        var selected:CurrentVisitLocation?=null
        locate={throw IllegalStateException("Private GPS QA detail")}
        compose.setContent {MaterialTheme {CurrentVisitLocationDialog(source,{},select={selected=it})}}
        assertEquals(0,gpsCalls.get())
        compose.onNodeWithText("Solicita una sola ubicación para esta visita. La grabación sigue igual. No se consulta ninguna dirección.").performScrollTo().assertIsDisplayed()
        requestLocation();waitText("La ubicación actual no está disponible. Revisa los ajustes de ubicación del dispositivo o elige un lugar guardado.")
        compose.onNodeWithText("Private GPS QA detail").assertDoesNotExist()
        compose.onNodeWithTag("current-location-use").assertIsNotEnabled()
        locate={CurrentVisitLocation(origin,101.0,SystemClock.elapsedRealtime())};requestLocation()
        waitText("No se encontró una ubicación reciente y precisa. Revisa los ajustes de ubicación del dispositivo, ve a una zona con señal GPS o elige un lugar guardado.")
        compose.onNodeWithTag("current-location-use").assertIsNotEnabled();assertEquals(0,placeCalls.get())
        locate={fresh()};requestLocation();waitText("Ubicación actual lista")
        compose.onNodeWithTag("current-location-coordinate").performScrollTo().assertTextEquals("18,00000; -66,00000 · ±5 m")
        compose.onNodeWithTag("current-location-use").performClick()
        compose.runOnIdle {assertEquals(origin,selected!!.coordinate)}
        assertEquals(0,placeCalls.get())
    }
    @Test fun lateCanceledGpsResultCannotReplaceANewerPendingRequest() {
        val first=CompletableDeferred<Unit>();val second=CompletableDeferred<Unit>();val firstReturned=AtomicInteger()
        locate={if(gpsCalls.get()==1) {
            try {withContext(NonCancellable) {first.await()}} catch(_:CancellationException) {}
            firstReturned.incrementAndGet();fresh()
        } else {second.await();fresh(business)}}
        compose.setContent {MaterialTheme {CurrentVisitLocationDialog(source,{},select={})}}
        try {
            requestLocation();compose.waitUntil(5000) {gpsCalls.get()==1}
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("current-location-use").assertIsNotEnabled()
            requestLocation();compose.waitUntil(5000) {gpsCalls.get()==2}
            first.complete(Unit);compose.waitUntil(5000) {firstReturned.get()==1}
            compose.onNodeWithTag("current-location-use").assertIsNotEnabled()
            compose.onNodeWithText("Buscando la ubicación actual…").assertExists()
            second.complete(Unit);waitText("Ubicación actual lista")
            compose.onNodeWithTag("current-location-coordinate").performScrollTo().assertTextEquals("18,00010; -66,00000 · ±5 m")
        } finally {first.complete(Unit);second.complete(Unit)}
    }
    private fun capture() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val size=if(target.resources.configuration.fontScale>=1.8f) "large" else "normal"
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-current-place-$size-20261029.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
}
