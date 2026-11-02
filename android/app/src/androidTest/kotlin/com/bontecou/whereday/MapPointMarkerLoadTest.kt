package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MapPointMarkerLoadTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val viewport=MapPointViewport(0.0,0.0,1.0,1.0,480.0,300.0)
    private val gates=mutableListOf<CompletableDeferred<Unit>>()
    private val latest=AtomicReference<MapPointMarkerLoad>()
    private fun point(id:String)=TrackPoint(id,Coordinate(0.0,0.0),Instant.EPOCH,5.0)
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    @After fun release() {gates.forEach {it.complete(Unit)}}
    private fun show(source:State<MapPointMarkerSource>,enabled:State<Boolean> = mutableStateOf(true)) {
        compose.setContent {
            val load=rememberMapPointMarkers(emptyList(),viewport,null,source.value,null,enabled.value,"scope")
            SideEffect {latest.set(load)}
            Text(if(load.failed) "failed" else if(load.loading) "loading" else load.markers.points.joinToString {it.id},Modifier.testTag("result"))
        }
    }
    @Test fun anOldNoncooperativeSourceCannotPublishAfterOwnerReplacement() {
        val began=CompletableDeferred<Unit>();val gate=CompletableDeferred<Unit>().also(gates::add)
        val completed=CompletableDeferred<Unit>()
        val old=MapPointMarkerSource {_,_,_->flow {
            began.complete(Unit)
            try {val stale=withContext(NonCancellable) {gate.await();MapPointMarkers(listOf(point("old")))};emit(stale)}
            finally {completed.complete(Unit)}
        }}
        val source=mutableStateOf(old);show(source)
        compose.waitUntil(5000) {began.isCompleted}
        compose.runOnIdle {source.value=MapPointMarkerSource {_,_,_->flowOf(MapPointMarkers(listOf(point("new"))))}}
        compose.waitUntil(5000) {latest.get()?.markers?.points?.singleOrNull()?.id=="new"}
        gate.complete(Unit);compose.waitUntil(5000) {completed.isCompleted}
        compose.onNodeWithTag("result").assertTextEquals("new")
    }
    @Test fun aReadFailureHasAnExplicitRetryAndDoesNotFallbackToTheRouteSample() {
        val attempts=AtomicInteger()
        val source=mutableStateOf(MapPointMarkerSource {_,_,_->flow {
            if(attempts.incrementAndGet()==1) error("Synthetic database read failure")
            emit(MapPointMarkers(listOf(point("recovered"))))
        }})
        show(source);compose.waitUntil(5000) {latest.get()?.failed==true}
        assertTrue(latest.get().markers.points.isEmpty())
        compose.runOnIdle {latest.get().retry()}
        compose.waitUntil(5000) {latest.get()?.markers?.points?.singleOrNull()?.id=="recovered"}
        assertEquals(2,attempts.get())
    }
    @Test fun hidingPointsCancelsAndRejectsLateResults() {
        val began=CompletableDeferred<Unit>();val completed=CompletableDeferred<Unit>()
        val gate=CompletableDeferred<Unit>().also(gates::add)
        val enabled=mutableStateOf(true)
        val source=mutableStateOf(MapPointMarkerSource {_,_,_->flow {
            began.complete(Unit)
            try {withContext(NonCancellable) {gate.await()};emit(MapPointMarkers(listOf(point("late"))))}
            finally {completed.complete(Unit)}
        }})
        show(source,enabled);compose.waitUntil(5000) {began.isCompleted}
        compose.runOnIdle {enabled.value=false};gate.complete(Unit)
        compose.waitUntil(5000) {completed.isCompleted && latest.get()?.ready==true}
        compose.onNodeWithTag("result").assertTextEquals("")
    }
    @Test fun pauseClearsMarkersAndResumeStartsAFreshCollection() {
        val starts=AtomicInteger();val stops=AtomicInteger()
        val source=mutableStateOf(MapPointMarkerSource {_,_,_->flow {
            val generation=starts.incrementAndGet()
            try {emit(MapPointMarkers(listOf(point("generation-$generation"))));awaitCancellation()}
            finally {stops.incrementAndGet()}
        }})
        show(source);compose.waitUntil(5000) {latest.get()?.markers?.points?.singleOrNull()?.id=="generation-1"}
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitUntil(5000) {latest.get()?.markers?.points?.isEmpty()==true && stops.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5000) {latest.get()?.markers?.points?.singleOrNull()?.id=="generation-2"}
        compose.onNodeWithTag("result").assertTextEquals("generation-2")
    }
}
