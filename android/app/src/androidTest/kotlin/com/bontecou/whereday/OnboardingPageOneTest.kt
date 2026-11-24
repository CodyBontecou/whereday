package com.bontecou.whereday

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.RecordingPreset
import com.bontecou.whereday.domain.SavedPlace
import com.bontecou.whereday.export.ExportFormat
import com.bontecou.whereday.platform.*
import org.junit.*
import org.junit.Assert.*

/** Page One with inert, synthetic services: no journal, location, photo or notification access is used. */
class OnboardingPageOneTest {
    @get:Rule val compose=createComposeRule()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun next()=compose.onNodeWithTag("onboarding-continue").performScrollTo().performClick()
    private fun at(step:OnboardingStep)=compose.onNodeWithTag("onboarding-progress").assertContentDescriptionEquals(target.getString(R.string.onboarding_step_count,step.ordinal+1,10))

    @Test fun intentsPersistAndRunsPreselectEveryStep() {
        val saved=mutableListOf<Set<OnboardingIntent>>();val presets=mutableListOf<RecordingPreset>()
        compose.setContent {MaterialTheme {Onboarding({},{},initialPreset=RecordingPreset.routes,choosePreset={presets+=it},
            services=OnboardingServices(saveIntents={saved+=it})) {}}}
        at(OnboardingStep.cover);compose.onNodeWithTag("onboarding-back").assertDoesNotExist()
        next();at(OnboardingStep.intent)
        compose.onNodeWithTag("onboarding-intent-days").assertIsOn()
        compose.onNodeWithTag("onboarding-intent-routes").assertIsOff().performScrollTo().performClick().assertIsOn()
        compose.onNodeWithTag("onboarding-intent-notes").performScrollTo().performClick()
        next();compose.waitUntil {saved.isNotEmpty()}
        assertEquals(setOf(OnboardingIntent.days,OnboardingIntent.routes,OnboardingIntent.notes),saved.single())
        at(OnboardingStep.scrub)
        compose.onNodeWithTag("onboarding-scrub").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) {it(12f)}
        compose.onNodeWithText(target.getString(R.string.onboarding_sample_office)).assertIsDisplayed()
        next();at(OnboardingStep.pen)
        compose.onNodeWithTag("onboarding-preset-every_step").assertIsOn()
        next();assertEquals(listOf(RecordingPreset.everyStep),presets)
    }
    @Test fun emptyIntentsSkipAndBackReturnsWithoutLosingChoices() {
        compose.setContent {MaterialTheme {Onboarding({},{}) {}}}
        next();compose.onNodeWithTag("onboarding-intent-days").performClick().assertIsOff()
        compose.onNodeWithTag("onboarding-continue").assertTextEquals(target.getString(R.string.onboarding_skip))
        next();at(OnboardingStep.scrub)
        compose.onNodeWithTag("onboarding-back").performScrollTo().performClick();at(OnboardingStep.intent)
        compose.onNodeWithTag("onboarding-intent-days").assertIsOff()
    }
    @Test fun permissionStepRequestsLocationThenMovesOnWhenGranted() {
        var requests=0
        var access by androidx.compose.runtime.mutableStateOf(RecordingAccessState(LocationPrecision.none,false))
        compose.setContent {MaterialTheme {Onboarding({requests++},{},access=access) {}}}
        compose.walkOnboarding(OnboardingStep.perm.ordinal);at(OnboardingStep.perm)
        compose.onNodeWithTag("onboarding-skip").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-continue").assertTextEquals(target.getString(R.string.onboarding_allow_location)).performClick()
        assertEquals(1,requests);at(OnboardingStep.perm)
        compose.runOnIdle {access=RecordingAccessState(LocationPrecision.precise,false)}
        at(OnboardingStep.stamp)
    }
    @Test fun stampSavesTheChosenNameAtTheFixAndRenamesTheSameRecord() {
        val saves=mutableListOf<Pair<SavedPlace,SavedPlace?>>()
        val services=OnboardingServices(locate={OnboardingFix(37.0,-122.0,12.0)},savePlace={place,expected->saves+=place to expected})
        compose.setContent {MaterialTheme {Onboarding({},{},authorization=UsageAuthorization.foreground,services=services) {}}}
        compose.walkOnboarding(OnboardingStep.stamp.ordinal);at(OnboardingStep.stamp)
        compose.waitUntil {compose.onAllNodesWithTag("onboarding-place-home").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("onboarding-place-home").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-continue").assertTextEquals(target.getString(R.string.onboarding_stamp_save,"Home"))
        next();compose.waitUntil {saves.size==1};at(OnboardingStep.backfill)
        val (first,expected)=saves.single()
        assertEquals("Home",first.name);assertEquals(37.0,first.coordinate.latitude,0.0);assertEquals(-122.0,first.coordinate.longitude,0.0)
        assertEquals(150.0,first.radius,0.0);assertNull(expected)
        compose.onNodeWithTag("onboarding-back").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-place-work").performScrollTo().performClick();next()
        compose.waitUntil {saves.size==2}
        assertEquals(first.id,saves[1].first.id);assertEquals("Work",saves[1].first.name);assertEquals(first,saves[1].second)
        // "Somewhere else" names nothing, so it saves nothing.
        compose.onNodeWithTag("onboarding-back").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-place-other").performScrollTo().performClick();next()
        at(OnboardingStep.backfill);assertEquals(2,saves.size)
    }
    @Test fun stampWithoutLocationFallsBackAndSkips() {
        compose.setContent {MaterialTheme {Onboarding({},{},services=OnboardingServices(locate={error("Not permitted")})) {}}}
        compose.walkOnboarding(OnboardingStep.stamp.ordinal)
        compose.onNodeWithTag("onboarding-stamp-unavailable").assertExists()
        compose.onNodeWithTag("onboarding-place-home").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-continue").assertTextEquals(target.getString(R.string.onboarding_stamp_skip))
        next();at(OnboardingStep.backfill)
        compose.onNodeWithTag("onboarding-heatmap").assertContentDescriptionEquals(target.getString(R.string.onboarding_backfill_heatmap_today))
    }
    @Test fun glanceAsksForNotificationsOnceAndContinues() {
        var asks=0
        compose.setContent {MaterialTheme {Onboarding({},{asks++},access=RecordingAccessState(LocationPrecision.none,false),notificationRequestAvailable=true) {}}}
        compose.walkOnboarding(OnboardingStep.glance.ordinal);at(OnboardingStep.glance)
        compose.onNodeWithTag("onboarding-glance-notification").assertExists()
        compose.onNodeWithTag("onboarding-continue").assertTextEquals(target.getString(R.string.onboarding_allow_notifications)).performClick()
        assertEquals(1,asks);at(OnboardingStep.export)
    }
    @Test fun olderAndroidSkipsTheNotificationRequest() {
        var asks=0
        compose.setContent {MaterialTheme {Onboarding({},{asks++},notificationRequestAvailable=false) {}}}
        compose.walkOnboarding(OnboardingStep.glance.ordinal)
        compose.onNodeWithTag("onboarding-skip").assertDoesNotExist()
        next();assertEquals(0,asks);at(OnboardingStep.export)
    }
    @Test fun exportChipFollowsIntentsAndNotesSetsMarkdown() {
        val formats=mutableListOf<ExportFormat>()
        compose.setContent {MaterialTheme {Onboarding({},{},services=OnboardingServices(saveExportFormat={formats+=it}),
            initialIntents=setOf(OnboardingIntent.countries,OnboardingIntent.routes)) {}}}
        compose.walkOnboarding(OnboardingStep.export.ordinal);at(OnboardingStep.export)
        compose.onNodeWithTag("onboarding-format-gpx").assertIsSelected()
        compose.onNodeWithText("whereday-2026-11-23.gpx").assertExists()
        compose.onNodeWithTag("onboarding-format-csv").performClick().assertIsSelected()
        next();assertTrue(formats.isEmpty()) // Without the notes intent the saved export format is left alone.
        compose.onNodeWithTag("onboarding-back").performScrollTo().performClick()
        repeat(OnboardingStep.export.ordinal-OnboardingStep.intent.ordinal) {compose.onNodeWithTag("onboarding-back").performScrollTo().performClick()}
        at(OnboardingStep.intent);compose.onNodeWithTag("onboarding-intent-notes").performScrollTo().performClick()
        compose.walkOnboarding(OnboardingStep.export.ordinal-OnboardingStep.intent.ordinal)
        compose.onNodeWithTag("onboarding-format-csv").assertIsSelected() // An explicit chip survives.
        next();assertEquals(listOf(ExportFormat.markdown),formats)
    }
    /** The id and the selected state sit on one node, the one accessibility clients (Argent, UiAutomator) report as
     *  resource-id; the Pixel e2e test onboarding-pixel-unconfigured-billing checks that tree on hardware. */
    @Test fun selectedFormatChipKeepsItsId() {
        compose.setContent {MaterialTheme {Onboarding({},{}) {}}}
        compose.walkOnboarding(OnboardingStep.export.ordinal);at(OnboardingStep.export)
        compose.onNodeWithTag("onboarding-export-step").assertExists()
        // Markdown is preselected for the default "Remember my days" intent; selected or not, each chip keeps its id.
        compose.onNode(hasTestTag("onboarding-format-markdown") and isSelected(),useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithTag("onboarding-format-json").performClick()
        compose.onNode(hasTestTag("onboarding-format-json") and isSelected(),useUnmergedTree=true).assertIsDisplayed()
        compose.onNode(hasTestTag("onboarding-format-markdown") and isNotSelected(),useUnmergedTree=true).assertIsDisplayed()
        // Without a plan-selling offer the note leaves out the plan sentence.
        compose.onNodeWithText(target.getString(R.string.onboarding_export_note_free),substring=true).assertExists()
        compose.onNodeWithText(target.getString(R.string.onboarding_export_note),substring=true).assertDoesNotExist()
    }
    @Test fun eveningRecapNeedsNotificationsAndIsPartOfTheDecision() {
        val decisions=mutableListOf<OnboardingDecision>()
        var access by androidx.compose.runtime.mutableStateOf(RecordingAccessState(LocationPrecision.precise,false))
        compose.setContent {MaterialTheme {Onboarding({},{},authorization=UsageAuthorization.foreground,access=access) {decisions+=it}}}
        compose.walkOnboarding();at(OnboardingStep.tonight)
        compose.onNodeWithTag("onboarding-recap-toggle").performScrollTo().assertIsOff().assertIsNotEnabled()
        compose.runOnIdle {access=RecordingAccessState(LocationPrecision.precise,true)}
        compose.onNodeWithTag("onboarding-recap-toggle").performScrollTo().assertIsOn().assertIsEnabled()
            .assert(hasText(target.getString(R.string.onboarding_recap_toggle),substring=true))
        // No "page one closes tonight" promise remains on this step.
        compose.onNodeWithText(target.getString(R.string.onboarding_tonight_title),useUnmergedTree=true).assertExists()
        compose.onNodeWithText("Page one closes tonight",substring=true,useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText(target.getString(R.string.onboarding_tonight_first_place),useUnmergedTree=true).assertExists()
        next();compose.waitUntil {decisions.isNotEmpty()}
        assertTrue(decisions.single().recap);assertTrue(decisions.single().start) // "Start recording immediately" is on by default.
        assertEquals(com.bontecou.whereday.domain.RecapFrequency.daily,OnboardingRecap.frequency(decisions.single()))
    }
    @Test fun everyStepAfterCoverOffersBackAndAccessibleIllustrations() {
        compose.setContent {MaterialTheme {Onboarding({},{}) {}}}
        compose.onNodeWithTag("onboarding-cover-dial").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        // The cover's button starts the journal; it no longer promises "Write page one".
        compose.onNodeWithTag("onboarding-continue").assert(androidx.compose.ui.test.hasText("Start my journal"))
        compose.onNodeWithText("Write page one",useUnmergedTree=true).assertDoesNotExist()
        for(step in OnboardingStep.entries.drop(1)) {
            compose.walkOnboarding(1);at(step)
            compose.onNodeWithTag("onboarding-back").assertExists()
        }
        listOf("onboarding-draft").forEach {compose.onNodeWithTag(it).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))}
    }
}
