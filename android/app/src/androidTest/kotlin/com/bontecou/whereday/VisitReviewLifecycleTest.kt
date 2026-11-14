package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

@SdkSuppress(minSdkVersion=33)
class VisitReviewLifecycleTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private var oldLocales:LocaleList?=null
    private val at=Instant.ofEpochMilli(System.currentTimeMillis()).minusSeconds(7200)
    private val origin=Coordinate(18.0,-66.0)
    private val original=Visit("audit-qa",origin,at,at.plusSeconds(600),"Original QA","Original street",
        source=VisitSource.manual,confirmation=Confirmation.confirmed,confirmedAt=at.plusSeconds(300),updatedAt=at.plusSeconds(300),placeSource="geoapify",placeDistanceMeters=99.0)
    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target)
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        Unit
    }
    @After fun cleanup()=runBlocking {
        oldLocales?.let {applyQaAppLocales(compose,it)}
        if(::graph.isInitialized) graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        Unit
    }
    private fun screen(initial:Visit):StateRestorationTester {
        var current by mutableStateOf(initial)
        return StateRestorationTester(compose).also {tester->tester.setContent {MaterialTheme {
            var showing by rememberSaveable {mutableStateOf(false)}
            OutlinedButton(onClick={showing=true},modifier=Modifier.testTag("audit-open")) {Text("Open QA editor")}
            if(showing) {
                val baseline=current
                VisitEditor(graph,baseline,{showing=false},{value,reuse,remember,overlap->
                    current=graph.history.saveVisit(value,baseline,reuse,remember,overlap)
                },null)
            }
        }}}
    }
    private fun open() {compose.onNodeWithTag("audit-open").performClick()}
    private fun awaitClosed() {compose.waitUntil(5000) {compose.onAllNodesWithTag("visit-name").fetchSemanticsNodes().isEmpty()}}
    private fun saveAndAwait() {
        // Compose idleness does not settle the restored Android dialog/IME transition.
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
        compose.onNodeWithTag("visit-save").assertIsEnabled().performClick();awaitClosed()
    }
    private suspend fun stored()=graph.history.dao.visit(original.id)!!.value
    @Test fun repeatedCorrectionsAndRestorationKeepFirstConfirmationAndManagedMemory()=runBlocking {
        graph.history.dao.put(VisitRow(original));val tester=screen(original);open()
        compose.onNodeWithTag("visit-name").performTextReplacement("Reviewed QA")
        saveAndAwait()
        val first=stored()
        assertEquals(original.confirmedAt,first.confirmedAt);assertTrue(first.updatedAt!!>original.updatedAt!!)
        assertEquals(origin,first.originalCoordinate);assertEquals("Original QA",first.originalName)
        assertEquals("userEntered",first.placeSource);assertNull(first.placeDistanceMeters)
        val remembered=graph.history.dao.places().single().value
        assertEquals("Reviewed QA",remembered.name);assertEquals(150.0,remembered.radius,0.0)
        open();compose.onNodeWithTag("visit-address").performTextReplacement("Second visit street")
        tester.emulateSavedInstanceStateRestore()
        saveAndAwait()
        val second=stored()
        assertEquals(original.confirmedAt,second.confirmedAt);assertEquals(origin,second.originalCoordinate)
        assertEquals("Original QA",second.originalName);assertEquals("Original street",second.originalAddress)
        assertEquals("Second visit street",second.address)
        assertEquals(listOf(remembered),graph.history.dao.places().map {it.value})
    }
    @Test fun repeatedRestoredAddressEditsUseTheCurrentEditor()=runBlocking {
        graph.history.dao.put(VisitRow(original));val tester=screen(original)
        repeat(5) {index->
            open();compose.onNodeWithTag("visit-address").performTextReplacement("Repeated QA street $index")
            tester.emulateSavedInstanceStateRestore()
            saveAndAwait()
            val current=stored()
            assertEquals("Repeated QA street $index",current.address)
            assertEquals(original.arrivedAt,current.arrivedAt);assertEquals(original.departedAt,current.departedAt)
            assertEquals(original.coordinate,current.coordinate);assertEquals(original.confirmedAt,current.confirmedAt)
            assertEquals(original.address,current.originalAddress);assertEquals(original.coordinate,current.originalCoordinate)
            assertEquals(1,graph.history.dao.places().size)
        }
    }
    @Test fun undoRetainsAuditButHidesUndoUntilAnotherCorrection()=runBlocking {
        val corrected=original.correct(Coordinate(18.001,-66.0),"Reviewed QA","Reviewed street",at.plusSeconds(900)).copy(placeSource="geoapify",placeDistanceMeters=11.0)
        graph.history.dao.put(VisitRow(corrected));screen(corrected);open()
        compose.onNodeWithText("Deshacer corrección").performScrollTo().performClick()
        assertEquals(corrected,stored())
        compose.onNodeWithText("Restaurar lugar").performClick();awaitClosed()
        val undone=stored()
        assertEquals(origin,undone.coordinate);assertEquals("Original QA",undone.name)
        assertEquals(origin,undone.originalCoordinate);assertEquals("Original QA",undone.originalName)
        assertEquals(Confirmation.unconfirmed,undone.confirmation);assertNull(undone.confirmedAt)
        assertNull(undone.placeSource);assertNull(undone.placeDistanceMeters)
        open();compose.onNodeWithText("Deshacer corrección").assertDoesNotExist()
        compose.onNodeWithText("Confirmar visita").performScrollTo().assertIsDisplayed()
        if(InstrumentationRegistry.getArguments().getString("captureVisitReview")=="true") capture()
        compose.onNodeWithText("Confirmar visita").performClick();awaitClosed()
        val confirmed=stored()
        assertEquals(Confirmation.confirmed,confirmed.confirmation);assertNotNull(confirmed.confirmedAt)
        assertEquals(origin,confirmed.originalCoordinate)
        open();compose.onNodeWithText("Deshacer corrección").assertDoesNotExist()
        compose.onNodeWithTag("visit-name").performTextReplacement("Later correction QA")
        saveAndAwait()
        val latest=stored()
        assertEquals(confirmed.confirmedAt,latest.confirmedAt);assertEquals(Confirmation.corrected,latest.confirmation)
        assertEquals(origin,latest.originalCoordinate);assertEquals("Original QA",latest.originalName)
        assertEquals(setOf("Original QA","Later correction QA"),graph.history.dao.places().map {it.value.name}.toSet())
    }
    @Test fun notesOnlySaveDoesNotCreateACorrectionOrRememberAPlace()=runBlocking {
        graph.history.dao.put(VisitRow(original));screen(original);open()
        compose.onNodeWithTag("visit-notes").performScrollTo().performTextInput("Only notes QA")
        saveAndAwait()
        val value=stored()
        assertEquals("Only notes QA",value.notes);assertEquals(original.confirmedAt,value.confirmedAt)
        assertEquals(original.confirmation,value.confirmation);assertNull(value.originalCoordinate)
        assertEquals(original.placeSource,value.placeSource);assertEquals(original.placeDistanceMeters,value.placeDistanceMeters)
        assertTrue(graph.history.dao.places().isEmpty())
    }
    private fun capture() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        val size=if(target.resources.configuration.fontScale>=1.8f) "large" else "normal"
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-visit-review-$size-20261029.png")
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
