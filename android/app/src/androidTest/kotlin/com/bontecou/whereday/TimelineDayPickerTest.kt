package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*

class TimelineDayPickerTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val graph by lazy {AppGraph.from(target)}
    private val models=ViewModelStore()
    private val original=LocalDate.of(2026,11,2)
    @Before fun guard():Unit=runBlocking {check(target.packageName=="com.bontecou.whereday.mapqa");graph.journalClear.clear().join()}
    @After fun close():Unit=runBlocking {compose.activityRule.scenario.onActivity {models.clear()};graph.journalClear.clear().join()}
    private fun show(opening:TimelineDateOpening=TimelineDateOpening(original,ZoneOffset.UTC),choose:(LocalDate)->Unit) {
        compose.setContent {MaterialTheme {TimelineDayPicker(opening,{},choose)}}
    }
    private fun iso() {compose.onNodeWithTag("timeline-date-iso").assertIsDisplayed().performClick()}
    private fun input(value:String)=compose.onNodeWithTag("timeline-date-input").performTextReplacement(value)
    private fun model():JournalViewModel {
        lateinit var result:JournalViewModel
        compose.activityRule.scenario.onActivity {
            result=JournalViewModel(graph,Clock.fixed(original.atTime(12,0).toInstant(ZoneOffset.UTC),ZoneOffset.UTC),{ZoneOffset.UTC})
            result.selectTimelineDay(original);models.put("timeline",result)
        }
        return result
    }
    @Test fun publicTimelineCalendarCancelAndHistoricalApplyOnlyChangeTheSelectedDay() {
        val model=model();compose.setContent {MaterialTheme {TimelineScreen(graph,model,true,{})}}
        compose.onNodeWithTag("timeline-choose-date").performClick();iso();input("1850-01-02")
        compose.onNodeWithText("Cancel").performClick();compose.runOnIdle {assertEquals(original,model.timelineDay.value)}
        compose.onNodeWithTag("timeline-choose-date").performClick();iso();input("1850-01-02")
        compose.onNodeWithTag("timeline-date-apply").performClick()
        compose.runOnIdle {assertEquals(LocalDate.of(1850,1,2),model.timelineDay.value)}
        compose.onNodeWithTag("timeline-date-input").assertDoesNotExist()
    }
    @Test fun impossibleSkippedAndStorageOverflowDatesCannotApplyEvenWhenForced() {
        val selected=mutableListOf<LocalDate>()
        show(TimelineDateOpening(LocalDate.of(2011,12,29),ZoneId.of("Pacific/Apia")),selected::add);iso()
        for(value in listOf("2026-02-30","2011-12-30","+999999999-12-31","not a date")) {
            input(value);compose.onNodeWithTag("timeline-date-apply").assertIsNotEnabled()
            val action=compose.onNodeWithTag("timeline-date-apply").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle {action()};assertTrue(selected.isEmpty())
        }
        input("2012-01-01");compose.onNodeWithTag("timeline-date-apply").assertIsEnabled().performClick()
        assertEquals(listOf(LocalDate.of(2012,1,1)),selected)
    }
    @Test fun invalidPendingTextSurvivesSavedStateAndCanBeCorrected() {
        val restoration=StateRestorationTester(compose);val selected=mutableListOf<LocalDate>()
        val opening=TimelineDateOpening(original,ZoneOffset.UTC)
        restoration.setContent {MaterialTheme {TimelineDayPicker(opening,{},selected::add)}}
        iso();input("2024-02-30");restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("timeline-date-input").assertTextContains("2024-02-30")
        compose.onNodeWithTag("timeline-date-apply").assertIsNotEnabled();assertTrue(selected.isEmpty())
        input("2024-02-29");compose.onNodeWithTag("timeline-date-apply").performClick()
        assertEquals(listOf(LocalDate.of(2024,2,29)),selected)
    }
    @Test fun actualCalendarSelectionTransfersToStrictIsoEntry() {
        val selected=mutableListOf<LocalDate>()
        val opening=TimelineDateOpening(LocalDate.of(2026,2,1),ZoneOffset.UTC)
        val restoration=StateRestorationTester(compose)
        restoration.setContent {MaterialTheme {TimelineDayPicker(opening,{},selected::add)}}
        compose.onNodeWithText("Sunday, February 15, 2026").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore();iso()
        compose.onNodeWithTag("timeline-date-input").assertTextContains("2026-02-15")
        compose.onNodeWithTag("timeline-date-apply").performClick()
        assertEquals(listOf(LocalDate.of(2026,2,15)),selected)
    }
    @Test fun largeCalendarKeepsTheEntireTypedEntryActionAboveTheApplyRow() {
        val opening=TimelineDateOpening(original,ZoneOffset.UTC)
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.8f)) {MaterialTheme {TimelineDayPicker(opening,{},{})}}}
        val entry=compose.onNodeWithTag("timeline-date-iso").getUnclippedBoundsInRoot()
        val apply=compose.onNodeWithTag("timeline-date-apply").getUnclippedBoundsInRoot()
        assertTrue("Typed entry overlaps the Apply row: entry=$entry, apply=$apply",entry.bottom.value<=apply.top.value+0.5f)
        iso();compose.onNodeWithTag("timeline-date-input").assertIsDisplayed()
    }
    @Test fun stoppedAndOldResumedApplyActionsCannotNavigate() {
        val selected=mutableListOf<LocalDate>();show(choose=selected::add);iso();input("2020-01-02")
        val old=compose.onNodeWithTag("timeline-date-apply").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {old()};assertTrue(selected.isEmpty())
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle {old()};assertTrue(selected.isEmpty())
        compose.onNodeWithTag("timeline-date-apply").performClick();assertEquals(listOf(LocalDate.of(2020,1,2)),selected)
    }
    @Test fun changedTimelineOwnerRejectsAnOldPickerApply() {
        val model=model();compose.setContent {MaterialTheme {TimelineScreen(graph,model,true,{})}}
        compose.onNodeWithTag("timeline-choose-date").performClick();iso();input("2020-01-02")
        val old=compose.onNodeWithTag("timeline-date-apply").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {model.selectTimelineDay(original.minusDays(1));old()}
        compose.runOnIdle {assertEquals(original.minusDays(1),model.timelineDay.value)}
        compose.onNodeWithTag("timeline-date-input").assertDoesNotExist()
    }
    @SdkSuppress(minSdkVersion=33)
    @Test fun largeSpanishHistoricalCalendarAndStrictEntryRemainReachable() {
        val manager=target.getSystemService(LocaleManager::class.java);val old=manager.applicationLocales
        try {
            applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
            val selected=mutableListOf<LocalDate>();val opening=TimelineDateOpening(LocalDate.of(1850,1,1),ZoneOffset.UTC)
            compose.setContent {CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.8f)) {MaterialTheme {TimelineDayPicker(opening,{},selected::add)}}}
            compose.onNodeWithText("Elegir fecha de la cronología").assertIsDisplayed();iso()
            compose.onNodeWithTag("timeline-date-input").assertTextContains("1850-01-01")
            input("2200-12-31");compose.onNodeWithTag("timeline-date-apply").assertIsDisplayed().performClick()
            assertEquals(listOf(LocalDate.of(2200,12,31)),selected)
        } finally {applyQaAppLocales(compose,old)}
    }
}
