package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real offline MapLibre view; synthetic provider; no AppGraph/database/location access. */
@SdkSuppress(minSdkVersion=33)
class PlaceLocationPickerTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val origin=Coordinate(18.25,-66.5)
    private val moved=Coordinate(19.0,-67.0)
    private val allowed=MutableStateFlow(true)
    private val calls=AtomicInteger()
    private val searches=AtomicInteger()
    private val canceled=AtomicInteger()
    private val nativeMap=AtomicReference<MapLibreMap>()
    private val record=PlaceLookupRecord("Pin QA","Synthetic address",0.0,0.0)
    private var load:suspend ()->List<PlaceLookupRecord> = {listOf(record)}
    private var dropped=moved
    private var requested:Coordinate?=null
    private var selected:PlaceLocationSelection?=null
    private var dismissed=false
    private var oldLocales:LocaleList?=null
    private val releases=mutableListOf<CompletableDeferred<Unit>>()
    @Before fun setup() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
    }
    @After fun cleanup() {releases.forEach {it.complete(Unit)};oldLocales?.let {applyQaAppLocales(compose,it)}}
    private fun show(initial:Coordinate?=origin,timeout:Long=15000):StateRestorationTester {
        val provider=object:PlaceLookupProvider {
            override suspend fun search(query:String,language:String):List<PlaceLookupRecord> {
                searches.incrementAndGet();return listOf(PlaceLookupRecord("Search QA","Search address",moved.latitude,moved.longitude))
            }
            override suspend fun reverse(coordinate:Coordinate,language:String):List<PlaceLookupRecord> {
                calls.incrementAndGet();requested=coordinate;assertEquals("es",language);return load()
            }
        }
        val lookup=PlaceLookupService(provider,allowed,{"es"},timeoutMillis=timeout,minimumRequestIntervalMillis=0)
        val tester=StateRestorationTester(compose)
        tester.setContent {MaterialTheme {PlaceLocationPicker(initial,lookup,allowed,{dismissed=true},{selected=it},{nativeMap.set(it)})}}
        compose.waitUntil(10000) {compose.runOnIdle {nativeMap.get()?.style?.isFullyLoaded==true}}
        return tester
    }
    private fun click(tag:String)=compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun find()=click("place-pin-find")
    private fun waitText(value:String) {
        compose.waitUntil(5000) {compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(value).performScrollTo().assertIsDisplayed()
    }
    private fun use() {compose.onNodeWithTag("place-pin-use").performClick();compose.waitUntil(5000) {selected!=null}}
    private fun action(tag:String):()->Boolean? {
        val node=compose.onNodeWithTag(tag)
        if(tag!="place-pin-use") node.performScrollTo()
        return node.fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun moveCameraOnMain() {
        nativeMap.get().moveCamera(CameraUpdateFactory.newLatLng(LatLng(moved.latitude,moved.longitude)))
        val target=checkNotNull(nativeMap.get().cameraPosition.target)
        assertEquals(moved.latitude,target.latitude,1e-10);assertEquals(moved.longitude,target.longitude,1e-10)
        dropped=Coordinate(target.latitude,target.longitude)
    }
    private fun moveCamera()=compose.runOnIdle {moveCameraOnMain()}
    private fun hold():CompletableDeferred<Unit> {
        val release=CompletableDeferred<Unit>()
        releases.add(release)
        load={try {withContext(NonCancellable) {release.await()};listOf(record)} finally {canceled.incrementAndGet()}}
        return release
    }
    @Test fun openingIsOfflineAndUsingInitialCoordinatePreservesEditorMetadata() {
        allowed.value=false;show()
        assertEquals(0,calls.get());assertEquals(0,searches.get())
        compose.onNodeWithTag("place-pin-online").performScrollTo().assertIsOff()
        waitText("La geocodificación en línea está desactivada. Puedes usar coordenadas sin conexión.")
        compose.onNodeWithTag("place-pin-find").assertIsNotEnabled()
        use();assertEquals(PlaceLocationSelection(origin),selected)
    }
    @Test fun explicitReverseKeepsPinCoordinateAndReturnsUnmodifiedProviderMetadata() {
        show();assertEquals(0,calls.get());find()
        waitText("Se encontró una dirección para el marcador seleccionado. Revísala antes de usarla.")
        waitText("Latitud: 18,250000");waitText("Longitud: -66,500000")
        if(InstrumentationRegistry.getArguments().getString("capturePin")=="true") capture()
        assertEquals(origin,requested);assertNull(selected)
        use();assertEquals(PlaceLocationSelection(origin,true,"Pin QA","Synthetic address","coreLocationGeocode"),selected)
    }
    @Test fun heldUseReadsNewPinAndClearedMetadataBeforeRecomposition() {
        show();find();waitText("Pin QA")
        val use=action("place-pin-use");val drop=action("place-pin-drop")
        compose.runOnIdle {moveCameraOnMain();drop();use()}
        compose.waitUntil(5000) {selected!=null}
        assertEquals(PlaceLocationSelection(dropped,true),selected)
    }
    @Test fun changedPinRejectsLateNonCooperativeReverseResponse() {
        val release=hold();show();find();compose.waitUntil(5000) {calls.get()==1}
        moveCamera();click("place-pin-drop");release.complete(Unit)
        compose.waitUntil(5000) {canceled.get()==1};compose.waitForIdle()
        compose.onNodeWithText("Pin QA").assertDoesNotExist()
        use();assertEquals(PlaceLocationSelection(dropped,true),selected)
    }
    @Test fun pendingOptOutRejectsLateResponseWithoutRetryAfterReenable() {
        val release=hold();show();find();compose.waitUntil(5000) {calls.get()==1}
        compose.runOnIdle {allowed.value=false;release.complete(Unit)}
        compose.waitUntil(5000) {canceled.get()==1};compose.waitForIdle()
        allowed.value=true;compose.waitForIdle();assertEquals(1,calls.get())
        compose.onNodeWithText("Pin QA").assertDoesNotExist()
        use();assertEquals(PlaceLocationSelection(origin),selected)
    }
    @Test fun explicitCancelRejectsPendingResponseAndRetryCanFindAddress() {
        val release=hold();show();find();compose.waitUntil(5000) {calls.get()==1}
        click("place-pin-stop");release.complete(Unit)
        compose.waitUntil(5000) {canceled.get()==1};compose.waitForIdle()
        compose.onNodeWithText("Pin QA").assertDoesNotExist()
        load={listOf(record)};find();waitText("Pin QA");assertEquals(2,calls.get())
    }
    @Test fun backgroundCancelsPendingLookupAndReturnDoesNotRetry() {
        load={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        show();find();compose.waitUntil(5000) {calls.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5000) {canceled.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle();assertEquals(1,calls.get())
        compose.onNodeWithText("Pin QA").assertDoesNotExist();use();assertEquals(PlaceLocationSelection(origin),selected)
    }
    @Test fun dismissCancelsPendingAndHeldUseCannotEmit() {
        load={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        show();val use=action("place-pin-use");find();compose.waitUntil(5000) {calls.get()==1}
        compose.onNodeWithTag("place-pin-close").performClick()
        compose.waitUntil(5000) {canceled.get()==1}
        compose.runOnIdle {use()};compose.waitForIdle();assertTrue(dismissed);assertNull(selected)
    }
    @Test fun searchSelectionCancelsReverseAndMovesPinAndMetadataTogether() {
        load={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        show();find();compose.waitUntil(5000) {calls.get()==1}
        click("place-pin-search");compose.waitUntil(5000) {canceled.get()==1}
        compose.onNodeWithTag("place-search-query").performScrollTo().performTextInput("Find QA")
        click("place-search-submit");waitText("Search QA")
        compose.onNodeWithText("Search QA").performClick()
        waitText("Latitud: 19,000000");waitText("Longitud: -67,000000")
        use();assertEquals(PlaceLocationSelection(moved,true,"Search QA","Search address","coreLocationGeocode"),selected)
        assertEquals(1,searches.get());assertEquals(1,calls.get())
    }
    @Test fun emptyAndSanitizedErrorHaveLocalizedMessagesAndKeepRawCoordinate() {
        load={emptyList()};show();find()
        waitText("No se encontró ninguna dirección. Puedes introducirla en el editor.")
        moveCamera();click("place-pin-drop")
        load={throw IllegalStateException("Secret geocoder detail")};find()
        waitText("La búsqueda de direcciones no está disponible. Puedes introducirla en el editor.")
        compose.onNodeWithText("Secret geocoder detail").assertDoesNotExist()
        use();assertEquals(PlaceLocationSelection(dropped,true),selected)
    }
    @Test fun timeoutIsLocalizedAndDoesNotReplaceMetadata() {
        load={awaitCancellation()};show(timeout=150);find()
        waitText("Se agotó el tiempo de búsqueda de la dirección. Inténtalo de nuevo o introduce una dirección en el editor.")
        use();assertEquals(PlaceLocationSelection(origin),selected)
    }
    @Test fun emptyInitialSelectionNeedsExplicitPinAndDismissWritesNothing() {
        show(initial=null)
        compose.onNodeWithTag("place-pin-use").assertIsNotEnabled()
        click("place-pin-drop");compose.onNodeWithTag("place-pin-use").assertIsEnabled()
        compose.onNodeWithTag("place-pin-close").performClick()
        assertTrue(dismissed);assertNull(selected);assertEquals(0,calls.get());assertEquals(0,searches.get())
    }
    @Test fun savedStateRestoreKeepsReviewedMetadataWithoutNewLookup() {
        val tester=show();find();waitText("Pin QA")
        val oldUse=action("place-pin-use")
        val previous=nativeMap.get()
        tester.emulateSavedInstanceStateRestore()
        compose.waitUntil(10000) {compose.runOnIdle {nativeMap.get()!==previous && nativeMap.get()?.style?.isFullyLoaded==true}}
        waitText("Pin QA");waitText("Synthetic address")
        compose.runOnIdle {oldUse()};compose.waitForIdle();assertNull(selected)
        assertEquals(1,calls.get());assertNull(selected)
        use();assertEquals(PlaceLocationSelection(origin,true,"Pin QA","Synthetic address","coreLocationGeocode"),selected)
    }
    @Test fun savedStateRestoreCancelsPendingAndDoesNotResumeIt() {
        load={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        val tester=show();find();compose.waitUntil(5000) {calls.get()==1}
        tester.emulateSavedInstanceStateRestore()
        compose.waitUntil(5000) {canceled.get()==1}
        compose.waitForIdle();assertEquals(1,calls.get())
        compose.onNodeWithTag("place-pin-stop").assertDoesNotExist()
        use();assertEquals(PlaceLocationSelection(origin),selected)
    }
    @Test fun offlineMapRendersThePinAtTheNewAccessibleDropCoordinate() {
        show(initial=null)
        moveCamera();click("place-pin-drop")
        compose.onNodeWithContentDescription("Mapa de selección de ubicación. Usa Colocar marcador en el centro del mapa para seleccionar de forma accesible.").performScrollTo()
        compose.awaitNativeMapFrame(compose.runOnIdle {checkNotNull(findNativeMapView())},nativeMap.get())
        compose.waitUntil(10000) {compose.runOnIdle {
            val map=nativeMap.get()
            val pixel=map.projection.toScreenLocation(LatLng(dropped.latitude,dropped.longitude))
            map.queryRenderedFeatures(pixel,"selected-pin").size==1
        }}
        if(InstrumentationRegistry.getArguments().getString("capturePin")=="true") snapshot("whereday-pin-map-large-20261029.png")
        use();assertEquals(PlaceLocationSelection(dropped,true),selected)
        assertEquals(0,calls.get());assertEquals(0,searches.get())
    }
    private fun capture() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val target=instrumentation.targetContext
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        compose.onNodeWithTag("place-pin-find").performScrollTo()
        compose.onNodeWithTag("place-pin-use").assertIsDisplayed();compose.onNodeWithTag("place-pin-close").assertIsDisplayed()
        snapshot("whereday-pin-large-20261029.png")
    }
    private fun snapshot(filename:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val target=instrumentation.targetContext
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        compose.waitForIdle();instrumentation.waitForIdleSync();android.os.SystemClock.sleep(600)
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,filename)
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
}
