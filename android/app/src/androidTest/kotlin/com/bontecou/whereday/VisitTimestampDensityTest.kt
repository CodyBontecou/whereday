package com.bontecou.whereday

import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.util.TimeZone

class VisitTimestampDensityTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun switchingToLargeTextRetainsTheEditedAnalogClockValue() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")
        val previous=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        try {
            var font by mutableFloatStateOf(1f);var result:Instant?=null
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,font)) {
                    MaterialTheme {VisitDateTimeControl("Arrived",Instant.parse("2026-10-31T05:30:45.125Z")) {result=it}}
                }
            }
            compose.onNodeWithTag("visit-timestamp-time").performClick()
            val resource=if(DateFormat.is24HourFormat(compose.activity)) "m3c_time_picker_hour_24h_suffix" else "m3c_time_picker_hour_suffix"
            val id=compose.activity.resources.getIdentifier(resource,"string",compose.activity.packageName);check(id!=0)
            val four=compose.activity.getString(id,4)
            compose.onNodeWithContentDescription(four).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) {it()}
            compose.onNodeWithText("04").assertIsDisplayed()
            compose.runOnIdle {font=1.8f}
            compose.onNodeWithTag("visit-timestamp-hour").assertTextContains("04")
            compose.onNodeWithText("Apply time").performClick()
            assertEquals(Instant.parse("2026-10-31T08:30:45.125Z"),result)
        } finally {TimeZone.setDefault(previous)}
    }
}
