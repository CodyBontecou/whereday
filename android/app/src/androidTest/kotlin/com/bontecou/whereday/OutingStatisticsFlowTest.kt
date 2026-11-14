package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.*
import java.time.*
import java.util.Locale

class OutingStatisticsFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private val start=Instant.parse("2026-11-01T00:00:00Z")
    private val a=TrackPoint("detail-first",Coordinate(18.0,-66.0),start.plusSeconds(120),5.0)
    private val b=a.copy(id="detail-last",coordinate=Coordinate(18.01,-66.0),time=start.plusSeconds(180))
    @Before fun seed():Unit=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,networkGeocoding=false)}
        graph.history.dao.putPoints(listOf(PointRow(a,null),PointRow(b,null)))
    }
    @After fun clear():Unit=runBlocking {graph.journalClear.clear().join()}
    private fun ready()=compose.waitUntil(10_000) {compose.onAllNodesWithTag("outing-duration").fetchSemanticsNodes().isNotEmpty()}
    private fun expectedDuration(minutes:Long,seconds:Double):String {
        val resources=InstrumentationRegistry.getInstrumentation().targetContext.resources
        val average=resources.getString(R.string.outing_average_speed,distanceText(a.coordinate.distanceTo(b.coordinate)*3600/seconds,true,Locale.US))
        return resources.getString(R.string.outing_duration_summary,0L,minutes,average)
    }
    @Test fun closedDurationAndAverageUseSessionBoundsRatherThanFixBounds():Unit=runBlocking {
        val session=RecordingSession("closed-detail",start,start.plusSeconds(600))
        graph.history.dao.put(SessionRow(session))
        compose.setContent {MaterialTheme {OutingDetailScreen(graph,session.id,true,{},{})}}
        ready();compose.onNodeWithTag("outing-duration").assertTextEquals(expectedDuration(10,600.0));capture("closed")
    }
    @Test fun openDurationAndAverageEndAtLastIncludedFix():Unit=runBlocking {
        val session=RecordingSession("open-detail",start)
        graph.history.dao.put(SessionRow(session))
        compose.setContent {MaterialTheme {OutingDetailScreen(graph,session.id,true,{},{})}}
        ready();compose.onNodeWithTag("outing-duration").assertTextEquals(expectedDuration(3,180.0))
        compose.onNodeWithTag("outing-latest").assertExists();capture("open")
    }
    @Test fun subMinuteDurationRetainsSeconds():Unit=runBlocking {
        val session=RecordingSession("short-detail",start.plusSeconds(240),start.plusSeconds(289))
        graph.history.dao.put(SessionRow(session))
        graph.history.dao.putPoints(listOf(PointRow(a.copy(id="short-first",time=start.plusSeconds(260)),null),PointRow(b.copy(id="short-last",time=start.plusSeconds(270)),null)))
        compose.setContent {MaterialTheme {OutingDetailScreen(graph,session.id,true,{},{})}}
        ready()
        val resources=InstrumentationRegistry.getInstrumentation().targetContext.resources
        val average=resources.getString(R.string.outing_average_speed,distanceText(a.coordinate.distanceTo(b.coordinate)*3600/49,true,Locale.US))
        compose.onNodeWithTag("outing-duration").assertTextEquals(resources.getString(R.string.outing_duration_seconds_summary,49L,average));capture("seconds")
    }
    @Test fun runtimeOnlyHeaderChangesRefreshBothDirectionsWithoutDatabaseWrites() {
        var header by mutableStateOf(InferredOutingHeader("inferred:detail-first",a.time,b.time,PointSummary(2,0,a.coordinate.distanceTo(b.coordinate),a.time,b.time)))
        compose.setContent {MaterialTheme {OutingDetailScreen(graph,header.id,true,{},{},header)}}
        ready();compose.onNodeWithText("Recording",substring=true).assertDoesNotExist()
        compose.runOnIdle {header=header.copy(active=true)}
        compose.waitUntil(10_000) {compose.onAllNodesWithText("Recording",substring=true).fetchSemanticsNodes().isNotEmpty()}
        capture("active")
        compose.runOnIdle {header=header.copy(active=false)}
        compose.waitUntil(10_000) {compose.onAllNodesWithText("Recording",substring=true).fetchSemanticsNodes().isEmpty()}
        capture("inactive")
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion=33)
    fun spanishOpenDetailShowsCapturedLatestAndSeconds():Unit=runBlocking {
        val manager=InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.app.LocaleManager::class.java)
        val original=manager.applicationLocales
        try {
            applyQaAppLocales(compose,android.os.LocaleList.forLanguageTags("es"))
            val session=RecordingSession("spanish-short",start.plusSeconds(240))
            graph.history.dao.put(SessionRow(session))
            graph.history.dao.putPoints(listOf(PointRow(a.copy(id="es-first",time=start.plusSeconds(260)),null),PointRow(b.copy(id="es-last",time=start.plusSeconds(289)),null)))
            compose.setContent {MaterialTheme {OutingDetailScreen(graph,session.id,true,{},{})}}
            ready()
            val average=distanceText(a.coordinate.distanceTo(b.coordinate)*3600/49,true,Locale.forLanguageTag("es"))
            compose.onNodeWithTag("outing-duration").assertTextEquals("49 s · $average/h de media")
            compose.onNodeWithTag("outing-latest").assertTextContains("Último:",substring=true)
            capture("spanish")
        } finally {applyQaAppLocales(compose,original)}
    }
    private fun capture(label:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        if(InstrumentationRegistry.getArguments().getString("captureOutingStatistics")!="true") return
        compose.onNodeWithTag("outing-duration").performScrollTo().assertIsDisplayed();compose.waitForIdle()
        val target=instrumentation.targetContext
        val size=if(target.resources.configuration.fontScale>=1.8f) "large" else "normal"
        val name="whereday-outing-metrics-$label-$size-20261102.png"
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
        instrumentation.sendStatus(2,android.os.Bundle().apply {putString("outingMetricsCapture","$label|$uri|$name")})
    }
}
