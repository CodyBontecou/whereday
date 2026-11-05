package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.paging.*
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

/** No picker launches, permission grants or real image files. Only disposable QA records. */
@SdkSuppress(minSdkVersion=33)
class PhotoLocalizationTest {
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
        check(!graph.preferences.settings.first().automaticPhotoSync)
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        Unit
    }
    @After fun cleanup()=runBlocking {
        try {oldLocales?.let {applyQaAppLocales(compose,it)}}
        finally {if(::graph.isInitialized) graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}}
        Unit
    }
    private fun awaitText(text:String) {compose.waitUntil(10000) {runCatching {compose.onNodeWithText(text).assertExists()}.isSuccess}}
    private fun library() {
        compose.setContent {MaterialTheme {PhotosScreen(graph,{})}}
        compose.waitUntil(10000) {graph.photoLibrary.state.value.validated && !graph.photoLibrary.state.value.running}
    }
    private fun scrollLibrary(tag:String) {compose.onNodeWithTag("photo-library-content").performScrollToNode(hasTestTag(tag))}
    private fun openOptions() {
        scrollLibrary("photo-library-options");compose.onNodeWithTag("photo-library-options").performClick()
        compose.onNodeWithTag("photo-library-dialog").assertExists()
    }
    @Test fun spanishAccessLabelsAndPluralsRetainTheirDistinctMeanings() {
        compose.setContent {MaterialTheme {Column {PhotoLibraryAccess.entries.forEach {Text(photoLibraryAccessLabel(it))}}}}
        compose.onNodeWithText("Acceso a la biblioteca: no permitido. Usa el selector del sistema para elegir fotos individuales.").assertExists()
        compose.onNodeWithText("Acceso a la biblioteca: solo fotos seleccionadas.").assertExists()
        compose.onNodeWithText("Acceso a la biblioteca: todas las fotos.").assertExists()
        val resources=compose.activity.resources
        assertEquals("0 referencias de fotos guardadas",resources.getQuantityString(R.plurals.photo_saved_count,0,0L))
        assertEquals("1 referencia de foto guardada",resources.getQuantityString(R.plurals.photo_saved_count,1,1L))
        assertEquals("2 referencias de fotos guardadas",resources.getQuantityString(R.plurals.photo_saved_count,2,2L))
        assertEquals("1 foto coincidente",resources.getQuantityString(R.plurals.photo_matched_count,1,1L))
        assertEquals("2 fotos coincidentes",resources.getQuantityString(R.plurals.photo_matched_count,2,2L))
    }
    @Test fun optionsKeepConsentExplicitAndLongSpanishActionsReachable()=runBlocking {
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(compose.activity.resources.configuration.fontScale>=1.8f)
        val original=graph.preferences.settings.first();val access=graph.photoAccess.current()
        library();scrollLibrary("photo-library-count")
        compose.onNodeWithTag("photo-library-count").assertTextEquals("0 referencias de fotos guardadas")
        openOptions()
        compose.onNodeWithTag("photo-auto-sync").performScrollTo().assertIsOff()
        if(access==PhotoLibraryAccess.None) compose.onNodeWithTag("photo-auto-sync").assertIsNotEnabled()
        compose.onNodeWithText("Elegir acceso a la biblioteca").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("photo-library-close").assertIsDisplayed().assertIsEnabled()
        capture()
        compose.onNodeWithTag("photo-picker-locations").performScrollTo()
        if(original.requestPhotoLocations) compose.onNodeWithTag("photo-picker-locations").assertIsOn() else compose.onNodeWithTag("photo-picker-locations").assertIsOff()
        compose.onNodeWithTag("photo-library-close").performClick()
        compose.onNodeWithTag("photo-library-dialog").assertDoesNotExist()
        val after=graph.preferences.settings.first()
        assertEquals(original.automaticPhotoSync,after.automaticPhotoSync);assertEquals(original.requestPhotoLocations,after.requestPhotoLocations)
        assertEquals(access,graph.photoAccess.current());assertEquals(0L,graph.history.dao.photoAssetCount())
    }
    @Test fun unavailableReferenceRemovalStillRequiresSpanishConfirmation()=runBlocking {
        val asset=PhotoAssetRow("content://synthetic/spanish-remove",null,null,true,false,at)
        graph.history.dao.put(asset);library();scrollLibrary("photo-library-count")
        compose.onNodeWithTag("photo-library-count").assertTextEquals("1 referencia de foto guardada")
        compose.onNodeWithTag("photo-library-content").performScrollToNode(hasTestTag("photo-library-card"))
        compose.onNodeWithTag("photo-library-card").performClick();awaitText("Foto 1 de 1")
        compose.onNodeWithText("Eliminar foto de Whereday").performScrollTo().performClick()
        compose.onNodeWithText("¿Eliminar esta referencia de foto?").assertIsDisplayed()
        compose.onNodeWithText("Cancelar").performClick();assertNotNull(graph.history.dao.photoAsset(asset.uri))
        compose.onNodeWithText("Eliminar foto de Whereday").performScrollTo().performClick()
        compose.onNodeWithText("Eliminar foto",substring=false).performClick()
        compose.waitUntil(5000) {runBlocking {graph.history.dao.photoAsset(asset.uri)==null}}
        scrollLibrary("photo-library-count")
        compose.onNodeWithTag("photo-library-count").assertTextEquals("0 referencias de fotos guardadas")
        assertEquals(0L,graph.history.dao.totalPointCount());assertEquals(0L,graph.history.dao.totalVisitCount())
    }
    @Test fun heldRemovalApprovalExpiresWhileStoppedAndAfterResume()=runBlocking {
        val asset=PhotoAssetRow("content://synthetic/spanish-resume-remove",null,null,true,false,at)
        graph.history.dao.put(asset);library()
        compose.onNodeWithTag("photo-library-content").performScrollToNode(hasTestTag("photo-library-card"))
        compose.onNodeWithTag("photo-library-card").performClick();awaitText("Foto 1 de 1")
        compose.onNodeWithText("Eliminar foto de Whereday").performScrollTo().performClick()
        val old=compose.onNodeWithText("Eliminar foto",substring=false).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {old()}
        assertNotNull(graph.history.dao.photoAsset(asset.uri))
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle();compose.runOnIdle {old()}
        graph.photoLibrary.refresh();assertNotNull(graph.history.dao.photoAsset(asset.uri))
        compose.onNodeWithText("Eliminar foto",substring=false).performClick()
        compose.waitUntil(5000) {runBlocking {graph.history.dao.photoAsset(asset.uri)==null}}
        assertEquals(0L,graph.history.dao.totalPointCount());assertEquals(0L,graph.history.dao.totalVisitCount())
    }
    @Test fun translatedCardsSelectOriginalUrisAndPreserveWireSources() {
        val sources=listOf("photo_gps","inferred_route","inferred_visit",null,"photo_gps")
        val entries=sources.mapIndexed {i,source->PhotoLibraryEntry(PhotoAssetRow("content://synthetic/spanish-card-$i",at,null,true,i!=4,at),source)}
        var selected:String?=null
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {entries.forEach {entry->PhotoReferenceCard(entry,{selected=entry.asset.uri}) {Box(it)}}}}}
        val labels=listOf("GPS de la foto","Inferida de la ruta","Inferida de la visita","Sin coincidencia de ubicación","No disponible · vuelve a seleccionarla para restaurar el acceso")
        for((i,label) in labels.withIndex()) {
            compose.onNodeWithContentDescription(label,substring=true).performScrollTo().performClick()
            compose.runOnIdle {assertEquals(entries[i].asset.uri,selected)}
        }
        assertEquals(sources,entries.map {it.coordinateSource})
    }
    @Test fun libraryPagerKeepsCurrentUriAcrossSpanishNavigationAndMissingMetadata() {
        val entries=(0..2).map {i->PhotoLibraryEntry(PhotoAssetRow("content://synthetic/spanish-page-$i",if(i==2) null else at,null,true,i!=2,at),"inferred_route")}
        val open=mutableStateOf(true);var removed:String?=null
        val source={object:PagingSource<Int,PhotoLibraryEntry>() {
            override val jumpingSupported=true
            override fun getRefreshKey(state:PagingState<Int,PhotoLibraryEntry>):Int?=null
            override suspend fun load(params:LoadParams<Int>):LoadResult<Int,PhotoLibraryEntry> =LoadResult.Page(entries,null,null,0,0)
        }}
        compose.setContent {MaterialTheme {if(open.value) PagedLibraryPhotoViewer(0,source,entries[0].asset.uri,{open.value=false},{removed=it}) {entry,modifier->Box(modifier.testTag(entry.asset.uri))}}}
        awaitText("Foto 1 de 3")
        compose.onNodeWithTag("photo-pager-previous").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("photo-pager-next").performClick();awaitText("Foto 2 de 3")
        compose.onNodeWithText("Eliminar foto de Whereday").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(entries[1].asset.uri,removed)}
        compose.onNodeWithTag("photo-pager-next").performScrollTo().performClick();awaitText("Foto 3 de 3")
        compose.onNodeWithText("Fecha de captura no disponible").assertExists()
        compose.onNodeWithText("No disponible · vuelve a seleccionarla para restaurar el acceso").assertExists()
        compose.onNodeWithTag("photo-pager-next").assertIsNotEnabled()
        compose.onNodeWithTag("photo-pager-previous").performClick();awaitText("Foto 2 de 3")
        compose.onNodeWithTag("photo-pager-close").performScrollTo().performClick()
        compose.onNodeWithTag("photo-pager-content").assertDoesNotExist()
    }
    @Test fun deniedImageHasSpanishFallbackWithoutPublishingAnOldBitmap() {
        var unavailable:String?=null
        compose.setContent {MaterialTheme {PhotoImageContent("content://synthetic/spanish-denied",true,128,Modifier.size(128.dp),{_,_->throw SecurityException("Synthetic denial")},{unavailable=it})}}
        awaitText("Foto no disponible")
        compose.onNodeWithContentDescription("Foto seleccionada").assertDoesNotExist()
        compose.runOnIdle {assertEquals("content://synthetic/spanish-denied",unavailable)}
    }
    @Test fun matchedEmptyErrorAndRefreshUseLocalizedCopyWithoutSavingPhotos()=runBlocking {
        var refreshed=0;val state=mutableStateOf(MatchedPhotosState(0,refresh={refreshed++}))
        compose.setContent {MaterialTheme {Column {MatchedPhotoStrip(graph,state.value,com.bontecou.whereday.domain.HistoryRange(at,at.plusSeconds(60)))}}}
        compose.onNodeWithText("Actualizar fotos coincidentes").performClick()
        compose.runOnIdle {assertEquals(1,refreshed);state.value=MatchedPhotosState(error=R.string.photo_error_matched)}
        compose.onNodeWithText("No se pudieron cargar las fotos coincidentes. Intenta actualizarlas.").assertExists()
        assertEquals(0L,graph.history.dao.photoAssetCount())
    }
    private fun capture() {
        if(InstrumentationRegistry.getArguments().getString("capturePhotoLocalization")!="true") return
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val size=if(target.resources.configuration.fontScale>=1.8f) "large" else "normal"
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-photo-localization-$size-20261029.png")
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
