package com.bontecou.whereday

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.RecapFacts
import com.bontecou.whereday.domain.RecapFrequency
import com.bontecou.whereday.domain.RecapSettings
import org.junit.*
import org.junit.Assert.*
import java.time.ZonedDateTime

/** Settings › Reminders with synthetic facts; no journal, notification or WorkManager access is used. */
class RemindersScreenTest {
    @get:Rule val compose=createComposeRule()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext

    private var settings by mutableStateOf(RecapSettings(RecapFrequency.daily))
    private var facts by mutableStateOf<RecapFacts?>(null)
    private var allowed by mutableStateOf(true)
    private var metric by mutableStateOf(true)
    private val changes=mutableListOf<RecapSettings>()
    private var opened=0
    private var backs=0

    private fun show() {
        compose.setContent {MaterialTheme {
            RemindersContent(settings,facts,metric,allowed,ZonedDateTime.now(),{backs++},{opened++}) {settings=it;changes+=it}
        }}
    }
    private fun preview(tag:String)=compose.onNodeWithTag("reminders-preview-$tag",useUnmergedTree=true)

    @Test fun defaultsShowAnExampleOfTonightsRecap() {
        show()
        compose.onNodeWithTag("reminders-frequency-daily").assertIsSelected()
        compose.onNodeWithTag("reminders-time-21").assertIsSelected()
        compose.onNodeWithTag("reminders-quiet").assertIsOn()
        compose.onNodeWithTag("reminders-names").assertIsOff()
        preview("title").assertTextEquals("Your day")
        preview("when").assertTextEquals("Daily 9:00 PM")
        preview("body").assertTextEquals("4 places and 5.6 km. Tap to see it on the map.")
        preview("example").assertExists()
        compose.onNodeWithTag("reminders-blocked").assertDoesNotExist()
    }
    @Test fun thePreviewUsesTheJournalSoFarAndFollowsUnitsAndNames() {
        facts=RecapFacts(3,listOf("Home","Corner café","Riverside park"),2_400.0,null,recorded=true,tracked=true,newPlace=false)
        show()
        preview("body").assertTextEquals("3 places and 2.4 km. Tap to see it on the map.")
        preview("example").assertDoesNotExist()
        compose.runOnIdle {metric=false}
        preview("body").assertTextEquals("3 places and 1.5 mi. Tap to see it on the map.")
        compose.onNodeWithTag("reminders-names").performScrollTo().performClick().assertIsOn()
        assertTrue(changes.last().placeNames)
        preview("body").assertTextEquals("Home, Corner café and Riverside park. 1.5 mi.")
    }
    @Test fun frequencyTimeAndQuietDaysAreSaved() {
        show()
        compose.onNodeWithTag("reminders-frequency-weekly").performScrollTo().performClick().assertIsSelected()
        assertEquals(RecapFrequency.weekly,changes.last().frequency)
        preview("title").assertTextEquals("Your week")
        preview("when").assertTextEquals("Sun 9:00 PM")
        preview("body").assertTextEquals("11 places, 1,520 km and 2 countries. Tap to see the week.")
        compose.onNodeWithTag("reminders-frequency-weekdays").performScrollTo().performClick()
        preview("when").assertTextEquals("Mon–Fri 9:00 PM")
        compose.onNodeWithTag("reminders-time-22").performScrollTo().performClick().assertIsSelected()
        assertEquals(22*60,changes.last().minuteOfDay)
        preview("when").assertTextEquals("Mon–Fri 10:00 PM")
        compose.onNodeWithTag("reminders-quiet").performScrollTo().performClick().assertIsOff()
        assertFalse(changes.last().skipQuietDays)
        assertEquals(RecapSettings(RecapFrequency.weekdays,22*60,skipQuietDays=false,placeNames=false),settings)
    }
    @Test fun customTimeOpensAPickerStartingHalfAnHourLater() {
        show()
        compose.onNodeWithTag("reminders-time-custom").performScrollTo().performClick()
        compose.onNodeWithTag("reminders-time-dialog").assertExists()
        compose.onNodeWithTag("reminders-time-confirm").performClick()
        assertEquals(21*60+30,changes.last().minuteOfDay)
        compose.onNodeWithTag("reminders-time-custom").assertIsSelected().assertTextContains("9:30 PM",substring=true)
        compose.onNodeWithTag("reminders-time-21").assertIsNotSelected()
        preview("when").assertTextEquals("Daily 9:30 PM")
        // Cancel keeps the time.
        compose.onNodeWithTag("reminders-time-custom").performClick()
        compose.onNodeWithTag("reminders-time-cancel").performClick()
        assertEquals(1,changes.size)
    }
    @Test fun offExplainsAndDisablesTheRest() {
        show()
        compose.onNodeWithTag("reminders-frequency-off").performScrollTo().performClick()
        assertEquals(RecapFrequency.off,changes.last().frequency)
        compose.onNodeWithTag("reminders-preview-off",useUnmergedTree=true).assertTextEquals(target.getString(R.string.reminders_preview_off))
        preview("title").assertDoesNotExist()
        compose.onNodeWithTag("reminders-time-20").assertIsNotEnabled()
        compose.onNodeWithTag("reminders-time-custom").assertIsNotEnabled()
        compose.onNodeWithTag("reminders-quiet").assertIsNotEnabled()
        compose.onNodeWithTag("reminders-names").assertIsNotEnabled()
    }
    @Test fun blockedNotificationsOpenTheAppsNotificationSettings() {
        allowed=false
        show()
        compose.onNodeWithTag("reminders-blocked").assertExists()
        compose.onNodeWithTag("reminders-open-notification-settings").performScrollTo().performClick()
        assertEquals(1,opened)
        compose.runOnIdle {allowed=true}
        compose.onNodeWithTag("reminders-blocked").assertDoesNotExist()
        compose.onNodeWithTag("reminders-back").performClick()
        assertEquals(1,backs)
    }
}
