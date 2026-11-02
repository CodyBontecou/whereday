package com.bontecou.whereday

import android.graphics.RectF
import android.os.SystemClock
import android.os.Build
import android.os.LocaleList
import android.app.LocaleManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.*
import java.time.*
import java.util.concurrent.atomic.*

class VisitMapFocusTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var graph:AppGraph
    private lateinit var model:JournalViewModel
    private val models=ViewModelStore()
    private var locales:LocaleList?=null
    private val zone=ZoneId.of("America/New_York")
    private val at=Instant.parse("2026-11-01T06:30:00Z")
    private val original=Visit("123e4567-e89b-12d3-a456-426614174010",Coordinate(18.0,-66.0),at,at.plusSeconds(3600),name="QA focus visit",notes="Saved note",source=VisitSource.manual,confirmation=Confirmation.confirmed,timeZone="Asia/Tokyo")
    @Before fun setup()=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        if(Build.VERSION.SDK_INT>=33) {
            locales=target.getSystemService(LocaleManager::class.java).applicationLocales
            if(InstrumentationRegistry.getArguments().getString("visitFocusSpanish")=="true") applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        }
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,networkGeocoding=false,inferredEnabled=false,appearance=if(InstrumentationRegistry.getArguments().getString("visitFocusDark")=="true") "dark" else "light")}
        graph.history.dao.put(VisitRow(original))
        val range=visitMapSelection(original,zone).range(Instant.EPOCH,zone)
        for((id,time,latitude) in listOf(Triple("before",range.from.minusMillis(1),18.2),Triple("first",range.from,18.1),Triple("visit-point",at,18.0),Triple("through",range.until.minusMillis(1),18.1),Triple("after",range.until,18.2)))
            graph.history.dao.put(PointRow(TrackPoint(id,Coordinate(latitude,-66.0),time,5.0),null))
        compose.activityRule.scenario.onActivity {model=JournalViewModel(graph,Clock.fixed(Instant.parse("2026-11-01T18:00:00Z"),ZoneOffset.UTC),{zone});models.put("focus",model)}
        Unit
    }
    @After fun cleanup()=runBlocking {
        try {if(::graph.isInitialized) graph.journalClear.clear().join()}
        finally {compose.activityRule.scenario.onActivity {models.clear()};locales?.let {applyQaAppLocales(compose,it)}}
    }
    private fun show(link:VisitLinkRequest?=null):StateRestorationTester {
        val pending=mutableStateOf(link)
        return StateRestorationTester(compose).also {tester->tester.setContent {WheredayApp(graph,model,{},{},{},{},visitLink=pending.value,onVisitLinkHandled={id->if(pending.value?.id==id) pending.value=null})}}
    }
    private fun awaitName(name:String=original.name!!) {compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("visit-name").assertTextContains(name)}.isSuccess}}
    private fun openTimeline() {
        compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("navigation-timeline").assertExists()}.isSuccess}
        compose.onNodeWithTag("navigation-timeline").performClick()
        compose.waitUntil(10000) {runCatching {compose.onNodeWithText(original.name!!).assertExists()}.isSuccess}
        compose.onNodeWithText(original.name!!).performScrollTo().performClick();awaitName()
    }
    private fun keyboard() {androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot()).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())}
    private fun viewMap() {keyboard();compose.onNodeWithTag("visit-view-map").performScrollTo().performClick()}
    private fun nativeMap():Pair<MapView,MapLibreMap> {
        compose.onNodeWithTag("journal-native-map").performScrollTo()
        val view=compose.runOnIdle {checkNotNull(findNativeMapView())};val map=AtomicReference<MapLibreMap>()
        compose.runOnUiThread {view.getMapAsync {map.set(it)}}
        compose.waitUntil(10000) {compose.runOnUiThread {map.get()?.style?.isFullyLoaded==true}}
        compose.awaitNativeMapFrame(view,map.get());return view to map.get()
    }
    private fun selectedMap():Pair<MapView,MapLibreMap> {
        compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("map-focused-visit").assertTextEquals(target.getString(R.string.map_selected_visit,original.name))}.isSuccess}
        val pair=nativeMap()
        compose.waitUntil(10000) {compose.runOnUiThread {
            val camera=pair.second.cameraPosition
            kotlin.math.abs(camera.target!!.latitude-original.coordinate.latitude)<0.0001 && kotlin.math.abs(camera.target!!.longitude-original.coordinate.longitude)<0.0001
        }}
        compose.waitUntil(10000) {compose.runOnUiThread {
            pair.second.queryRenderedFeatures(RectF(0f,0f,pair.first.width.toFloat(),pair.first.height.toFloat()),"journal-focused-visit").singleOrNull()?.getStringProperty("id")==original.id
        }}
        return pair
    }
    private fun assertUnchanged()=runBlocking {
        assertEquals(original,graph.history.dao.visit(original.id)!!.value);assertEquals(1L,graph.history.dao.totalVisitCount());assertEquals(5L,graph.history.dao.totalPointCount());assertFalse(graph.history.dao.recording()?.requested==true)
    }
    @Test fun savedVisitActionLoadsItsInclusiveDayAndCentersTheNativePin()=runBlocking {
        show();openTimeline();viewMap();selectedMap()
        val state=model.mapWindow.value as WindowState.Ready
        assertEquals(Instant.parse("2026-11-01T04:00:00Z"),state.window.range.from)
        assertEquals(Instant.parse("2026-11-02T05:00:00.001Z"),state.window.range.until)
        assertEquals(setOf("first","visit-point","through"),state.window.points.map {it.id}.toSet())
        assertEquals(3L,state.window.summary.count);assertFalse(model.mapSelection.value.tracksNow);assertUnchanged();capture("saved-map")
    }
    @Test fun dirtyDraftSurvivesCanceledReviewAndRestorationBeforeExplicitDiscard() {
        val tester=show();openTimeline()
        compose.onNodeWithTag("visit-name").performTextReplacement("Unsaved QA edit")
        compose.onNodeWithTag("visit-latitude").performScrollTo().performTextReplacement("19.0");viewMap()
        compose.onNodeWithTag("visit-discard-view-map").assertIsDisplayed();assertUnchanged();capture("discard-review")
        compose.onNodeWithText(target.getString(R.string.visit_keep_editing)).performClick()
        tester.emulateSavedInstanceStateRestore();awaitName("Unsaved QA edit")
        compose.onNodeWithTag("visit-latitude").performScrollTo().assertTextContains("19.0")
        compose.onNodeWithTag("visit-discard-view-map").assertDoesNotExist();assertUnchanged()
        viewMap();compose.onNodeWithTag("visit-discard-view-map").performClick();selectedMap();assertUnchanged();capture("discarded-map")
    }
    @Test fun deletedSavedVisitShowsRecoveryWithoutNavigating()=runBlocking {
        show();openTimeline();val before=model.mapSelection.value
        graph.history.deleteVisit(original);viewMap()
        compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("visit-editor-error").assertTextEquals(target.getString(R.string.visit_error_map))}.isSuccess}
        compose.onNodeWithTag("visit-editor-fields").assertExists();assertEquals(before,model.mapSelection.value);assertEquals(0L,graph.history.dao.totalVisitCount())
    }
    @Test fun failedPreparationIsSanitizedAndCanBeRetriedWithoutSaving() {
        val fail=AtomicBoolean(true);val calls=AtomicInteger()
        compose.setContent {MaterialTheme(colorScheme=if(InstrumentationRegistry.getArguments().getString("visitFocusDark")=="true") darkColorScheme() else lightColorScheme()) {VisitEditor(graph,original,{},{_,_,_,_->error("Read-only map action must not save")},null,onShowOnMap={stored->
            calls.incrementAndGet();if(fail.get()) error("Private map preparation detail") else model.showVisitOnMap(stored)
        })}}
        viewMap();compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("visit-editor-error").assertTextEquals(target.getString(R.string.visit_error_map))}.isSuccess}
        compose.onNodeWithText("Private map preparation detail").assertDoesNotExist();assertNull(model.mapSelection.value.visitFocus);assertUnchanged()
        fail.set(false);viewMap();compose.waitUntil(5000) {model.mapSelection.value.visitFocus?.id==original.id};assertEquals(2,calls.get());assertUnchanged()
    }
    @Test fun explicitFollowReplacesDateLineFocusAndClearsItsSelection() {
        val visit=original.copy(id="date-line",coordinate=Coordinate(1.8,179.999))
        val focus=mutableStateOf<VisitMapFocus?>(VisitMapFocus(visit.id,visit.coordinate));val enabled=mutableStateOf(false)
        val gps=Coordinate(18.2,-66.2)
        compose.setContent {MaterialTheme(colorScheme=if(InstrumentationRegistry.getArguments().getString("visitFocusDark")=="true") darkColorScheme() else lightColorScheme()) {Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            JournalMap(emptyList(),listOf(visit),visitFocus=focus.value,onVisitFocusCleared={focus.value=null},follow=MapFollowUi(enabled.value,true,MapLocationFix(gps,5.0,SystemClock.elapsedRealtime()),null,{enabled.value=!enabled.value},{enabled.value=false},{}))
        }}}}
        val (_,map)=nativeMap()
        compose.waitUntil(10000) {compose.runOnUiThread {kotlin.math.abs(map.cameraPosition.target!!.latitude-1.8)<0.0001 && longitudeDistance(map.cameraPosition.target!!.longitude,179.999)<0.0001}}
        compose.onNodeWithTag("map-follow-location").performScrollTo().performClick()
        compose.waitUntil(5000) {compose.runOnUiThread {kotlin.math.abs(map.cameraPosition.target!!.latitude-gps.latitude)<0.0001 && longitudeDistance(map.cameraPosition.target!!.longitude,gps.longitude)<0.0001}}
        assertNull(focus.value);compose.onNodeWithTag("map-focused-visit").assertDoesNotExist();assertUnchanged()
    }
    @Test fun repeatMapActionRefocusesTheSameVisitWithoutRecreatingItsWindow() {
        show();openTimeline();viewMap();val (view,map)=selectedMap();val first=model.mapSelection.value.visitFocus!!.requestId
        compose.runOnUiThread {map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(18.05,-66.05),15.0))}
        compose.onNodeWithText(target.getString(R.string.map_visits)).performScrollTo().performClick()
        compose.onNodeWithText(original.name!!).performClick();awaitName();viewMap()
        val (again,_)=selectedMap();assertSame(view,again);assertNotEquals(first,model.mapSelection.value.visitFocus!!.requestId);assertUnchanged()
    }
    @Test fun existingVisitLinkCanShowTheSavedVisitWithoutSavingIt() {
        show(VisitLinkRequest.receive("whereday://visit/${original.id}",graph.journalClear.state.value.generation.orEmpty()))
        awaitName();viewMap();selectedMap();assertUnchanged()
    }
    @Test fun linkedOutingVisitUsesTheSharedInspectionAndMapAction()=runBlocking {
        val session=RecordingSession("visit-map-outing",at.minusSeconds(3600),at.plusSeconds(7200),name="QA focus outing")
        graph.history.dao.put(SessionRow(session))
        graph.history.dao.put(PointRow(TrackPoint("visit-point",original.coordinate,at,5.0),session.id))
        show();compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("navigation-timeline").assertExists()}.isSuccess}
        compose.onNodeWithTag("navigation-timeline").performClick()
        compose.waitUntil(10000) {runCatching {compose.onNodeWithText(session.name!!).assertExists()}.isSuccess}
        compose.onNodeWithText(session.name!!).performScrollTo().performClick()
        compose.waitUntil(10000) {runCatching {compose.onNodeWithText(original.name!!).assertExists()}.isSuccess}
        compose.onNodeWithText(original.name!!).performScrollTo().performClick();awaitName();viewMap();selectedMap();assertUnchanged()
        assertEquals(session,graph.history.dao.session(session.id)!!.value)
        assertEquals(session.id,graph.history.dao.point("visit-point")!!.sessionId)
    }
    private fun longitudeDistance(a:Double,b:Double)=kotlin.math.abs(((a-b+540)%360)-180)
    private fun capture(stage:String) {
        if(InstrumentationRegistry.getArguments().getString("captureVisitMap")!="true") return
        val instrumentation=InstrumentationRegistry.getInstrumentation();compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-visit-map-$stage-${(target.resources.configuration.fontScale*10).toInt()}-20261101.png");put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")}
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {output->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))} finally {bitmap.recycle()}}
    }
}
