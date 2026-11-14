package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.*
import java.util.concurrent.atomic.AtomicInteger

class OutingRefreshContinuityTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var graph:AppGraph
    private lateinit var source:DelayedSource
    private val models=ViewModelStore()
    private val start=Instant.parse("2026-11-01T12:00:00Z")
    private val first=TrackPoint("continuity-first",Coordinate(18.0,-66.0),start,5.0)
    private val second=first.copy(id="continuity-second",time=start.plusSeconds(60),coordinate=Coordinate(18.01,-66.0))
    private val third=first.copy(id="continuity-third",time=start.plusSeconds(120),coordinate=Coordinate(18.02,-66.0))
    private var header by mutableStateOf(InferredOutingHeader("inferred:${first.id}",start,second.time,PointSummary(2,0,first.coordinate.distanceTo(second.coordinate),start,second.time)))
    private val folder get()=File(target.cacheDir,"exports/outing-continuity-qa")
    private class DelayedSource(graph:AppGraph):OutingDetailSource {
        private val delegate=DatabaseOutingDetailSource(graph)
        override val invalidations=delegate.invalidations
        val started=AtomicInteger()
        @Volatile var gate:CompletableDeferred<Unit>?=null
        @Volatile var fail=false
        override suspend fun read(id:String,header:InferredOutingHeader?):OutingDetail {
            started.incrementAndGet();gate?.await()
            if(fail) {fail=false;throw java.io.IOException("Synthetic refresh failure")}
            return delegate.read(id,header)
        }
    }
    @Before fun setup():Unit=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,networkGeocoding=false,showPhotoMarkers=false)}
        graph.history.dao.putPoints(listOf(first,second,third).map {PointRow(it,null)})
        check(folder.mkdirs() || folder.isDirectory)
        val formatter=java.time.format.DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withZone(ZoneOffset.UTC)
        for((index,seconds) in listOf(10L,20L,30L,100L).withIndex()) {
            val file=File(folder,"photo-$index.jpg");val time=start.plusSeconds(seconds)
            val bitmap=android.graphics.Bitmap.createBitmap(48,32,android.graphics.Bitmap.Config.ARGB_8888)
            try {bitmap.eraseColor(android.graphics.Color.rgb(40+index*40,90,65));file.outputStream().use {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,90,it))}} finally {bitmap.recycle()}
            android.media.ExifInterface(file.absolutePath).apply {
                setAttribute("DateTimeOriginal",formatter.format(time));setAttribute("OffsetTimeOriginal","+00:00")
                setAttribute("GPSLatitude","18/1,0/1,0/1");setAttribute("GPSLatitudeRef","N")
                setAttribute("GPSLongitude","66/1,0/1,0/1");setAttribute("GPSLongitudeRef","W");saveAttributes()
            }
            val uri=androidx.core.content.FileProvider.getUriForFile(target,"${target.packageName}.exports",file).toString()
            graph.history.dao.put(PhotoAssetRow(uri,time,first.coordinate,true,true,time))
            graph.history.dao.put(PhotoRow(uri,time,first.coordinate,"photo_gps",time))
        }
        graph.photoLibrary.refresh();check(graph.photoLibrary.state.value.validated)
        source=DelayedSource(graph)
    }
    @After fun cleanup():Unit=runBlocking {
        source.gate?.complete(Unit)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {models.clear()}
        graph.journalClear.clear().join();folder.deleteRecursively()
    }
    private fun ready()=compose.waitUntil(15_000) {compose.onAllNodesWithTag("outing-duration").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() && compose.onAllNodesWithTag("outing-refreshing").fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty()}
    private fun show(back:()->Unit={},map:(OutingMapTarget)->Unit={}) {compose.setContent {MaterialTheme {OutingDetailContent(graph,header.id,true,back,map,header,source=source)}};ready()}
    private fun photo(number:Int,total:Int=3)=compose.waitUntil(15_000) {runCatching {compose.onNodeWithText("Photo $number of $total").assertExists()}.isSuccess}
    private fun gallery(total:Int=3) {
        compose.waitUntil(15_000) {compose.onAllNodesWithTag("matched-photo-card").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("matched-photo-strip").performScrollTo()
        compose.onAllNodesWithTag("matched-photo-card")[0].performClick();photo(1,total)
        compose.onNodeWithTag("photo-pager-next").performScrollTo().performClick();photo(2,total)
    }
    private fun pause(update:()->Unit):CompletableDeferred<Unit> {
        val before=source.started.get();val gate=CompletableDeferred<Unit>();source.gate=gate
        compose.runOnIdle(update);compose.waitUntil(15_000) {source.started.get()>before}
        compose.waitForIdle();return gate
    }
    private fun release(gate:CompletableDeferred<Unit>) {source.gate=null;gate.complete(Unit);ready()}
    private fun scroll()=compose.onNodeWithTag("outing-detail-content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    @Test fun sameRangeSlowHeaderRefreshRetainsTheCurrentPhotoAndNativeMap() {
        show();val map=compose.runOnIdle {checkNotNull(findNativeMapView())}
        gallery();val gate=pause {header=header.copy(active=true)}
        photo(2);release(gate);photo(2)
        compose.onNodeWithTag("photo-pager-close").performScrollTo().performClick()
        compose.runOnIdle {assertSame(map,findNativeMapView())}
        compose.onNodeWithTag("outing-latest").assertExists()
    }
    @Test fun slowHeaderRefreshRetainsScrollAndUsesTheDisplayedSnapshotForActions() {
        val targets=mutableListOf<OutingMapTarget>()
        show(map={targets+=it});compose.onNodeWithTag("outing-show-map").performScrollTo();val before=scroll();assertTrue(before>0)
        val gate=pause {header=header.copy(active=true)}
        assertEquals(before,scroll(),.01f);compose.onNodeWithTag("outing-show-map").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(second.time,targets.single().session.endedAt)}
        release(gate);assertEquals(before,scroll(),.01f)
        compose.onNodeWithTag("outing-show-map").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(2,targets.size);assertNull(targets.last().session.endedAt);assertEquals(targets.first().range,targets.last().range)}
    }
    @Test fun pausedReplayKeepsItsOpeningAndPositionAcrossRuntimeOnlyRefresh() {
        show();compose.onNodeWithText(target.getString(R.string.map_replay)).performScrollTo().performClick()
        compose.waitUntil(15_000) {compose.onAllNodesWithTag("replay-counter").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("replay-end").performScrollTo().performClick()
        val counter=compose.onNodeWithTag("replay-counter").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        val gate=pause {header=header.copy(active=true)}
        compose.onNodeWithTag("replay-counter").assertTextEquals(counter)
        release(gate);compose.onNodeWithTag("replay-counter").assertTextEquals(counter)
        compose.onNodeWithTag("replay-close").performScrollTo().performClick()
    }
    @Test fun galleryKeepsItsOpeningRangeAcrossExpansionAndContractionUntilReopened() {
        show();gallery();val gate=pause {header=header.copy(endedAt=third.time)}
        photo(2);release(gate);photo(2)
        compose.onNodeWithTag("photo-pager-close").performScrollTo().performClick()
        gallery(4)
        val shrinking=pause {header=header.copy(endedAt=start.plusSeconds(10))}
        photo(2,4);release(shrinking);photo(2,4)
        compose.onNodeWithTag("photo-pager-close").performScrollTo().performClick()
        compose.waitUntil(15_000) {compose.onAllNodesWithTag("matched-photo-card").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("matched-photo-strip").performScrollTo()
        compose.onAllNodesWithTag("matched-photo-card")[0].performClick();photo(1,1)
        compose.onNodeWithTag("photo-pager-next").assertIsNotEnabled()
    }
    @Test fun aFailedRefreshRemovesStaleContentAndLaterInvalidationCanRecover():Unit=runBlocking {
        show();source.fail=true;val gate=pause {header=header.copy(active=true)}
        source.gate=null;gate.complete(Unit)
        compose.waitUntil(15_000) {compose.onAllNodesWithTag("outing-duration").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText(target.getString(R.string.outing_load_error)).assertExists()
        graph.history.dao.put(VisitRow(Visit("refresh-retry",first.coordinate,start.plusSeconds(1))))
        ready();compose.onNodeWithTag("outing-latest").assertExists()
    }
    @Test fun timelineLoadingAndRestorationRetainTheGalleryButConfirmedRemovalDoesNot():Unit=runBlocking {
        val day=start.atZone(ZoneOffset.UTC).toLocalDate()
        val window=graph.history.readWindow(HistoryRange.day(day,ZoneOffset.UTC),inference=InferenceRules(minimumDuration=Duration.ZERO,minimumPoints=2),includeTimelineActivity=true)
        val windows=MutableStateFlow<WindowState>(WindowState.Ready(window))
        val model=JournalViewModel(graph,Clock.fixed(start.plusSeconds(3600),ZoneOffset.UTC),{ZoneOffset.UTC})
        InstrumentationRegistry.getInstrumentation().runOnMainSync {models.put("continuity",model)}
        model.selectTimelineDay(day)
        val restoration=StateRestorationTester(compose)
        restoration.setContent {MaterialTheme {TimelineContent(graph,model,true,{},windows=windows)}}
        compose.onNodeWithText(target.getString(R.string.timeline_inferred)).performScrollTo().performClick();ready();gallery(4)
        compose.runOnIdle {windows.value=WindowState.Loading};photo(2,4)
        restoration.emulateSavedInstanceStateRestore();ready();photo(2,4)
        compose.runOnIdle {windows.value=WindowState.Ready(window)};ready();photo(2,4)
        compose.runOnIdle {windows.value=WindowState.Ready(window.copy(inferred=emptyList()))}
        compose.onNodeWithText(target.getString(R.string.timeline_inferred_missing)).assertExists()
        compose.onNodeWithTag("photo-pager-content").assertDoesNotExist()
        compose.runOnIdle {windows.value=WindowState.Loading}
        var reopened=false
        try {
            compose.waitUntil(2_000) {compose.onAllNodesWithTag("outing-duration").fetchSemanticsNodes().isNotEmpty()}
            reopened=true
        } catch(_:ComposeTimeoutException) { /* A confirmed missing header stays cleared. */ }
        assertFalse("A loading state must not reopen a confirmed-missing outing",reopened)
    }
    @Test fun anOpenDeleteReviewKeepsItsOriginalRangeAfterTheHeaderExpands():Unit=runBlocking {
        val left=java.util.concurrent.atomic.AtomicBoolean(false)
        show(back={left.set(true)})
        val label=target.getString(R.string.outing_delete)
        compose.onNodeWithText(label).performScrollTo().performClick()
        val gate=pause {header=header.copy(endedAt=third.time)}
        release(gate)
        compose.onNode(hasText(label) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(15_000) {left.get()}
        assertEquals(1L,graph.history.dao.totalPointCount())
        assertEquals(listOf(third.id),graph.history.dao.exportFirstPoints(start,third.time,null).map {it.value.id})
    }

}
