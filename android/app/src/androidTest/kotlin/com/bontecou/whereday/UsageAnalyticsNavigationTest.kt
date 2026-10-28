package com.bontecou.whereday

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

class UsageAnalyticsNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actualSettingsShowsUnavailableConsentScreenWithoutCreatingIdentityOrQueue():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        val graph=AppGraph.from(context)
        graph.preferences.update {it.copy(onboarded=true)}
        compose.waitUntil(15_000) {compose.onAllNodesWithText("Start recording").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Usage analytics"))
        compose.onNodeWithText("Usage analytics").performClick()
        compose.onNodeWithTag("usage-analytics-screen").assertExists()
        compose.onNodeWithTag("usage-analytics-switch").assertIsOff().assertIsNotEnabled()
        assertFalse(graph.usageAnalytics.state.value.available)
        assertFalse(graph.usageAnalytics.state.value.enabled)
        assertEquals(0,graph.usageAnalytics.state.value.pending)
        assertFalse(File(context.filesDir,"usage-analytics").exists())
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("settings-screen").assertExists()
    }
}
