package com.bontecou.whereday

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebhookNavigationIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun realSettingsAndPurchaseSheetKeepTheSystemBackDraftGuard():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        val graph=AppGraph.from(context)
        try {
            graph.preferences.update {it.copy(onboarded=true)}
            compose.waitUntil(10_000) {compose.onAllNodesWithTag("map-header").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.openSettings()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Webhooks"))
            compose.onNodeWithText("Webhooks").performClick()
            compose.waitUntil(10_000) {compose.onAllNodesWithTag("webhook-url").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("webhook-url").performTextInput("https://example.invalid/navigation-qa")
            compose.onNodeWithText("Save webhook settings").performScrollTo().performClick()
            withTimeout(10_000) {while(graph.webhookController.state.value.message!="Webhook settings saved." || graph.webhookController.state.value.busy) delay(25)}
            compose.onNodeWithTag("webhook-enabled").performScrollTo().performClick()
            compose.onNodeWithText("Save webhook settings").performScrollTo().performClick()
            compose.onNodeWithText("Whereday plans").assertIsDisplayed()
            compose.onNodeWithText("Close").performClick();compose.waitForIdle()
            androidx.test.espresso.Espresso.pressBack()
            compose.waitUntil(5000) {runCatching {compose.onNodeWithText("Discard unsaved webhook edits?").assertIsDisplayed()}.isSuccess}
            compose.onNodeWithText("Discard unsaved webhook edits?").assertIsDisplayed()
            compose.onNodeWithTag("webhook-screen").assertExists()
            assertFalse(graph.webhooks.state().configuration.settings.enabled)
            compose.onNodeWithText("Cancel").performClick();compose.waitForIdle()
            androidx.test.espresso.Espresso.pressBack();compose.waitUntil(5000) {runCatching {compose.onNodeWithText("Discard edits").assertIsDisplayed()}.isSuccess};compose.onNodeWithText("Discard edits").performClick()
            compose.onNodeWithTag("settings-screen").assertExists()
        } finally {graph.webhookController.clearAll {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}}
    }
}
