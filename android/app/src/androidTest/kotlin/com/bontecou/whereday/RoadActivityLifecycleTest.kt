package com.bontecou.whereday

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Owned mapQa only; this class runs on its own installation outside denied-permission fixtures. */
class RoadActivityLifecycleTest {
    @Test fun serviceStartsResolveTheCurrentOwnerAfterRealActivityRecreation():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        val graph=AppGraph.from(context);graph.journalClear.awaitReady()
        graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,networkGeocoding=false)}
        val permissions=listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION,android.Manifest.permission.ACCESS_FINE_LOCATION)+
            if(android.os.Build.VERSION.SDK_INT>=33) listOf(android.Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        permissions.forEach {instrumentation.uiAutomation.grantRuntimePermission(context.packageName,it)}
        val scenario=ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        var original:androidx.activity.ComponentActivity?=null
        scenario.onActivity {original=it}
        try {
            startSelectedLocationServices(scenario,graph,route=true,visits=true,focusTimeoutMs=20_000) {
                assertTrue(graph.history.dao.visitMonitoring()!!.requested)
                scenario.recreate()
                scenario.onActivity {assertNotSame(original,it)}
                assertTrue(checkNotNull(original).isDestroyed)
            }
            withTimeout(20_000) {
                while(!com.bontecou.whereday.platform.RecordingRuntime.active.value ||
                    com.bontecou.whereday.platform.VisitMonitoringRuntime.state.value!=com.bontecou.whereday.platform.VisitMonitoringMode.route) delay(30)
            }
            assertTrue(graph.history.dao.recording()!!.requested)
            assertTrue(graph.history.dao.visitMonitoring()!!.requested)
            assertEquals(1L,graph.history.dao.openSessionCount())
        } finally {
            graph.visitMonitoringCommands.stop();graph.recordingCommands.stop();scenario.close()
        }
    }

    @Test fun realActivityRecreationBackgroundAndDestroyReleaseOnlyTheirOwnSessions()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        val graph=AppGraph.from(context)
        suspend fun count(expected:Int) {withTimeout(10000) {graph.roadDisplay.state.first {it.foregroundSessions==expected}}}
        val first=ActivityScenario.launch(MainActivity::class.java)
        try {
            count(1);assertFalse(graph.roadDisplay.state.value.configured)
            first.recreate();count(1)
            first.moveToState(Lifecycle.State.CREATED);count(0)
            first.moveToState(Lifecycle.State.RESUMED);count(1)
            // A separate session models another resumed window while this activity pauses/destroys.
            val other=graph.roadDisplay.createSession();other.foreground();count(2)
            try {first.moveToState(Lifecycle.State.CREATED);count(1);first.close();count(1)}
            finally {other.close()}
            count(0)
        } finally {first.close()}
    }
}
