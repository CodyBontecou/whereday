package com.bontecou.whereday

import android.Manifest
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Only the disposable mapQa package; real platform service/FLP, no owner history. */
class VisitMonitoringServiceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private lateinit var graph:AppGraph
    @Before fun setup()=runBlocking {
        check(context.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(context);graph.journalClear.awaitReady()
        graph.journalClear.clear().join();assertFalse(graph.journalClear.state.value.blocked)
        for(permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.POST_NOTIFICATIONS))
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName,permission)
        compose.waitUntil(5000) {compose.activity.hasWindowFocus()}
    }
    @After fun close()=runBlocking {
        if(::graph.isInitialized) {graph.visitMonitoringCommands.stop();graph.recordingCommands.stop()}
    }
    private suspend fun start()=graph.visitMonitoringCommands.startVisible(compose.activity,graph.journalClear.state.value.generation)
    private suspend fun awaitMode(mode:VisitMonitoringMode)=withTimeout(15_000) {while(VisitMonitoringRuntime.state.value!=mode) delay(30)}
    private fun notice()=context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull {it.id==41}
    private suspend fun awaitNoNotice()=withTimeout(5000) {while(notice()!=null) delay(30)}

    @Test fun explicitSettingsStartAcknowledgesAndNotificationStopWorksWithoutForegroundActivity()=runBlocking {
        compose.setContent {MaterialTheme {VisitMonitoringScreen(graph,{})}}
        compose.onNodeWithTag("visits-start").assertIsEnabled().performClick()
        awaitMode(VisitMonitoringMode.balanced)
        assertTrue(graph.history.dao.visitMonitoring()!!.requested)
        assertEquals(0L,graph.history.dao.openSessionCount());assertEquals(0L,graph.history.dao.totalPointCount())
        val notification=withTimeout(5000) {while(notice()==null) delay(30);notice()!!.notification}
        assertEquals(context.getString(com.bontecou.whereday.platform.R.string.visits_notification_title),notification.extras.getString("android.title"))
        val stop=notification.actions.single().actionIntent
        compose.runOnUiThread {compose.activity.moveTaskToBack(true)}
        stop.send();awaitMode(VisitMonitoringMode.off)
        withTimeout(5000) {while(graph.history.dao.visitMonitoring()?.requested==true) delay(30)}
        awaitNoNotice();assertFalse(RecordingRuntime.active.value)
    }

    @Test fun routeStopResumesBalancedAndMonitoringStopLeavesRouteRunning()=runBlocking {
        start();awaitMode(VisitMonitoringMode.balanced)
        graph.recordingCommands.startVisible(compose.activity)
        awaitMode(VisitMonitoringMode.route)
        val lease=graph.history.dao.visitMonitoring()!!.generation
        graph.recordingCommands.stop();awaitMode(VisitMonitoringMode.balanced)
        assertEquals(lease,graph.history.dao.visitMonitoring()!!.generation)
        graph.recordingCommands.startVisible(compose.activity);awaitMode(VisitMonitoringMode.route)
        graph.visitMonitoringCommands.stop();awaitMode(VisitMonitoringMode.off)
        assertTrue(RecordingRuntime.active.value)
        assertTrue(graph.history.dao.recording()!!.requested)
        assertEquals(1L,graph.history.dao.openSessionCount())
        graph.recordingCommands.stop()
    }

    @Test fun clearJoinsTheServiceRemovesNotificationAndOldLeaseCannotRepopulate()=runBlocking {
        start();awaitMode(VisitMonitoringMode.balanced)
        val lease=graph.history.dao.visitMonitoring()!!.generation!!
        graph.journalClear.clear().join()
        assertFalse(graph.journalClear.state.value.blocked)
        assertEquals(VisitMonitoringMode.off,VisitMonitoringRuntime.state.value)
        assertNull(graph.history.dao.visitMonitoring());awaitNoNotice()
        graph.history.ingestVisits(lease,listOf(com.bontecou.whereday.domain.TrackPoint(
            coordinate=com.bontecou.whereday.domain.Coordinate(18.0,-66.0),time=java.time.Instant.now(),accuracy=5.0)))
        assertNull(graph.history.dao.dwell());assertEquals(0L,graph.history.dao.totalVisitCount())
    }

    @Test fun requestedIntentAloneIsInterruptedAndNeverDisplayedAsRunning()=runBlocking {
        graph.history.startVisitMonitoring()
        assertEquals(VisitMonitoringMode.off,VisitMonitoringRuntime.state.value)
        graph.visitMonitoringCommands.reconcile("visits.interrupted")
        compose.setContent {MaterialTheme {VisitMonitoringScreen(graph,{})}}
        compose.onNodeWithTag("visits-status").assertTextEquals(context.getString(R.string.visits_interrupted))
        compose.onNodeWithTag("visits-start").assertIsEnabled()
        assertFalse(graph.history.dao.visitMonitoring()!!.requested)
        awaitNoNotice()
    }

    @Test fun abandonedRouteIntentCannotSilentlyBlockAnActiveVisitsOnlyService()=runBlocking {
        start();awaitMode(VisitMonitoringMode.balanced)
        graph.history.start(0)
        assertFalse(RecordingRuntime.active.value)
        withTimeout(10_000) {while(graph.history.dao.recording()?.requested==true) delay(50)}
        assertEquals(VisitMonitoringMode.balanced,VisitMonitoringRuntime.state.value)
        assertTrue(graph.history.dao.visitMonitoring()!!.requested)
        assertEquals(0L,graph.history.dao.openSessionCount())
    }

    @Test fun settingsDestinationAndSystemBackDoNotEnableMonitoring() {
        compose.setContent {MaterialTheme {SettingsScreen(graph,com.bontecou.whereday.data.AppSettings(onboarded=true),emptyList(),0)}}
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasText(context.getString(R.string.visits_title)))
        compose.onNodeWithText(context.getString(R.string.visits_title)).performClick()
        compose.onNodeWithTag("visits-only-screen").assertExists()
        compose.runOnUiThread {compose.activity.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithTag("settings-screen").assertExists()
        assertEquals(VisitMonitoringMode.off,VisitMonitoringRuntime.state.value)
    }

    @Test fun explicitRecordingRestartClosesAbandonedSessionAndStartsANewIdentity()=runBlocking {
        val old=graph.history.start(0).sessionId!!
        val oldStart=graph.history.dao.session(old)!!.value.startedAt
        assertFalse(RecordingRuntime.active.value)
        graph.recordingCommands.startVisible(compose.activity)
        val current=graph.history.dao.recording()!!.sessionId!!
        assertNotEquals(old,current)
        assertEquals(oldStart,graph.history.dao.session(old)!!.value.endedAt)
        assertTrue(RecordingRuntime.active.value)
        graph.recordingCommands.startVisible(compose.activity)
        assertEquals(current,graph.history.dao.recording()!!.sessionId)
        assertEquals(1L,graph.history.dao.openSessionCount())
    }
}
