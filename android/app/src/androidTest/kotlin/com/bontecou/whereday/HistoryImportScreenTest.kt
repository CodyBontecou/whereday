package com.bontecou.whereday

import androidx.compose.material3.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.export.ExportFormat
import com.bontecou.whereday.platform.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

class HistoryImportScreenTest {
    @get:Rule val compose=createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val review=HistoryImportReview("review",ExportFormat.markdown,1,2,1,0,1,listOf("Row 9: Missing latitude"),Instant.EPOCH,Instant.EPOCH,"en-US","UTC")
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    @Test fun reviewDefaultsToKeepingRecordsAndDoesNotAutomaticallyWrite() {
        val choices=mutableListOf<HistoryImportPolicy>()
        compose.setContent {MaterialTheme {HistoryImportForm(HistoryImportState(HistoryImportPhase.review,review),{},{}) {_,policy,_->choices+=policy}}}
        compose.onNodeWithText("Your journal has not changed. Review these records before importing.").assertExists()
        compose.onNodeWithTag("history-import-keep").performScrollTo().assertIsSelected()
        assertTrue(choices.isEmpty())
        compose.onNodeWithText("Import valid records").performScrollTo().performClick()
        assertEquals(listOf(HistoryImportPolicy.keepExisting),choices)
    }
    @Test fun replacementNeedsASeparateConfirmationAndCanBeCanceled() {
        val choices=mutableListOf<HistoryImportPolicy>()
        compose.setContent {MaterialTheme {HistoryImportForm(HistoryImportState(HistoryImportPhase.review,review),{},{}) {_,policy,_->choices+=policy}}}
        compose.onNodeWithText("Records with matching IDs: 1",substring=true).performScrollTo().assertExists()
        compose.onNodeWithTag("history-import-replace").performScrollTo().performClick()
        compose.onNodeWithText("Import valid records").performScrollTo().performClick()
        compose.onNodeWithText("Replace matching journal records?").assertIsDisplayed();assertTrue(choices.isEmpty())
        compose.onNodeWithText("Keep reviewing").performClick();assertTrue(choices.isEmpty())
        compose.onNodeWithText("Import valid records").performClick();compose.onNodeWithText("Replace and import").performClick()
        assertEquals(listOf(HistoryImportPolicy.replaceExisting),choices)
    }
    @Test fun recreationNeverRestoresAReplacementChoice() {
        val restoration=StateRestorationTester(compose);val choices=mutableListOf<HistoryImportPolicy>()
        restoration.setContent {MaterialTheme {HistoryImportForm(HistoryImportState(HistoryImportPhase.review,review),{},{}) {_,policy,_->choices+=policy}}}
        compose.onNodeWithTag("history-import-replace").performScrollTo().performClick().assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history-import-keep").performScrollTo().assertIsSelected()
        assertTrue(choices.isEmpty())
        compose.onNodeWithText("Import valid records").performScrollTo().performClick()
        assertEquals(listOf(HistoryImportPolicy.keepExisting),choices)
    }
    @Test fun activeImportOffersCancellationWithoutClaimingItAlreadyRolledBack() {
        var canceled=0
        compose.setContent {MaterialTheme {HistoryImportForm(HistoryImportState(HistoryImportPhase.applying,review),{canceled++},{}) {_,_,_->fail("Unexpected commit")}}}
        compose.onNodeWithText("Importing reviewed records…").assertExists()
        compose.onNodeWithText("Cancel import").performClick();assertEquals(1,canceled)
        compose.onNodeWithText("Import canceled. No records from this operation were written.").assertDoesNotExist()
    }
    @Test fun darkLargeTextKeepsErrorsPolicyAndDiscardReachable() {
        var discarded=0
        compose.setContent {val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)) {
            MaterialTheme(colorScheme=darkColorScheme()) {HistoryImportForm(HistoryImportState(HistoryImportPhase.review,review.copy(errors=List(10) {"Invalid row: "+"x".repeat(180)})),{},{discarded++}) {_,_,_->}}
        }}
        compose.onNodeWithTag("history-import-keep").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Discard review").performScrollTo().performClick();assertEquals(1,discarded)
    }
    @Test fun googleReviewShowsSourceAndUnsupportedCountsWithoutOfferingReplacement() {
        val choices=mutableListOf<HistoryImportSelection>()
        val google=review.copy(format=ExportFormat.json,source=com.bontecou.whereday.export.HistoryImportSource.googleDevice,unsupported=1)
        compose.setContent {MaterialTheme {HistoryImportForm(HistoryImportState(HistoryImportPhase.review,google),{},{}) {_,policy,selection->assertEquals(HistoryImportPolicy.keepExisting,policy);choices+=selection}}}
        compose.onNodeWithTag("history-import-summary").assertTextEquals("Google Device Timeline · 1 visit · 2 points")
        compose.onNodeWithText("1 unsupported Google record will be skipped.",substring=true).assertExists()
        compose.onNodeWithTag("history-import-replace").assertDoesNotExist()
        compose.onNodeWithTag("history-import-select-visits").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("history-import-select-points").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithText("Import valid records").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Import valid records").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
        assertTrue(choices.isEmpty())
    }
    @Test fun recreationRetainsRecordSelectionWithoutAuthorizingTheImport() {
        val restoration=StateRestorationTester(compose);val choices=mutableListOf<HistoryImportSelection>()
        restoration.setContent {MaterialTheme {HistoryImportForm(HistoryImportState(HistoryImportPhase.review,review),{},{}) {_,_,selection->choices+=selection}}}
        compose.onNodeWithTag("history-import-select-points").performScrollTo().performClick().assertIsOff()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history-import-select-points").performScrollTo().assertIsOff();assertTrue(choices.isEmpty())
        compose.onNodeWithTag("history-import-select-visits").performScrollTo().assertIsOn()
        compose.onNodeWithText("Import valid records").performScrollTo().performClick()
        assertEquals(listOf(HistoryImportSelection(true,false)),choices)
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion=33)
    @Test fun spanishGoogleReviewKeepsSourceSelectionAndImportReachable() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val locales=context.getSystemService(android.app.LocaleManager::class.java).applicationLocales
        var previousStatus:Boolean?=null;var previousNavigation:Boolean?=null
        try {
            applyQaAppLocales(compose,android.os.LocaleList.forLanguageTags("es"))
            compose.runOnUiThread {
                val window=compose.activity.window;val bars=androidx.core.view.WindowInsetsControllerCompat(window,window.decorView)
                previousStatus=bars.isAppearanceLightStatusBars;previousNavigation=bars.isAppearanceLightNavigationBars
                val light=InstrumentationRegistry.getArguments().getString("googleImportDark")!="true"
                bars.isAppearanceLightStatusBars=light;bars.isAppearanceLightNavigationBars=light
            }
            val google=review.copy(format=ExportFormat.json,source=com.bontecou.whereday.export.HistoryImportSource.googleSemantic,unsupported=3,repeated=2,rejected=0,errors=emptyList(),locale="es-ES",from=Instant.parse("2020-01-01T00:00:00Z"),until=Instant.parse("2020-01-01T01:00:00Z"))
            val choices=mutableListOf<HistoryImportSelection>()
            compose.setContent {MaterialTheme(colorScheme=if(InstrumentationRegistry.getArguments().getString("googleImportDark")=="true") darkColorScheme() else lightColorScheme()) {
                Surface(androidx.compose.ui.Modifier.fillMaxSize()) {Box(androidx.compose.ui.Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)) {
                    HistoryImportForm(HistoryImportState(HistoryImportPhase.review,google),{},{}) {_,policy,selection->assertEquals(HistoryImportPolicy.keepExisting,policy);choices+=selection}
                }}
            }}
            compose.onNodeWithTag("history-import-summary").assertTextEquals("Cronología semántica de Google · 1 visita · 2 puntos")
            compose.onNodeWithText("Las horas de los archivos Markdown antiguos",substring=true).assertDoesNotExist()
            captureGoogle("top")
            compose.onNodeWithTag("history-import-select-visits").performScrollTo().assertIsOn()
            compose.onNodeWithText(context.getString(R.string.history_import_confirm)).performScrollTo().assertIsDisplayed()
            captureGoogle("bottom")
            assertTrue(choices.isEmpty())
            compose.onNodeWithText(context.getString(R.string.history_import_confirm)).performClick()
            assertEquals(listOf(HistoryImportSelection()),choices)
        } finally {
            compose.runOnUiThread {val window=compose.activity.window;val bars=androidx.core.view.WindowInsetsControllerCompat(window,window.decorView)
                previousStatus?.let {bars.isAppearanceLightStatusBars=it};previousNavigation?.let {bars.isAppearanceLightNavigationBars=it}}
            applyQaAppLocales(compose,locales)
        }
    }
    private fun captureGoogle(position:String) {
        if(InstrumentationRegistry.getArguments().getString("captureGoogleImport")!="true") return
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val scale=(context.resources.configuration.fontScale*10).toInt()
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-google-import-es-$scale-$position-20261101.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        context.contentResolver.openOutputStream(uri)!!.use {stream->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}}
    }
    @Test fun nearbyMatchesCannotOfferIdentityReplacement() {
        val choices=mutableListOf<HistoryImportPolicy>()
        val nearby=review.copy(existing=2,replaceable=0)
        compose.setContent {MaterialTheme {HistoryImportForm(HistoryImportState(HistoryImportPhase.review,nearby),{},{}) {_,policy,_->choices+=policy}}}
        compose.onNodeWithTag("history-import-replace").assertDoesNotExist()
        compose.onNodeWithText("Import valid records").performScrollTo().performClick()
        assertEquals(listOf(HistoryImportPolicy.keepExisting),choices)
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion=33)
    @Test fun spanishWheredayReviewExplainsExactReplacementAndRequiresConfirmation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val locales=context.getSystemService(android.app.LocaleManager::class.java).applicationLocales
        val choices=mutableListOf<HistoryImportPolicy>()
        try {
            applyQaAppLocales(compose,android.os.LocaleList.forLanguageTags("es"))
            val backup=review.copy(format=ExportFormat.json,existing=2,replaceable=1,repeated=1,rejected=0,errors=emptyList(),locale="es-ES")
            compose.setContent {MaterialTheme(colorScheme=darkColorScheme()) {
                Surface(androidx.compose.ui.Modifier.fillMaxSize()) {Box(androidx.compose.ui.Modifier.windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)) {
                    HistoryImportForm(HistoryImportState(HistoryImportPhase.review,backup),{},{}) {_,policy,_->choices+=policy}
                }}
            }}
            compose.onNodeWithTag("history-import-existing").performScrollTo()
                .assertTextEquals(context.getString(R.string.history_import_existing,2,1))
            compose.onNodeWithText(context.getString(R.string.history_import_replaceable,1)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("history-import-replace").performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.history_import_confirm)).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.history_import_replace_body)).assertIsDisplayed()
            assertTrue(choices.isEmpty())
            compose.onNodeWithText(context.getString(R.string.history_import_replace_confirm)).performClick()
            assertEquals(listOf(HistoryImportPolicy.replaceExisting),choices)
        } finally {applyQaAppLocales(compose,locales)}
    }
}
