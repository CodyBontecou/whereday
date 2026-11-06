package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

class PhotoRemovalReviewTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var showing by mutableStateOf(true)
    private var approvals=0
    @Test fun canceledAndReopenedConfirmationCannotReuseAnOldApprovalOrDismissAction() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")
        compose.setContent {MaterialTheme {if(showing) PhotoRemovalReview(false,{showing=false}) {approvals++;showing=false}}}
        fun action(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
        val oldApproval=action("photo-remove-confirm");val oldCancel=action("photo-remove-cancel")
        compose.onNodeWithTag("photo-remove-cancel").performClick()
        compose.runOnIdle {showing=true};compose.onNodeWithTag("photo-remove-confirm").assertExists()
        compose.runOnIdle {oldApproval();oldCancel();assertEquals(0,approvals);assertTrue(showing)}
        compose.onNodeWithTag("photo-remove-confirm").performClick()
        compose.runOnIdle {assertEquals(1,approvals);assertFalse(showing);oldApproval();assertEquals(1,approvals)}
    }
}
