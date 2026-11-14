package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Presentation fixtures and synthetic callbacks only; production dispatch gates are not replaced. */
@RunWith(AndroidJUnit4::class)
class WebhookScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val editor=WebhookEditorViewModel()
    private val busyState=mutableStateOf(false)
    private val saved=WebhookConfiguration(UUID.randomUUID().toString(),WebhookSettings(endpoint="https://example.invalid/qa",mode=WebhookMode.manual))
    private data class Save(val draft:WebhookDraft,val transition:WebhookQueueTransition,val revision:String)
    private val saves=mutableListOf<Save>()
    private val calls=mutableListOf<String>()
    private val actions=WebhookActions({a,b,c->saves.add(Save(a,b,c))},{calls.add("disable")},{calls.add("reset")},{calls.add("test")},{calls.add("history")},{calls.add("flush")},{calls.add("retry:$it")},{calls.add("discard:$it")},{calls.add("resume")},{calls.add("refresh")})
    @Before fun isolated() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun show(config:WebhookConfiguration=saved,paid:Boolean=true,head:WebhookBatchRow?=null,busy:Boolean=false) {
        busyState.value=busy
        val snapshot=WebhookQueueSnapshot(null,if(head==null) 0 else head.pointCount,if(head==null) 0 else 1,null,head)
        compose.setContent {MaterialTheme {WebhookSettingsContent(WebhookUiState(loaded=true,state=WebhookState(config,snapshot),busy=busyState.value),editor,actions,paid,{calls.add("back")}) {androidx.compose.material3.Text("Synthetic purchase panel")}}}
        compose.waitUntil(5000) {editor.baseline!=null}
    }
    private fun click(text:String)=compose.onNodeWithText(text).performScrollTo().performClick()
    @Test fun enableRequiresSaveAndExplicitConfirmationAndUrlIsMasked() {
        show()
        compose.onNodeWithTag("webhook-url").assert(SemanticsMatcher.expectValue(SemanticsProperties.Password,Unit))
        compose.onNodeWithTag("webhook-enabled").performClick()
        assertTrue(saves.isEmpty());click("Save webhook settings")
        compose.onNodeWithText("Enable webhook delivery?").assertIsDisplayed();assertTrue(saves.isEmpty())
        compose.onNodeWithText("Cancel").performClick();assertTrue(saves.isEmpty())
        click("Save webhook settings");compose.onNodeWithText("Enable and save").performClick()
        compose.runOnIdle {assertEquals(1,saves.size);assertTrue(saves.single().draft.settings.enabled);assertEquals(saved.revision,saves.single().revision);assertEquals(WebhookQueueTransition.requireEmpty,saves.single().transition)}
    }
    @Test fun enablingAfterAnotherEditStillRequiresTheEnableConfirmation() {
        show()
        compose.onNodeWithTag("webhook-url").performTextReplacement("https://example.invalid/changed-first")
        compose.onNodeWithTag("webhook-enabled").performScrollTo().performClick()
        click("Save webhook settings")
        compose.onNodeWithText("Enable webhook delivery?").assertIsDisplayed()
        compose.runOnIdle {assertTrue(saves.isEmpty());assertTrue(editor.draft.settings.enabled)}
    }
    @Test fun authenticationChangeClearsThePreviousSecretAndResetsItsVisibility() {
        show();click("None");compose.onNodeWithText("Bearer token").performClick()
        compose.onNodeWithTag("webhook-auth-value").performScrollTo().performTextInput("synthetic-token")
        compose.onNodeWithTag("webhook-auth-value").assert(SemanticsMatcher.expectValue(SemanticsProperties.Password,Unit))
        compose.onNodeWithContentDescription("Show Authentication value").performClick()
        compose.onNodeWithContentDescription("Hide Authentication value").assertIsDisplayed()
        val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag("webhook-auth-value").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {it(layouts)}
        assertEquals("synthetic-token",layouts.single().layoutInput.text.text)
        click("Bearer token");compose.onNodeWithText("Basic").performClick()
        compose.onNodeWithTag("webhook-auth-value").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.Password,Unit))
        compose.onNodeWithContentDescription("Show Password").assertExists()
        compose.runOnIdle {assertEquals("",editor.draft.credentials.value);assertEquals("",editor.draft.credentials.username);assertTrue(saves.isEmpty())}
    }
    @Test fun freeConnectionTestOpensPurchaseInformationWithoutCallingDelivery() {
        show(paid=false);click("Test connection")
        compose.onNodeWithText("Whereday plans").assertIsDisplayed()
        compose.onNodeWithText("Synthetic purchase panel").assertIsDisplayed()
        compose.runOnIdle {assertTrue(calls.isEmpty());assertTrue(saves.isEmpty())}
        compose.onNodeWithText("Close").performClick();compose.onNodeWithTag("webhook-screen").assertExists()
    }
    @Test fun queuedFormatChangeOffersDiscardOnlyAndSameFormatCanRetain() {
        val enabled=saved.copy(settings=saved.settings.copy(enabled=true))
        val head=WebhookBatchRow(UUID.randomUUID().toString(),enabled.revision,Instant.now(),2)
        show(enabled,head=head)
        click("JSON");compose.onNodeWithText("CSV").performClick();click("Save webhook settings")
        compose.onNodeWithText("What should happen to queued data?").assertIsDisplayed()
        compose.onNodeWithText("Save and keep queued data").assertDoesNotExist();assertTrue(saves.isEmpty())
        compose.onNodeWithText("Cancel").performClick()
        click("CSV");compose.onNodeWithText("JSON").performClick()
        compose.onNodeWithTag("webhook-url").performScrollTo().performTextReplacement("https://example.invalid/new-qa")
        click("Save webhook settings");compose.onNodeWithText("Save and keep queued data").performClick()
        compose.runOnIdle {assertEquals(WebhookQueueTransition.retain,saves.single().transition);assertEquals("https://example.invalid/new-qa",saves.single().draft.settings.endpoint)}
    }
    @Test fun sendingHistoryAndDiscardingQueuedDataRequireSeparateConfirmations() {
        val enabled=saved.copy(settings=saved.settings.copy(enabled=true))
        val head=WebhookBatchRow(UUID.randomUUID().toString(),enabled.revision,Instant.now(),2)
        show(enabled,head=head)
        click("Send history now");compose.onNodeWithText("Send all saved history?").assertIsDisplayed();assertTrue(calls.isEmpty())
        compose.onNodeWithText("Cancel").performClick();assertTrue(calls.isEmpty())
        click("Send history now");compose.onNodeWithText("Send history").performClick()
        click("Discard oldest request");compose.onNodeWithText("Discard oldest queued request?").assertIsDisplayed()
        compose.runOnIdle {assertEquals(listOf("history"),calls)}
        compose.onNodeWithText("Discard request").performClick()
        compose.runOnIdle {assertEquals(listOf("history","discard:${head.id}"),calls)}
    }
    @Test fun invalidUrlStaysUnsavedAndBackCanDiscardOnlyTheDraft() {
        show()
        compose.onNodeWithTag("webhook-url").performTextReplacement("http://example.invalid/unsafe")
        click("Save webhook settings");compose.onNodeWithText("Use an HTTPS endpoint URL.").assertExists();assertTrue(saves.isEmpty())
        compose.onNodeWithContentDescription("Back to settings").performClick();compose.onNodeWithText("Discard unsaved webhook edits?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick();compose.runOnIdle {assertTrue(editor.dirty);assertTrue(calls.isEmpty())}
        compose.onNodeWithContentDescription("Back to settings").performClick();compose.onNodeWithText("Discard edits").performClick()
        compose.runOnIdle {assertFalse(editor.dirty);assertEquals(saved.settings.endpoint,editor.draft.settings.endpoint);assertEquals(listOf("back"),calls)}
    }
    @Test fun systemBackAfterPurchaseInformationStillGuardsUnsavedEdits() {
        show(paid=false);compose.onNodeWithTag("webhook-enabled").performClick();click("Save webhook settings")
        compose.onNodeWithText("Whereday plans").assertIsDisplayed();compose.onNodeWithText("Close").performClick()
        compose.waitForIdle();androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5000) {runCatching {compose.onNodeWithText("Discard unsaved webhook edits?").assertIsDisplayed()}.isSuccess}
        compose.onNodeWithText("Discard unsaved webhook edits?").assertIsDisplayed();compose.onNodeWithTag("webhook-screen").assertExists()
        compose.runOnIdle {assertTrue(editor.dirty);assertTrue(calls.isEmpty())}
    }
    @Test fun busyDirtyFormOffersALeaveChoiceAndRetainsItsDraft() {
        show()
        compose.runOnIdle {editor.edit(WebhookDraft(saved.settings.copy(endpoint="https://example.invalid/unsaved")));busyState.value=true}
        compose.onNodeWithText("Updating webhook delivery…").performScrollTo().assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5000) {runCatching {compose.onNodeWithText("Leave while webhook work finishes?").assertIsDisplayed()}.isSuccess}
        compose.onNodeWithText("Leave while webhook work finishes?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick();compose.runOnIdle {assertTrue(editor.dirty);assertTrue(calls.isEmpty())}
        androidx.test.espresso.Espresso.pressBack();compose.waitUntil(5000) {runCatching {compose.onNodeWithText("Leave screen").assertIsDisplayed()}.isSuccess};compose.onNodeWithText("Leave screen").performClick()
        compose.runOnIdle {assertEquals(listOf("back"),calls);assertTrue(editor.dirty)}
    }
    @Test fun discardConfirmationKeepsTheReviewedIdWhenTheQueueHeadChanges() {
        val config=saved.copy(settings=saved.settings.copy(enabled=true))
        val first=WebhookBatchRow(UUID.randomUUID().toString(),config.revision,Instant.now(),2)
        val next=first.copy(id=UUID.randomUUID().toString(),pointCount=3)
        val head=mutableStateOf(first)
        compose.setContent {MaterialTheme {WebhookSettingsContent(WebhookUiState(loaded=true,state=WebhookState(config,WebhookQueueSnapshot(null,head.value.pointCount,1,null,head.value))),editor,actions,true,{})}}
        compose.waitUntil(5000) {editor.baseline!=null}
        click("Discard oldest request");compose.onNodeWithText("Discard oldest queued request?").assertIsDisplayed()
        compose.runOnIdle {head.value=next}
        compose.onNodeWithText("Discard request").performClick()
        compose.runOnIdle {assertEquals(listOf("discard:${first.id}"),calls)}
    }
    @Test fun largeTextDarkLayoutKeepsAuthenticationAndConfirmationsReachable() {
        val config=saved.copy(settings=saved.settings.copy(auth=WebhookAuth.basic),credentials=WebhookCredentials(value="synthetic-password",username="Long synthetic username for layout verification"))
        compose.setContent {
            val density=androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,1.6f)) {
                MaterialTheme(colorScheme=androidx.compose.material3.darkColorScheme()) {WebhookSettingsContent(WebhookUiState(loaded=true,state=WebhookState(config,WebhookQueueSnapshot(null,0,0,null,null))),editor,actions,true,{})}
            }
        }
        compose.waitUntil(5000) {editor.baseline!=null}
        compose.onNodeWithTag("webhook-enabled").performScrollTo().assertContentDescriptionEquals("Enable webhook delivery")
        compose.onNodeWithTag("webhook-username").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("webhook-auth-value").performScrollTo().assertIsDisplayed().assert(SemanticsMatcher.expectValue(SemanticsProperties.Password,Unit))
        click("Reset webhook settings");compose.onNodeWithText("Reset webhook settings?").assertIsDisplayed()
        compose.onNodeWithText("Reset webhooks").assertIsDisplayed();compose.onNodeWithText("Cancel").assertIsDisplayed().performClick()
        compose.runOnIdle {assertTrue(calls.isEmpty());assertTrue(saves.isEmpty())}
    }
    @Test fun secretDraftSurvivesActivityRecreationInTheSameViewModel() {
        var first:WebhookEditorViewModel?=null
        compose.activityRule.scenario.onActivity {activity->
            first=ViewModelProvider(activity)[WebhookEditorViewModel::class.java]
            first!!.reload(saved);first!!.edit(WebhookDraft(saved.settings.copy(endpoint="https://example.invalid/private-draft"),WebhookCredentials(value="synthetic-unsaved-secret")))
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity {activity->
            val restored=ViewModelProvider(activity)[WebhookEditorViewModel::class.java]
            assertSame(first,restored);assertEquals("synthetic-unsaved-secret",restored.draft.credentials.value)
            assertEquals("https://example.invalid/private-draft",restored.draft.settings.endpoint);assertTrue(restored.dirty)
            assertFalse(restored.draft.toString().contains("synthetic-unsaved-secret"))
        }
    }
}
