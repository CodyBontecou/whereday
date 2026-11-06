package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
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
class VisitOverlapFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var graph:AppGraph
    private var previousLocales:LocaleList?=null
    private val visible=mutableStateOf(true)
    private val showing=mutableStateOf(true)
    private val attempts=mutableListOf<Pair<Visit,Boolean>>()
    private val now=Instant.ofEpochMilli(System.currentTimeMillis())
    private val conflict=Visit("overlap-conflict",Coordinate(18.0,-66.0),now.minusSeconds(3600),name="Existing QA",source=VisitSource.manual,confirmation=Confirmation.confirmed)
    @Before fun setup()=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        previousLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.history.dao.put(VisitRow(conflict))
    }
    @After fun cleanup()=runBlocking {
        try {if(::graph.isInitialized) graph.journalClear.clear().join()}
        finally {previousLocales?.let {applyQaAppLocales(compose,it)}}
    }
    private fun show(original:Visit?=null):StateRestorationTester=StateRestorationTester(compose).also {tester->
        tester.setContent {MaterialTheme(colorScheme=if(InstrumentationRegistry.getArguments().getString("overlapDark")=="true") darkColorScheme() else lightColorScheme()) {
            if(visible.value && showing.value) VisitEditor(graph,original,{showing.value=false},{value,reuse,remember,overlap->
                attempts+=value to overlap;graph.history.saveVisit(value,original,reuse,remember,overlap)
            },null,mode=ManualVisitMode.current)
        }}
    }
    private fun fill() {
        compose.onNodeWithTag("visit-name").performTextReplacement("Candidate QA")
        compose.onNodeWithTag("visit-latitude").performScrollTo().performTextReplacement("18")
        compose.onNodeWithTag("visit-longitude").performScrollTo().performTextReplacement("-66")
    }
    private fun review() {
        compose.onNodeWithTag("visit-save").performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("visit-overlap-save").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("visit-overlap-save").assertIsDisplayed()
    }
    private fun held()=compose.onNodeWithTag("visit-overlap-save").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun awaitClosed() {compose.waitUntil(10_000) {!showing.value}}
    @Test fun canceledOrReplacedConfirmationCannotSaveAndCurrentConfirmationSavesOnce()=runBlocking {
        show();fill();review();val old=held()
        assertEquals(1L,graph.history.dao.totalVisitCount())
        compose.onNodeWithTag("visit-overlap-cancel").performClick()
        compose.runOnIdle {old()};assertEquals(1L,graph.history.dao.totalVisitCount())
        review();compose.runOnIdle {old()};compose.onNodeWithTag("visit-overlap-save").assertExists()
        assertEquals(1L,graph.history.dao.totalVisitCount())
        val current=held();compose.runOnIdle {current();current()};awaitClosed()
        assertEquals(listOf(false,false,true),attempts.map {it.second})
        assertEquals(2L,graph.history.dao.totalVisitCount());assertEquals(conflict,graph.history.dao.visit(conflict.id)!!.value)
        assertFalse(graph.history.dao.recording()?.requested==true)
    }
    @Test fun aSameFrameDraftEditInvalidatesTheOldConfirmationBeforeRecomposition()=runBlocking {
        show();fill()
        val edit=compose.onNodeWithTag("visit-name").fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        review();val old=held()
        compose.runOnIdle {edit(AnnotatedString("Changed QA"));old()}
        compose.waitForIdle();assertEquals(1L,graph.history.dao.totalVisitCount());assertEquals(1,attempts.size)
        compose.onNodeWithTag("visit-overlap-save").assertDoesNotExist()
        review();compose.onNodeWithTag("visit-overlap-save").performClick();awaitClosed()
        assertEquals("Changed QA",graph.history.dao.visit(attempts.last().first.id)!!.value.name)
    }
    @Test fun backgroundAndDisposalInvalidateOutstandingConfirmationCallbacks()=runBlocking {
        show();fill();review();val background=held()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {background()}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);compose.waitForIdle()
        assertEquals(1L,graph.history.dao.totalVisitCount());compose.onNodeWithTag("visit-overlap-save").assertDoesNotExist()
        review();val disposed=held();compose.runOnIdle {visible.value=false};compose.waitForIdle()
        compose.runOnIdle {disposed()};assertEquals(1L,graph.history.dao.totalVisitCount());assertEquals(2,attempts.size)
    }
    @Test fun restorationRetainsTheDraftWithoutRestoringAdmission()=runBlocking {
        val tester=show();fill();review();val old=held()
        tester.emulateSavedInstanceStateRestore();compose.runOnIdle {old()}
        compose.onNodeWithTag("visit-overlap-save").assertDoesNotExist()
        compose.onNodeWithTag("visit-name").assertTextContains("Candidate QA")
        assertEquals(1L,graph.history.dao.totalVisitCount())
        review();compose.onNodeWithTag("visit-overlap-save").performClick();awaitClosed()
        assertEquals(2L,graph.history.dao.totalVisitCount())
    }
    @Test fun explicitAdmissionStillRejectsAStaleVisitSnapshot()=runBlocking {
        val original=conflict.copy(id="original",arrivedAt=now.minusSeconds(7200),departedAt=now.minusSeconds(4000),name="Original QA")
        graph.history.dao.put(VisitRow(original));show(original)
        compose.onNodeWithContentDescription(target.getString(R.string.visit_still_here)).performScrollTo().performClick()
        review()
        val newer=original.copy(notes="Concurrent QA edit");graph.history.dao.put(VisitRow(newer))
        compose.onNodeWithTag("visit-overlap-save").performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("visit-editor-error").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("visit-editor-error").assertTextEquals(target.getString(R.string.visit_error_changed))
        assertEquals(newer,graph.history.dao.visit(original.id)!!.value);assertTrue(showing.value)
    }
    @Test fun spanishReviewKeepsReusablePlaceCreationBehindExplicitAdmission()=runBlocking {
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"));show();fill()
        compose.onNodeWithContentDescription(target.getString(R.string.visit_reuse_accessibility)).performScrollTo().performClick()
        compose.waitUntil(10_000) {runCatching {compose.onNodeWithTag("visit-save").assertIsEnabled()}.isSuccess}
        review();compose.onNodeWithText("Visita superpuesta").assertIsDisplayed()
        compose.onNodeWithText("Estas horas se superponen con tu visita a Existing QA. Revisa las horas o guarda ambas visitas.").assertExists()
        compose.onNodeWithText("Guardar de todos modos").assertIsDisplayed()
        if(InstrumentationRegistry.getArguments().getString("captureVisitOverlap")=="true") captureReview()
        assertTrue(graph.history.dao.places().isEmpty())
        compose.onNodeWithTag("visit-overlap-cancel").performClick();assertTrue(graph.history.dao.places().isEmpty())
        review();compose.onNodeWithTag("visit-overlap-save").performClick();awaitClosed()
        assertEquals("Candidate QA",graph.history.dao.places().single().value.name)
        assertEquals(2L,graph.history.dao.totalVisitCount())
    }
    @Test fun notesOnAnAlreadyOverlappingManualVisitDoNotRequireAnotherAdmission()=runBlocking {
        val original=conflict.copy(id="original",arrivedAt=now.minusSeconds(7200),name="Original QA")
        graph.history.dao.put(VisitRow(original));show(original)
        compose.onNodeWithTag("visit-notes").performScrollTo().performTextReplacement("Only QA notes")
        compose.onNodeWithTag("visit-save").performClick();awaitClosed()
        compose.onNodeWithTag("visit-overlap-save").assertDoesNotExist()
        assertEquals(original.copy(notes="Only QA notes",updatedAt=graph.history.dao.visit(original.id)!!.value.updatedAt),graph.history.dao.visit(original.id)!!.value)
        assertEquals(listOf(false),attempts.map {it.second})
    }
    @Test fun nativeCheckoutClosesAnOverlappingManualVisitAndPreservesStoredMetadata()=runBlocking {
        val original=conflict.copy(id="original",arrivedAt=now.minusSeconds(7200),name="Original QA",notes="Stored QA notes",departureInferred=true)
        graph.history.dao.put(VisitRow(original));show(original)
        compose.onNodeWithTag("visit-notes").performScrollTo().performTextReplacement("Unsaved QA draft")
        compose.onNodeWithTag("visit-checkout").performScrollTo().assertIsDisplayed().performClick();awaitClosed()
        val saved=graph.history.dao.visit(original.id)!!.value
        assertNotNull(saved.departedAt);assertTrue(saved.departedAt!!>=now);assertTrue(saved.departedAt!!<=Instant.now())
        assertEquals(original.copy(departedAt=saved.departedAt,departureInferred=false,updatedAt=saved.updatedAt),saved)
        assertEquals(listOf(true),attempts.map {it.second});assertEquals(conflict,graph.history.dao.visit(conflict.id)!!.value)
        assertFalse(graph.history.dao.recording()?.requested==true)
    }
    @Test fun detectedVisitsDoNotOfferNativeManualCheckout()=runBlocking {
        val original=conflict.copy(id="detected",source=VisitSource.automatic)
        graph.history.dao.put(VisitRow(original));show(original)
        compose.onNodeWithTag("visit-checkout").assertDoesNotExist()
        assertTrue(attempts.isEmpty());assertEquals(original,graph.history.dao.visit(original.id)!!.value)
    }

    private fun captureReview() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val scale=(target.resources.configuration.fontScale*10).toInt()
        val dark=InstrumentationRegistry.getArguments().getString("overlapDark")=="true"
        val name="whereday-visit-overlap-es-$scale-${if(dark) "dark" else "light"}-20261031.png"
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
        println("Whereday overlap capture: $uri ($name)")
    }

}
