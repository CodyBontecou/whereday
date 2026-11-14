package com.bontecou.whereday

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

/** Leaves Trace/Timeline for Today, waiting for the primary sections. */
internal fun ComposeTestRule.leaveJournal() {
    waitUntil(15000) {count("journal-back-today")>0 || count("navigation-today")>0}
    if(count("journal-back-today")>0) onNodeWithTag("journal-back-today").performClick()
    waitUntil(15000) {count("navigation-today")==1}
}
/** Archive on compact windows; on the rail it is a destination of its own. */
internal fun ComposeTestRule.openShellArchive() {leaveJournal();onNodeWithTag("navigation-archive").performClick()}
/** Settings: the rail slot when expanded, otherwise the gear behind Archive. */
internal fun ComposeTestRule.openShellSettings() {
    leaveJournal()
    if(count("navigation-rail")==0) {onNodeWithTag("navigation-archive").performClick();waitUntil(15000) {count("navigation-settings")==1}}
    onNodeWithTag("navigation-settings").performClick()
    waitUntil(15000) {count("settings-screen")==1}
}
/** Opens the journal from Today when it is not already showing. */
internal fun ComposeTestRule.openJournal(tab:String) {
    waitUntil(15000) {count("navigation-$tab")>0 || count("navigation-today")>0}
    if(count("navigation-$tab")==0) {
        if(count("today-pick-day")==0) {onNodeWithTag("navigation-today").performClick();waitUntil(15000) {count("today-pick-day")==1}}
        onNodeWithTag("today-pick-day").performClick();waitUntil(15000) {count("navigation-$tab")==1}
    }
    onNodeWithTag("navigation-$tab").performClick()
}
private fun ComposeTestRule.count(tag:String)=onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).size
