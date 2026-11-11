package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.time.LocalDate

@SdkSuppress(minSdkVersion=33)
class VisitLinkFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var graph:AppGraph
    private var locales:LocaleList?=null
    private val pending=mutableStateOf<VisitLinkRequest?>(null)
    private val normalEditor=mutableStateOf(false)
    private val rangeEditor=mutableStateOf(false)
    private val windowFocused=mutableStateOf(true)
    private val id="123e4567-e89b-12d3-a456-426614174000"
    private val at=Instant.parse("2026-10-31T12:30:00Z")
    private val original=Visit(id,Coordinate(18.0,-66.0),at,at.plusSeconds(120),name="Stored QA",notes="Original note",source=VisitSource.manual,confirmation=Confirmation.confirmed,confirmedAt=at)
    @Before fun setup()=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        locales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target);graph.journalClear.clear().join()
    }
    @After fun cleanup()=runBlocking {
        try {if(::graph.isInitialized) graph.journalClear.clear().join()}
        finally {locales?.let {applyQaAppLocales(compose,it)}}
    }
    private fun request(url:String)=VisitLinkRequest.receive(url,graph.journalClear.state.value.generation.orEmpty())
    private fun show(url:String?=null):StateRestorationTester {
        pending.value=url?.let(::request)
        val registry=VisitLinkPresentations()
        return StateRestorationTester(compose).also {tester->tester.setContent {
            MaterialTheme(colorScheme=if(InstrumentationRegistry.getArguments().getString("visitLinksDark")=="true") darkColorScheme() else lightColorScheme()) {
                CompositionLocalProvider(LocalVisitLinkPresentations provides registry) {
                    if(normalEditor.value) VisitEditor(graph,null,{normalEditor.value=false},{_,_,_,_->error("The unrelated editor must not save")},null)
                    if(rangeEditor.value) CustomDateRange(LocalDate.of(2026,10,1),LocalDate.of(2026,10,3),{rangeEditor.value=false}) {_,_->rangeEditor.value=false}
                    VisitLinkHost(graph,pending.value,{token->if(pending.value?.id==token) pending.value=null},windowFocused=windowFocused.value)
                }
            }
        }}
    }
    private fun awaitName(name:String) {compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("visit-name").assertTextContains(name)}.isSuccess}}
    private fun keyboard() {androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot()).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(closeQaKeyboard())}
    private fun save() {keyboard();compose.onNodeWithTag("visit-save").performClick();compose.waitUntil(5000) {compose.onAllNodesWithTag("visit-save").fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty()}}
    private fun discard() {
        keyboard();compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithText(target.getString(R.string.visit_discard)).performClick()
    }
    @Test fun checkInLinkPrefillsButOnlyExplicitSaveCreatesTheOpenManualVisit()=runBlocking {
        show("isome://check-in?name=Linked%20Cafe&latitude=18.1&longitude=-66.2&arrival=2026-10-31T12:30:00Z");awaitName("Linked Cafe")
        compose.onNodeWithText("Registrar llegada").assertIsDisplayed();assertEquals(0L,graph.history.dao.totalVisitCount())
        compose.onNodeWithTag("visit-latitude").assertTextContains("18.1");capture();save()
        val saved=graph.history.dao.rangeVisits(at.minusSeconds(1),Instant.now().plusSeconds(1)).single().value
        assertEquals("Linked Cafe",saved.name);assertEquals(at,saved.arrivedAt);assertNull(saved.departedAt)
        assertEquals(Coordinate(18.1,-66.2),saved.coordinate);assertEquals(VisitSource.manual,saved.source)
        assertFalse(graph.history.dao.recording()?.requested==true)
    }
    @Test fun pastVisitLinkKeepsBothSuppliedTimesThroughTheNormalSavePath()=runBlocking {
        show("whereday://log-visit?name=Past&latitude=18&longitude=-66&arrival=2026-10-31T12:30:00Z&departure=2026-10-31T13:30:00Z");awaitName("Past")
        assertEquals(0L,graph.history.dao.totalVisitCount());save()
        val visit=graph.history.dao.rangeVisits(at.minusSeconds(1),at.plusSeconds(7200)).single().value
        assertEquals(at,visit.arrivedAt);assertEquals(at.plusSeconds(3600),visit.departedAt)
    }
    @Test fun linkedIntervalsStillRequireExplicitOverlapAdmission()=runBlocking {
        graph.history.dao.put(VisitRow(original))
        show("isome://check-in?name=Overlap%20link&latitude=18&longitude=-66&arrival=2026-10-31T12:31:00Z");awaitName("Overlap link")
        keyboard();compose.onNodeWithTag("visit-save").performClick()
        compose.onNodeWithTag("visit-overlap-dialog").assertIsDisplayed();assertEquals(1L,graph.history.dao.totalVisitCount())
        compose.onNodeWithTag("visit-overlap-cancel").performClick();assertEquals(1L,graph.history.dao.totalVisitCount())
        compose.onNodeWithTag("visit-save").performClick();compose.onNodeWithTag("visit-overlap-save").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithTag("visit-save").fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty()}
        assertEquals(2L,graph.history.dao.totalVisitCount());assertEquals(original,graph.history.dao.visit(id)!!.value)
    }
    @Test fun missingCoordinatesNeverTriggerGpsOrAnAutomaticSave()=runBlocking {
        show("isome://check-in?name=Choose%20a%20place");awaitName("Choose a place")
        compose.onNodeWithTag("visit-latitude").assertTextContains("")
        keyboard();compose.onNodeWithTag("visit-save").performClick()
        compose.onNodeWithTag("visit-editor-error").assertExists();assertEquals(0L,graph.history.dao.totalVisitCount())
        assertFalse(graph.history.dao.recording()?.requested==true)
    }
    @Test fun invalidAndMissingLinksShowLocalizedFeedbackWithoutChangingHistory()=runBlocking {
        graph.history.dao.put(VisitRow(original));show("isome://check-in?name=Private&latitude=99&longitude=0")
        compose.waitUntil(10000) {runCatching {compose.onNodeWithText(target.getString(R.string.visit_link_invalid)).assertExists()}.isSuccess}
        compose.onNodeWithTag("visit-save").assertDoesNotExist();compose.onNodeWithTag("visit-link-dismiss").performClick()
        compose.runOnIdle {pending.value=request("isome://visit/123e4567-e89b-12d3-a456-426614174001")}
        compose.waitUntil(10000) {runCatching {compose.onNodeWithText(target.getString(R.string.visit_link_missing)).assertExists()}.isSuccess}
        assertEquals(original,graph.history.dao.visit(id)!!.value)
    }
    @Test fun existingUuidLinkLoadsTheSavedSnapshotWithoutCreatingAnotherVisit()=runBlocking {
        val stored=original.copy(id=id.uppercase());graph.history.dao.put(VisitRow(stored))
        show("whereday://visit/$id");awaitName("Stored QA")
        assertEquals(1L,graph.history.dao.totalVisitCount());compose.onNodeWithTag("visit-notes").performScrollTo().performTextReplacement("Reviewed")
        save();val saved=graph.history.dao.visit(stored.id)!!.value;assertNotNull(saved.updatedAt);assertEquals(stored.copy(notes="Reviewed",updatedAt=saved.updatedAt),saved)
    }
    @Test fun anotherLinkWaitsForAnUnfinishedEditorAndOnlyTheLatestPendingLinkOpens()=runBlocking {
        normalEditor.value=true;show();compose.onNodeWithTag("visit-name").performTextReplacement("Unfinished QA")
        compose.runOnIdle {pending.value=request("isome://check-in?name=Superseded")};compose.waitForIdle()
        compose.runOnIdle {pending.value=request("isome://check-in?name=Latest")};compose.waitForIdle()
        compose.onNodeWithTag("visit-name").assertTextContains("Unfinished QA");assertEquals(0L,graph.history.dao.totalVisitCount())
        discard();awaitName("Latest");assertEquals(0L,graph.history.dao.totalVisitCount())
    }
    @Test fun anotherLinkDoesNotReplaceAnAlreadyOpenLinkedDraft()=runBlocking {
        show("isome://check-in?name=First");awaitName("First")
        compose.onNodeWithTag("visit-name").performTextReplacement("My unfinished edit")
        compose.runOnIdle {pending.value=request("whereday://log-visit?name=Second")};compose.waitForIdle()
        compose.onNodeWithTag("visit-name").assertTextContains("My unfinished edit")
        discard();awaitName("Second");assertEquals(0L,graph.history.dao.totalVisitCount())
    }
    @Test fun aDateFilterBlocksAnIncomingLinkUntilTheFilterIsDismissed()=runBlocking {
        rangeEditor.value=true;show("isome://check-in?name=After%20filter")
        compose.onNodeWithTag("visit-name").assertDoesNotExist();assertNotNull(pending.value)
        compose.onNodeWithText("Cancelar").performClick();awaitName("After filter")
        assertEquals(0L,graph.history.dao.totalVisitCount())
    }
    @Test fun restoredLinkedDraftKeepsEditsAndSavesOnlyOneVisit()=runBlocking {
        val tester=show("isome://check-in?name=Restored&latitude=18&longitude=-66");awaitName("Restored")
        compose.onNodeWithTag("visit-name").performTextReplacement("Restored edit")
        tester.emulateSavedInstanceStateRestore();awaitName("Restored edit")
        assertNull(pending.value);assertEquals(0L,graph.history.dao.totalVisitCount());save()
        assertEquals(1L,graph.history.dao.totalVisitCount())
    }
    @Test fun restoredExistingVisitRetainsTheOriginalOptimisticLockBaseline()=runBlocking {
        graph.history.dao.put(VisitRow(original));val tester=show("isome://visit/$id");awaitName("Stored QA")
        val newer=original.copy(notes="Concurrent edit");graph.history.dao.put(VisitRow(newer))
        tester.emulateSavedInstanceStateRestore();awaitName("Stored QA")
        keyboard();compose.onNodeWithTag("visit-save").performClick()
        compose.onNodeWithTag("visit-editor-error").assertTextContains(target.getString(R.string.visit_error_changed))
        assertEquals(newer,graph.history.dao.visit(id)!!.value)
    }
    @Test fun aLinkReceivedWhileBackgroundedWaitsForForeground()=runBlocking {
        show();compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {pending.value=request("isome://check-in?name=After%20resume")}
        assertEquals(0L,graph.history.dao.totalVisitCount())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);awaitName("After resume")
        assertEquals(0L,graph.history.dao.totalVisitCount())
    }
    @Test fun anotherWindowDefersTheLinkUntilMainWindowFocusReturns()=runBlocking {
        windowFocused.value=false;show("isome://check-in?name=After%20window")
        compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        compose.onNodeWithTag("visit-name").assertDoesNotExist();assertNotNull(pending.value)
        compose.runOnIdle {windowFocused.value=true};awaitName("After window")
        assertEquals(0L,graph.history.dao.totalVisitCount())
    }
    @Test fun aPendingRequestFromAnErasedGenerationIsConsumedWithoutOpening()=runBlocking {
        show();compose.runOnIdle {pending.value=request("isome://check-in?name=Erased private draft").copy(generation="old-generation")}
        compose.waitUntil(10000) {pending.value==null}
        compose.onNodeWithTag("visit-save").assertDoesNotExist();assertEquals(0L,graph.history.dao.totalVisitCount())
    }
    private fun capture() {
        if(InstrumentationRegistry.getArguments().getString("captureVisitLinks")!="true") return
        keyboard();compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val scale=(target.resources.configuration.fontScale*10).toInt()
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-visit-link-es-$scale-20261101.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}}
    }

}
