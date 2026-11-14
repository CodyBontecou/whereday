package com.bontecou.whereday

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.export.*
import java.time.*
import org.junit.*
import org.junit.Assert.*

class ExportFiltersTest {
    @get:Rule val compose=createComposeRule()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    @Test fun old1970BoundIsExplicitUntilTheUserResetsWithoutChangingFieldChoices() {
        var options by mutableStateOf(ExportOptions(from=Instant.EPOCH,zone=ZoneOffset.UTC,pointAltitude=false))
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {ExportFilters(options,{options=it},DataKind.points)}}}
        compose.onNodeWithText("From Jan 1, 1970").assertIsDisplayed()
        assertFalse(options.includes(Instant.parse("1969-12-31T23:59:59.125Z")))
        compose.onNodeWithText("Reset to all dates").performClick()
        compose.onNodeWithText("All dates").assertIsDisplayed()
        assertEquals(ExportOptions.allDatesStart,options.from);assertEquals(Instant.MAX,options.until)
        assertTrue(options.includes(Instant.parse("1969-12-31T23:59:59.125Z")));assertFalse(options.pointAltitude)
        compose.onNodeWithText("All dates").performClick()
        compose.onNodeWithText("Choose dates").assertIsDisplayed();compose.onNodeWithText("Apply").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
    }
    @Test fun openStartAndFiniteEndAreShownAsAnUpperBound() {
        val options=ExportOptions(until=Instant.parse("1969-12-31T23:59:59.999Z"),zone=ZoneOffset.UTC)
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {ExportFilters(options,{},DataKind.points)}}}
        compose.onNodeWithText("Through Dec 31, 1969").assertIsDisplayed()
        assertTrue(options.includes(Instant.parse("1969-01-01T00:00:00Z")))
        compose.onNodeWithText("Through Dec 31, 1969").performClick()
        compose.onNodeWithText("Choose dates").assertIsDisplayed();compose.onNodeWithText("Apply").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
    }
    @Test fun futureOpenEndInitializesAnOrderedPickerRange() {
        val options=ExportOptions(from=Instant.parse("2099-01-01T00:00:00Z"),zone=ZoneOffset.UTC)
        compose.setContent {MaterialTheme {Column(Modifier.verticalScroll(rememberScrollState())) {ExportFilters(options,{},DataKind.points)}}}
        compose.onNodeWithText("From Jan 1, 2099").performClick()
        compose.onNodeWithText("Choose dates").assertIsDisplayed();compose.onNodeWithText("Apply").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
    }

}
