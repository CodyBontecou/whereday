package com.bontecou.whereday

import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import org.junit.*
import org.junit.Assert.*

/** Synthetic window features through the real WindowInfoTracker adapter; not physical foldable acceptance. */
class JournalWorkspaceFrameTest {
    @get:Rule(order=0) val windows=WindowLayoutInfoPublisherRule()
    @get:Rule(order=1) val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun bounds(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
    @Test fun unknownPostureDoesNotExposeControlsBeforeTheFirstKnownReport() {
        val folds=mutableStateOf<List<WorkspaceFold>?>(null)
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f)) {
            JournalWorkspaceFrame(true,folds=folds.value,primary={Text("Primary")},companion={Text("Companion")})
        }}
        compose.onNodeWithText("Primary").assertDoesNotExist()
        compose.runOnIdle {folds.value=emptyList()}
        compose.onNodeWithTag("workspace-primary").assertIsDisplayed();compose.onNodeWithTag("workspace-companion").assertIsDisplayed()
    }
    @Test fun trackerBookAndTabletopReportsKeepBothWholePanesOutOfTheHinge() {
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f)) {
            JournalWorkspaceFrame(true,primary={Button({}) {Text("Primary")}},companion={Button({}) {Text("Companion")}})
        }}
        for(axis in listOf(FoldingFeature.Orientation.VERTICAL,FoldingFeature.Orientation.HORIZONTAL)) {
            val feature=FoldingFeature(compose.activity,size=32,orientation=axis)
            windows.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(feature)))
            compose.waitUntil(5000) {
                runCatching {
                    val a=bounds("workspace-primary");val b=bounds("workspace-companion")
                    if(axis==FoldingFeature.Orientation.VERTICAL) a.right<=feature.bounds.left && b.left>=feature.bounds.right
                    else a.bottom<=feature.bounds.top && b.top>=feature.bounds.bottom
                }.getOrDefault(false)
            }
            compose.onNodeWithText("Primary").assertIsDisplayed();compose.onNodeWithText("Companion").assertIsDisplayed()
        }
    }
    @Test fun windowCoordinatesAreTranslatedForAnInsetWorkspace() {
        val fold=FoldingFeature(compose.activity,size=32,orientation=FoldingFeature.Orientation.VERTICAL)
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f)) {
            Box(Modifier.fillMaxSize().padding(start=80.dp,top=60.dp)) {
                JournalWorkspaceFrame(true,primary={Text("Inset primary")},companion={Text("Inset companion")})
            }
        }}
        compose.runOnIdle {windows.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(fold)))}
        compose.waitUntil(5000) {runCatching {bounds("workspace-primary").right<=fold.bounds.left && bounds("workspace-companion").left>=fold.bounds.right}.getOrDefault(false)}
        assertTrue(bounds("workspace-primary").left>=80)
    }
    @Test fun rtlUsesThePhysicalRightPaneAsPrimaryAndIndependentDestinationsUseOneSafePane() {
        val independent=mutableStateOf(false)
        val fold=FoldingFeature(compose.activity,size=32,orientation=FoldingFeature.Orientation.VERTICAL)
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f),LocalLayoutDirection provides LayoutDirection.Rtl) {
            JournalWorkspaceFrame(!independent.value,primary={Text("Primary")},companion={Text("Companion")})
        }}
        compose.runOnIdle {windows.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(fold)))}
        compose.waitUntil(5000) {runCatching {bounds("workspace-primary").left>=fold.bounds.right}.getOrDefault(false)}
        compose.runOnIdle {independent.value=true}
        compose.onNodeWithTag("workspace-companion").assertDoesNotExist()
        val pane=bounds("workspace-primary");assertTrue(pane.right<=fold.bounds.left || pane.left>=fold.bounds.right)
    }
    @Test fun swappingPrimaryAndCompanionRetainsTheSameLiveDraftsAndRememberedOwners() {
        val reversed=mutableStateOf(false);val ids=mutableMapOf<String,Any>()
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f)) {
            val first=rememberWorkspaceContent {val token=remember {Any()};SideEffect {ids["first"]=token};var value by rememberSaveable {mutableStateOf("")};OutlinedTextField(value,{value=it},Modifier.testTag("first-draft"))}
            val second=rememberWorkspaceContent {val token=remember {Any()};SideEffect {ids["second"]=token};var value by rememberSaveable {mutableStateOf("")};OutlinedTextField(value,{value=it},Modifier.testTag("second-draft"))}
            JournalWorkspaceFrame(true,folds=emptyList(),primary={if(reversed.value) second() else first()},companion={if(reversed.value) first() else second()})
        }}
        compose.onNodeWithTag("first-draft").performTextReplacement("First unsaved")
        compose.onNodeWithTag("second-draft").performTextReplacement("Second unsaved")
        val original=compose.runOnIdle {ids.toMap()}
        compose.runOnIdle {reversed.value=true}
        compose.onNodeWithTag("first-draft").assertTextEquals("First unsaved");compose.onNodeWithTag("second-draft").assertTextEquals("Second unsaved")
        compose.runOnIdle {assertSame(original["first"],ids["first"]);assertSame(original["second"],ids["second"])}
    }
    @Test fun collapsingAndReopeningCompanionRetainsItsSaveableDraftWithoutHiddenInteractiveNodes() {
        val expanded=mutableStateOf(true)
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(1f)) {
            val states=rememberSaveableStateHolder()
            val companion=rememberWorkspaceContent {states.SaveableStateProvider("companion") {var text by rememberSaveable {mutableStateOf("")};OutlinedTextField(text,{text=it},Modifier.testTag("companion-draft"))}}
            JournalWorkspaceFrame(expanded.value,folds=emptyList(),primary={Text("Primary")},companion={companion()})
        }}
        compose.onNodeWithTag("companion-draft").performTextReplacement("Unfinished")
        compose.runOnIdle {expanded.value=false};compose.onNodeWithTag("companion-draft").assertDoesNotExist()
        compose.runOnIdle {expanded.value=true};compose.onNodeWithTag("companion-draft").assertTextEquals("Unfinished")
    }
}
