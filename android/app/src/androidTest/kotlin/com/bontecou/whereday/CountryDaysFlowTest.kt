package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import java.time.*
import java.util.concurrent.atomic.AtomicInteger

@SdkSuppress(minSdkVersion=33)
class CountryDaysFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var controller:CountryAnalysisController
    private var oldLocales:LocaleList?=null
    private var oldAllowed=true
    private val calls=AtomicInteger()
    private val canceled=AtomicInteger()
    private val finished=AtomicInteger()
    private var load:suspend ()->CountryLocation?={try {awaitCancellation()} finally {canceled.incrementAndGet()}}
    private val visible=mutableStateOf(true)
    private val releases=mutableListOf<CompletableDeferred<CountryLocation?>>()
    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        graph=AppGraph.from(target);oldAllowed=graph.preferences.settings.first().networkGeocoding
        graph.preferences.update {it.copy(networkGeocoding=true)}
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        val lookup=object:CountryLookup {
            override suspend fun lookup(coordinate:Coordinate):CountryLocation? {calls.incrementAndGet();return load()}
            override suspend fun finish() {finished.incrementAndGet()}
        }
        controller=CountryAnalysisController(graph.preferences,CountryAnalysis(graph.database,lookup,"synthetic-country-ui",minimumInterval=Duration.ZERO))
        graph.history.dao.put(PointRow(TrackPoint(id="pending",coordinate=Coordinate(0.0,0.0),time=Instant.now().minusSeconds(60),accuracy=5.0),null))
    }
    @After fun cleanup()=runBlocking {
        releases.forEach {it.complete(null)}
        controller.close()
        oldLocales?.let {applyQaAppLocales(compose,it)}
        graph.preferences.update {it.copy(networkGeocoding=oldAllowed)}
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
    }
    private fun show() {compose.setContent {MaterialTheme {Surface(Modifier.systemBarsPadding()) {if(visible.value) CountryDaysScreen(graph,controller) {visible.value=false}}}}}
    private fun click(tag:String) {find(tag).performClick()}
    private fun find(tag:String):SemanticsNodeInteraction {
        val node=compose.onNodeWithTag(tag)
        if(runCatching {node.assertIsDisplayed()}.isFailure) compose.onNodeWithTag("country-days-content").performScrollToNode(hasTestTag(tag))
        return node
    }
    private fun start() {click("country-analysis-open");compose.onNodeWithTag("country-analysis-start").performClick()}
    private fun waitStatus(text:String) {
        find("country-analysis-status")
        compose.waitUntil(5000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
        find("country-analysis-status").assertTextEquals(text)
    }
    private fun allTime() {compose.onNodeWithText("Este año").performClick();compose.onNodeWithText("Todo el tiempo").performClick()}
    private fun stays(recorded:Boolean=false)=runBlocking {
        graph.history.dao.put(StayRow(CountryStay("pr-main","PR",LocalDate.parse("2024-10-27"),LocalDate.parse("2024-10-28"))))
        graph.history.dao.put(StayRow(CountryStay("es-main","ES",LocalDate.parse("2024-10-28"),LocalDate.parse("2024-10-28"))))
        graph.history.dao.put(StayRow(CountryStay("overlap","PR",LocalDate.parse("2024-10-28"),LocalDate.parse("2024-10-28"))))
        if(recorded) graph.history.dao.put(PointRow(TrackPoint(id="recorded",coordinate=Coordinate(18.0,-66.0),time=Instant.parse("2024-10-27T12:00:00Z"),accuracy=5.0,countryCode="PR",timeZone="UTC"),null))
    }
    @Test fun disposingCountryScreenCancelsItsPendingAnalysis() {
        show();controller.start();compose.waitUntil(5000) {calls.get()==1}
        compose.runOnIdle {visible.value=false}
        compose.waitUntil(5000) {canceled.get()==1 && finished.get()==1 && !controller.state.value.running}
        assertFalse(controller.state.value.running)
        assertNull(runBlocking {graph.history.dao.point("pending")!!.value.countryCode})
    }
    @Test fun backgroundCancelsWithoutRestartingOnResume() {
        show();start();compose.waitUntil(5000) {calls.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5000) {canceled.get()==1 && finished.get()==1 && !controller.state.value.running}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitStatus("Análisis detenido. Los resultados completados se han guardado; vuelve a analizar para continuar.")
        find("country-analysis-open").assertIsEnabled();assertEquals(1,calls.get())
    }
    @Test fun disclosureCancelAndHeldActionCannotStartAnalysis() {
        show();assertEquals(0,calls.get());click("country-analysis-open")
        val action=compose.onNodeWithTag("country-analysis-start").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle {action()};compose.waitForIdle();assertEquals(0,calls.get());assertFalse(controller.state.value.running)
        click("country-analysis-open");compose.onNodeWithTag("country-analysis-start").performClick()
        compose.waitUntil(5000) {calls.get()==1};click("country-analysis-stop")
        compose.waitUntil(5000) {!controller.state.value.running};assertEquals(1,canceled.get())
    }
    @Test fun optOutRejectsLateNonCooperativeResultAndDoesNotRetryAfterOptIn() {
        val release=CompletableDeferred<CountryLocation?>();releases.add(release)
        load={withContext(NonCancellable) {release.await()}}
        show();start();compose.waitUntil(5000) {calls.get()==1}
        runBlocking {graph.preferences.update {it.copy(networkGeocoding=false)}}
        release.complete(CountryLocation("PR","UTC"))
        compose.waitUntil(5000) {!controller.state.value.running}
        assertNull(runBlocking {graph.history.dao.point("pending")!!.value.countryCode})
        find("country-analysis-open").assertIsNotEnabled()
        runBlocking {graph.preferences.update {it.copy(networkGeocoding=true)}}
        find("country-analysis-open").assertIsEnabled();assertEquals(1,calls.get())
    }
    @Test fun overviewDeduplicatesTravelAndOverlappingDaysAndDetailsAreNewestFirst() {
        stays(recorded=true);show();allTime()
        find("country-summary-countries").assertTextEquals("2 países")
        find("country-summary-days").assertTextEquals("2 días con presencia registrada o manual")
        if(InstrumentationRegistry.getArguments().getString("captureCountryDays")=="true") capture()
        click("country-row-PR")
        find("country-detail-count").assertTextEquals("2 días de presencia")
        val latest=find("country-day-2024-10-28").fetchSemanticsNode().boundsInRoot.top
        val older=compose.onNodeWithTag("country-day-2024-10-27").fetchSemanticsNode().boundsInRoot.top
        assertTrue(latest<older)
        compose.onNodeWithText("Registrado + manual").assertExists();compose.onNodeWithText("Manual").assertExists()
        click("country-back");find("country-summary-days").assertTextEquals("2 días con presencia registrada o manual")
        assertEquals(0,calls.get())
    }
    @Test fun enteringCountryDetailStopsPendingAnalysisWithoutRestartingOnBack() {
        stays();show();allTime();start();compose.waitUntil(5000) {calls.get()==1}
        click("country-row-PR")
        compose.waitUntil(5000) {canceled.get()==1 && !controller.state.value.running}
        click("country-back");find("country-analysis-open").assertIsEnabled();assertEquals(1,calls.get())
    }
    @Test fun overviewFormatsLargeCountsAndPeriodChoiceKeepsStableWireKeys()=runBlocking {
        val first=LocalDate.parse("2020-01-01")
        graph.history.dao.put(StayRow(CountryStay("long","PR",first,first.plusDays(1000))))
        show();allTime()
        find("country-summary-countries").assertTextEquals("1 país")
        find("country-summary-days").assertTextEquals("1.001 días con presencia registrada o manual")
        compose.onNodeWithText("Todo el tiempo").performScrollTo().performClick();compose.onNodeWithText("Este año").performClick()
        find("country-summary-countries").assertTextEquals("0 países")
        find("country-summary-days").assertTextEquals("0 días con presencia registrada o manual")
        assertEquals(1,graph.history.dao.stays().size);assertEquals(0,calls.get())
    }
    @Test fun successfulAnalysisPersistsMetadataAndShowsLocalizedCompletion() {
        runBlocking {graph.history.dao.put(PointRow(TrackPoint(id="unavailable",coordinate=Coordinate(1.0,1.0),time=Instant.now().minusSeconds(60),accuracy=5.0),null))}
        load={if(calls.get()==1) CountryLocation("PR","America/Puerto_Rico") else null}
        show();start()
        compose.waitUntil(5000) {calls.get()==2 && !controller.state.value.running}
        waitStatus("Ubicaciones identificadas: 1 de 2. Los resultados no disponibles se vuelven a intentar después de una hora.")
        assertEquals(CountryAnalysisStatus.finished,controller.state.value.progress.status)
        assertEquals("PR",runBlocking {graph.history.dao.point("pending")!!.value.countryCode})
        find("country-summary-countries").assertTextEquals("1 país")
        start();compose.waitUntil(5000) {controller.state.value.progress.status==CountryAnalysisStatus.nothingDue && !controller.state.value.running}
        waitStatus("No hay ubicaciones guardadas que necesiten análisis ahora. Los resultados no disponibles se vuelven a intentar después de una hora.")
        assertEquals(2,calls.get())
    }
    @Test fun providerFailureIsLocalizedAndDoesNotExposeProviderDetails() {
        load={throw IllegalStateException("Private provider diagnostic")}
        show();start();compose.waitUntil(5000) {calls.get()==1 && !controller.state.value.running}
        waitStatus("La consulta de países no está disponible temporalmente. Los resultados completados se han guardado. Comprueba la conexión e inténtalo de nuevo más tarde.")
        compose.onNodeWithText("Private provider diagnostic").assertDoesNotExist()
        assertNull(runBlocking {graph.history.dao.point("pending")!!.value.countryCode})
    }
    @Test fun freeCsvPreviewKeepsProtocolHeadersAndSourceValuesUntranslated() {
        stays(recorded=true);show();allTime();click("country-preview")
        compose.onNode(hasText("country_code,local_date,source",substring=true)).assertExists()
        compose.onNode(hasText("PR,2024-10-27,recorded+manual",substring=true)).assertExists()
        compose.onNodeWithText("Listo").performClick()
        assertEquals(3,runBlocking {graph.history.dao.stays().size});assertEquals(0,calls.get())
    }
    @Test fun oldConfirmationCannotStartAfterBackgroundAndResume() {
        show();click("country-analysis-open")
        val action=compose.onNodeWithTag("country-analysis-start").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {action()};compose.waitForIdle()
        assertEquals(0,calls.get());assertFalse(controller.state.value.running)
        find("country-analysis-open").assertIsEnabled()
    }
    @Test fun savingManualStayUpdatesOverviewWithoutChangingRecordedMetadata() {
        stays(recorded=true);show();allTime();click("country-stay-pr-main")
        compose.onNodeWithTag("stay-country").performScrollTo().performClick()
        compose.onNodeWithTag("stay-country-search").performTextInput("AC")
        compose.onNodeWithTag("stay-country-list").performScrollToNode(hasTestTag("stay-country-AC"))
        compose.onNodeWithTag("stay-country-AC").performClick()
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot()).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
        compose.onNodeWithTag("stay-save").performClick()
        compose.waitUntil(5000) {runBlocking {graph.history.dao.stay("pr-main")?.value?.countryCode=="AC"}}
        find("country-summary-countries").assertTextEquals("3 países")
        find("country-summary-days").assertTextEquals("2 días con presencia registrada o manual")
        find("country-row-AC").assertExists()
        assertEquals("PR",runBlocking {graph.history.dao.point("recorded")!!.value.countryCode})
        assertEquals(0,calls.get())
    }
    @Test fun countryDetailStartsAtTopAndReturnsToOverviewScrollPosition() {
        stays()
        runBlocking {
            val first=LocalDate.parse("2024-01-01")
            graph.history.dao.put(StayRow(CountryStay("pr-main","PR",first,first.plusDays(99))))
            for(code in listOf("AC","US","FR","DE","CA")) graph.history.dao.put(StayRow(CountryStay("scroll-$code",code,first,first.plusDays(149))))
        }
        show();allTime()
        find("country-row-PR")
        val before=compose.onNodeWithTag("country-days-content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        click("country-row-PR")
        compose.onNodeWithTag("country-back").assertIsDisplayed()
        compose.onNodeWithTag("country-detail-count").assertIsDisplayed()
        compose.onNodeWithTag("country-back").performClick()
        val after=compose.onNodeWithTag("country-days-content").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue("Fixture must scroll the overview",before>0f)
        assertEquals("Back must retain the overview position",before,after,1f)
        compose.onNodeWithTag("country-row-PR").assertIsDisplayed()
        assertEquals(0,calls.get())
    }
    private fun capture() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val target=instrumentation.targetContext
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        find("country-summary-countries").assertIsDisplayed();find("country-summary-days").assertIsDisplayed()
        compose.waitForIdle();instrumentation.waitForIdleSync();android.os.SystemClock.sleep(600)
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-country-days-overview-large-20261029.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
}
