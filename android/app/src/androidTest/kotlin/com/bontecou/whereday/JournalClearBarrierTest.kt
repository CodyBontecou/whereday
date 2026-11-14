package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.*
import org.junit.*
import org.junit.Assert.*

@SdkSuppress(minSdkVersion=33)
class JournalClearBarrierTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var oldLocales:LocaleList?=null
    private val state=mutableStateOf(JournalClearState(JournalClearPhase.failed,1,restricted=true))
    private var calls=0
    @Before fun setup() {check(target.packageName=="com.bontecou.whereday.mapqa");oldLocales=target.getSystemService(LocaleManager::class.java).applicationLocales;applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))}
    @After fun cleanup() {oldLocales?.let {applyQaAppLocales(compose,it)}}
    private fun show() {compose.setContent {MaterialTheme(colorScheme=darkColorScheme()) {JournalClearBarrier(state.value) {calls++}}}}
    private fun click()=compose.onNodeWithTag("journal-clear-retry").performScrollTo().performClick()
    private fun confirm()=compose.onNodeWithTag("journal-clear-recovery-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    @Test fun checkingAndClearingOfferNoJournalOrRetryAction() {
        state.value=JournalClearState(JournalClearPhase.checking,restricted=true);show()
        compose.onNodeWithText("Abriendo tu diario").assertExists();compose.onNodeWithTag("journal-clear-retry").assertDoesNotExist()
        compose.runOnIdle {state.value=JournalClearState(JournalClearPhase.clearing,1,restricted=true)}
        compose.onNodeWithText("Eliminación del diario").assertExists();compose.onNodeWithTag("journal-clear-retry").assertDoesNotExist();assertEquals(0,calls)
    }
    @Test fun knownPendingFailureRequiresAnExplicitRetryAndNeverDismissesTheFence() {
        show();assertEquals(0,calls);compose.onNodeWithTag("journal-clear-barrier").assertExists();click();assertEquals(1,calls)
        compose.onNodeWithTag("journal-clear-recovery-confirm").assertDoesNotExist()
    }
    @Test fun unreadableConsentRequiresReviewAndCancelNeverSubmits() {
        state.value=state.value.copy(needsApproval=true);show();click()
        compose.onNodeWithTag("journal-clear-recovery-confirm").assertExists();val old=confirm()
        compose.onNodeWithText("Cancelar").performClick();compose.runOnIdle {old()};assertEquals(0,calls)
        click();val positive=confirm();compose.runOnIdle {positive();positive()};assertEquals(1,calls)
    }
    @Test fun anOlderApprovalCannotSubmitForANewerAttempt() {
        state.value=state.value.copy(needsApproval=true);show();click();val old=confirm()
        compose.runOnIdle {state.value=state.value.copy(attempt=2)};compose.waitForIdle()
        compose.runOnIdle {old()};assertEquals(0,calls)
        click();compose.onNodeWithTag("journal-clear-recovery-confirm").performClick();assertEquals(1,calls)
    }
    @Test fun backgroundDiscardsAnUnacceptedReview() {
        state.value=state.value.copy(needsApproval=true);show();click();val old=confirm()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);compose.waitForIdle()
        compose.runOnIdle {old()};assertEquals(0,calls)
        compose.onNodeWithTag("journal-clear-recovery-confirm").assertDoesNotExist()
        click();compose.onNodeWithTag("journal-clear-recovery-confirm").performClick();assertEquals(1,calls)
    }
    @Test fun aHeldRetryCannotRunWhileCleanupIsBusy() {
        show();val old=compose.onNodeWithTag("journal-clear-retry").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {state.value=JournalClearState(JournalClearPhase.clearing,2,restricted=true)};compose.waitForIdle()
        compose.runOnIdle {old()};assertEquals(0,calls)
    }
    @Test fun largeTextRecoveryAndReviewKeepTheirActionsReachable() {
        state.value=state.value.copy(needsApproval=true);show()
        compose.onNodeWithTag("journal-clear-retry").performScrollTo().assertIsDisplayed();capture("barrier")
        click()
        compose.onNodeWithTag("journal-clear-recovery-confirm").assertIsDisplayed();capture("review")
        compose.onNodeWithText("Cancelar").performClick();assertEquals(0,calls)
    }
    @Test fun aNewClearGenerationCannotRestoreAnOldPrivateDraftAfterRecreation() {
        val clear=mutableStateOf(JournalClearState(generation="old"))
        val tester=StateRestorationTester(compose)
        tester.setContent {MaterialTheme {JournalReadyState(clear.value) {
            var draft by rememberSaveable {mutableStateOf("empty draft")}
            TextButton(onClick={draft="synthetic private draft"}) {Text("Set synthetic draft")};Text(draft)
        }}}
        compose.onNodeWithText("Set synthetic draft").performClick();compose.onNodeWithText("synthetic private draft").assertExists()
        compose.runOnIdle {clear.value=JournalClearState(JournalClearPhase.clearing,1,restricted=true,generation="new")};compose.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        compose.runOnIdle {clear.value=JournalClearState(JournalClearPhase.complete,1,generation="new")}
        compose.onNodeWithText("empty draft").assertExists();compose.onNodeWithText("synthetic private draft").assertDoesNotExist()
    }
    @Test fun ordinaryRecreationWithinTheSameGenerationPreservesAnUnsubmittedDraft() {
        val tester=StateRestorationTester(compose)
        tester.setContent {MaterialTheme {JournalReadyState(JournalClearState(generation="same")) {
            var draft by rememberSaveable {mutableStateOf("empty draft")}
            TextButton(onClick={draft="synthetic current draft"}) {Text("Set current draft")};Text(draft)
        }}}
        compose.onNodeWithText("Set current draft").performClick();tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("synthetic current draft").assertExists()
    }
    private fun capture(suffix:String) {
        if(InstrumentationRegistry.getArguments().getString("captureClearRecovery")!="true") return
        if(InstrumentationRegistry.getArguments().getString("requireLargeFont")=="true") assertTrue(target.resources.configuration.fontScale>=1.8f)
        val instrumentation=InstrumentationRegistry.getInstrumentation();compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val values=android.content.ContentValues().apply {put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-clear-recovery-$suffix-large-20261030.png");put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")}
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot());try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}}
    }

}
