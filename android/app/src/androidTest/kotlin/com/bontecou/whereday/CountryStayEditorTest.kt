package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.*

@SdkSuppress(minSdkVersion=33)
class CountryStayEditorTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val today=LocalDate.of(2026,10,29)
    private val original=CountryStay("manual","PR",today.minusDays(2),today.minusDays(1))
    private lateinit var db:HistoryDatabase
    private lateinit var repo:HistoryRepository
    private var oldLocales:LocaleList?=null
    private var dismissed=0
    private val ids=mutableListOf<String>()
    private var saver:suspend (CountryStay,CountryStay?)->Unit={value,expected->ids.add(value.id);repo.saveCountryStay(value,expected);Unit}
    private val releases=mutableListOf<CompletableDeferred<Unit>>()
    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
        db=HistoryDatabase.create(target,null)
        repo=HistoryRepository(db,Clock.fixed(today.atTime(12,0).toInstant(ZoneOffset.UTC),ZoneOffset.UTC),{ZoneOffset.UTC})
        db.history().put(VisitRow(Visit("journal",Coordinate(18.0,-66.0),Instant.EPOCH,name="Kept")))
    }
    @After fun cleanup()=runBlocking {
        releases.forEach {it.complete(Unit)}
        oldLocales?.let {applyQaAppLocales(compose,it)}
        assertEquals("Kept",db.history().visit("journal")!!.value.name)
        db.close()
    }
    private fun show(stay:CountryStay?=null):StateRestorationTester {
        if(stay!=null) runBlocking {db.history().put(StayRow(stay))}
        return StateRestorationTester(compose).also {tester->tester.setContent {MaterialTheme {
            val baseline by rememberSaveable(stateSaver=CountryStayStateSaver) {mutableStateOf(stay)}
            CountryStayEditor(baseline,{dismissed++},{v,e->saver(v,e)},{repo.deleteCountryStay(it)},today)
        }}}
    }
    private fun click(tag:String)=compose.onNodeWithTag(tag).performScrollTo().performClick()
    private fun country(code:String) {
        click("stay-country")
        compose.onNodeWithTag("stay-country-search").performTextReplacement(code)
        chooseCountry(code)
    }
    private fun chooseCountry(code:String) {
        compose.onNodeWithTag("stay-country-list").performScrollToNode(hasTestTag("stay-country-$code"))
        compose.onNodeWithTag("stay-country-$code").performClick()
    }
    private fun closeStayKeyboard() {
        // The stay editor owns a dialog window; the default root can be its unfocused background Activity.
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(closeQaKeyboard())
    }
    private fun save() {
        // IME window dismissal is independent of Compose's idle clock after a country dialog closes.
        closeStayKeyboard()
        compose.onNodeWithTag("stay-country-search").assertDoesNotExist()
        compose.onNodeWithTag("stay-save").assertIsEnabled().assertIsDisplayed().performClick()
    }
    private fun rows()=runBlocking {db.history().stays().map {it.value}}
    private fun waitText(text:String) {
        compose.waitUntil(5000) {compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
    @Test fun creatingStayUsesPastCivilDatesAndKeepsOneStableIdentity() {
        show();country("ES");save()
        try {compose.waitUntil(5000) {dismissed==1}}
        catch(e:Exception) {throw AssertionError("Save did not complete: dispatched=${ids.size}, rows=${rows()}, dismissed=$dismissed",e)}
        val value=rows().single();assertEquals("ES",value.countryCode);assertEquals(today,value.firstDay);assertEquals(today,value.lastDay)
        assertEquals(listOf(value.id),ids)
    }
    @Test fun draftAndFrozenExpectedSnapshotSurviveRestoration() {
        val tester=show(original);country("ES");tester.emulateSavedInstanceStateRestore()
        waitText("País: España (ES)")
        if(InstrumentationRegistry.getArguments().getString("captureStay")=="true") capture()
        save();compose.waitUntil(5000) {dismissed==1}
        assertEquals(original.copy(countryCode="ES"),rows().single())
    }
    @Test fun staleRestoredEditorReportsConflictWithoutOverwritingNewStay() {
        val tester=show(original);country("ES")
        runBlocking {db.history().put(StayRow(original.copy(countryCode="CA")))}
        tester.emulateSavedInstanceStateRestore();save()
        waitText("Esta estancia ha cambiado. Cierra el editor y revisa la estancia actual.")
        assertEquals(original.copy(countryCode="CA"),rows().single());assertEquals(0,dismissed)
        waitText("País: España (ES)")
    }
    @Test fun dirtyCancelOffersKeepEditingAndExplicitDiscardWithoutWrites() {
        show(original);country("ES");compose.onNodeWithTag("stay-close").performClick()
        compose.onNodeWithText("Seguir editando").performClick();waitText("País: España (ES)")
        assertEquals(listOf(original),rows());assertEquals(0,dismissed)
        compose.onNodeWithTag("stay-close").performClick()
        compose.onNodeWithTag("stay-discard-confirm").performClick();assertEquals(1,dismissed);assertEquals(listOf(original),rows())
    }
    @Test fun deleteRequiresConfirmationAndKeepsJournalRecords() {
        show(original);click("stay-delete");compose.onNodeWithTag("stay-delete-cancel").performClick()
        assertEquals(listOf(original),rows());assertEquals(0,dismissed)
        click("stay-delete");compose.onNodeWithTag("stay-delete-confirm").performClick()
        compose.waitUntil(5000) {dismissed==1};assertTrue(rows().isEmpty())
    }
    @Test fun staleDeleteRemainsOpenAndPreservesLatestRecord() {
        show(original);click("stay-delete")
        runBlocking {db.history().put(StayRow(original.copy(countryCode="CA")))}
        compose.onNodeWithTag("stay-delete-confirm").performClick()
        waitText("Esta estancia ha cambiado. Cierra el editor y revisa la estancia actual.")
        assertEquals(original.copy(countryCode="CA"),rows().single());assertEquals(0,dismissed)
    }
    @Test fun busySaveRejectsQueuedDuplicateAndCancelUntilCommitCompletes() {
        val release=CompletableDeferred<Unit>();releases.add(release)
        saver={v,e->ids.add(v.id);release.await();repo.saveCountryStay(v,e);Unit}
        show();val action=compose.onNodeWithTag("stay-save").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {action();action()};compose.waitUntil(5000) {ids.size==1}
        compose.onNodeWithTag("stay-save").assertIsNotEnabled();compose.onNodeWithTag("stay-close").assertIsNotEnabled()
        assertTrue(rows().isEmpty());assertEquals(0,dismissed)
        release.complete(Unit);compose.waitUntil(5000) {dismissed==1}
        assertEquals(1,rows().size);assertEquals(1,ids.size)
    }
    @Test fun failureIsLocalizedAndRetryAfterRestoreKeepsDraftIdentity() {
        var attempt=0
        saver={v,e->ids.add(v.id);if(attempt++==0) error("Private database detail");repo.saveCountryStay(v,e);Unit}
        val tester=show();country("ES");save()
        waitText("No se pudo guardar la estancia. Inténtalo de nuevo.")
        compose.onNodeWithText("Private database detail").assertDoesNotExist();assertTrue(rows().isEmpty())
        tester.emulateSavedInstanceStateRestore();save();compose.waitUntil(5000) {dismissed==1}
        assertEquals(2,ids.size);assertEquals(ids[0],ids[1]);assertEquals(ids[0],rows().single().id)
    }
    @Test fun futureStayCannotBeSavedAndDateRangeCannotApplyFutureCivilDates() {
        show(original.copy(firstDay=today.plusDays(1),lastDay=today.plusDays(1)))
        waitText("Las fechas de la estancia deben ser de hoy o anteriores.")
        compose.onNodeWithTag("stay-save").assertIsNotEnabled();assertEquals(0,ids.size)
        click("stay-dates");compose.onNodeWithText("Aplicar").assertIsNotEnabled()
        val action=compose.onNodeWithText("Aplicar").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {action()};compose.waitForIdle();compose.onNodeWithText("Aplicar").assertExists()
        assertEquals(listOf(original.copy(firstDay=today.plusDays(1),lastDay=today.plusDays(1))),rows())
    }
    @Test fun countrySearchUsesLocalizedNamesAccentFoldingAndEmptyState() {
        show();click("stay-country")
        compose.onNodeWithTag("stay-country-search").performTextInput("espana")
        compose.onNodeWithTag("stay-country-ES").assertExists()
        compose.onNodeWithTag("stay-country-PR").assertDoesNotExist()
        compose.onNodeWithTag("stay-country-search").performTextReplacement("No such country QA")
        compose.onNodeWithText("No se encontraron países.").assertIsDisplayed()
        compose.onNodeWithTag("stay-country-search").performTextReplacement("ES")
        chooseCountry("ES");waitText("País: España (ES)");assertTrue(rows().isEmpty())
    }
    @Test fun sarkIsSearchableByNameAndSurvivesRestorationAndSave() {
        val tester=show();click("stay-country")
        val name=CountryRegions.displayName("CQ",java.util.Locale.forLanguageTag("es"))
        assertNotEquals("CQ",name)
        compose.onNodeWithTag("stay-country-search").performTextInput(name)
        chooseCountry("CQ");tester.emulateSavedInstanceStateRestore()
        waitText("País: $name (CQ)")
        if(InstrumentationRegistry.getArguments().getString("captureCountryRegion")=="true") capture("whereday-country-region-cq-large-20261029.png")
        save();compose.waitUntil(5000) {dismissed==1}
        assertEquals("CQ",rows().single().countryCode)
    }
    @Test fun kosovoCanBeEditedToAscensionWithItsOriginalSnapshot() {
        val value=original.copy(countryCode="XK")
        show(value);country("AC");save();compose.waitUntil(5000) {dismissed==1}
        assertEquals(value.copy(countryCode="AC"),rows().single())
    }
    @Test fun repeatedCountryPickerDismissalsKeepTheStayWindowReadyToSave() {
        show(original)
        val codes=listOf("ES","AC","CQ","XK")
        repeat(20) {index->
            country(codes[index%codes.size])
            // Exercise the same keyboard-dismissal seam as save after closing the nested picker.
            closeStayKeyboard()
            compose.onNodeWithTag("stay-country-search").assertDoesNotExist()
            compose.onNodeWithTag("stay-save").assertIsEnabled().assertIsDisplayed()
            assertEquals(listOf(original),rows())
        }
        save();compose.waitUntil(5000) {dismissed==1}
        assertEquals(original.copy(countryCode=codes.last()),rows().single())
    }
    private fun capture(filename:String="whereday-country-stay-large-20261029.png") {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val target=instrumentation.targetContext
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        closeStayKeyboard()
        compose.onNodeWithTag("stay-dates").performScrollTo();compose.onNodeWithTag("stay-save").assertIsDisplayed()
        compose.waitForIdle();instrumentation.waitForIdleSync();android.os.SystemClock.sleep(600)
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,filename)
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
}
