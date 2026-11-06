package com.bontecou.whereday

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.test.filters.SdkSuppress
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import java.time.*

class CustomDateRangeTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}

    @Test fun typedImpossibleDatesCannotSilentlyNormalizeAndSave() {
        val first=LocalDate.of(2026,2,1);val last=LocalDate.of(2026,3,1)
        var saved:Pair<LocalDate,LocalDate>?=null
        compose.setContent {MaterialTheme {CustomDateRange(first,last,{}) {a,b->saved=a to b}}}
        // Exercise the public text-entry route on both the old SDK input and strict ISO UI.
        val sdkInput=compose.onAllNodesWithContentDescription("Switch to text input mode")
        if(sdkInput.fetchSemanticsNodes().isNotEmpty()) {
            sdkInput[0].performClick()
            compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("02302026")
        } else {
            compose.onNodeWithText("Enter ISO dates").performClick()
            compose.onNodeWithTag("date-range-from").performTextReplacement("2026-02-30")
        }
        closeKeyboard()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        // A disabled semantic action must also preserve the save boundary.
        compose.onNodeWithText("Apply").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.runOnIdle {assertNull("An impossible civil date must never become a different saved day",saved)}
    }

    @Test fun invalidDateDraftSurvivesRestorationAndCanBeCorrectedToALeapDay() {
        val first=LocalDate.of(2024,2,1);val last=LocalDate.of(2024,3,1)
        var saved:Pair<LocalDate,LocalDate>?=null
        val tester=StateRestorationTester(compose)
        tester.setContent {MaterialTheme {CustomDateRange(first,last,{}) {a,b->saved=a to b}}}
        compose.onNodeWithContentDescription("Switch to text input mode").assertDoesNotExist()
        compose.onNodeWithText("Enter ISO dates").performClick()
        val start=compose.onNodeWithTag("date-range-from")
        val end=compose.onNodeWithTag("date-range-through")
        for(value in listOf("2024-02-30","2023-02-29","2024-04-31")) {
            start.performTextReplacement(value);closeKeyboard()
            compose.onNodeWithText("Apply").assertIsNotEnabled()
        }
        start.performTextReplacement("2024-02-29")
        end.performTextReplacement("2024-02-30")
        tester.emulateSavedInstanceStateRestore();closeKeyboard()
        end.assertTextContains("2024-02-30")
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        end.performTextReplacement("2024-02-29");closeKeyboard()
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(LocalDate.of(2024,2,29) to LocalDate.of(2024,2,29),saved)}
    }

    @Test fun historicalDatesRemainSelectedWhenApplied() {
        val first=LocalDate.of(1850,12,31);val last=LocalDate.of(1851,1,2)
        var result:Pair<LocalDate,LocalDate>?=null
        compose.setContent {MaterialTheme {CustomDateRange(first,last,{}) {a,b->result=a to b}}}
        // An enabled Apply alone missed a large-text headline that hid the entire calendar.
        compose.onNodeWithText("December 1850").assertIsDisplayed()
        capture("historical-calendar")
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        assertEquals(first to last,result)
    }

    @Test fun datesBeyondTheOldUpperYearRemainSelectedWhenApplied() {
        val first=LocalDate.of(2200,1,1);val last=LocalDate.of(2200,1,2)
        var result:Pair<LocalDate,LocalDate>?=null
        compose.setContent {MaterialTheme {CustomDateRange(first,last,{}) {a,b->result=a to b}}}
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        assertEquals(first to last,result)
    }

    @Test fun isoDatesPreserveYearZeroAndSurviveRestoration() {
        val first=LocalDate.of(0,12,30);val last=LocalDate.of(2026,10,31)
        var result:Pair<LocalDate,LocalDate>?=null
        val tester=StateRestorationTester(compose)
        tester.setContent {MaterialTheme {CustomDateRange(first,last,{}) {a,b->result=a to b}}}
        compose.onNodeWithTag("date-range-from").assertTextContains("0000-12-30")
        compose.onNodeWithTag("date-range-through").performTextReplacement("2026-11-01")
        tester.emulateSavedInstanceStateRestore()
        closeKeyboard();capture("iso-restored")
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        assertEquals(first to last.plusDays(1),result)
    }

    @Test fun skippedLocalDateIsRejectedAndTheRangeCanBeCorrected() {
        val skipped=LocalDate.of(2011,12,30)
        var result:Pair<LocalDate,LocalDate>?=null
        compose.setContent {MaterialTheme {CustomDateRange(skipped,skipped,{},zone=ZoneId.of("Pacific/Apia")) {a,b->result=a to b}}}
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithText("These dates did not occur in the selected time zone. Choose a range that includes an existing day.").assertExists()
        capture("skipped-day")
        compose.onNodeWithText("Enter ISO dates").performClick()
        compose.onNodeWithTag("date-range-through").performTextReplacement("2011-12-31")
        closeKeyboard()
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        assertEquals(skipped to skipped.plusDays(1),result)
    }

    @Test fun isoInputValidatesOrderFutureMalformedAndOutOfStorageRangeDates() {
        val today=LocalDate.of(2026,10,31)
        var saved=0
        compose.setContent {MaterialTheme {CustomDateRange(today,today,{},latestDate=today) {_,_->saved++}}}
        compose.onNodeWithText("Enter ISO dates").performClick()
        val end=compose.onNodeWithTag("date-range-through")
        for(value in listOf("2026-10-30","2026-11-01","2026-02-30","bad","+999999999-12-31")) {
            end.performTextReplacement(value);closeKeyboard()
            compose.onNodeWithText("Apply").assertIsNotEnabled()
        }
        compose.onNodeWithTag("date-range-from").performTextReplacement("-999999999-01-01")
        end.performTextReplacement(today.toString());closeKeyboard()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.onNodeWithTag("date-range-from").performTextReplacement(today.toString());closeKeyboard()
        compose.onNodeWithText("Apply").assertIsEnabled().performClick();assertEquals(1,saved)
    }

    @Test fun switchingBetweenCalendarAndIsoKeepsTheEditedDates() {
        val day=LocalDate.of(2026,10,31)
        var result:Pair<LocalDate,LocalDate>?=null
        compose.setContent {MaterialTheme {CustomDateRange(day,day,{}) {a,b->result=a to b}}}
        compose.onNodeWithText("Enter ISO dates").performClick()
        compose.onNodeWithTag("date-range-from").performTextReplacement("1850-12-31")
        compose.onNodeWithTag("date-range-through").performTextReplacement("1851-01-02")
        closeKeyboard();compose.onNodeWithText("Use calendar").performScrollTo().performClick()
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        assertEquals(LocalDate.of(1850,12,31) to LocalDate.of(1851,1,2),result)
    }

    @Test fun civilCountryDatesAreNotRejectedByThePhonesTimeZone() {
        val day=LocalDate.of(2011,12,30)
        var result:Pair<LocalDate,LocalDate>?=null
        compose.setContent {MaterialTheme {CustomDateRange(day,day,{},zone=null) {a,b->result=a to b}}}
        compose.onNodeWithText("Apply").assertIsEnabled().performClick();assertEquals(day to day,result)
    }
    @Test @SdkSuppress(minSdkVersion=33)
    fun spanishIsoEntryUsesTheActualAppLocaleAndRetainsDates() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val previous=target.getSystemService(android.app.LocaleManager::class.java).applicationLocales
        try {
            applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
            val first=LocalDate.of(0,12,30);val last=LocalDate.of(2026,10,31)
            var result:Pair<LocalDate,LocalDate>?=null
            compose.setContent {MaterialTheme {CustomDateRange(first,last,{}) {a,b->result=a to b}}}
            compose.onNodeWithText("Elegir fechas").assertIsDisplayed()
            compose.onNodeWithText("Desde").assertIsDisplayed();compose.onNodeWithText("Hasta").assertIsDisplayed()
            capture("spanish-iso")
            compose.onNodeWithText("Aplicar").assertIsDisplayed().assertIsEnabled().performClick()
            assertEquals(first to last,result)
        } finally {applyQaAppLocales(compose,previous)}
    }
    private fun capture(name:String) {
        val arguments=InstrumentationRegistry.getArguments()
        if(arguments.getString("captureCustomDates")!="true") return
        compose.waitForIdle()
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val label=arguments.getString("captureLabel") ?: "normal"
        check(label in listOf("normal","large"))
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-custom-dates-$name-$label-20261031.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=stableScreenshot()
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
    private fun stableScreenshot():android.graphics.Bitmap {
        // Compose can be idle while WindowManager is still fading in a dialog.
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        var previous:android.graphics.Bitmap?=null
        var equalFrames=0
        val deadline=android.os.SystemClock.elapsedRealtime()+5000
        try {
            while(android.os.SystemClock.elapsedRealtime()<deadline) {
                val current=checkNotNull(automation.takeScreenshot())
                equalFrames=if(previous?.sameAs(current)==true) equalFrames+1 else 0
                previous?.recycle();previous=current
                if(equalFrames>=2) {previous=null;return current}
                android.os.SystemClock.sleep(100)
            }
            error("Date dialog pixels did not settle before capture")
        } finally {previous?.recycle()}
    }
    private fun closeKeyboard() {
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
    }
    @Test fun heldCalendarApplyCannotCommitAfterSwitchingToInvalidIsoInput() {
        val day=LocalDate.of(2024,3,10);var result:Pair<LocalDate,LocalDate>?=null
        compose.setContent {MaterialTheme {CustomDateRange(day,day,{}) {a,b->result=a to b}}}
        val obsolete=compose.onNodeWithText("Apply").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        compose.onNodeWithText("Enter ISO dates").performClick()
        compose.onNodeWithTag("date-range-from").performTextReplacement("2024-02-30");closeKeyboard()
        compose.onNodeWithText("Apply").assertIsNotEnabled()
        compose.runOnIdle {obsolete();assertNull("An old valid calendar action cannot apply over the current invalid ISO draft",result)}
    }

}
