package com.bontecou.whereday

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo

/** Moves Page One forward [steps] times without asking for anything: "Not now" / "Set up later" where offered,
 *  otherwise the primary action. The default stops on the final "tonight" step. */
internal fun ComposeTestRule.walkOnboarding(steps:Int=OnboardingStep.tonight.ordinal) {
    repeat(steps) {
        waitForIdle()
        val skip=onAllNodesWithTag("onboarding-skip").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()
        onNodeWithTag(if(skip) "onboarding-skip" else "onboarding-continue").performScrollTo().performClick()
    }
    waitForIdle()
}
