package com.bontecou.whereday

import android.app.Activity
import android.app.LocaleManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.LocaleList
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.util.UUID

@SdkSuppress(minSdkVersion=33)
class SavedPlacesLocalizationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private var oldLocales:LocaleList?=null
    private val documents=mutableListOf<Uri>()
    private var document:Uri?=null
    private val visit=Visit("saved-library-preserved",Coordinate(0.0,0.0),Instant.EPOCH,name="Unchanged journal QA")
    private val point=TrackPoint("saved-library-point",Coordinate(0.0,0.0),Instant.EPOCH,5.0)
    private val registry=object:ActivityResultRegistry() {
        override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) {
            check(contract is ActivityResultContracts.OpenDocument)
            check((input as Array<*>).contains("text/*"))
            dispatchResult(requestCode,Activity.RESULT_OK,Intent().setData(checkNotNull(document)))
        }
    }
    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target)
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        graph.history.dao.put(VisitRow(visit));graph.history.dao.put(PointRow(point,null))
        Unit
    }
    @After fun cleanup()=runBlocking {
        oldLocales?.let {applyQaAppLocales(compose,it)}
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        for(uri in documents) target.contentResolver.delete(uri,null,null)
        if(::graph.isInitialized) graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        Unit
    }
    private fun screen() {
        val places=graph.history.dao.observePlaces().map {rows->rows.map {it.value}}
        val owner=object:ActivityResultRegistryOwner {override val activityResultRegistry=registry}
        compose.setContent {MaterialTheme {CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
            val current by places.collectAsState(initial=emptyList())
            Surface(Modifier.fillMaxSize().systemBarsPadding()) {SavedPlacesScreen(graph,current,{})}
        }}}
    }
    private fun csv(text:String) {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val values=ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,"whereday-saved-csv-${UUID.randomUUID()}-20261029.csv")
            put(MediaStore.Downloads.MIME_TYPE,"text/csv");put(MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        documents+=uri;document=uri
        target.contentResolver.openOutputStream(uri)!!.use {it.write(text.toByteArray())}
    }
    private fun find(text:String) {
        compose.onNodeWithTag("saved-places-content").performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }
    private fun waitText(text:String) {
        compose.waitUntil(5000) {runCatching {find(text)}.isSuccess}
    }
    private fun click(text:String) {find(text);compose.onNodeWithText(text).performClick()}
    private fun plan(text:String) {waitText(text);compose.onNodeWithTag("saved-csv-plan").assertTextEquals(text)}
    private suspend fun unchangedJournal() {
        assertEquals(visit,graph.history.dao.visit(visit.id)!!.value)
        assertEquals(point,graph.history.dao.point(point.id)!!.value)
        assertEquals(1L,graph.history.dao.totalPointCount());assertEquals(1L,graph.history.dao.totalVisitCount())
    }
    @Test fun commaDecimalEditorSearchAndConfirmedDeletionPreserveTheJournal()=runBlocking {
        screen();compose.onNodeWithTag("saved-places-add").performClick()
        compose.onNodeWithText("Guardar").performClick()
        compose.onNodeWithTag("saved-editor-error").performScrollTo().assertTextEquals("Introduce un nombre, coordenadas válidas y un radio de entre 25 y 10 000 metros.")
        compose.onNodeWithTag("saved-name").performScrollTo().performTextInput("CAFÉ QA")
        compose.onNodeWithTag("saved-address").performTextInput("Original address QA")
        compose.onNodeWithTag("saved-latitude").performScrollTo().performTextInput("18,25")
        compose.onNodeWithTag("saved-longitude").performScrollTo().performTextInput("-66,5")
        compose.onNodeWithTag("saved-radius").performScrollTo().performTextReplacement("250,5")
        compose.onNodeWithText("Guardar").performClick()
        waitText("1 de 1 lugar guardado")
        val saved=graph.history.dao.places().single().value
        assertEquals("CAFÉ QA",saved.name);assertEquals(Coordinate(18.25,-66.5),saved.coordinate);assertEquals(250.5,saved.radius,0.0)
        waitText("Radio de coincidencia: 250,5 m")
        compose.onNodeWithTag("saved-places-search").performScrollTo().performTextInput("cafe")
        waitText("CAFÉ QA");click("Borrar búsqueda");click("CAFÉ QA")
        // A place opens its detail first; Edit opens the editor.
        compose.onNodeWithTag("place-detail-edit").performClick()
        compose.onNodeWithTag("saved-radius").performScrollTo().assertTextContains("250.5")
        compose.onNodeWithText("Eliminar lugar guardado").performScrollTo().performClick()
        compose.onNodeWithTag("saved-delete-cancel").performClick()
        assertEquals(saved,graph.history.dao.place(saved.id)!!.value)
        compose.onNodeWithText("Eliminar lugar guardado").performScrollTo().performClick()
        compose.onNodeWithTag("saved-delete-confirm").performClick()
        waitText("Aún no hay lugares guardados. Añade un lugar o importa un archivo CSV.")
        assertTrue(graph.history.dao.places().isEmpty());unchangedJournal()
    }
    @Test fun csvReviewAppliesBothPoliciesWithLocalizedCountsAndInvalidRows()=runBlocking {
        val managed=SavedPlace("managed","Café QA",Coordinate(18.0001,-66.0),"Managed address",333.0)
        graph.history.dao.put(PlaceRow(managed))
        csv("name,latitude,longitude,address,radius_meters\nCAFÉ QA,18,-66,Incoming address,250\nNew QA,19,-67,New address,150\nInvalid QA,91,-66,,150\n")
        screen();compose.onNodeWithTag("saved-places-import").performClick()
        plan("1 añadido · 0 actualizados · 1 omitido")
        find("2 filas válidas · 1 fila no válida. Las filas no válidas no se importarán.")
        find("La latitud debe ser un número de entre -90 y 90.")
        click("Importar lugares válidos")
        compose.onNodeWithText("¿Importar lugares guardados?").assertIsDisplayed()
        if(InstrumentationRegistry.getArguments().getString("captureSavedPlaces")=="true") capture()
        compose.onNodeWithText("Cancelar").performClick()
        assertEquals(listOf(managed),graph.history.dao.places().map {it.value})
        click("Importar lugares válidos");compose.onNodeWithText("Importar lugares").performClick()
        waitText("Añadidos: 1. Actualizados: 0. Omitidos: 1.")
        assertEquals(managed,graph.history.dao.place(managed.id)!!.value)
        assertEquals(2,graph.history.dao.places().size)
        compose.onNodeWithTag("saved-places-import").performClick();plan("0 añadidos · 0 actualizados · 2 omitidos")
        click("Conservar lugares existentes")
        compose.onNodeWithText("Actualizar lugares coincidentes").performClick()
        plan("0 añadidos · 2 actualizados · 0 omitidos")
        click("Importar lugares válidos");compose.onNodeWithText("Importar lugares").performClick()
        waitText("Añadidos: 0. Actualizados: 2. Omitidos: 0.")
        assertEquals(managed.copy(name="CAFÉ QA",coordinate=Coordinate(18.0,-66.0),address="Incoming address",radius=250.0),graph.history.dao.place(managed.id)!!.value)
        assertEquals(2,graph.history.dao.places().size);unchangedJournal()
    }
    @Test fun aChangedLibraryRejectsTheConfirmedImportWithoutPartialWrites()=runBlocking {
        csv("name,latitude,longitude\nIncoming QA,18,-66\n");screen();compose.onNodeWithTag("saved-places-import").performClick()
        plan("1 añadido · 0 actualizados · 0 omitidos");click("Importar lugares válidos")
        val concurrent=SavedPlace("concurrent","Concurrent QA",Coordinate(20.0,-60.0));graph.history.dao.put(PlaceRow(concurrent))
        compose.onNodeWithText("Importar lugares").performClick()
        waitText("Los lugares guardados han cambiado. Revisa la lista actualizada antes de guardar o importar.")
        assertEquals(listOf(concurrent),graph.history.dao.places().map {it.value});unchangedJournal()
    }
    @Test fun missingHeaderDiagnosticKeepsProtocolKeysAndWritesNothing()=runBlocking {
        csv("name,latitude\nPlace,18\n");screen();compose.onNodeWithTag("saved-places-import").performClick()
        waitText("Faltan columnas CSV obligatorias: longitude.")
        assertTrue(graph.history.dao.places().isEmpty());unchangedJournal()
    }
    @Test fun turkishChooserCaseHandlingPreservesTheOriginalName()=runBlocking {
        applyQaAppLocales(compose,LocaleList.forLanguageTags("tr"))
        val place=SavedPlace("turkish","İSTANBUL QA",Coordinate(18.0,-66.0),"Original address")
        graph.history.dao.put(PlaceRow(place));graph.history.dao.put(PlaceRow(place.copy(id="other",name="Other QA")))
        var selected:SavedPlace?=null
        compose.setContent {MaterialTheme {SavedPlaceChooser(graph,{}, {selected=it})}}
        compose.onNodeWithTag("saved-chooser-search").performTextInput("istanbul")
        compose.waitUntil(5000) {compose.onAllNodesWithText(place.name).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Other QA").assertDoesNotExist()
        compose.onNodeWithText(place.name).performClick()
        compose.runOnIdle {assertEquals(place,selected)}
        assertEquals(place,graph.history.dao.place(place.id)!!.value);unchangedJournal()
    }
    private fun capture() {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Dialog window animation runs outside the Compose test clock.
        android.os.SystemClock.sleep(600)
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        val size=if(target.resources.configuration.fontScale>=1.8f) "large" else "normal"
        val values=ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,"whereday-saved-places-$size-20261029.png")
            put(MediaStore.Downloads.MIME_TYPE,"image/png");put(MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
}
