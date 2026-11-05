package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.*
import org.junit.*
import org.junit.Assert.*

/** Synthetic permission states only: no grant, GPS, purchase or owner package. */
@SdkSuppress(minSdkVersion=33)
class RecordingAccessTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var oldLocales:LocaleList?=null
    private var review by mutableStateOf<PermissionReview?>(null)
    private val actions=mutableListOf<String>()
    @Before fun setup() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales
        applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
    }
    @After fun cleanup() {oldLocales?.let {applyQaAppLocales(compose,it)}}
    private fun showReview() {compose.setContent {MaterialTheme(colorScheme=darkColorScheme()) {
        PermissionAccessReview(review,{actions+="dismiss:$it";review=null},{actions+="request:$it";review=null},{actions+="settings:$it";review=null})
    }}}
    private fun positive()=compose.onNodeWithTag("permission-review-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    private fun ticket(id:Long,kind:PermissionAccessKind=PermissionAccessKind.location,allowed:Boolean=true)=PermissionReview(id,"synthetic-generation",0,kind,allowed)
    @Test fun deniedLocationAndNotificationsExplainTheAvailableJournalWithoutOpeningAnything() {
        var state by mutableStateOf(RecordingAccessState(LocationPrecision.none,false,locationDeclined=true))
        val selected=mutableListOf<PermissionAccessKind>()
        compose.setContent {MaterialTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {RecordingAccessNotice(state,{selected+=it})}}}
        compose.onNodeWithText(target.getString(R.string.permission_location_denied)).assertExists()
        compose.onNodeWithText(target.getString(R.string.permission_notifications_body)).assertExists()
        assertTrue(selected.isEmpty())
        compose.onNodeWithTag("location-access-options").performScrollTo().performClick()
        compose.onNodeWithTag("notification-access-options").performScrollTo().performClick()
        assertEquals(listOf(PermissionAccessKind.location,PermissionAccessKind.notifications),selected)
        compose.runOnIdle {state=RecordingAccessState(LocationPrecision.precise,true)}
        compose.onNodeWithTag("recording-access-notice").assertDoesNotExist()
    }
    @Test fun approximateAccessExplainsFilteringButLeavesTheStartActionAvailable() {
        var starts=0;val selected=mutableListOf<PermissionAccessKind>()
        compose.setContent {MaterialTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            RecordingControls(false,null) {starts++}
            RecordingAccessNotice(RecordingAccessState(LocationPrecision.approximate,true),{selected+=it})
        }}}
        compose.onNodeWithText(target.getString(R.string.permission_approximate_body)).performScrollTo().assertIsDisplayed()
        assertEquals(0,starts)
        compose.onNodeWithTag("location-access-options").performScrollTo().performClick();assertEquals(listOf(PermissionAccessKind.precise),selected)
        compose.onNodeWithText("Iniciar grabación").performScrollTo().performClick();assertEquals(1,starts)
    }
    @Test fun unavailableSystemRequestOffersSettingsAndCancelWithoutARequestCallback() {
        review=ticket(1,allowed=false);showReview();assertTrue(actions.isEmpty())
        compose.onNodeWithTag("permission-review-confirm").assertTextEquals("Abrir ajustes de la app").performClick()
        assertEquals(listOf("settings:1"),actions)
        compose.onNodeWithTag("permission-access-review").assertDoesNotExist()
    }
    @Test fun eachKindUsesAnExplicitPermissionActionAndSettingsIsSeparate() {
        review=ticket(1);showReview()
        for((index,kind) in PermissionAccessKind.entries.withIndex()) {
            val id=index.toLong()+1
            compose.runOnIdle {review=ticket(id,kind)}
            compose.onNodeWithTag("permission-review-confirm").assertTextEquals(target.getString(when(kind) {PermissionAccessKind.location->R.string.permission_request_location;PermissionAccessKind.precise->R.string.permission_request_precise;PermissionAccessKind.notifications->R.string.permission_request_notifications})).performClick()
            assertEquals("request:$id",actions.last())
        }
        compose.runOnIdle {review=ticket(8,PermissionAccessKind.precise)}
        compose.onNodeWithTag("permission-review-settings").performScrollTo().performClick()
        assertEquals("settings:8",actions.last())
    }
    @Test fun anApprovalIsSingleUseAndAnOldSameKindCallbackCannotApproveANewerReview() {
        review=ticket(1);showReview();val old=positive()
        compose.runOnIdle {review=ticket(2)};compose.waitForIdle()
        compose.runOnIdle {old()};assertTrue(actions.isEmpty())
        val current=positive();compose.runOnIdle {current();current()}
        assertEquals(listOf("request:2"),actions)
    }
    @Test fun cancelAndDisposalMakeRetainedPermissionActionsInert() {
        review=ticket(1);showReview();val old=positive()
        compose.onNodeWithTag("permission-review-dismiss").performClick()
        compose.runOnIdle {old()};assertEquals(listOf("dismiss:1"),actions)
        compose.runOnIdle {review=ticket(2)};val disposed=positive()
        compose.runOnIdle {review=null};compose.waitForIdle();compose.runOnIdle {disposed()}
        assertEquals(listOf("dismiss:1"),actions)
    }
    @Test fun backgroundDiscardsReviewAndCannotTurnAnOldButtonIntoANewRequest() {
        review=ticket(1);showReview();val old=positive()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);compose.waitForIdle()
        compose.runOnIdle {old()};assertEquals(listOf("dismiss:1"),actions)
        compose.onNodeWithTag("permission-access-review").assertDoesNotExist()
        compose.runOnIdle {review=ticket(2)}
        compose.onNodeWithTag("permission-review-confirm").performClick()
        assertEquals(listOf("dismiss:1","request:2"),actions)
    }
    @Test fun approximateOnboardingKeepsImmediateRecordingAnExplicitDefaultOffChoice() {
        var completed:OnboardingDecision?=null
        compose.setContent {MaterialTheme {Onboarding({},{},authorization=UsageAuthorization.foreground,access=RecordingAccessState(LocationPrecision.approximate,false)) {completed=it}}}
        repeat(4) {compose.onNodeWithTag("onboarding-continue").performScrollTo().performClick()}
        compose.onNodeWithText(target.getString(R.string.permission_approximate_body)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("onboarding-start-now").performScrollTo().assertIsOff().performClick()
        assertNull(completed)
        compose.onNodeWithTag("onboarding-continue").performScrollTo().performClick()
        compose.waitUntil {completed!=null};assertTrue(completed!!.start)
    }
    @Test fun largeDarkRecoveryKeepsLocationNotificationAndSettingsActionsReachable() {
        val selected=mutableListOf<PermissionAccessKind>()
        compose.setContent {MaterialTheme(colorScheme=darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
                RecordingControls(false,null) {}
                RecordingAccessNotice(RecordingAccessState(LocationPrecision.approximate,false),{selected+=it})
            }
            }
            PermissionAccessReview(review,{review=null},{selected+=PermissionAccessKind.location;review=null},{selected+=PermissionAccessKind.precise;review=null})
        }}
        compose.onNodeWithTag("location-access-options").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("notification-access-options").performScrollTo().assertIsDisplayed()
        capture("approximate")
        compose.runOnIdle {review=ticket(1,PermissionAccessKind.location,false)}
        compose.onNodeWithTag("permission-review-confirm").assertIsDisplayed();compose.onNodeWithTag("permission-review-dismiss").assertIsDisplayed()
        capture("review")
        compose.onNodeWithTag("permission-review-dismiss").performClick();assertTrue(selected.isEmpty())
        compose.runOnIdle {review=ticket(2).copy(start=true)}
        compose.onNodeWithTag("permission-review-confirm").assertTextEquals(target.getString(R.string.permission_request_location_start)).assertIsDisplayed()
        compose.onNodeWithTag("permission-review-dismiss").assertIsDisplayed()
        capture("rationale")
        compose.onNodeWithTag("permission-review-confirm").performClick()
        assertEquals(listOf(PermissionAccessKind.location),selected)
    }
    private fun capture(suffix:String) {
        if(InstrumentationRegistry.getArguments().getString("captureRecordingAccess")!="true") return
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        val instrumentation=InstrumentationRegistry.getInstrumentation();compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-permission-access-$suffix-large-20261030.png");put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")}
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {out->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out))} finally {bitmap.recycle()}}
    }
}
