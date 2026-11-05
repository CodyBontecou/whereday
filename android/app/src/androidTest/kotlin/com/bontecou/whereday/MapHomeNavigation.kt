package com.bontecou.whereday

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

/** The day's timeline is no longer a tab: it opens over the map from the day sheet. */
internal fun ComposeTestRule.openTimeline(timeoutMillis:Long=15_000) {
    waitUntil(timeoutMillis) {onAllNodesWithTag("map-day-peek").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
    onNodeWithTag("map-day-peek").performClick()
    waitUntil(timeoutMillis) {onAllNodesWithTag("map-open-timeline").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
    onNodeWithTag("map-open-timeline").performScrollTo().performClick()
    waitUntil(timeoutMillis) {onAllNodesWithTag("timeline-content").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
}
