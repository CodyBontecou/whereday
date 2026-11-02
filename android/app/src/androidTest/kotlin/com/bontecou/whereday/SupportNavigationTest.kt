package com.bontecou.whereday

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.*

class SupportNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actualSettingsSupportLogsAndReleaseNotesHaveSystemBackNavigation():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        AppGraph.from(context).preferences.update {it.copy(onboarded=true)}
        compose.waitUntil(15_000) {compose.onAllNodesWithText("Start recording").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Support and about"))
        compose.onNodeWithText("Support and about").performClick()
        compose.onNodeWithTag("support-screen").assertExists()
        compose.onNodeWithText("View activity logs").performClick()
        compose.onNodeWithTag("activity-logs-screen").assertExists()
        compose.onNodeWithText("Copy logs").performScrollTo().performClick()
        compose.runOnIdle {
            val clip=context.getSystemService(android.content.ClipboardManager::class.java).primaryClip
            org.junit.Assert.assertNotNull(clip)
            org.junit.Assert.assertTrue(clip!!.getItemAt(0).text.toString().contains(SupportInfo.from(context).reportHeader))
            if(android.os.Build.VERSION.SDK_INT>=33) org.junit.Assert.assertTrue(clip.description.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE)==true)
        }
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("support-screen").assertExists()
        compose.onNodeWithText("Release notes").performClick()
        compose.onNodeWithTag("android-release-notes").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("support-screen").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("settings-screen").assertExists()
        compose.onNodeWithText("Support and about").assertIsDisplayed()
    }
}
