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

/** A visit picked on the map opens its quick view first; Details opens the shared editor. */
internal fun ComposeTestRule.openVisitDetails(timeoutMillis:Long=10_000) {
    waitUntil(timeoutMillis) {onAllNodesWithTag("map-visit-details").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
    onNodeWithTag("map-visit-details").performClick()
}

/** The full-bleed map offers its point and visit choosers as custom accessibility actions. */
internal fun SemanticsNodeInteraction.performMapAction(label:String) {
    val actions=fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
    val action=checkNotNull(actions.firstOrNull {it.label==label}) {"No map action named $label"}
    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {action.action()}
}
