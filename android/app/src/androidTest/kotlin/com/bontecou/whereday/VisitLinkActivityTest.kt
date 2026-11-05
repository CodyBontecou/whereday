package com.bontecou.whereday

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.bontecou.whereday.platform.AppGraph
import com.bontecou.whereday.platform.RecordingRuntime
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class VisitLinkActivityTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private lateinit var graph:AppGraph
    @Before fun setup()=runBlocking {check(context.packageName=="com.bontecou.whereday.mapqa");graph=AppGraph.from(context);graph.journalClear.clear().join();graph.preferences.update {it.copy(onboarded=true)}}
    @After fun cleanup()=runBlocking {graph.journalClear.clear().join()}
    private fun intent(url:String)=Intent(Intent.ACTION_VIEW,Uri.parse(url)).setPackage(context.packageName)
    private fun awaitName(name:String) {compose.waitUntil(15000) {compose.onAllNodesWithTag("visit-name").fetchSemanticsNodes(atLeastOneRootRequired=false).any {runCatching {compose.onNodeWithTag("visit-name").assertTextContains(name)}.isSuccess}}}
    @Test fun browsableColdLaunchAndRecreationKeepOneUnsavedDraft()=runBlocking {
        val link="whereday://check-in?name=Cold%20link&latitude=18&longitude=-66"
        assertEquals(MainActivity::class.java.name,context.packageManager.resolveActivity(intent(link),0)!!.activityInfo.name)
        ActivityScenario.launch<MainActivity>(intent(link)).use {scenario->
            awaitName("Cold link");assertEquals(0L,graph.history.dao.totalVisitCount())
            compose.onNodeWithTag("visit-name").performTextReplacement("My restored link")
            scenario.recreate();awaitName("My restored link")
            assertEquals(0L,graph.history.dao.totalVisitCount());assertFalse(RecordingRuntime.active.value)
        }
    }
    @Test fun onboardingRetainsTheLinkAcrossRecreationWithoutStartingRecording()=runBlocking {
        graph.preferences.update {it.copy(onboarded=false)}
        ActivityScenario.launch<MainActivity>(intent("isome://log-visit?name=After%20onboarding")).use {scenario->
            compose.waitUntil(15000) {compose.onAllNodesWithText(context.getString(R.string.common_continue)).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            scenario.recreate()
            graph.preferences.update {it.copy(onboarded=true)}
            awaitName("After onboarding")
            assertFalse(RecordingRuntime.active.value);assertEquals(0L,graph.history.dao.totalVisitCount())
        }
    }
    @Test fun warmBrowsableDeliveryReusesMainActivityAndWaitsForTheUnfinishedEditor()=runBlocking {
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun resumed()=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
        try {
            // The home map's + opens the add menu; "Log a past stop" is the old Add past visit.
            compose.waitUntil(15000) {compose.onAllNodesWithTag("map-add").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithTag("map-add").performClick();compose.onNodeWithTag("map-add-past").performClick()
            compose.onNodeWithTag("visit-name").performTextReplacement("Unfinished native draft")
            val url="isome://check-in?name=Warm%20link"
            instrumentation.runOnMainSync {activity.startActivity(intent(url))}
            compose.waitUntil(5000) {runCatching {compose.runOnUiThread {resumed().intent.dataString==url}}.getOrDefault(false)}
            instrumentation.runOnMainSync {assertSame(activity,resumed());assertEquals(url,resumed().intent.dataString)}
            compose.onNodeWithTag("visit-name").assertTextContains("Unfinished native draft")
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot()).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
            compose.onNodeWithText(context.getString(R.string.visit_cancel)).performClick()
            compose.onNodeWithText(context.getString(R.string.visit_discard)).performClick()
            awaitName("Warm link");assertEquals(0L,graph.history.dao.totalVisitCount());assertFalse(RecordingRuntime.active.value)
            instrumentation.runOnMainSync {resumed().recreate()};awaitName("Warm link")
        } finally {
            instrumentation.runOnMainSync {Stage.values().flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.filterIsInstance<MainActivity>().distinct().forEach {it.finish()}}
        }
    }
    @Test fun switchingBackToMapForALinkRestoresItsUnfinishedEditorBeforeOpeningAnother()=runBlocking {
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun resumed()=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
        try {
            // The home map's + opens the add menu; "Log a past stop" is the old Add past visit.
            compose.waitUntil(15000) {compose.onAllNodesWithTag("map-add").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithTag("map-add").performClick();compose.onNodeWithTag("map-add-past").performClick()
            compose.onNodeWithTag("visit-name").performTextReplacement("Retained map draft")
            instrumentation.runOnMainSync {activity.startActivity(Intent(activity,MainActivity::class.java).setAction(LauncherAction.timeline.action))}
            compose.waitUntil(15000) {compose.onAllNodes(hasTestTag("timeline-content")).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            instrumentation.runOnMainSync {resumed().startActivity(intent("isome://check-in?name=After%20retained%20draft"))}
            awaitName("Retained map draft")
            assertEquals(0L,graph.history.dao.totalVisitCount())
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot()).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
            compose.onNodeWithText(context.getString(R.string.visit_cancel)).performClick()
            compose.onNodeWithText(context.getString(R.string.visit_discard)).performClick()
            awaitName("After retained draft");assertEquals(0L,graph.history.dao.totalVisitCount())
        } finally {instrumentation.runOnMainSync {Stage.values().flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.filterIsInstance<MainActivity>().distinct().forEach {it.finish()}}}
    }
    @Test fun incomingLinkWaitsForASavedPlaceEditorInSettings()=runBlocking {
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun resumed()=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
        try {
            compose.openSettings()
            compose.onNodeWithTag("settings-screen").performScrollToNode(hasText(context.getString(R.string.settings_manage_places)))
            compose.onNodeWithText(context.getString(R.string.settings_manage_places)).performClick()
            compose.onNodeWithText(context.getString(R.string.saved_add)).performClick()
            compose.onNodeWithTag("saved-name").performTextReplacement("Unfinished saved place")
            val url="isome://check-in?name=After%20saved%20place"
            instrumentation.runOnMainSync {resumed().startActivity(intent(url))}
            compose.waitUntil(5000) {runCatching {compose.runOnUiThread {resumed().intent.dataString==url}}.getOrDefault(false)}
            compose.mainClock.advanceTimeByFrame();compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
            compose.onNodeWithTag("saved-name").assertTextContains("Unfinished saved place")
            // Settings (behind Archive on the phone bar) keeps the saved-place editor open over its page.
            compose.onNode(hasTestTag("navigation-archive") and isSelected()).assertExists()
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot()).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
            compose.onNodeWithTag("saved-editor-cancel").performClick()
            compose.onNodeWithText(context.getString(R.string.saved_discard_action)).performClick()
            awaitName("After saved place");assertTrue(graph.history.dao.places().isEmpty());assertEquals(0L,graph.history.dao.totalVisitCount())
        } finally {instrumentation.runOnMainSync {Stage.values().flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.filterIsInstance<MainActivity>().distinct().forEach {it.finish()}}}
    }
    @Test fun wheredayStopAliasResolvesWithoutStartingRecordingOrOpeningAVisitEditor()=runBlocking {
        val link=intent("whereday://stop")
        assertEquals(MainActivity::class.java.name,context.packageManager.resolveActivity(link,0)!!.activityInfo.name)
        ActivityScenario.launch<MainActivity>(link).use {
            compose.waitUntil(15000) {compose.onAllNodesWithTag("navigation-map").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithTag("visit-name").assertDoesNotExist()
            assertFalse(RecordingRuntime.active.value);assertTrue(graph.history.dao.recording()?.requested!=true)
            assertEquals(0L,graph.history.dao.totalVisitCount())
        }
    }
    @Test fun journalClearAndRecreationCannotRestoreTheOldLinkedDraft()=runBlocking {
        ActivityScenario.launch<MainActivity>(intent("isome://check-in?name=Private%20link%20draft")).use {scenario->
            awaitName("Private link draft")
            graph.journalClear.clear().join()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("visit-name").fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty()}
            scenario.recreate()
            compose.waitUntil(15000) {compose.onAllNodesWithTag("navigation-map").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithTag("visit-name").assertDoesNotExist();assertEquals(0L,graph.history.dao.totalVisitCount())
        }
    }
}
