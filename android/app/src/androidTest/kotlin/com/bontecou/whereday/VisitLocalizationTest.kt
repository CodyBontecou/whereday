package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
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
class VisitLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private var oldLocales:LocaleList?=null
    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        val manager=target.getSystemService(LocaleManager::class.java)
        oldLocales=manager.applicationLocales;applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target)
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        Unit
    }
    @After fun cleanup()=runBlocking {
        try {if(::graph.isInitialized) graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}}
        finally {oldLocales?.let {applyQaAppLocales(compose,it)}}
        Unit
    }
    private fun awaitError(text:String) {
        compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("visit-editor-error").assertTextEquals(text)}.isSuccess}
        compose.onNodeWithTag("visit-editor-error").performScrollTo().assertIsDisplayed()
    }
    @Test fun spanishValidationAcceptsCommaCoordinatesAndCommitsOnlyOnSave()=runBlocking {
        compose.setContent {MaterialTheme {
            var showing by remember {mutableStateOf(true)}
            if(showing) VisitEditor(graph,null,{showing=false},{value,reuse,remember,overlap->graph.history.saveVisit(value,null,reuse,remember,overlap)},null)
        }}
        compose.onNodeWithText("Guardar").performClick()
        awaitError("Elige una ubicación o introduce coordenadas válidas.")
        assertEquals(0L,graph.history.dao.totalVisitCount())
        compose.onNodeWithTag("visit-latitude").performScrollTo().performTextInput("48,8584")
        compose.onNodeWithTag("visit-longitude").performScrollTo().performTextInput("2,2945")
        compose.onNodeWithText("Guardar").performClick()
        awaitError("Introduce el nombre de un lugar.")
        compose.onNodeWithTag("visit-name").performScrollTo().performTextInput("Biblioteca QA ñ")
        compose.onNodeWithTag("visit-notes").performScrollTo().performTextInput("Nota sin traducir: café")
        assertEquals(0L,graph.history.dao.totalVisitCount())
        compose.onNodeWithText("Guardar").performClick()
        compose.waitUntil(5000) {runBlocking {graph.history.dao.totalVisitCount()==1L}}
        val visit=graph.history.dao.rangeVisits(Instant.EPOCH,Instant.now().plusSeconds(1)).single().value
        assertEquals(Coordinate(48.8584,2.2945),visit.coordinate)
        assertEquals("Biblioteca QA ñ",visit.name);assertEquals("Nota sin traducir: café",visit.notes)
        assertEquals(Confirmation.confirmed,visit.confirmation);assertNotNull(visit.departedAt)
        assertFalse(graph.history.dao.recording()?.requested==true)
    }
    @Test fun unavailableNearbySearchPreservesUnsavedVisitFields()=runBlocking {
        check(!com.bontecou.whereday.platform.GeoapifyPlaceLookup.available)
        compose.setContent {MaterialTheme {VisitEditor(graph,null,{}, {_,_,_,_->error("Unexpected save")},null)}}
        compose.onNodeWithTag("visit-name").performTextInput("My unchanged name")
        compose.onNodeWithTag("visit-latitude").performScrollTo().performTextInput("18,0")
        compose.onNodeWithTag("visit-longitude").performScrollTo().performTextInput("-66,0")
        compose.onNodeWithTag("visit-nearby").performScrollTo().performClick()
        compose.onNodeWithText("La búsqueda de negocios cercanos no está configurada en esta versión.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("nearby-search").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("nearby-close").performClick()
        compose.onNodeWithTag("visit-name").performScrollTo().assertTextContains("My unchanged name")
        compose.onNodeWithTag("visit-latitude").performScrollTo().assertTextContains("18,0")
        compose.onNodeWithTag("visit-longitude").performScrollTo().assertTextContains("-66,0")
        assertEquals(0L,graph.history.dao.totalVisitCount())
    }
    @Test fun spanishConflictDoesNotOverwriteANewerVisit()=runBlocking {
        val at=Instant.ofEpochMilli(System.currentTimeMillis()).minusSeconds(3600)
        val original=Visit("spanish-conflict",Coordinate(48.8584,2.2945),at,at.plusSeconds(600),name="Original QA")
        graph.history.dao.put(VisitRow(original))
        compose.setContent {MaterialTheme {VisitEditor(graph,original,{}, {value,reuse,remember,overlap->graph.history.saveVisit(value,original,reuse,remember,overlap)},null)}}
        compose.onNodeWithText("Editar visita").assertIsDisplayed()
        compose.onNodeWithTag("visit-name").performTextReplacement("Mi cambio QA")
        val latest=original.copy(notes="Newer untouched data")
        graph.history.dao.put(VisitRow(latest))
        compose.onNodeWithText("Guardar").performClick()
        awaitError("Esta visita ha cambiado. Cierra el editor y revisa la versión más reciente antes de volver a intentarlo.")
        assertEquals(latest,graph.history.dao.visit(original.id)!!.value)
    }
    @Test fun spanishDiscardRequiresExplicitConfirmation() {
        var dismissed=0;var writes=0
        compose.setContent {MaterialTheme {VisitEditor(graph,null,{dismissed++},{_,_,_,_->writes++},null)}}
        compose.onNodeWithTag("visit-name").performTextInput("Borrador QA")
        compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithText("¿Descartar los cambios de la visita?").assertIsDisplayed()
        compose.onNodeWithText("Seguir editando").performClick()
        compose.onNodeWithTag("visit-name").assertTextContains("Borrador QA")
        assertEquals(0,dismissed);assertEquals(0,writes)
        compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithText("Descartar cambios").performClick()
        assertEquals(1,dismissed);assertEquals(0,writes)
    }
    @Test fun spanishSavedChooserCopiesUserMetadataWithoutChangingTheLibrary()=runBlocking {
        val place=SavedPlace("saved-spanish","Name kept in its original language",Coordinate(48.8584,2.2945),"Address kept",150.0)
        graph.history.dao.put(PlaceRow(place))
        var chosen:SavedPlace?=null
        compose.setContent {MaterialTheme {SavedPlaceChooser(graph,{}, {chosen=it})}}
        compose.waitUntil(5000) {runCatching {compose.onNodeWithText(place.name).assertExists()}.isSuccess}
        compose.onNodeWithText("Usar este lugar guardado").performClick()
        assertEquals(place,chosen);assertEquals(place,graph.history.dao.places().single().value)
    }
    private fun capture(name:String) {
        if(InstrumentationRegistry.getArguments().getString("captureVisitLocalization")!="true") return
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-visit-localization-$name-20261029.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
    @Test fun largeSpanishReusableControlsKeepTheirValuesAndSaveActionVisible()=runBlocking {
        var writes=0
        compose.setContent {MaterialTheme {VisitEditor(graph,null,{}, {_,_,_,_->writes++},null)}}
        compose.onNodeWithTag("visit-name").performTextInput("Lugar reutilizable QA")
        compose.onNodeWithTag("visit-latitude").performScrollTo().performTextInput("48,8584")
        compose.onNodeWithTag("visit-longitude").performScrollTo().performTextInput("2,2945")
        compose.onNodeWithText("Llegada").performScrollTo().assertIsDisplayed()
        capture("dates-large")
        compose.onNodeWithContentDescription("Guardar visita como lugar reutilizable").performScrollTo().performClick()
        compose.waitUntil(10000) {runCatching {compose.onNodeWithText("Crear lugar guardado: Lugar reutilizable QA").assertExists()}.isSuccess}
        compose.onNodeWithText("Radio de coincidencia").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("150 m").performScrollTo().assertIsDisplayed()
        val label=compose.onNodeWithText("Radio de coincidencia").fetchSemanticsNode().boundsInRoot
        val value=compose.onNodeWithText("150 m").fetchSemanticsNode().boundsInRoot
        if(compose.activity.resources.configuration.fontScale>=1.3f) assertTrue(value.top>=label.bottom)
        else assertTrue(label.right<=value.left+.5f)
        compose.onNodeWithText("150 m").performClick()
        compose.onNode(hasText("250 m") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("250 m").assertIsDisplayed()
        compose.onNodeWithText("Guardar").assertIsDisplayed().assertIsEnabled()
        capture("reuse-large")
        assertEquals(0,writes);assertEquals(0L,graph.history.dao.totalVisitCount());assertTrue(graph.history.dao.places().isEmpty())
    }
}
