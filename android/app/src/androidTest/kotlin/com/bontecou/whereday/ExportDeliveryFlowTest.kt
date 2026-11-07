package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real delivery engine/controller and private synthetic content URIs; never an owner install. */
@SdkSuppress(minSdkVersion=33)
class ExportDeliveryFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var files:ExportFiles
    private lateinit var picker:ExportPickerController
    private var oldLocales:LocaleList?=null
    private val visible=mutableStateOf(true)
    private val calls=AtomicInteger()
    private val canceled=AtomicInteger()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun setup()=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target)
        withTimeout(5000) {graph.exportPicker.state.first {it.loaded && !it.busy}}
        graph.exports.clearCache()
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        graph.history.dao.put(StayRow(CountryStay("delivery-stay","PR",LocalDate.parse("2024-01-01"),LocalDate.parse("2024-01-02"))))
        // This injected authorization belongs only to the fixture. Production keeps graph.purchases::requireAccess.
        files=ExportFiles(target,graph.database) {}
        picker=ExportPickerController(files)
        awaitPicker {it.loaded && !it.busy}
        Unit
    }
    @After fun cleanup()=runBlocking {
        try {
            if(::picker.isInitialized) picker.close()
            if(::files.isInitialized) files.clearCache()
            if(::graph.isInitialized) graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        } finally {oldLocales?.let {applyQaAppLocales(compose,it)}}
        Unit
    }
    private suspend fun awaitPicker(predicate:(ExportPickerUiState)->Boolean)=withTimeout(5000) {picker.state.first(predicate)}
    private fun show(prepare:suspend ()->PreparedExport={files.prepareCountryDays(CountryDayBounds())}) {
        compose.setContent {MaterialTheme {Surface(Modifier.systemBarsPadding()) {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if(visible.value) HistoryFileActions(graph,"Exportar CSV por país",true,files,picker,prepare)
        }}}}
        compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("history-export-open").assertIsEnabled()}.isSuccess || picker.state.value.request?.active==true}
    }
    private fun action(tag:String) {compose.onNodeWithTag(tag).performScrollTo().performClick()}
    private fun open()=action("history-export-open")
    private fun destination():Pair<File,Uri> {
        val file=File(target.cacheDir,"exports/ui-destination-${UUID.randomUUID()}/result.csv")
        check(file.parentFile!!.mkdirs());check(file.createNewFile())
        return file to FileProvider.getUriForFile(target,"${target.packageName}.exports",file)
    }
    @Test fun disclosureAndDestinationsAreLocalizedAndOpeningDoesNotPrepareAnything() {
        show {calls.incrementAndGet();files.prepareCountryDays(CountryDayBounds())};open()
        compose.onNodeWithText("Compartir archivo").assertExists()
        compose.onNodeWithTag("history-export-folder").assertIsNotEnabled()
        compose.onNodeWithText("Las exportaciones completas de archivos están incluidas en todos los planes de Whereday. Elige dónde compartir o guardar este archivo.").assertExists()
        assertEquals(0,calls.get());assertEquals(1,runBlocking {graph.history.dao.stays().size})
        if(InstrumentationRegistry.getArguments().getString("captureExportDelivery")=="true") capture()
        compose.onNodeWithText("Listo").performScrollTo().performClick()
        assertEquals(0,calls.get())
    }
    @Test fun heldDoubleActionStartsOnceAndExplicitCancelStopsPreparation() {
        show {calls.incrementAndGet();try {awaitCancellation()} finally {canceled.incrementAndGet()}};open()
        val click=compose.onNodeWithTag("history-export-save").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {click();click()}
        compose.waitUntil(5000) {calls.get()==1}
        action("history-export-cancel-preparation")
        compose.waitUntil(5000) {canceled.get()==1}
        assertEquals(1,calls.get());assertNull(runBlocking {files.pickerState()})
    }
    @Test fun actualPurchaseGateHasLocalizedFailureAndDoesNotStageAFile() {
        show {graph.exports.prepareCountryDays(CountryDayBounds())};open();action("history-export-save")
        compose.onNodeWithTag("history-export-sheet-notice").assertTextEquals("Para exportar se necesita un plan de Whereday activo: Semanal, Anual o De por vida. Las importaciones y las vistas previas de exportación son gratuitas.")
        assertNull(runBlocking {graph.exports.pickerState()})
        assertTrue(File(target.cacheDir,"exports").listFiles().orEmpty().isEmpty())
        assertEquals(1,runBlocking {graph.history.dao.stays().size})
    }
    @Test fun preparationFailureDoesNotExposeExceptionText() {
        show {throw IllegalStateException("private-provider://secret-key")};open();action("history-export-share")
        compose.onNodeWithTag("history-export-sheet-notice").assertTextEquals("No se ha podido preparar la exportación. Comprueba el almacenamiento y los ajustes de exportación e inténtalo de nuevo.")
        compose.onNode(hasText("secret-key",substring=true)).assertDoesNotExist()
    }
    @Test fun preparedRequestCanBeCanceledAndLateCallbackCannotWrite()=runBlocking {
        val prepared=files.prepareCountryDays(CountryDayBounds());val request=files.beginPicker(prepared)
        picker.refresh();awaitPicker {it.request?.id==request.id && !it.busy};show()
        compose.onNodeWithTag("history-export-pending").assertTextEquals("Guardado pendiente: iso-me-country-days.csv")
        action("history-export-cancel-pending")
        awaitPicker {it.request?.phase==ExportPickerPhase.canceled && !it.busy}
        compose.onNodeWithTag("history-export-picker-notice").assertTextEquals("iso-me-country-days.csv · Exportación cancelada. Los archivos ya escritos permanecen en el destino.")
        val (file,uri)=destination();file.writeText("preserve synthetic destination")
        picker.result(request.id,uri,0).join();awaitPicker {it.notice==ExportDeliveryNotice.canceled && !it.busy}
        compose.waitForIdle();assertEquals("preserve synthetic destination",file.readText())
        assertFalse(prepared.files.single().file.exists())
    }
    @Test fun completedContentWriteIsVerifiedAndShownInSpanish()=runBlocking {
        val prepared=files.prepareCountryDays(CountryDayBounds());val bytes=prepared.files.single().file.readBytes();val request=files.beginPicker(prepared)
        picker.refresh();awaitPicker {it.request?.id==request.id && !it.busy};show()
        compose.runOnIdle {visible.value=false}
        val (file,uri)=destination();picker.result(request.id,uri,0).join()
        awaitPicker {it.notice==ExportDeliveryNotice.saved && !it.busy}
        compose.runOnIdle {visible.value=true}
        compose.onNodeWithTag("history-export-picker-notice").assertTextEquals("iso-me-country-days.csv · Exportación guardada y verificada.")
        assertArrayEquals(bytes,file.readBytes());assertFalse(prepared.files.single().file.exists())
        picker.result(request.id,uri,0).join();awaitPicker {it.notice==ExportDeliveryNotice.saved && !it.busy}
        assertArrayEquals(bytes,file.readBytes())
    }
    @Test fun missingProviderKeepsRetryRequestAndHidesItsUri()=runBlocking {
        val request=files.beginPicker(files.prepareCountryDays(CountryDayBounds()))
        picker.refresh();awaitPicker {it.request?.id==request.id && !it.busy};show()
        picker.result(request.id,Uri.parse("content://missing.qa.export/private-provider-token"),0).join()
        awaitPicker {it.notice==ExportDeliveryNotice.failed && !it.busy}
        compose.onNodeWithTag("history-export-picker-notice").assertTextEquals("iso-me-country-days.csv · No se ha podido terminar el guardado. Reanuda o elige otro destino; comprueba si hay un archivo incompleto en el destino anterior.")
        compose.onNode(hasText("private-provider-token",substring=true)).assertDoesNotExist()
        compose.onNodeWithTag("history-export-resume").assertExists()
        assertEquals(ExportPickerPhase.failed,files.pickerState()?.phase)
    }
    @Test fun backgroundStopsPreparationAndHeldActionCannotRestartIt() {
        show {calls.incrementAndGet();try {awaitCancellation()} finally {canceled.incrementAndGet()}};open()
        val click=compose.onNodeWithTag("history-export-save").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        action("history-export-save");compose.waitUntil(5000) {calls.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5000) {canceled.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {click()};compose.waitForIdle()
        assertEquals(1,calls.get());compose.onNodeWithTag("history-export-open").assertIsEnabled()
    }
    @Test fun disposalStopsPreparation() {
        show {calls.incrementAndGet();try {awaitCancellation()} finally {canceled.incrementAndGet()}};open();action("history-export-save")
        compose.waitUntil(5000) {calls.get()==1};compose.runOnIdle {visible.value=false}
        compose.waitUntil(5000) {canceled.get()==1};assertNull(runBlocking {files.pickerState()})
    }
    @Test fun countsAndRestorationFailureUseLocaleResources() {
        compose.setContent {MaterialTheme {Column {
            Text(exportDeliveryText(ExportDeliveryNotice.saved,1))
            Text(exportDeliveryText(ExportDeliveryNotice.saved,1001))
            Text(exportSettingsText(ExportSettingsFailure.restore))
        }}}
        compose.onNodeWithText("1 archivo de exportación guardado y verificado.").assertExists()
        compose.onNodeWithText("1.001 archivos de exportación guardados y verificados.").assertExists()
        compose.onNodeWithText("No se han podido recuperar los ajustes de exportación guardados. Restablécelos y revisa los filtros antes de exportar.").assertExists()
    }
    @Test fun mainExportDestinationsWrapWithinTheAvailableWidth() {
        compose.setContent {MaterialTheme {Surface(Modifier.systemBarsPadding()) {ExportScreen(graph)}}}
        val share=compose.onNodeWithText("Compartir exportación")
        val save=compose.onNodeWithText("Guardar exportación como…")
        share.performScrollTo();save.performScrollTo()
        share.assertIsDisplayed();save.assertIsDisplayed()
        val screen=compose.onNodeWithTag("export-screen").fetchSemanticsNode().boundsInRoot
        for(node in listOf(share,save)) {
            val bounds=node.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left>=screen.left && bounds.right<=screen.right)
        }
    }
    private fun capture() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        compose.waitForIdle();instrumentation.waitForIdleSync()
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-export-delivery-large-20261029.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
}
