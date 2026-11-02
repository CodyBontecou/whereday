package com.bontecou.whereday

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Each concrete class must run alone after a fresh mapQa install: Android denial flags are durable. */
abstract class RecordingPermissionSystemScenario {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    protected val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    protected val target get()=instrumentation.targetContext
    protected val graph get()=AppGraph.from(target)
    private val systemPackages=setOf("com.android.permissioncontroller","com.google.android.permissioncontroller","com.android.settings")
    @Before fun freshFixture() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply {flags=flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
        check(Resources.getSystem().configuration.locales[0].language=="en") {"This API 35 system-dialog fixture requires English Android"}
        assertEquals(PackageManager.PERMISSION_DENIED,target.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED,target.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED,target.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        runBlocking {graph.journalClear.awaitReady();assertFalse(graph.preferences.settings.first().onboarded);assertEquals(0L,graph.history.dao.totalPointCount())}
        compose.onNodeWithText("Your days, remembered").assertExists()
    }
    @After fun stopOwnRecording() {
        if(target.packageName=="com.bontecou.whereday.mapqa") runBlocking {graph.recordingCommands.stop()}
    }
    protected fun onboardWithoutPermissions() {
        repeat(4) {compose.onNodeWithTag("onboarding-continue").performScrollTo().performClick()}
        compose.onNodeWithTag("onboarding-start-now").assertDoesNotExist()
        compose.onNodeWithText("Open Whereday").performScrollTo().performClick()
        appFocused();compose.onNodeWithText("Start recording").assertExists()
        assertFalse(RecordingRuntime.active.value)
    }
    protected fun appFocused() {
        await("QA activity window focus") {compose.activity.hasWindowFocus()}
        compose.waitForIdle()
    }
    protected fun start() {compose.onNodeWithText("Start recording").performScrollTo().performClick()}
    protected fun stop() {
        compose.onNodeWithText("Stop recording").performScrollTo().performClick()
        await("recording stopped") {!RecordingRuntime.active.value}
        runBlocking {withTimeout(15000) {while(graph.history.dao.recording()?.requested==true) delay(25)}}
        compose.onNodeWithText("Start recording").assertExists()
    }
    protected fun nativeNode(id:String?=null,text:String?=null,contains:Boolean=false):AccessibilityNodeInfo? {
        val root=instrumentation.uiAutomation.rootInActiveWindow ?: return null
        if(!root.refresh()) return null
        val pending=java.util.ArrayDeque<AccessibilityNodeInfo>();pending.add(root);var visited=0
        while(pending.isNotEmpty() && visited++<4096) {
            val node=pending.removeFirst()
            if(!node.refresh()) continue
            val allowed=node.packageName?.toString() in systemPackages
            val matches=if(id!=null) node.viewIdResourceName?.substringAfterLast('/')==id
                else if(contains) node.text?.toString()?.contains(requireNotNull(text),ignoreCase=true)==true else node.text?.toString()==text
            if(allowed && matches && node.isVisibleToUser) return node
            for(i in 0 until node.childCount) node.getChild(i)?.let(pending::add)
        }
        return null
    }
    protected fun nativeClick(id:String?=null,text:String?=null) {
        var selected:AccessibilityNodeInfo?=null
        await("system control ${id ?: text}") {nativeNode(id,text)?.also {selected=it}!=null}
        var node=requireNotNull(selected)
        while(!node.isClickable) node=node.parent ?: error("System control has no clickable ancestor")
        check(node.packageName?.toString() in systemPackages)
        assertTrue("System control must be enabled",node.isEnabled)
        assertTrue("System semantic click must be accepted",node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    protected fun nativeTextContains(text:String) {await("system text $text") {nativeNode(text=text,contains=true)!=null}}
    protected fun nativeDeny() {
        var id:String?=null
        await("Android denial action") {listOf("permission_deny_button","permission_deny_and_dont_ask_again_button").firstOrNull {nativeNode(id=it)!=null}?.also {id=it}!=null}
        nativeClick(id=requireNotNull(id))
    }
    protected fun denyLocation() {
        await("Android location prompt") {nativeNode(id="permission_location_accuracy_radio_coarse")!=null}
        nativeDeny();appFocused()
        compose.onNodeWithText(target.getString(R.string.permission_location_denied)).assertExists()
        assertFalse(RecordingRuntime.active.value)
        assertEquals(LocationPrecision.none,recordingAccess(target).location)
    }
    protected fun openLocationOptions() {compose.onNodeWithTag("location-access-options").performScrollTo().performClick();compose.onNodeWithTag("permission-access-review").assertExists()}
    protected fun visitSettingsAndReturnIdle() {
        if(compose.onAllNodesWithTag("permission-review-settings").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("permission-review-settings").performScrollTo().performClick()
        else compose.onNodeWithTag("permission-review-confirm").assertTextEquals("Open app settings").performClick()
        await("own app details in Android Settings") {nativeNode(text=target.getString(R.string.app_name))!=null}
        assertFalse(RecordingRuntime.active.value)
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        appFocused();compose.onNodeWithText("Start recording").assertExists()
        assertFalse(RecordingRuntime.active.value)
        runBlocking {assertTrue(graph.history.dao.recording()?.requested!=true)}
    }
    private fun systemDiagnostic():String {
        val root=instrumentation.uiAutomation.rootInActiveWindow ?: return "no active accessibility root"
        val rows=mutableListOf<String>();val nodes=java.util.ArrayDeque<AccessibilityNodeInfo>();nodes.add(root)
        while(nodes.isNotEmpty() && rows.size<120) {
            val node=nodes.removeFirst();node.refresh()
            rows+="${node.packageName}:${node.viewIdResourceName}:${if(node.packageName?.toString() in systemPackages) node.text else "[app content omitted]"}"
            for(i in 0 until node.childCount) node.getChild(i)?.let(nodes::add)
        }
        return rows.joinToString(" | ")
    }
    protected fun await(label:String,condition:()->Boolean) {
        try {compose.waitUntil(15000) {condition()}}
        catch(error:ComposeTimeoutException) {throw AssertionError("Timed out: $label; ${systemDiagnostic()}",error)}
    }
}

@SdkSuppress(minSdkVersion=35,maxSdkVersion=35)
class RecordingPermissionDenialSystemTest:RecordingPermissionSystemScenario() {
    @Test fun repeatedDenialHasExplicitRecoveryAndSettingsReturnCannotStartRecording() {
        onboardWithoutPermissions();start();denyLocation()
        compose.onNodeWithTag("permission-access-review").assertDoesNotExist()
        start();compose.onNodeWithTag("permission-review-confirm").assertTextEquals("Allow location and start recording")
        compose.onNodeWithTag("permission-review-dismiss").performClick()
        assertFalse(RecordingRuntime.active.value)
        start();compose.onNodeWithTag("permission-review-confirm").performClick();denyLocation()
        assertFalse(compose.activity.shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION))
        openLocationOptions();compose.onNodeWithTag("permission-review-confirm").assertTextEquals("Open app settings")
        visitSettingsAndReturnIdle()
        assertEquals(LocationPrecision.none,recordingAccess(target).location)
        runBlocking {assertEquals(0L,graph.history.dao.totalPointCount());assertTrue(graph.history.dao.recording()?.requested!=true)}
    }
}

@SdkSuppress(minSdkVersion=35,maxSdkVersion=35)
class RecordingApproximateSystemTest:RecordingPermissionSystemScenario() {
    @Test fun approximateGrantStartsAfterFocusAndDeniedNotificationsDoNotGateOrNagRecording() {
        onboardWithoutPermissions();start()
        nativeClick(id="permission_location_accuracy_radio_coarse")
        await("approximate selected") {nativeNode(id="permission_location_accuracy_radio_coarse")?.isChecked==true}
        nativeClick(id="permission_allow_foreground_only_button")
        nativeTextContains("notifications");nativeDeny();appFocused()
        await("actual service active with coarse access") {RecordingRuntime.active.value}
        assertEquals(PackageManager.PERMISSION_GRANTED,target.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED,target.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED,target.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        compose.onNodeWithText(target.getString(R.string.permission_approximate_body)).assertExists()
        compose.onNodeWithText(target.getString(R.string.permission_notifications_body)).assertExists()
        stop()
        openLocationOptions();compose.onNodeWithTag("permission-review-confirm").assertTextEquals("Request precise location").performClick()
        nativeTextContains("precise");nativeDeny();appFocused()
        assertEquals(LocationPrecision.approximate,recordingAccess(target).location);assertFalse(RecordingRuntime.active.value)
        openLocationOptions();visitSettingsAndReturnIdle()
        start();appFocused();await("second explicit start") {RecordingRuntime.active.value}
        assertFalse(recordingAccess(target).notifications)
        assertNull(nativeNode(id="permission_deny_button"))
        stop()
    }
}
