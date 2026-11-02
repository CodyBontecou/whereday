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
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.billing.*
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.time.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@SdkSuppress(minSdkVersion=33)
class ExportOptionsFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var original:ExportSelection
    private var oldLocales:LocaleList?=null
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val at=Instant.parse("2026-10-28T10:00:00Z")
    private val visible=mutableStateOf(true)
    private val releases=mutableListOf<CompletableDeferred<String>>()
    @Before fun setup():Unit=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        check(!PurchaseConfiguration().configured)
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target)
        original=withTimeout(5000) {graph.exportSettings.state.first {it.ready && !it.saving}}.selection
        setSelection(ExportSelection(options=ExportOptions(zone=ZoneOffset.UTC)))
        graph.purchases.refresh();withTimeout(5000) {graph.purchases.state.first {it.access==AccessState.unavailable && !it.busy}}
        withTimeout(5000) {graph.exportPicker.state.first {it.loaded && !it.busy}};graph.exports.clearCache()
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        graph.history.dao.put(SessionRow(RecordingSession("options-session",at,at.plusSeconds(7200),"Salida QA")))
        graph.history.dao.put(VisitRow(Visit("closed-visit",Coordinate(18.0,-66.0),at,at.plusSeconds(1200),name="Visita QA 日本語",notes="Nota conservada")))
        graph.history.dao.put(VisitRow(Visit("open-visit",Coordinate(18.1,-66.1),at.plusSeconds(3600),name="Visita abierta QA")))
        graph.history.dao.putPoints(listOf(
            PointRow(TrackPoint("good-point",Coordinate(18.0,-66.0),at,5.0),"options-session"),
            PointRow(TrackPoint("outlier-point",Coordinate(18.1,-66.1),at.plusSeconds(3600),80.0,outlier=true),"options-session")))
    }
    @After fun cleanup():Unit=runBlocking {
        releases.forEach {it.complete("released during fixture cleanup")}
        try {
            if(::graph.isInitialized) {
                if(::original.isInitialized) setSelection(original)
                graph.exports.clearCache()
                graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
            }
        } finally {oldLocales?.let {applyQaAppLocales(compose,it)}}
    }
    private suspend fun setSelection(value:ExportSelection) {
        graph.exportSettings.update(value)
        withTimeout(5000) {graph.exportSettings.state.first {it.ready && !it.saving && it.failure==null && it.selection==value}}
    }
    private fun awaitSelection(predicate:(ExportSelection)->Boolean) {
        compose.waitUntil(5000) {graph.exportSettings.state.value.let {it.ready && !it.saving && it.failure==null && predicate(it.selection)}}
    }
    private fun show(render:suspend (ExportFormat,ExportOptions,Boolean,InferenceRules?)->String={f,o,outings,inference->graph.exports.preview(f,o,outings,inference)}) {
        compose.setContent {MaterialTheme {Surface(Modifier.systemBarsPadding()) {if(visible.value) ExportScreen(graph,render)}}}
        compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("export-preview-start").assertIsEnabled()}.isSuccess}
    }
    private fun action(tag:String) {compose.onNodeWithTag(tag).performScrollTo().performClick()}
    private fun choose(current:String,next:String) {
        compose.onNodeWithText(current).performScrollTo().performClick()
        compose.onNode(hasText(next) and hasAnyAncestor(isPopup())).performClick()
    }
    private fun preview():String {
        action("export-preview-start")
        compose.waitUntil(10000) {compose.onAllNodesWithTag("export-preview-result").fetchSemanticsNodes().isNotEmpty()}
        return text("export-preview-result")
    }
    private fun text(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") {it.text}
    @Test fun pointCountsAndUnavailablePurchaseAreLocalizedWithoutChangingStoredData():Unit=runBlocking {
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        graph.history.dao.put(PointRow(TrackPoint("one",Coordinate(0.0,0.0),at,5.0),null));show()
        compose.waitUntil(5000) {text("export-history-count")=="1 punto de ruta guardado en este teléfono."}
        compose.onNodeWithTag("export-access-notice").assertTextEquals("Las compras no están disponibles en esta versión. La grabación, las importaciones y las vistas previas siguen siendo gratuitas.")
        graph.history.dao.putPoints((0 until 1000).map {PointRow(TrackPoint("count-$it",Coordinate(0.0,0.0),at.plusSeconds(it.toLong()+1),5.0),null)})
        compose.waitUntil(5000) {text("export-history-count")=="1.001 puntos de ruta guardados en este teléfono."}
        assertEquals(1001L,graph.history.dao.totalPointCount());assertEquals(AccessState.unavailable,graph.purchases.state.value.access)
        if(InstrumentationRegistry.getArguments().getString("captureExportOptions")=="true") capture()
    }
    @Test fun allEightRealFreePreviewsKeepWireFormatAndStoredSettings() {
        val rendered=CopyOnWriteArrayList<ExportFormat>();show {f,o,outings,inference->rendered.add(f);graph.exports.preview(f,o,outings,inference)}
        var current=ExportFormat.json
        val markers=mapOf(ExportFormat.json to "\"visits\"",ExportFormat.csv to "arrived_at,departed_at",ExportFormat.markdown to "#",ExportFormat.geojson to "FeatureCollection",ExportFormat.gpx to "<gpx",ExportFormat.kml to "<kml",ExportFormat.owntracks to "_type",ExportFormat.overland to "locations")
        for(format in ExportFormat.entries) {
            if(format!=current) choose(current.name.replaceFirstChar {it.uppercase()},format.name.replaceFirstChar {it.uppercase()})
            awaitSelection {it.format==format};current=format
            val value=preview();assertTrue("Missing ${format.name} syntax",value.contains(markers.getValue(format)))
            assertEquals(format,ExportSelectionCodec.decode(runBlocking {graph.preferences.settings.first().exportConfiguration}).format)
            assertEquals(AccessState.unavailable,graph.purchases.state.value.access)
        }
        assertEquals(ExportFormat.entries,rendered.toList())
        assertEquals(2L,runBlocking {graph.history.dao.totalPointCount()})
    }
    @Test fun dataChoicesKeepStableKeysAndRetainHiddenFieldPreferences() {
        show();action("export-advanced");action("export-point-altitude");awaitSelection {!it.options.pointAltitude}
        choose("Todo","Visitas");awaitSelection {it.options.kind==DataKind.visits && !it.outings}
        compose.onNodeWithTag("export-point-altitude").assertDoesNotExist()
        choose("Visitas","Salidas");awaitSelection {it.outings && it.options.kind==DataKind.visits}
        compose.onNodeWithTag("export-point-altitude").assertIsOff()
        compose.onNodeWithTag("export-exclude-outliers").assertDoesNotExist()
        choose("Salidas","Puntos");awaitSelection {!it.outings && it.options.kind==DataKind.points && !it.options.pointAltitude}
        compose.onNodeWithTag("export-visit-notes").assertDoesNotExist()
        val decoded=ExportSelectionCodec.decode(runBlocking {graph.preferences.settings.first().exportConfiguration})
        assertEquals(DataKind.points,decoded.options.kind);assertFalse(decoded.options.pointAltitude)
    }
    @Test fun filenamePresetLabelsDoNotTranslatePatternsOrUserTemplates() {
        val calls=AtomicInteger();show {f,o,outings,inference->calls.incrementAndGet();graph.exports.preview(f,o,outings,inference)}
        val before=preview();action("export-advanced");choose("Legible","Carpetas por fecha")
        awaitSelection {it.filename==FilenamePlanner.datedFoldersPattern}
        if(target.resources.configuration.fontScale>=1.3f) {
            val value=compose.onNodeWithText("Carpetas por fecha").performScrollTo().assertIsDisplayed()
            val label=compose.onNodeWithText("Nombre de archivo predefinido").assertIsDisplayed()
            assertTrue("Large-text selectors must give the label full width",value.fetchSemanticsNode().boundsInRoot.top>=label.fetchSemanticsNode().boundsInRoot.bottom)
        }
        if(InstrumentationRegistry.getArguments().getString("captureExportOptions")=="true") capture("-filename")
        val custom="Mis rutas 日本語/{year}/{date} - {type}"
        compose.onNodeWithTag("export-filename").performScrollTo().performTextReplacement(custom)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        awaitSelection {it.filename==custom}
        compose.onNodeWithText("Personalizado").assertExists()
        action("export-split");awaitSelection {it.splitDays}
        action("export-extra-gpx");awaitSelection {ExportFormat.gpx in it.additionalFormats}
        val decoded=ExportSelectionCodec.decode(runBlocking {graph.preferences.settings.first().exportConfiguration})
        assertEquals(custom,decoded.filename);assertTrue(decoded.splitDays);assertEquals(setOf(ExportFormat.gpx),decoded.additionalFormats)
        assertEquals(before,text("export-preview-result"));assertEquals(1,calls.get())
    }
    @Test fun dateApplicationPreservesDstCivilBoundsAndResetKeepsOtherFilters() {
        val zone=ZoneId.of("America/New_York")
        var options by mutableStateOf(ExportOptions(from=Instant.parse("2024-03-10T05:00:00Z"),until=Instant.parse("2024-03-11T03:59:59.999999999Z"),zone=zone,pointAltitude=false,completedOnly=true))
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {ExportFilters(options,{options=it},options.kind)}}}
        compose.onNodeWithTag("export-date-range").assertTextEquals("10 mar 2024 – 10 mar 2024")
        action("export-date-range");compose.onNodeWithText("Aplicar").performClick()
        assertEquals(Instant.parse("2024-03-10T05:00:00Z"),options.from);assertEquals(Instant.parse("2024-03-11T03:59:59.999999999Z"),options.until)
        assertFalse(options.pointAltitude);assertTrue(options.completedOnly)
        action("export-reset-dates");assertEquals(ExportOptions.allDatesStart,options.from);assertEquals(Instant.MAX,options.until)
        assertFalse(options.pointAltitude);assertTrue(options.completedOnly)
    }
    @Test fun fractionalAccuracyIsDisplayedWithoutTruncationAndTranslatedAnyIsNull() {
        var options by mutableStateOf(ExportOptions(maximumAccuracy=37.5,minimumVisitMinutes=15,pointAltitude=false))
        compose.setContent {MaterialTheme {Surface(Modifier.systemBarsPadding()) {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {ExportFilters(options,{options=it},options.kind)}}}}
        compose.onNodeWithText("37,5").performScrollTo().assertIsDisplayed()
        assertEquals(37.5,options.maximumAccuracy!!,0.0)
        if(InstrumentationRegistry.getArguments().getString("captureExportOptions")=="true") {compose.onNodeWithText("Precisión máxima (m)").performScrollTo().assertIsDisplayed();capture("-filters")}
        choose("37,5","50");assertEquals(50.0,options.maximumAccuracy!!,0.0)
        choose("50","Cualquiera");assertNull(options.maximumAccuracy)
        choose("15","30");assertEquals(30L,options.minimumVisitMinutes);assertFalse(options.pointAltitude)
    }
    @Test fun nativeTimePickerReturnsAnOvernightWindowWithoutChangingDates() {
        var options by mutableStateOf(ExportOptions(zone=ZoneOffset.UTC))
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {ExportFilters(options,{options=it},options.kind)}}}
        action("export-time-enabled");assertEquals(LocalTime.of(8,0),options.startTime)
        fun time(tag:String,hour:Int,minute:Int) {
            action(tag)
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.TimePicker::class.java)).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(object:androidx.test.espresso.ViewAction {
                override fun getConstraints():org.hamcrest.Matcher<android.view.View> = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.TimePicker::class.java)
                override fun getDescription()="Set synthetic export time"
                override fun perform(controller:androidx.test.espresso.UiController,view:android.view.View) {(view as android.widget.TimePicker).apply {this.hour=hour;this.minute=minute};controller.loopMainThreadUntilIdle()}
            })
            compose.onNodeWithTag("$tag-confirm").performClick()
        }
        time("export-time-from",22,15);time("export-time-until",6,45)
        assertEquals(LocalTime.of(22,15),options.startTime);assertEquals(LocalTime.of(6,45),options.endTime)
        assertTrue(options.includes(Instant.parse("2026-10-28T23:00:00Z")));assertTrue(options.includes(Instant.parse("2026-10-28T05:00:00Z")));assertFalse(options.includes(Instant.parse("2026-10-28T12:00:00Z")))
        assertEquals(ExportOptions.allDatesStart,options.from);assertEquals(Instant.MAX,options.until)
        action("export-time-enabled");assertNull(options.startTime);assertNull(options.endTime)
    }
    @Test fun changedOptionsDiscardEvenANonCooperativeLatePreviewWithoutAutoRetry():Unit=runBlocking {
        val calls=AtomicInteger();val release=CompletableDeferred<String>();releases.add(release)
        show {f,o,outings,inference->if(calls.incrementAndGet()==1) withContext(NonCancellable) {release.await()} else graph.exports.preview(f,o,outings,inference)}
        action("export-preview-start");compose.waitUntil(5000) {calls.get()==1}
        val next=graph.exportSettings.state.value.selection.copy(options=ExportOptions(kind=DataKind.points,zone=ZoneOffset.UTC))
        setSelection(next);compose.waitForIdle();release.complete("obsolete preview must never appear")
        compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("export-preview-start").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("export-preview-result").assertDoesNotExist();assertEquals(1,calls.get())
        assertFalse(preview().contains("obsolete preview"));assertEquals(2,calls.get())
    }
    @Test fun changingAndRestoringFiltersDoesNotBringBackAnOldPreview() {
        val calls=AtomicInteger();show {f,o,outings,inference->calls.incrementAndGet();graph.exports.preview(f,o,outings,inference)}
        preview();choose("Todo","Puntos");awaitSelection {it.options.kind==DataKind.points}
        compose.onNodeWithTag("export-preview-result").assertDoesNotExist()
        choose("Puntos","Todo");awaitSelection {it.options.kind==DataKind.all}
        compose.onNodeWithTag("export-preview-result").assertDoesNotExist();assertEquals(1,calls.get())
    }
    @Test fun heldButtonActionUsesTheCurrentSelection():Unit=runBlocking {
        val calls=CopyOnWriteArrayList<ExportFormat>();show {f,_,_,_->calls.add(f);"current format: ${f.name}"}
        val click=compose.onNodeWithTag("export-preview-start").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        setSelection(graph.exportSettings.state.value.selection.copy(format=ExportFormat.csv));compose.waitForIdle()
        compose.runOnIdle {click()};compose.waitForIdle();assertEquals(listOf(ExportFormat.csv),calls.toList())
        compose.onNodeWithTag("export-preview-result").assertTextEquals("current format: csv")
    }
    @Test fun heldDoubleActionStartsOnceAndExplicitCancelStopsThePreview() {
        val calls=AtomicInteger();val canceled=AtomicInteger()
        show {_,_,_,_->calls.incrementAndGet();try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        val click=compose.onNodeWithTag("export-preview-start").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {click();click()};compose.waitUntil(5000) {calls.get()==1}
        action("export-cancel-operation");compose.waitUntil(5000) {canceled.get()==1}
        compose.onNodeWithTag("export-preview-result").assertDoesNotExist();assertEquals(1,calls.get())
    }
    @Test fun backgroundCancelsPreviewAndReturningDoesNotRestartIt() {
        val calls=AtomicInteger();val canceled=AtomicInteger()
        show {_,_,_,_->calls.incrementAndGet();try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        action("export-preview-start");compose.waitUntil(5000) {calls.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);compose.waitUntil(5000) {canceled.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("export-preview-start").assertIsEnabled();assertEquals(1,calls.get());compose.onNodeWithTag("export-preview-result").assertDoesNotExist()
    }
    @Test fun previewFailureIsLocalizedAndRetryUsesTheRealFreeRenderer() {
        val calls=AtomicInteger();show {f,o,outings,inference->if(calls.incrementAndGet()==1) throw IOException("secret-file:///private-history") else graph.exports.preview(f,o,outings,inference)}
        action("export-preview-start");compose.waitUntil(5000) {compose.onAllNodesWithTag("export-preview-error").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("export-preview-error").assertTextEquals("No se ha podido generar la vista previa. Inténtalo de nuevo.")
        compose.onNode(hasText("private-history",substring=true)).assertDoesNotExist()
        assertTrue(preview().contains("Visita QA 日本語"));assertEquals(2,calls.get())
    }
    @Test fun lifetimeCardPreservesStorePriceAndDisablesPendingCheckoutAndBusyActions() {
        var purchase by mutableStateOf(BillingState(AccessState.pending,LifetimeProduct("synthetic","12,34 €"),notice=BillingNotice.pendingPayment))
        var checkout by mutableStateOf(false);var buys=0;var restores=0
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {LifetimeExportAccess(purchase,checkout,{buys++},{restores++})}}}
        compose.onNodeWithTag("export-access-buy").assertTextEquals("Comprar acceso de por vida · 12,34 €").assertIsNotEnabled()
        action("export-access-restore");assertEquals(1,restores);assertEquals(0,buys)
        compose.runOnIdle {purchase=purchase.copy(access=AccessState.free,notice=null)}
        action("export-access-buy");assertEquals(1,buys)
        compose.runOnIdle {checkout=true};compose.onNodeWithTag("export-access-buy").assertIsNotEnabled();compose.onNodeWithTag("export-access-restore").assertIsNotEnabled()
        compose.runOnIdle {checkout=false;purchase=purchase.copy(busy=true)};compose.onNodeWithTag("export-access-buy").assertIsNotEnabled()
        compose.runOnIdle {purchase=purchase.copy(busy=false,access=AccessState.active,notice=BillingNotice.cached)}
        compose.onNodeWithTag("export-access-buy").assertDoesNotExist();compose.onNodeWithText("Compra verificada").assertExists()
    }
    @Test fun billingNoticeUsesCurrentUiLocaleWithoutChangingItsIdentity() {
        val es=compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("es"))})
        val en=compose.activity.createConfigurationContext(android.content.res.Configuration(compose.activity.resources.configuration).apply {setLocales(LocaleList.forLanguageTags("en-US"))})
        var spanish by mutableStateOf(true)
        compose.setContent {val context=if(spanish) es else en;CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides context.resources.configuration) {Text(billingNoticeText(BillingNotice.cached))}}
        compose.onNodeWithText("Se está usando un acceso verificado anteriormente. Conéctate para actualizar el estado de la compra.").assertExists()
        compose.runOnIdle {spanish=false}
        compose.onNodeWithText("Using previously verified access. Connect to refresh purchase status.").assertExists()
    }
    @Test fun configuredCharacterLimitWithManyLinesCanBeDisplayedAndScrolled() {
        val value="row\n".repeat(25_000)
        show {_,_,_,_->value};action("export-preview-start")
        compose.waitUntil(30000) {compose.onAllNodesWithTag("export-preview-result").fetchSemanticsNodes().isNotEmpty()}
        assertEquals(100_000,text("export-preview-result").length);assertEquals(value,text("export-preview-result"))
        val node=compose.onNodeWithTag("export-preview-result").performScrollTo().assertIsDisplayed()
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
        assertEquals(25_001,layouts.single().lineCount);assertFalse(layouts.single().didOverflowHeight)
    }
    @Test fun disposalStopsPreviewPreparation() {
        val calls=AtomicInteger();val canceled=AtomicInteger()
        show {_,_,_,_->calls.incrementAndGet();try {awaitCancellation()} finally {canceled.incrementAndGet()}}
        action("export-preview-start");compose.waitUntil(5000) {calls.get()==1}
        compose.runOnIdle {visible.value=false};compose.waitUntil(5000) {canceled.get()==1}
        assertEquals(1,calls.get())
    }
    private fun capture(suffix:String="") {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        compose.waitForIdle();instrumentation.waitForIdleSync()
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-export-options${suffix}-large-20261029.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}}
    }
}
