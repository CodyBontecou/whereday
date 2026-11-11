package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

/** Real Spanish Activity/dialog resources, real repositories, disposable QA records only. */
@SdkSuppress(minSdkVersion=33)
class OutingLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var originalExport:ExportSelection
    private var oldLocales:LocaleList?=null
    private val at=Instant.parse("2026-10-29T12:00:00Z")
    private val session=RecordingSession("spanish-outing",at,at.plusSeconds(4380),"QA original 日本語","Original note")
    private val visit=Visit("linked-spanish",Coordinate(48.8584,2.2945),at.plusSeconds(60),at.plusSeconds(120),name="QA linked visit",notes="Keep original visit")
    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        val manager=target.getSystemService(LocaleManager::class.java)
        oldLocales=manager.applicationLocales;applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target)
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        originalExport=graph.exportSettings.state.first {it.ready}.selection
        Unit
    }
    @After fun cleanup()=runBlocking {
        try {
            if(::graph.isInitialized) {
                if(::originalExport.isInitialized) {
                    graph.exportSettings.update(originalExport)
                    withTimeout(5000) {graph.exportSettings.state.first {!it.saving && it.selection==originalExport}}
                }
                graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
            }
        } finally {oldLocales?.let {applyQaAppLocales(compose,it)}}
        Unit
    }
    private suspend fun seed() {
        graph.history.dao.put(SessionRow(session));graph.history.dao.put(VisitRow(visit))
        graph.history.dao.putPoints(listOf(
            PointRow(TrackPoint("good",Coordinate(48.8584,2.2945),at,5.0),session.id),
            PointRow(TrackPoint("outlier",Coordinate(48.8594,2.2955),at.plusSeconds(60),5.0,outlier=true),session.id),
        ))
    }
    private fun show(id:String=session.id,header:InferredOutingHeader?=null,back:()->Unit={},map:(RecordingSession)->Unit={}) {
        compose.setContent {MaterialTheme {OutingDetailScreen(graph,id,true,back,map,header)}}
    }
    private fun awaitText(text:String) {compose.waitUntil(10000) {runCatching {compose.onNodeWithText(text).assertExists()}.isSuccess}}
    private fun action(text:String)=compose.onNodeWithText(text).performScrollTo().performClick()
    @Test fun detailsLocalizeCountsDurationAndDistanceWithoutChangingHistory()=runBlocking {
        seed();show();awaitText(session.name!!)
        compose.onNodeWithTag("outing-point-summary").assertTextEquals("2 puntos · 1 anomalía del GPS")
        compose.onNodeWithTag("outing-duration").assertTextEquals("1 h 13 min · 0,00 km/h de media")
        assertEquals(session,graph.history.dao.session(session.id)!!.value)
        assertEquals(visit,graph.history.dao.visit(visit.id)!!.value)
        assertEquals(2L,graph.history.dao.totalPointCount())
        capture("overview")
    }
    @Test fun spanishRenameAndFormatSelectionKeepRecordIdentity()=runBlocking {
        seed();show();awaitText(session.name!!);action("Editar salida")
        compose.onNodeWithTag("outing-name").performTextReplacement("  Paseo QA 日本語  ")
        compose.onNodeWithTag("outing-notes").performScrollTo().performTextReplacement("Nota QA\nSegunda línea")
        compose.onNodeWithText("Guardar salida").performClick();awaitText("Paseo QA 日本語")
        assertEquals(session.copy(name="Paseo QA 日本語",notes="Nota QA\nSegunda línea"),graph.history.dao.session(session.id)!!.value)
        assertEquals(visit,graph.history.dao.visit(visit.id)!!.value)
        compose.onNodeWithText("Formato del archivo de la salida").performScrollTo()
        val targetFormat=if(originalExport.format==ExportFormat.csv) ExportFormat.json else ExportFormat.csv
        compose.onNode(hasText(recipeFormatName(originalExport.format)) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText(recipeFormatName(targetFormat)) and hasAnyAncestor(isPopup())).performScrollTo().performClick()
        withTimeout(5000) {graph.exportSettings.state.first {!it.saving && it.selection.format==targetFormat}}
        assertEquals(targetFormat,ExportSelectionCodec.decode(graph.preferences.settings.first().exportConfiguration).format)
    }
    @Test fun defaultNameRemainsNullAndLinkedVisitNavigationPreservesVisit()=runBlocking {
        seed();show();awaitText(session.name!!);action("Editar salida")
        compose.onNodeWithText("Usar nombre predeterminado").performClick()
        compose.onNodeWithText("Guardar salida").performClick();awaitText("Detalles de la salida")
        assertEquals(session.copy(name=null),graph.history.dao.session(session.id)!!.value)
        action("Abrir visita");awaitText("Editar visita")
        compose.onNodeWithTag("visit-name").assertTextContains(visit.name!!)
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(visit,graph.history.dao.visit(visit.id)!!.value)
    }
    @Test fun mapFocusReceivesTheOriginalSession()=runBlocking {
        seed();var focused:RecordingSession?=null
        show(map={focused=it});awaitText(session.name!!);action("Mostrar en el mapa")
        compose.runOnIdle {assertEquals(session,focused)}
    }
    @Test fun spanishDeleteRequiresConfirmationAndPreservesVisitsAndOtherOutings()=runBlocking {
        seed()
        val other=RecordingSession("other",at.plusSeconds(7200),at.plusSeconds(7300),"Other outing")
        graph.history.dao.put(SessionRow(other))
        graph.history.dao.putPoints(listOf(PointRow(TrackPoint("other-point",Coordinate(48.8584,2.2945),other.startedAt,5.0),other.id)))
        var left=false;show(back={left=true});awaitText(session.name!!);action("Eliminar salida")
        compose.onNodeWithText("¿Eliminar esta salida?").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick()
        assertNotNull(graph.history.dao.session(session.id));assertEquals(3L,graph.history.dao.totalPointCount())
        action("Eliminar salida")
        compose.onNode(hasText("Eliminar salida") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5000) {left}
        assertNull(graph.history.dao.session(session.id));assertEquals(other,graph.history.dao.session(other.id)!!.value)
        assertEquals(visit,graph.history.dao.visit(visit.id)!!.value)
        assertEquals(listOf("other-point"),graph.history.dao.exportFirstPoints(at,at.plusSeconds(8000),null).map {it.value.id})
    }
    @Test fun inferredDetailsHaveNoExactSessionEditorOrStoredSession()=runBlocking {
        val point=TrackPoint("unassigned",Coordinate(48.8584,2.2945),at,5.0)
        graph.history.dao.putPoints(listOf(PointRow(point,null)))
        val header=InferredOutingHeader("inferred:unassigned",at,at,PointSummary(count=1,firstTime=at,lastTime=at))
        show(header.id,header);awaitText("Salida inferida")
        compose.onNodeWithText("Editar salida").assertDoesNotExist()
        compose.onNodeWithTag("outing-point-summary").assertTextEquals("1 punto · 0 anomalías del GPS")
        compose.onNodeWithTag("outing-duration").assertTextEquals("0 h 0 min · Velocidad media no disponible")
        assertTrue(graph.history.dao.rangeSessions(at.minusSeconds(1),at.plusSeconds(1)).isEmpty())
        assertEquals(point,graph.history.dao.exportFirstPoints(at.minusSeconds(1),at.plusSeconds(1),null).single().value)
    }
    @Test fun missingOutingHasSpanishFailureAndBackAction() {
        var left=false;show("missing",back={left=true})
        awaitText("No se pudo cargar esta salida. Es posible que se haya eliminado.")
        compose.onNodeWithText("Volver a la línea de tiempo").performClick()
        compose.runOnIdle {assertTrue(left)}
    }
    @Test fun editorActionsStayReachableWithLongSpanishText()=runBlocking {
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(compose.activity.resources.configuration.fontScale>=1.8f)
        seed();show();awaitText(session.name!!);action("Editar salida")
        compose.onNodeWithTag("outing-name").performTextReplacement("Salida QA con un nombre largo para comprobar el texto grande")
        compose.onNodeWithTag("outing-notes").performScrollTo().performTextReplacement("Nota QA larga que debe poder leerse sin perder los botones del diálogo.")
        compose.onNodeWithText("Guardar salida").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("Cancelar").assertIsDisplayed().assertIsEnabled()
        capture("editor")
        assertEquals(session,graph.history.dao.session(session.id)!!.value)
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(session,graph.history.dao.session(session.id)!!.value)
    }
    private fun capture(label:String) {
        if(InstrumentationRegistry.getArguments().getString("captureOutingLocalization")!="true") return
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val size=if(target.resources.configuration.fontScale>=1.8f) "-large" else ""
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-outing-localization-$label$size-20261029.png")
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
