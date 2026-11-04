package com.bontecou.whereday

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.AppGraph
import com.bontecou.whereday.platform.AndroidUsageAnalyticsStore
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

class UsageAnalyticsNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actualSettingsShowsUnavailableConsentScreenWithoutCreatingIdentityOrQueue():Unit=runBlocking {
        verifyUnavailableSettings()
    }
    @Test fun clearedOffReceiptSurvivesOpeningUnavailableSettingsWithoutCreatingIdentityOrQueue():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        AppGraph.from(context).usageAnalytics.clearAll()
        verifyUnavailableSettings()
    }
    private suspend fun verifyUnavailableSettings() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        val graph=AppGraph.from(context)
        val before=analyticsFiles(context.filesDir)
        graph.preferences.update {it.copy(onboarded=true)}
        compose.waitUntil(15_000) {compose.onAllNodesWithText("Start recording").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("today-settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Usage analytics"))
        compose.onNodeWithText("Usage analytics").performClick()
        compose.onNodeWithTag("usage-analytics-screen").assertExists()
        compose.onNodeWithTag("usage-analytics-switch").assertIsOff().assertIsNotEnabled()
        assertFalse(graph.usageAnalytics.state.value.available)
        assertFalse(graph.usageAnalytics.state.value.enabled)
        assertEquals(0,graph.usageAnalytics.state.value.pending)
        // Journal clear deliberately persists an off receipt; its directory is not an identity.
        // Viewing unavailable Settings must neither create consent/events nor alter that receipt.
        val persisted=AndroidUsageAnalyticsStore(context).read()
        assertNull(persisted.consentId)
        assertTrue(persisted.pending.isEmpty())
        assertEquals(before,analyticsFiles(context.filesDir))
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("settings-screen").assertExists()
    }
    private fun analyticsFiles(filesDir:File):Map<String,String> {
        val root=File(filesDir,"usage-analytics")
        return root.walkTopDown().filter {it.isFile}.associate {file ->
            file.relativeTo(root).path to java.security.MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") {"%02x".format(it)}
        }
    }
}
