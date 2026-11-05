package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Fresh disposable platform.test process; synthetic directions/disclosure, actual graph and DataStore. */
class RoadConsentGraphTest {
    @Test fun reviewedConsentPersistsAndRevocationFencesBeforeItsWriteFinishes():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val graph=AppGraph(context,RoadDirections {a,b,_->RoadDirectionsResult(a.distanceTo(b)*1.3,listOf(a,b))},
            RoadProviderDisclosure("Synthetic routing","https://example.test/privacy"))
        val consent=graph.roadConsentSession()
        val writerEntered=CompletableDeferred<Unit>();val writerRelease=CompletableDeferred<Unit>()
        var writer:Deferred<Unit>?=null
        var display:RoadDisplayController.Session?=null
        var phase="startup"
        try {
            withTimeout(30000) {graph.journalClear.awaitReady()}
            val initial=graph.preferences.settings.first();assertFalse(initial.roadDisplayEnabled)
            consent.foreground();consent.review(initial)
            val id=requireNotNull(consent.state.value.review).id
            requireNotNull(consent.confirm(id)).join();assertNull(consent.confirm(id))
            assertTrue(graph.preferences.settings.first().roadDisplayEnabled)
            phase="observe saved consent"
            withTimeout(5000) {graph.roadDisplay.state.first {it.enabled && !it.policyUnavailable && !it.loading}}
            val points=listOf(TrackPoint("consent-a",Coordinate(0.0,0.0),Instant.EPOCH,5.0),TrackPoint("consent-b",Coordinate(0.0,.01),Instant.EPOCH.plusSeconds(600),5.0))
            val session=graph.roadDisplay.createSession().also {display=it;it.foreground()}
            val plan=session.prepare(points);assertTrue(session.isCurrent(plan,plan.route.fingerprint))
            writer=async(Dispatchers.IO) {graph.preferences.update {value->runBlocking {writerEntered.complete(Unit);writerRelease.await()};value}}
            phase="enter queued settings write"
            withTimeout(5000) {writerEntered.await()}
            val revoked=requireNotNull(consent.disable())
            assertFalse(revoked.isCompleted)
            assertFalse(session.isCurrent(plan,plan.route.fingerprint))
            consent.background();consent.close();writerRelease.complete(Unit);writer.await();phase="persist revocation after close";withTimeout(5000) {revoked.join()}
            assertFalse(graph.preferences.settings.first().roadDisplayEnabled)
            assertTrue(graph.preferences.settings.first().networkGeocoding)
        } catch(error:Exception) {throw AssertionError("Failed at $phase; road state=${graph.roadDisplay.state.value}; consent=${consent.state.value}",error)}
        finally {writerRelease.complete(Unit);writer?.join();consent.close();display?.close();graph.roadDisplay.close();graph.journalClear.close();graph.database.close()}
    }
}
