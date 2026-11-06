package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.*
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
import java.io.File
import java.time.Instant

/** Real archives and lifecycle, synthetic records in the disposable QA package only. */
@SdkSuppress(minSdkVersion=33)
class ReplayLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private var oldLocales:LocaleList?=null
    private val at=Instant.parse("2026-10-29T12:00:00Z")
    private val range=HistoryRange(at,at.plusSeconds(121))
    private fun point(i:Int)=TrackPoint("replay-$i",Coordinate(48.8584+i*.001,2.2945),at.plusSeconds(i*60L),5.0,altitude=12.5)
    private val cache:File get()=File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"route-replay")
    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target)
        graph.replay.clearCache {graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}}
        Unit
    }
    @After fun cleanup()=runBlocking {
        try {if(::graph.isInitialized) graph.replay.clearCache {graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}}}
        finally {oldLocales?.let {applyQaAppLocales(compose,it)}}
        Unit
    }
    private suspend fun seed(points:List<TrackPoint> =(0..2).map(::point),session:String?=null) {
        graph.history.dao.putPoints(points.map {PointRow(it,session)})
    }
    private fun show(request:ReplayRequest=ReplayRequest.Range(range),available:Boolean=true) {
        compose.setContent {MaterialTheme {RouteReplayButton(graph,request,true,available)}}
    }
    private fun open() {
        compose.onNodeWithText("Reproducir ruta").performClick()
        compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("replay-progress").assertExists()}.isSuccess}
    }
    private fun awaitPoint(index:Int,count:Int) {compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("replay-counter").assertTextEquals("${java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("es")).format(index)} DE ${java.text.NumberFormat.getIntegerInstance(java.util.Locale.forLanguageTag("es")).format(count)} PTS")}.isSuccess}}
    private fun jump(end:Boolean) {compose.onNodeWithTag(if(end) "replay-end" else "replay-start").performScrollTo().performClick()}
    private fun awaitCacheEmpty() {compose.waitUntil(5000) {cache.listFiles().orEmpty().isEmpty()}}
    private fun close() {
        compose.onNodeWithTag("replay-close").performClick()
        compose.onNodeWithTag("route-replay-content").assertDoesNotExist()
    }
    @Test fun restoredReplayRebuildsItsArchiveAndRetainsPausedMiddlePosition()=runBlocking {
        seed()
        val restoration=StateRestorationTester(compose)
        restoration.setContent {MaterialTheme {RouteReplayButton(graph,ReplayRequest.Range(range),true,true)}}
        open()
        compose.onNodeWithTag("replay-progress").performSemanticsAction(SemanticsActions.SetProgress) {it(.5f)}
        awaitPoint(2,3)
        restoration.emulateSavedInstanceStateRestore();awaitPoint(2,3)
        compose.onNodeWithTag("replay-toggle").assertContentDescriptionContains("Reproducir ruta")
        assertEquals(3L,graph.history.dao.totalPointCount());close();awaitCacheEmpty()
    }
    @Test fun restoredReplayRejectsChangedPointsEvenWhenTheOldObserverCannotSeeThem()=runBlocking {
        seed()
        val restoration=StateRestorationTester(compose)
        var changeOnDispose=false
        restoration.setContent {MaterialTheme {
            DisposableEffect(Unit) {onDispose {
                if(changeOnDispose) {
                    changeOnDispose=false
                    runBlocking {graph.history.dao.putPoints(listOf(PointRow(point(1).copy(accuracy=99.0),null)))}
                }
            }}
            RouteReplayButton(graph,ReplayRequest.Range(range),true,true)
        }}
        open();jump(false);awaitPoint(1,3)
        changeOnDispose=true;restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(10000) {runCatching {compose.onNodeWithText("El historial ha cambiado. Cierra y vuelve a iniciar la reproducción para cargar la ruta actualizada.").assertExists()}.isSuccess}
        compose.onNodeWithTag("replay-progress").assertDoesNotExist();awaitCacheEmpty()
        assertEquals(99.0,graph.history.dao.exportFirstPoints(range.from,range.until,null).single {it.value.id=="replay-1"}.value.accuracy,0.0)
        close();open();jump(true);awaitPoint(3,3)
        assertEquals(3L,graph.history.dao.totalPointCount())
    }
    @Test fun liveRangeAdvanceKeepsTheOpeningButAnExplicitScopeChangeClosesIt()=runBlocking {
        seed()
        var request by mutableStateOf<ReplayRequest>(ReplayRequest.Range(range))
        var owner by mutableStateOf("today")
        compose.setContent {MaterialTheme {RouteReplayButton(graph,request,true,true,owner=owner)}}
        open();jump(false);awaitPoint(1,3)
        compose.runOnIdle {request=ReplayRequest.Range(HistoryRange(range.from,range.until.plusSeconds(30)))}
        awaitPoint(1,3)
        compose.runOnIdle {owner="yesterday"}
        compose.onNodeWithTag("route-replay-content").assertDoesNotExist();awaitCacheEmpty()
        assertEquals(3L,graph.history.dao.totalPointCount())
    }
    @Test fun rangeScrubLocalizesStatisticsAndAccessibilityWithoutChangingPoints()=runBlocking {
        val originals=(0..2).map(::point)+point(4).copy(id="outlier",time=at.plusSeconds(30),outlier=true)
        seed(originals);show();open();jump(true);awaitPoint(3,3)
        compose.onNodeWithTag("replay-elapsed").assertTextEquals("Tiempo transcurrido 2:00 / 2:00")
        compose.onNodeWithTag("replay-distance").assertTextEquals("Distancia 0,22 km / 0,22 km")
        compose.onNodeWithTag("replay-progress").assertContentDescriptionEquals("Progreso de la reproducción de ruta")
        compose.onNodeWithTag("replay-progress").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Punto 3 de 3. Tiempo transcurrido 2:00 de 2:00. Distancia 0,22 km de 0,22 km."))
        jump(false);awaitPoint(1,3)
        compose.onNodeWithTag("replay-progress").performSemanticsAction(SemanticsActions.SetProgress) {it(.5f)}
        awaitPoint(2,3)
        compose.onNodeWithTag("replay-elapsed").assertTextEquals("Tiempo transcurrido 1:00 / 2:00")
        compose.onNodeWithTag("replay-distance").assertTextEquals("Distancia 0,11 km / 0,22 km")
        assertEquals(originals.associateBy {it.id},graph.history.dao.exportFirstPoints(range.from,range.until,null).associate {it.value.id to it.value})
        close();awaitCacheEmpty()
    }
    @Test fun exactOutingRetainsAllOutlierFallback()=runBlocking {
        val session=RecordingSession("all-outliers",at,at.plusSeconds(60),"Original outing")
        graph.history.dao.put(SessionRow(session))
        val points=(0..1).map {point(it).copy(outlier=true)};seed(points,session.id)
        show(ReplayRequest.Outing(session.id));open();jump(true);awaitPoint(2,2)
        assertEquals(session,graph.history.dao.session(session.id)!!.value)
        assertEquals(2L,graph.history.dao.totalPointCount())
    }
    @Test fun inferredReplayExcludesExactSessionWindowWithoutReassigningPoints()=runBlocking {
        seed()
        val exact=RecordingSession("exact-middle",at.plusSeconds(60),at.plusSeconds(60))
        graph.history.dao.put(SessionRow(exact))
        val header=InferredOutingHeader("inferred:replay-0",at,at.plusSeconds(120),PointSummary(count=3))
        show(ReplayRequest.Inferred(header));open();jump(true);awaitPoint(2,2)
        assertEquals(exact,graph.history.dao.session(exact.id)!!.value)
        assertEquals(3L,graph.history.dao.totalPointCount())
        assertTrue(graph.history.dao.exportFirstPoints(range.from,range.until,null).all {it.sessionId==null})
    }
    @Test fun missingOutingHasLocalizedLoadErrorAndCanClose() {
        show(ReplayRequest.Outing("missing"))
        compose.onNodeWithText("Reproducir ruta").performClick()
        compose.waitUntil(5000) {runCatching {compose.onNodeWithText("No se pudo cargar la reproducción de la ruta. Ciérrala e inténtalo de nuevo.").assertExists()}.isSuccess}
        close();compose.onNodeWithTag("route-replay-content").assertDoesNotExist()
    }
    @Test fun changedHistoryClosesOldArchiveAndReopenReadsTheNewRoute()=runBlocking {
        seed();show();open();jump(false);awaitPoint(1,3)
        graph.history.dao.putPoints(listOf(PointRow(point(3).copy(time=at.plusSeconds(100)),null)))
        val message="El historial ha cambiado. Cierra y vuelve a iniciar la reproducción para cargar la ruta actualizada."
        compose.waitUntil(5000) {runCatching {compose.onNodeWithText(message).assertExists()}.isSuccess}
        compose.onNodeWithTag("replay-progress").assertDoesNotExist();awaitCacheEmpty()
        close();open();jump(true);awaitPoint(4,4)
        assertEquals(4L,graph.history.dao.totalPointCount())
    }
    @Test fun backgroundReleasesArchiveAndResumesPausedAtTheSamePoint()=runBlocking {
        seed();show();open();jump(false);awaitPoint(1,3)
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            awaitCacheEmpty()
        } finally {compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)}
        awaitPoint(1,3)
        compose.onNodeWithTag("replay-toggle").assertContentDescriptionContains("Reproducir ruta")
        assertEquals(3L,graph.history.dao.totalPointCount())
    }
    @Test fun closedCacheHasLocalizedFailureAndDoesNotChangeHistory()=runBlocking {
        seed();show();open();jump(false);awaitPoint(1,3)
        graph.replay.clearCache()
        jump(true)
        compose.waitUntil(5000) {runCatching {compose.onNodeWithText("La caché de reproducción no está disponible. Cierra la reproducción e inténtalo de nuevo.").assertExists()}.isSuccess}
        assertEquals(3L,graph.history.dao.totalPointCount());close()
    }
    @Test fun sampledMapCaptionKeepsTheFullReplayCount()=runBlocking {
        val originals=(0..2500).map {i->point(0).copy(id="sample-$i",time=at.plusMillis(i.toLong()),coordinate=Coordinate(48.8584+i*.000001,2.2945))}
        seed(originals);show();open();jump(true);awaitPoint(2501,2501)
        compose.onNodeWithText("El mapa dibuja 2500 puntos de muestra. La posición y las estadísticas de la reproducción incluyen todos los puntos de la ruta.").performScrollTo().assertIsDisplayed()
        assertEquals(2501L,graph.history.dao.totalPointCount())
        close();awaitCacheEmpty()
    }
    @Test fun unavailableReplayCannotOpenOrPrepareAnArchive()=runBlocking {
        seed(listOf(point(0)));show(available=false)
        compose.onNodeWithText("Reproducir ruta").assertIsNotEnabled()
        compose.onNodeWithTag("route-replay-content").assertDoesNotExist()
        awaitCacheEmpty();assertEquals(1L,graph.history.dao.totalPointCount())
    }
    @Test fun largeSpanishControlsAndStatisticsRemainReachable()=runBlocking {
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(compose.activity.resources.configuration.fontScale>=1.8f)
        seed();show();open();jump(true);awaitPoint(3,3)
        compose.onNodeWithTag("replay-toggle").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("replay-distance").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("replay-start").assertIsDisplayed()
        compose.onNodeWithTag("replay-end").assertIsDisplayed()
        capture()
        close();awaitCacheEmpty()
    }
    private fun capture() {
        if(InstrumentationRegistry.getArguments().getString("captureReplayLocalization")!="true") return
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val size=if(target.resources.configuration.fontScale>=1.8f) "large" else "normal"
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-replay-localization-$size-20261029.png")
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
