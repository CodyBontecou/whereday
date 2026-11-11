package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class QaKeyboardTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    private fun keyboardVisible():Boolean {
        var visible=false
        Espresso.onView(isRoot()).inRoot(isDialog()).perform(object:ViewAction {
            override fun getConstraints()=isRoot()
            override fun getDescription()="Read the real dialog's software keyboard visibility"
            override fun perform(controller:UiController,view:android.view.View) {
                visible=checkNotNull(ViewCompat.getRootWindowInsets(view)).isVisible(WindowInsetsCompat.Type.ime())
            }
        })
        return visible
    }

    @Test fun invalidDateDraftSurvivesActualSoftwareKeyboardDismissal() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        var changes=0
        compose.setContent {MaterialTheme {
            VisitDateTimeControl("Arrival",Instant.parse("2026-11-01T12:00:00Z")) {changes++}
        }}
        compose.onNodeWithTag("visit-timestamp-date").performClick()
        compose.onNodeWithText(target.getString(R.string.date_enter_iso)).performScrollTo().performClick()
        compose.onNodeWithTag("visit-timestamp-date-input").performClick().performTextReplacement("2026-02-30")
        compose.waitUntil(10000) {keyboardVisible()}
        Espresso.onView(isRoot()).inRoot(isDialog()).perform(closeQaKeyboard())
        compose.waitUntil(10000) {!keyboardVisible()}
        compose.onNodeWithTag("visit-timestamp-date-input").assertTextContains("2026-02-30")
        compose.onNodeWithText(target.getString(R.string.visit_apply_date)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(target.getString(R.string.visit_cancel)).assertIsDisplayed()
        assertEquals(0,changes)
    }
}
