package com.bontecou.whereday

import android.app.Activity
import android.app.Instrumentation
import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.LocaleList
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
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
import java.time.ZoneId
import androidx.lifecycle.ViewModelStore
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Native event handoff is intercepted before an external editor; no calendar writes or grants. */
@SdkSuppress(minSdkVersion=33)
class CalendarEventFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val target get()=instrumentation.targetContext
    private lateinit var graph:AppGraph
    private var locales:LocaleList?=null
    private var monitor:Instrumentation.ActivityMonitor?=null
    private val models=ViewModelStore()
    private val intents=CopyOnWriteArrayList<Intent>()
    private val now=Instant.parse("2026-10-29T12:00:00.123Z")
    private val coordinate=Coordinate(18.1234567,-66.9876543)
    private val visit=Visit("calendar-visit",coordinate,now,now.plusSeconds(120),"Saved Cafe","Saved address","Private journal note",confirmation=Confirmation.corrected,originalName="Original")
    @Before fun setup()=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        locales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target);graph.journalClear.clear().join()
    }
    @After fun cleanup()=runBlocking {
        monitor?.let(instrumentation::removeMonitor)
        compose.activityRule.scenario.onActivity {models.clear()}
        try {if(::graph.isInitialized) graph.journalClear.clear().join()}
        finally {locales?.let {applyQaAppLocales(compose,it)}}
    }
    private fun content(body:@Composable ()->Unit) {
        compose.setContent {MaterialTheme(colorScheme=if(InstrumentationRegistry.getArguments().getString("calendarDark")=="true") darkColorScheme() else lightColorScheme()) {Surface {body()}}}
    }
    private fun intercept() {
        monitor=object:Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent:Intent):Instrumentation.ActivityResult? {
                if(intent.action!=Intent.ACTION_INSERT || intent.data!=CalendarContract.Events.CONTENT_URI) return null
                intents+=Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null)
            }
        }.also(instrumentation::addMonitor)
    }
    private fun click(scroll:Boolean=false) {val node=compose.onNodeWithTag("calendar-add");if(scroll) node.performScrollTo();node.performClick()}
    private fun awaitIntent() {compose.waitUntil(10000) {intents.size==1}}
    private fun draft()=CalendarEventDraft.visit(visit,"Unknown",now)
    private fun heldClick()=compose.onNodeWithTag("calendar-add").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    @Test fun androidIntentHasExactDraftFieldsWithoutNotesAttendeesOrCalendarPermissions() {
        val intent=draft().calendarIntent()
        assertEquals(Intent.ACTION_INSERT,intent.action);assertEquals(CalendarContract.Events.CONTENT_URI,intent.data)
        assertEquals(setOf(CalendarContract.Events.TITLE,CalendarContract.EXTRA_EVENT_BEGIN_TIME,CalendarContract.EXTRA_EVENT_END_TIME,CalendarContract.EXTRA_EVENT_ALL_DAY,CalendarContract.Events.EVENT_LOCATION),intent.extras!!.keySet())
        assertEquals("Saved Cafe",intent.getStringExtra(CalendarContract.Events.TITLE));assertEquals("Saved address",intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        assertEquals(now.toEpochMilli(),intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,-1))
        assertEquals(visit.departedAt!!.toEpochMilli(),intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME,-1))
        assertFalse(intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY,true))
        val permissions=target.packageManager.getPackageInfo(target.packageName,android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse("android.permission.READ_CALENDAR" in permissions);assertFalse("android.permission.WRITE_CALENDAR" in permissions)
    }
    @Test fun oneClickCapturesNowAndDoubleDeliveryCannotOpenTwoEditors() {
        val drafts=mutableListOf<CalendarEventDraft>();val captured=mutableListOf<Instant>()
        content {CalendarEventButton("same",makeDraft={at->captured+=at;draft()},launch={drafts+=it})}
        val held=heldClick();compose.runOnIdle {held();held()}
        compose.waitUntil(5000) {drafts.size==1}
        assertEquals(1,captured.size);assertEquals(listOf(draft()),drafts)
    }
    @Test fun missingCalendarShowsSpanishRecoveryAndCanRetry() {
        var attempts=0
        content {Surface(Modifier.fillMaxSize()) {Box(Modifier.safeDrawingPadding().padding(20.dp)) {
            CalendarEventButton("same",makeDraft={draft()},launch={attempts++;throw ActivityNotFoundException("private diagnostic")})
        }}}
        click();compose.onNodeWithTag("calendar-error").assertTextContains("No hay un editor de calendario disponible.",substring=true)
        compose.onNodeWithText("private diagnostic",substring=true).assertDoesNotExist()
        click();compose.runOnIdle {assertEquals(2,attempts)}
        capture()
    }
    @Test fun preparationFailureDoesNotLaunchOrExposePrivateError() {
        var launched=false
        content {CalendarEventButton("same",makeDraft={throw IllegalStateException("private journal")},launch={launched=true})}
        click();compose.onNodeWithTag("calendar-error").assertTextContains("No se pudo preparar este evento.",substring=true)
        assertFalse(launched);compose.onNodeWithText("private journal",substring=true).assertDoesNotExist()
    }
    @Test fun replacedSourceRejectsHeldActionsAndLatePreparation()=runBlocking {
        val key=mutableStateOf("old");val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val drafts=mutableListOf<CalendarEventDraft>()
        content {CalendarEventButton(key.value,makeDraft={started.complete(Unit);withContext(NonCancellable) {release.await()};draft()},launch={drafts+=it})}
        val old=heldClick();click();withTimeout(5000) {started.await()}
        try {compose.runOnIdle {key.value="new"};compose.waitForIdle();compose.runOnIdle {old()}}
        finally {release.complete(Unit)}
        compose.waitForIdle();compose.runOnIdle {assertTrue(drafts.isEmpty())}
    }
    @Test fun backgroundReturnCannotLaunchAPreviouslyPreparedEvent()=runBlocking {
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val drafts=mutableListOf<CalendarEventDraft>()
        content {CalendarEventButton("same",makeDraft={started.complete(Unit);withContext(NonCancellable) {release.await()};draft()},launch={drafts+=it})}
        click();withTimeout(5000) {started.await()}
        try {compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)}
        finally {release.complete(Unit)}
        compose.waitForIdle();compose.runOnIdle {assertTrue(drafts.isEmpty())}
    }
    @Test fun latePausedPreparationCannotClearTheBusyFenceOfANewerRequest()=runBlocking {
        val releases=listOf(CompletableDeferred<Unit>(),CompletableDeferred<Unit>())
        var requests=0;val drafts=mutableListOf<CalendarEventDraft>()
        content {CalendarEventButton("same",makeDraft={val index=requests++;withContext(NonCancellable) {releases[index].await()};draft()},launch={drafts+=it})}
        click();compose.waitUntil(5000) {requests==1}
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            click();compose.waitUntil(5000) {requests==2}
            releases[0].complete(Unit);compose.waitForIdle()
            compose.onNodeWithTag("calendar-add").assertIsNotEnabled()
            assertTrue(drafts.isEmpty())
        } finally {releases.forEach {it.complete(Unit)}}
        compose.waitUntil(5000) {drafts.size==1};assertEquals(2,requests)
    }
    @Test fun disposedActionCannotOpenAnEditor() {
        val visible=mutableStateOf(true);val drafts=mutableListOf<CalendarEventDraft>()
        content {if(visible.value) CalendarEventButton("same",makeDraft={draft()},launch={drafts+=it})}
        val held=heldClick();compose.runOnIdle {visible.value=false};compose.waitForIdle();compose.runOnIdle {held();assertTrue(drafts.isEmpty())}
    }
    @Test fun savedVisitCalendarActionIgnoresDirtyEditorFieldsAndKeepsHistory()=runBlocking {
        graph.history.dao.put(VisitRow(visit));intercept()
        content {VisitEditor(graph,visit,{}, {_,_,_,_->error("Calendar must not save the visit")},null)}
        compose.onNodeWithTag("visit-name").performTextReplacement("Unsaved name")
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot()).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
        click(true);awaitIntent()
        assertEquals("Saved Cafe",intents.single().getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(visit,graph.history.dao.visit(visit.id)!!.value)
        assertFalse(graph.history.dao.recording()?.requested==true)
    }
    @Test fun unsavedVisitDoesNotOfferCalendarExport() {
        content {VisitEditor(graph,null,{}, {_,_,_,_->error("Unexpected save")},null)}
        compose.onNodeWithTag("calendar-add").assertDoesNotExist()
    }
    @Test fun exactOutingHandsOffStoredBoundsAndFullRouteFirstPointWithoutMutation()=runBlocking {
        val session=RecordingSession("calendar-outing",now,now.plusSeconds(600),notes="Private outing note")
        val first=TrackPoint("first",coordinate,now,5.0,outlier=true)
        graph.history.dao.put(SessionRow(session));graph.history.dao.putPoints(listOf(PointRow(first,session.id),PointRow(TrackPoint("good",Coordinate(19.0,-65.0),now.plusSeconds(60),5.0),session.id)))
        intercept();content {OutingDetailScreen(graph,session.id,true,{},{},sequence=4)}
        compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("calendar-add").assertExists()}.isSuccess};click(true);awaitIntent()
        val intent=intents.single();assertEquals("Salida 4",intent.getStringExtra(CalendarContract.Events.TITLE))
        assertEquals("18.123457, -66.987654",intent.getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        assertEquals(session.endedAt!!.toEpochMilli(),intent.getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME,-1))
        assertEquals(session,graph.history.dao.session(session.id)!!.value);assertEquals(2,graph.history.dao.totalPointCount().toInt())
    }
    @Test fun inferredOutingHandsOffItsRangeWithoutCreatingAStoredSession()=runBlocking {
        val point=TrackPoint("inferred-calendar",coordinate,now,5.0)
        graph.history.dao.putPoints(listOf(PointRow(point,null)))
        val header=InferredOutingHeader("inferred:inferred-calendar",now,now,PointSummary(count=1,firstTime=now,lastTime=now))
        intercept();content {OutingDetailScreen(graph,header.id,true,{},{},header,2)}
        compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("calendar-add").assertExists()}.isSuccess};click(true);awaitIntent()
        assertEquals("Salida 2",intents.single().getStringExtra(CalendarContract.Events.TITLE))
        assertEquals(now.toEpochMilli(),intents.single().getLongExtra(CalendarContract.EXTRA_EVENT_END_TIME,-1))
        assertTrue(graph.history.dao.rangeSessions(now.minusSeconds(1),now.plusSeconds(1)).isEmpty())
        assertEquals(point,graph.history.dao.exportFirstPoints(now.minusSeconds(1),now.plusSeconds(1),null).single().value)
    }
    private fun timeline() {
        lateinit var model:JournalViewModel
        compose.activityRule.scenario.onActivity {model=JournalViewModel(graph);models.put("calendar",model);model.timelineDay.value=now.atZone(ZoneId.systemDefault()).toLocalDate()}
        content {TimelineScreen(graph,model,true,{})}
        compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("calendar-add").assertExists()}.isSuccess}
        compose.onNodeWithTag("timeline-content").performScrollToNode(hasTestTag("calendar-add"))
    }
    @org.junit.Ignore("Material 3 Today rows no longer carry a calendar action; it lives in the visit editor and outing detail, covered above.")
    @Test fun timelineVisitActionHandsOffWithoutOpeningOrSavingTheEditor()=runBlocking {
        graph.history.dao.put(VisitRow(visit));intercept();timeline();click();awaitIntent()
        assertEquals("Saved Cafe",intents.single().getStringExtra(CalendarContract.Events.TITLE))
        compose.onNodeWithTag("visit-save").assertDoesNotExist()
        assertEquals(visit,graph.history.dao.visit(visit.id)!!.value)
    }
    @org.junit.Ignore("Material 3 Today rows no longer carry a calendar action; it lives in the visit editor and outing detail, covered above.")
    @Test fun timelineOutingActionLoadsTheFullRouteStartAndKeepsItsSequence()=runBlocking {
        val session=RecordingSession("calendar-timeline",now,now.plusSeconds(60))
        val first=TrackPoint("calendar-timeline-first",coordinate,now,5.0)
        graph.history.dao.put(SessionRow(session));graph.history.dao.putPoints(listOf(PointRow(first,session.id)))
        intercept();timeline();click();awaitIntent()
        assertEquals("Salida 1",intents.single().getStringExtra(CalendarContract.Events.TITLE))
        assertEquals("18.123457, -66.987654",intents.single().getStringExtra(CalendarContract.Events.EVENT_LOCATION))
        compose.onNodeWithTag("outing-title").assertDoesNotExist()
        assertEquals(session,graph.history.dao.session(session.id)!!.value)
    }
    private fun capture() {
        if(InstrumentationRegistry.getArguments().getString("captureCalendar")!="true") return
        compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val scale=(target.resources.configuration.fontScale*10).toInt()
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-calendar-es-$scale-20261101.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {out->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out))} finally {bitmap.recycle()}}
    }
}
