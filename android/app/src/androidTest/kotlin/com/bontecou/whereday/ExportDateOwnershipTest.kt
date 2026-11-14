package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.export.ExportOptions
import org.junit.*
import org.junit.Assert.*
import java.time.*

class ExportDateOwnershipTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val zone=ZoneId.of("America/New_York")
    private val initial=ExportOptions(from=Instant.parse("2024-03-09T05:00:00Z"),until=Instant.parse("2024-03-11T03:59:59.999999999Z"),zone=zone)
    private val options=mutableStateOf(initial)
    @Before fun guard() {check(target.packageName=="com.bontecou.whereday.mapqa")}
    private fun open() {
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {ExportFilters(options.value,{options.value=it},options.value.kind)}}}
        compose.onNodeWithTag("export-date-range").performScrollTo().performClick()
        compose.onNodeWithText(target.getString(R.string.date_enter_iso)).assertIsDisplayed().performClick()
        compose.onNodeWithTag("date-range-from").performTextReplacement("2024-03-10")
        compose.onNodeWithTag("date-range-through").performTextReplacement("2024-03-10")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
    }
    private fun approval()=compose.onNodeWithText(target.getString(R.string.map_apply_dates)).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
    @Test fun heldApplyUsesTheCurrentUnrelatedFilters() {
        open();val old=approval()
        compose.runOnIdle {options.value=options.value.copy(maximumAccuracy=8.5,pointAltitude=false,completedOnly=true)}
        compose.waitForIdle();compose.runOnIdle {old()}
        assertEquals(initial.copy(from=Instant.parse("2024-03-10T05:00:00Z"),maximumAccuracy=8.5,pointAltitude=false,completedOnly=true),options.value)
    }
    @Test fun changedRangeInvalidatesTheOldReviewAndItsHeldApply() {
        open();val old=approval()
        val changed=initial.copy(from=Instant.parse("2024-03-01T05:00:00Z"))
        compose.runOnIdle {options.value=changed};compose.waitForIdle()
        compose.runOnIdle {old()}
        assertEquals(changed,options.value)
        compose.onNodeWithTag("date-range-from").assertDoesNotExist()
    }
}
