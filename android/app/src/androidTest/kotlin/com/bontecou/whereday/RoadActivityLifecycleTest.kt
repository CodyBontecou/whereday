package com.bontecou.whereday

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** The mapqa package owns all state; no recording, location grant or HTTP request is needed. */
class RoadActivityLifecycleTest {
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
