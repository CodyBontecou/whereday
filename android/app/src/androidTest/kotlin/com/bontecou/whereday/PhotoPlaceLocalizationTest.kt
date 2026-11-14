package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
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
class PhotoPlaceLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private var oldLocales:LocaleList?=null
    private val at=Instant.parse("2026-10-29T12:00:00Z")
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
        try {oldLocales?.let {applyQaAppLocales(compose,it)}}
        finally {if(::graph.isInitialized) graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}}
        Unit
    }
    private fun uri(index:Int)="content://synthetic/spanish-place-${index.toString().padStart(5,'0')}"
    private suspend fun seed(count:Int,distinct:Boolean) {
        graph.database.writeTransaction {repeat(count) {i->
            val coordinate=Coordinate(18.0,-66.0+if(distinct) i*.01 else 0.0)
            graph.history.dao.put(PhotoAssetRow(uri(i),at.plusSeconds(i*60L),coordinate,true,true,at))
            graph.history.dao.put(PhotoRow(uri(i),at.plusSeconds(i*60L),coordinate,"photo_gps",at))
        }}
    }
    private fun awaitText(text:String) {compose.waitUntil(10000) {runCatching {compose.onNodeWithText(text).assertExists()}.isSuccess}}
    private suspend fun assertHistory(count:Long) {
        assertEquals(count,graph.history.dao.photoAssetCount())
        for(i in 0 until count.toInt()) {
            val photo=graph.history.dao.photoMoment(uri(i))!!
            assertEquals("photo_gps",photo.coordinateSource);assertEquals(at.plusSeconds(i*60L),photo.capturedAt)
        }
    }
    @Test fun spanishDirectoryPreservesPageWhenReturningFromAPlace()=runBlocking {
        seed(52,true);val directory=graph.photoPlaces.prepare(HistoryRange.all);val open=mutableStateOf(true)
        val counts=directory.counts()
        compose.setContent {MaterialTheme {if(open.value) PhotoPlaceBrowser(graph,directory,counts,{open.value=false}) {_,modifier,_->Box(modifier)}}}
        compose.onNodeWithTag("photo-places-summary").performScrollTo().assertTextEquals("52 lugares · 52 fotos")
        compose.onNodeWithTag("photo-places-next").performClick();awaitText("Página 2")
        compose.waitUntil(5000) {compose.onAllNodesWithTag("photo-place-card").fetchSemanticsNodes().size==2}
        compose.onAllNodesWithTag("photo-place-card").onFirst().performClick();awaitText("Fotos aquí")
        compose.onNodeWithTag("photo-places-close").assertTextContains("Volver a los lugares con fotos").performClick()
        compose.onNodeWithTag("photo-places-page").assertTextEquals("Página 2")
        compose.onNodeWithTag("photo-places-previous").performClick();awaitText("Página 1")
        assertHistory(52)
        compose.onNodeWithTag("photo-places-close").performClick();compose.runOnIdle {assertFalse(open.value)}
    }
    @Test fun spanishCollectionKeepsGlobalPhotoRankAndDismissesOnInvalidation()=runBlocking {
        seed(52,false);val directory=graph.photoPlaces.prepare(HistoryRange.all);val counts=directory.counts()
        val place=directory.places(0,1).single();val open=mutableStateOf(true)
        compose.setContent {MaterialTheme {if(open.value) PhotoPlaceBrowser(graph,directory,counts,{open.value=false},initialPlace=place) {photo,modifier,_->Box(modifier.testTag(photo.uri))}}}
        awaitText("Fotos aquí")
        compose.onNodeWithTag("photo-places-next").performClick();awaitText("Página 2")
        compose.waitUntil(5000) {compose.onAllNodesWithText("Abrir foto").fetchSemanticsNodes().size==2}
        compose.onAllNodesWithText("Abrir foto").onFirst().performClick();awaitText("Foto 51 de 52")
        compose.onNode(hasTestTag(uri(50)) and hasAnyAncestor(hasTestTag("photo-pager-content"))).assertExists()
        compose.onNodeWithTag("photo-pager-next").performScrollTo().performClick();awaitText("Foto 52 de 52")
        compose.onNode(hasTestTag(uri(51)) and hasAnyAncestor(hasTestTag("photo-pager-content"))).assertExists()
        compose.onNodeWithTag("photo-pager-close").performScrollTo().performClick();awaitText("Fotos aquí")
        compose.onNodeWithTag("photo-places-page").assertTextEquals("Página 2")
        directory.close();compose.waitUntil(5000) {!open.value}
        assertHistory(52)
    }
    @Test fun singularCountAndDecimalCommaCoordinatesRemainDisplayOnly()=runBlocking {
        seed(1,false);val directory=graph.photoPlaces.prepare(HistoryRange.all);val counts=directory.counts()
        compose.setContent {MaterialTheme {PhotoPlaceBrowser(graph,directory,counts,{}) {_,modifier,_->Box(modifier)}}}
        compose.onNodeWithTag("photo-places-summary").performScrollTo().assertTextEquals("1 lugar · 1 foto")
        compose.waitUntil(5000) {compose.onAllNodesWithTag("photo-place-card").fetchSemanticsNodes().size==1}
        compose.onNodeWithTag("photo-place-card").performClick();awaitText("Fotos aquí")
        compose.onNodeWithText("1 foto en 18,00000; -66,00000").performScrollTo().assertIsDisplayed()
        assertEquals(Coordinate(18.0,-66.0),graph.history.dao.photoMoment(uri(0))!!.coordinate)
        assertHistory(1)
    }
    @Test fun largeSpanishEmptyBrowserKeepsNavigationAndSummaryReachable()=runBlocking {
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(compose.activity.resources.configuration.fontScale>=1.8f)
        val directory=graph.photoPlaces.prepare(HistoryRange.all);val counts=directory.counts();val open=mutableStateOf(true)
        compose.setContent {MaterialTheme {if(open.value) PhotoPlaceBrowser(graph,directory,counts,{open.value=false}) {_,modifier,_->Box(modifier)}}}
        compose.onNodeWithTag("photo-place-directory-title").assertTextEquals("Todos los lugares con fotos").assertIsDisplayed()
        compose.onNodeWithTag("photo-places-summary").performScrollTo().assertTextEquals("0 lugares · 0 fotos").assertIsDisplayed()
        compose.onNodeWithTag("photo-places-previous").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("photo-places-next").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("photo-places-close").assertIsDisplayed().assertIsEnabled()
        capture()
        compose.onNodeWithTag("photo-places-close").performClick();compose.runOnIdle {assertFalse(open.value)}
        assertEquals(0L,graph.history.dao.photoAssetCount())
    }
    private fun capture() {
        if(InstrumentationRegistry.getArguments().getString("capturePhotoPlacesLocalization")!="true") return
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val size=if(target.resources.configuration.fontScale>=1.8f) "large" else "normal"
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-photo-places-localization-$size-20261029.png")
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
