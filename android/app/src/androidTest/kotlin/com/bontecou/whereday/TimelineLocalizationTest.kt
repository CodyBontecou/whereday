package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.time.*

@SdkSuppress(minSdkVersion=33)
class TimelineLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var model:JournalViewModel
    private val models=ViewModelStore()
    private var oldLocales:LocaleList?=null
    private val at=Instant.parse("2026-10-29T12:00:00Z")
    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        val manager=target.getSystemService(LocaleManager::class.java)
        oldLocales=manager.applicationLocales;applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target)
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        compose.activityRule.scenario.onActivity {model=JournalViewModel(graph);models.put("journal",model);model.timelineDay.value=at.atZone(ZoneId.systemDefault()).toLocalDate()}
        Unit
    }
    @After fun cleanup()=runBlocking {
        try {
            compose.activityRule.scenario.onActivity {models.clear()}
            if(::graph.isInitialized) graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        } finally {oldLocales?.let {applyQaAppLocales(compose,it)}}
        Unit
    }
    private fun show(large:Boolean=false) {compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,if(large) 1.8f else 1f)) {
            MaterialTheme {TimelineScreen(graph,model,true,{})}
        }
    }}
    private fun awaitText(text:String) {compose.waitUntil(10000) {runCatching {compose.onNodeWithText(text).assertExists()}.isSuccess}}
    @Test fun spanishDayActionsPreserveCivilDatesAcrossTheYearBoundary() {
        model.timelineDay.value=LocalDate.of(2027,1,1);show()
        compose.onNodeWithTag("timeline-today").assertTextContains("ene",substring=true)
        compose.onNodeWithTag("timeline-previous").performClick()
        compose.runOnIdle {assertEquals(LocalDate.of(2026,12,31),model.timelineDay.value)}
        compose.onNodeWithTag("timeline-next").performClick()
        compose.runOnIdle {assertEquals(LocalDate.of(2027,1,1),model.timelineDay.value)}
        compose.onNodeWithTag("timeline-today").performClick()
        compose.runOnIdle {assertEquals(LocalDate.now(),model.timelineDay.value)}
        awaitText("No hay salidas ni visitas para este día.")
    }
    @Test fun spanishCountsStatusesAndFallbackNamesDoNotRewriteStoredValues()=runBlocking {
        val visits=Confirmation.entries.mapIndexed {i,status->Visit("status-$i",Coordinate(48.8584,2.2945),at.plusSeconds(i*60L),at.plusSeconds(i*60L+30),name=if(i==0) " " else "QA original $i",notes="Original note",confirmation=status)}
        visits.forEach {graph.history.dao.put(VisitRow(it))}
        graph.history.dao.put(SessionRow(RecordingSession("session",at.minusSeconds(60),at.plusSeconds(600))))
        show();awaitText("QA original 2")
        for(label in listOf("Corregida","Confirmada","Sin confirmar")) {
            compose.onNodeWithTag("timeline-content").performScrollToNode(hasText(label))
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNodeWithText("Visita").assertExists()
        compose.onNodeWithTag("timeline-content").performScrollToIndex(0)
        compose.onNodeWithTag("timeline-summary").assertTextEquals("1 salida · 3 visitas · 0,00 km")
        for(visit in visits) assertEquals(visit,graph.history.dao.visit(visit.id)!!.value)
    }
    @Test fun openVisitRangeAndEditorNavigationAreLocalized()=runBlocking {
        val visit=Visit("open-spanish",Coordinate(48.8584,2.2945),at,name="QA open visit")
        graph.history.dao.put(VisitRow(visit));show();awaitText("QA open visit")
        compose.onNodeWithText("Aún estoy aquí",substring=true).assertExists()
        compose.onNodeWithText("QA open visit").performClick()
        compose.onNodeWithText("Editar visita").assertIsDisplayed()
        assertEquals(visit,graph.history.dao.visit(visit.id)!!.value)
    }
    @Test fun inferredOutingLabelIsDisplayOnly()=runBlocking {
        val original=graph.preferences.settings.first()
        try {
            graph.preferences.update {it.copy(inferredEnabled=true,inferredGapMinutes=30,inferredMinimumMinutes=0,inferredMinimumPoints=1)}
            val point=TrackPoint("unassigned",Coordinate(48.8584,2.2945),at,5.0)
            graph.history.dao.putPoints(listOf(PointRow(point,null)))
            show();awaitText("Salida inferida")
            compose.onNodeWithTag("timeline-summary").assertTextEquals("1 salida · 0 visitas · 0,00 km")
            assertEquals("unassigned",graph.history.dao.exportFirstPoints(at.minusSeconds(1),at.plusSeconds(1),null).single().value.id)
            assertTrue(graph.history.dao.rangeSessions(at.minusSeconds(1),at.plusSeconds(1)).isEmpty())
        } finally {graph.preferences.update {it.copy(inferredEnabled=original.inferredEnabled,inferredGapMinutes=original.inferredGapMinutes,inferredMinimumMinutes=original.inferredMinimumMinutes,inferredMinimumPoints=original.inferredMinimumPoints)}}
        Unit
    }
    @Test fun largeSpanishNavigationAndLongVisitTextStayReachable()=runBlocking {
        graph.history.dao.put(VisitRow(Visit("large",Coordinate(48.8584,2.2945),at,name="Visita QA con un nombre largo para comprobar el texto grande",notes="Una nota de prueba que permanece en su idioma original.")))
        show(large=true);awaitText("Visita QA con un nombre largo para comprobar el texto grande")
        for(id in listOf("timeline-previous","timeline-today","timeline-next")) compose.onNodeWithTag(id).assertIsDisplayed()
        compose.onNodeWithTag("timeline-content").performScrollToNode(hasText("Sin confirmar"))
        compose.onNodeWithText("Sin confirmar").assertIsDisplayed()
        capture()
        assertEquals(1L,graph.history.dao.totalVisitCount())
    }
    private fun capture() {
        if(InstrumentationRegistry.getArguments().getString("captureTimelineLocalization")!="true") return
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-timeline-localization-large-20261029.png")
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
