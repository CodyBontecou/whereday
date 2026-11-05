package com.bontecou.whereday

import android.content.ContentValues
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicLong

/** Disposable mapQa, synthetic data only; no GPS, history write, network or permission request. */
class CurrentSavedPlaceSuggestionTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val origin=Coordinate(0.0,0.0)
    private val place=SavedPlace("synthetic","QA saved place",Coordinate(.001,0.0),"Synthetic street",150.0)
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    @Test fun explicitUseReturnsTheCompleteSavedPlaceInsteadOfTheGpsCoordinate() {
        var selected:SavedPlace?=null
        compose.setContent {MaterialTheme {CurrentSavedPlaceSuggestion(CurrentVisitLocation(origin,5.0,0),listOf(place),10,{selected=it},{10})}}
        compose.onNodeWithText(place.name).assertIsDisplayed()
        compose.onNodeWithText(place.address!!).assertIsDisplayed()
        compose.onNodeWithTag("use-current-saved-place").performClick()
        assertEquals(place,selected);assertNotEquals(origin,selected!!.coordinate)
    }
    @Test fun expiryBetweenRenderAndQueuedClickCannotSelectThePlace() {
        val now=AtomicLong(10);var selections=0
        compose.setContent {MaterialTheme {CurrentSavedPlaceSuggestion(CurrentVisitLocation(origin,5.0,0),listOf(place),10,{selections++},{now.get()})}}
        compose.onNodeWithTag("use-current-saved-place").assertIsDisplayed()
        now.set(300_001)
        compose.onNodeWithTag("use-current-saved-place").performClick()
        assertEquals(0,selections)
    }
    @Test fun editedAndRemovedPlacesRefreshTheSuggestionAndNeverSelectAnOldSnapshot() {
        var places by mutableStateOf(listOf(place));var selected:SavedPlace?=null
        compose.setContent {MaterialTheme {CurrentSavedPlaceSuggestion(CurrentVisitLocation(origin,5.0,0),places,10,{selected=it},{10})}}
        val edited=place.copy(name="Updated QA place",address="Updated street")
        compose.runOnIdle {places=listOf(edited)}
        compose.onNodeWithText(edited.name).assertIsDisplayed()
        compose.onNodeWithTag("use-current-saved-place").performClick()
        assertEquals(edited,selected)
        compose.runOnIdle {places=emptyList()}
        compose.onNodeWithTag("current-saved-place-suggestion").assertDoesNotExist()
    }
    @Test fun aHeldSavedPlaceActionCannotSelectAfterThePublishedLibraryRemovesIt() {
        var places by mutableStateOf(listOf(place));var selected:SavedPlace?=null
        compose.setContent {MaterialTheme {CurrentSavedPlaceSuggestion(CurrentVisitLocation(origin,5.0,0),places,10,{selected=it},{10})}}
        val use=compose.onNodeWithTag("use-current-saved-place").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {places=emptyList()}
        compose.onNodeWithTag("current-saved-place-suggestion").assertDoesNotExist()
        compose.runOnIdle {use()};assertNull(selected)
    }
    @Test fun staleFutureAndMissingLocationsNeverOfferAPlace() {
        var fix by mutableStateOf<CurrentVisitLocation?>(null)
        compose.setContent {MaterialTheme {CurrentSavedPlaceSuggestion(fix,listOf(place),300_001,{}, {300_001})}}
        compose.onNodeWithTag("use-current-saved-place").assertDoesNotExist()
        compose.runOnIdle {fix=CurrentVisitLocation(origin,5.0,0)}
        compose.onNodeWithTag("use-current-saved-place").assertDoesNotExist()
        compose.runOnIdle {fix=CurrentVisitLocation(origin,5.0,300_002)}
        compose.onNodeWithTag("use-current-saved-place").assertDoesNotExist()
    }
    @Test fun longSavedPlaceInDarkLargeTextDialogKeepsItsUseActionReachable() {
        val long=place.copy(name="QA Library and Community Center with a deliberately long saved name",address="Synthetic second floor, accessible entrance on the other side of the public square")
        var selected:SavedPlace?=null
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.8f)) {
            MaterialTheme(colorScheme=darkColorScheme()) {
                AlertDialog(onDismissRequest={},title={Text("Find current location")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
                    CurrentSavedPlaceSuggestion(CurrentVisitLocation(origin,5.0,0),listOf(long),10,{selected=it},{10})
                }},confirmButton={TextButton(onClick={}) {Text("Use current location")}},dismissButton={TextButton(onClick={}) {Text("Cancel current location")}})
            }
        }}
        compose.onNodeWithTag("use-current-saved-place").performScrollTo().assertIsDisplayed()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val values=ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,"whereday-current-saved-place-qa-dark-v2-20261029.png")
            put(MediaStore.Downloads.MIME_TYPE,"image/png")
            put(MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)!!
        context.contentResolver.openOutputStream(uri)!!.use {stream->
            // Dialog lives in a separate Window; Compose PixelCopy may capture the empty Activity.
            val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
        compose.onNodeWithTag("use-current-saved-place").performClick()
        assertEquals(long,selected)
    }
}
