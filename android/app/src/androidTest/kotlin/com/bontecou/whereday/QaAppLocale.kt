package com.bontecou.whereday

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry

/** LocaleManager recreates this manifest's test Activity itself; never race it with recreate(). */
internal fun applyQaAppLocales(
    compose:AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>,ComponentActivity>,
    locales:LocaleList,
) {
    val target=InstrumentationRegistry.getInstrumentation().targetContext
    check(target.packageName=="com.bontecou.whereday.mapqa")
    val manager=target.getSystemService(LocaleManager::class.java)
    var oldActivity:ComponentActivity?=null
    compose.activityRule.scenario.onActivity {oldActivity=it}
    val changed=manager.applicationLocales!=locales
    val expected=if(locales.isEmpty) manager.systemLocales[0] else locales[0]
    manager.applicationLocales=locales
    var observed="No resumed Activity"
    try {compose.waitUntil(5000) {runCatching {
        var ready=false
        compose.activityRule.scenario.onActivity {current->
            observed="locale=${current.resources.configuration.locales.toLanguageTags()}, recreated=${current!==oldActivity}"
            ready=(!changed || current!==oldActivity) && current.resources.configuration.locales[0]==expected
        }
        ready
    }.getOrDefault(false)}} catch(error:androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("QA locale ${locales.toLanguageTags()} expected $expected; $observed",error)
    }
    compose.waitForIdle()
}
