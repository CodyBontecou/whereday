package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
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
class NearbyPlacesDialogTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val origin=Coordinate(18.0,-66.0)
    private val allowed=MutableStateFlow(true)
    private val calls=AtomicInteger()
    private var oldLocales:LocaleList?=null
    private val record=PlaceLookupRecord("Café QA","Synthetic street",18.0001,-66.0)
    private var load:suspend ()->List<PlaceLookupRecord> = {listOf(record)}
    private var selected:NearbyPlaceSuggestion?=null
    private var request:Triple<Coordinate,Double,String>?=null
    private val provider=object:PlaceLookupProvider {
        override suspend fun search(query:String,language:String)=error("No address search expected")
        override suspend fun reverse(coordinate:Coordinate,language:String)=error("No reverse lookup expected")
        override suspend fun nearby(coordinate:Coordinate,radiusMeters:Double,language:String,query:NearbyPlaceQuery):List<PlaceLookupRecord> {
            calls.incrementAndGet();request=Triple(coordinate,radiusMeters,language);return load()
        }
    }
    @Before fun setup() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
    }
    @After fun cleanup() {oldLocales?.let {applyQaAppLocales(compose,it)}}
    private fun show(available:Boolean=true,fix:CurrentVisitLocation?=null,now:()->Long={10},metric:Boolean=true) {
        val service=PlaceLookupService(provider,allowed,{"es"},minimumRequestIntervalMillis=0)
        compose.setContent {MaterialTheme {NearbyPlacesDialog(origin,service,allowed,available,{}, {selected=it},fix,now,metric)}}
        compose.waitForIdle()
    }
    private fun search() {compose.onNodeWithTag("nearby-search").performScrollTo().performClick()}
    private fun waitText(value:String) {
        compose.waitUntil(5000) {compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(value).performScrollTo().assertIsDisplayed()
    }
    @Test fun openingIsOfflineAndExplicitSelectionReturnsProviderMetadataAndCoordinate() {
        show();assertEquals(0,calls.get())
        compose.onNodeWithText("Con tecnología de Geoapify").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("© Colaboradores de OpenStreetMap").performScrollTo().assertIsDisplayed()
        search();waitText(record.name!!)
        assertEquals(Triple(origin,1000.0,"es"),request);assertEquals(1,calls.get());assertNull(selected)
        compose.onNodeWithText("Usar este negocio").performScrollTo().performClick()
        compose.waitUntil(5000) {selected!=null}
        assertEquals(record.name,selected!!.name);assertEquals(record.address,selected!!.address)
        assertEquals(Coordinate(18.0001,-66.0),selected!!.coordinate)
    }
    @Test fun localFilterMatchesNameOrAddressAndClearRestoresTheLoadedPageWithoutRequests() {
        val other=PlaceLookupRecord("Biblioteca QA","Calle de la Luna",18.0002,-66.0)
        load={listOf(record,other)}
        show();search();waitText(record.name!!)
        compose.onNodeWithText("A 11 m").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nearby-filter").performScrollTo().performTextInput("  LUNA  ")
        compose.onNodeWithText(record.name!!).assertDoesNotExist();waitText(other.name!!)
        compose.onNodeWithTag("nearby-filter").performScrollTo().performTextReplacement("CAFE\u0301")
        waitText(record.name!!);compose.onNodeWithText(other.name!!).assertDoesNotExist()
        compose.onNodeWithTag("nearby-filter").performScrollTo().performTextReplacement("No match QA")
        compose.onNodeWithTag("nearby-filter-empty").performScrollTo().assertTextEquals("Ningún negocio cercano coincide con «No match QA».")
        compose.onNodeWithTag("nearby-filter-clear").performScrollTo().performClick()
        waitText(record.name!!);waitText(other.name!!)
        assertEquals(1,calls.get());assertNull(selected)
        if(InstrumentationRegistry.getArguments().getString("captureNearbyFilter")=="true") {
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.onNodeWithTag("nearby-filter").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("nearby-close").assertIsDisplayed()
            capture("filter")
        }
    }
    @Test fun aQueuedActionCannotChooseAResultHiddenByTheNewFilter() {
        show(metric=false);search();waitText(record.name!!)
        compose.onNodeWithText("A 36 pies").performScrollTo().assertIsDisplayed()
        val use=compose.onNodeWithText("Usar este negocio").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithTag("nearby-filter").performScrollTo().performTextReplacement("Unmatched")
        compose.onNodeWithTag("nearby-filter-empty").assertExists()
        compose.runOnIdle {use()};compose.waitForIdle()
        assertNull(selected);assertEquals(1,calls.get())
    }
    @Test fun disabledSearchNeverCallsProvider() {
        allowed.value=false;show()
        compose.onNodeWithTag("nearby-search").performScrollTo().assertIsNotEnabled()
        assertEquals(0,calls.get());assertNull(selected)
    }
    @Test fun unconfiguredSearchKeepsDisclosureAttributionAndCloseReachable() {
        show(available=false)
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(compose.activity.resources.configuration.fontScale>=1.8f)
        compose.onNodeWithText("Negocios cercanos").assertIsDisplayed()
        compose.onNodeWithText("La búsqueda de negocios cercanos no está configurada en esta versión.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nearby-search").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("© Colaboradores de OpenStreetMap").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nearby-close").assertIsDisplayed().assertIsEnabled()
        assertEquals(0,calls.get());assertNull(selected)
        if(InstrumentationRegistry.getArguments().getString("captureNearby")=="true") capture("unconfigured")
    }
    private fun capture(kind:String) {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val size=if(target.resources.configuration.fontScale>=1.8f) "large" else "normal"
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-nearby-parity-$kind-$size-20261029.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
    @Test fun optOutCancelsPendingLookupAndClearsResults() {
        val canceled=AtomicInteger()
        load={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        show();search();compose.waitUntil(5000) {calls.get()==1}
        allowed.value=false
        compose.waitUntil(5000) {canceled.get()==1}
        compose.onNodeWithTag("nearby-search").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(record.name!!).assertDoesNotExist();assertNull(selected)
    }
    @Test fun optOutRemovesLoadedSuggestionsAndReenableDoesNotRestoreThem() {
        show();search();waitText(record.name!!)
        allowed.value=false
        compose.onNodeWithTag("nearby-search").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(record.name!!).assertDoesNotExist()
        allowed.value=true
        compose.onNodeWithTag("nearby-search").performScrollTo().assertIsEnabled()
        compose.onNodeWithText(record.name!!).assertDoesNotExist()
        assertEquals(1,calls.get());assertNull(selected)
    }
    @Test fun expiredFixCannotBeSelectedEvenBeforeNextUiTick() {
        val now=AtomicLong(10)
        show(fix=CurrentVisitLocation(origin,5.0,0),now={now.get()});search();waitText(record.name!!)
        val use=compose.onNodeWithText("Usar este negocio").performScrollTo().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        // Hold the actual action while time advances: a queued event may outlive its rendered row.
        compose.runOnIdle {now.set(300_001);use()}
        compose.waitForIdle();assertNull(selected)
    }
    @Test fun providerErrorHasRetryAndEmptyResultsAreExplained() {
        load={throw IllegalStateException("Private provider detail must never appear")}
        show();search();waitText("No se pudieron cargar los lugares cercanos. Inténtalo de nuevo.")
        compose.onNodeWithText("Private provider detail must never appear").assertDoesNotExist()
        load={emptyList()};search();waitText("No se encontraron lugares cercanos. Inténtalo de nuevo o introduce los datos del lugar.")
        assertEquals(8,calls.get()) // One failure, then businesses/all/five category fallbacks.
    }
    @Test fun backgroundCancelsPendingLookupWithoutAutomaticRetryOnReturn() {
        val canceled=AtomicInteger()
        load={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        show();search();compose.waitUntil(5000) {calls.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5000) {canceled.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("nearby-search").performScrollTo().assertIsEnabled()
        assertEquals(1,calls.get());assertNull(selected)
    }
}
