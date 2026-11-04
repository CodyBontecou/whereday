package com.bontecou.whereday

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.*

class SoftwareNoticesNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actualSettingsOpensOfflineNoticesAndReturnsToItsOwnRow():Unit=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        AppGraph.from(target).preferences.update {it.copy(onboarded=true)}
        compose.waitUntil(15_000) {compose.onAllNodesWithText("Start recording").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        compose.onNodeWithTag("today-settings").performClick()
        compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-software-notices"))
        compose.onNodeWithTag("settings-software-notices").performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("software-notices-list").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("settings-screen").assertExists()
        compose.onNodeWithTag("settings-software-notices").assertIsDisplayed()
    }
}
