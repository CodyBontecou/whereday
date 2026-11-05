package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.*
import java.time.Instant

class MapPointOutlierSourceTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun roundTrip(outlier:Boolean) {
        val point=TrackPoint("selection",Coordinate(18.0,-66.0),Instant.parse("2026-11-02T00:00:00Z"),5.0,outlier=outlier)
        val include=mutableStateOf(true);val excludedReadStarted=CompletableDeferred<Unit>()
        compose.setContent {MaterialTheme {Surface {Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {
            val source=remember(include.value) {
                val allowed=include.value
                MapPointMarkerSource {viewport,selected,_->flow {
                    if(!allowed) {excludedReadStarted.complete(Unit);awaitCancellation()}
                    emit(MapPointMarkerSampler(viewport,selected).apply {add(point)}.snapshot())
                }}
            }
            JournalMap(listOf(point),emptyList(),showOutliers=include.value,onOutliers={include.value=it},pointMarkerSource=source)
        }}}}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("map-point-browse").fetchSemanticsNodes().size==1}
        compose.onNodeWithTag("map-point-browse").performScrollTo().performClick()
        compose.onNodeWithTag("map-point-choice-selection").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("map-point-inspection").fetchSemanticsNodes().size==1}
        compose.runOnIdle {include.value=false}
        compose.waitUntil(10000) {excludedReadStarted.isCompleted}
        compose.onNodeWithTag("map-point-inspection").assertDoesNotExist()
        compose.runOnIdle {include.value=true}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("map-point-browse").fetchSemanticsNodes().size==1}
        if(outlier) compose.onNodeWithTag("map-point-inspection").assertDoesNotExist()
        else compose.onNodeWithTag("map-point-inspection").assertExists()
    }
    @Test fun excludedOutlierDoesNotResurrectIfTheFilterReturnsBeforeItsReadCompletes() {roundTrip(true)}
    @Test fun ordinarySelectedPointSurvivesTheSamePendingFilterRoundTrip() {roundTrip(false)}
}
