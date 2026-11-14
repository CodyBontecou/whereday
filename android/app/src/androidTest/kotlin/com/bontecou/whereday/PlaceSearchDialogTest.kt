package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
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

@SdkSuppress(minSdkVersion=33)
class PlaceSearchDialogTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val allowed=MutableStateFlow(true)
    private val calls=AtomicInteger()
    private val record=PlaceLookupRecord("Café QA","Synthetic address",18.25,-66.5)
    private var load:suspend ()->List<PlaceLookupRecord> = {listOf(record)}
    private var selected:PlaceLookupResult?=null
    private var submitted:String?=null
    private var language:String?=null
    private var dismissed=false
    private var oldLocales:LocaleList?=null
    @Before fun setup() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
    }
    @After fun cleanup() {oldLocales?.let {applyQaAppLocales(compose,it)}}
    private fun show(timeout:Long=15000) {
        val provider=object:PlaceLookupProvider {
            override suspend fun search(query:String,language:String):List<PlaceLookupRecord> {
                calls.incrementAndGet();submitted=query;this@PlaceSearchDialogTest.language=language;return load()
            }
            override suspend fun reverse(coordinate:Coordinate,language:String)=error("No reverse expected")
        }
        val service=PlaceLookupService(provider,allowed,{"es"},timeoutMillis=timeout,minimumRequestIntervalMillis=0)
        compose.setContent {MaterialTheme {PlaceSearchDialog(service,allowed,{dismissed=true},{selected=it})}}
        compose.waitForIdle()
    }
    private fun enter(value:String="  Café QA  ")=compose.onNodeWithTag("place-search-query").performScrollTo().performTextReplacement(value)
    private fun search()=compose.onNodeWithTag("place-search-submit").performScrollTo().performClick()
    private fun result():SemanticsNodeInteraction {
        compose.waitUntil(5000) {compose.onAllNodesWithText(record.name!!).fetchSemanticsNodes().isNotEmpty()}
        return compose.onNodeWithText(record.name!!).performScrollTo()
    }
    private fun action():()->Boolean? = result().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun waitText(text:String) {
        compose.waitUntil(5000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
    @Test fun explicitSearchUsesSubmittedTextAndReturnsOriginalMetadata() {
        show();enter();assertEquals(0,calls.get())
        search();result().assertIsDisplayed()
        assertEquals("Café QA",submitted);assertEquals("es",language)
        compose.onNodeWithText("18,250000, -66,500000").assertExists()
        if(InstrumentationRegistry.getArguments().getString("captureSearch")=="true") capture()
        result().performClick();compose.waitUntil(5000) {selected!=null}
        assertEquals(record.name,selected!!.name);assertEquals(record.address,selected!!.address)
        assertEquals(Coordinate(18.25,-66.5),selected!!.coordinate)
        assertEquals(1,calls.get())
    }
    @Test fun queuedSelectionRechecksOptOutBeforeRecomposition() {
        show();enter();search();val use=action()
        compose.runOnIdle {allowed.value=false;use()}
        compose.waitForIdle();assertNull(selected)
        compose.onNodeWithTag("place-search-submit").performScrollTo().assertIsNotEnabled()
        allowed.value=true;compose.waitForIdle();compose.onNodeWithText(record.name!!).assertDoesNotExist()
        assertEquals(1,calls.get())
    }
    @Test fun queuedSelectionCannotApplyAfterQueryEditOrClear() {
        show();enter();search();val use=action()
        enter("Other query");compose.runOnIdle {use()};compose.waitForIdle();assertNull(selected)
        search();val next=action()
        compose.onNodeWithTag("place-search-clear").performScrollTo().performClick()
        compose.runOnIdle {next()};compose.waitForIdle();assertNull(selected)
        compose.onNodeWithTag("place-search-submit").assertIsNotEnabled()
    }
    @Test fun queuedSelectionCannotApplyAfterDismiss() {
        show();enter();search();val use=action()
        compose.onNodeWithTag("place-search-close").performClick()
        compose.runOnIdle {use()};compose.waitForIdle();assertTrue(dismissed);assertNull(selected)
    }
    @Test fun optOutCancelsPendingAndReenableDoesNotResubmit() {
        val canceled=AtomicInteger()
        load={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        show();enter();search();compose.waitUntil(5000) {calls.get()==1}
        allowed.value=false;compose.waitUntil(5000) {canceled.get()==1}
        allowed.value=true;compose.waitForIdle();assertEquals(1,calls.get());assertNull(selected)
    }
    @Test fun backgroundClearsLoadedResultAndRejectsHeldAction() {
        show();enter();search();val use=action()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {use()};compose.waitForIdle();assertNull(selected)
        compose.onNodeWithText(record.name!!).assertDoesNotExist();assertEquals(1,calls.get())
    }
    @Test fun backgroundCancelsPendingLookupWithoutRetryOnReturn() {
        val canceled=AtomicInteger()
        load={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        show();enter();search();compose.waitUntil(5000) {calls.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5000) {canceled.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle();assertEquals(1,calls.get());assertNull(selected)
    }
    @Test fun shortQueriesAndExplicitCancelStayOfflineUntilResubmission() {
        val canceled=AtomicInteger()
        load={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        show();enter("a")
        compose.onNodeWithTag("place-search-submit").assertIsNotEnabled();assertEquals(0,calls.get())
        enter();search();compose.waitUntil(5000) {calls.get()==1}
        compose.onNodeWithTag("place-search-stop").performScrollTo().performClick()
        compose.waitUntil(5000) {canceled.get()==1}
        compose.onNodeWithTag("place-search-submit").assertIsEnabled();assertEquals(1,calls.get())
    }
    @Test fun errorsAreLocalizedAndPrivateProviderDetailsAreHidden() {
        load={throw IllegalStateException("Secret provider diagnostic")}
        show();enter();search()
        waitText("La búsqueda de lugares no está disponible. Comprueba la conexión o inténtalo de nuevo. Puedes elegir coordenadas sin conexión.")
        compose.onNodeWithText("Secret provider diagnostic").assertDoesNotExist()
        load={emptyList()};search()
        waitText("No se encontraron lugares. Prueba con una ciudad, un punto de interés o una dirección completa.")
        assertEquals(2,calls.get())
    }
    @Test fun timeoutAndDisabledStatesRemainLocalizedAndOffline() {
        load={awaitCancellation()};show(timeout=150);enter();search()
        waitText("Se agotó el tiempo de búsqueda. Inténtalo de nuevo o elige coordenadas sin conexión.")
        allowed.value=false;compose.waitForIdle()
        waitText("La geocodificación en línea está desactivada. Actívala en Ajustes para buscar o elige coordenadas sin conexión.")
        compose.onNodeWithTag("place-search-submit").performScrollTo().assertIsNotEnabled()
        assertEquals(1,calls.get())
    }
    private fun capture() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val target=instrumentation.targetContext
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        result().assertIsDisplayed()
        compose.onNodeWithTag("place-search-close").assertIsDisplayed()
        compose.waitForIdle();instrumentation.waitForIdleSync();android.os.SystemClock.sleep(600)
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-place-search-large-20261029.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
}
