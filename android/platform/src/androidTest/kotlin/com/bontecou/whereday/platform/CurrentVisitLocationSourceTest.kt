package com.bontecou.whereday.platform

import android.location.Location
import android.os.SystemClock
import com.bontecou.whereday.domain.*
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CurrentVisitLocationSourceTest {
    private fun fix()=Location("synthetic").apply {latitude=0.0;longitude=0.0;accuracy=10f;elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()}
    @Test fun singleFixRequestIsFreshBoundedAndNeverNeedsRecording()=runBlocking {
        val completion=TaskCompletionSource<Location>()
        val source=FusedCurrentVisitLocationSource({true}) {request,_->
            assertEquals(Priority.PRIORITY_HIGH_ACCURACY,request.priority);assertEquals(0L,request.maxUpdateAgeMillis)
            assertEquals(30_000L,request.durationMillis);completion.task
        }
        val result=async {source.locate()};yield();completion.setResult(fix())
        val value=result.await()!!;assertEquals(Coordinate(0.0,0.0),value.coordinate);assertTrue(value.isUsable(SystemClock.elapsedRealtime()))
    }
    @Test fun deniedPermissionNeverStartsTheProviderAndProviderFailuresPropagate()=runBlocking {
        val denied=FusedCurrentVisitLocationSource({false}) {_,_->error("Must not request")}
        try {denied.locate();fail()} catch(_:SecurityException) {}
        val completion=TaskCompletionSource<Location>()
        val source=FusedCurrentVisitLocationSource({true}) {_,_->completion.task}
        supervisorScope {
            val result=async {source.locate()};yield();completion.setException(IllegalStateException("synthetic unavailable"))
            try {result.await();fail()} catch(error:IllegalStateException) {assertEquals("synthetic unavailable",error.message)}
        }
    }
    @Test fun cancellationCancelsTheNativeTokenAndLateCompletionCannotReturnAFix()=runBlocking {
        val completion=TaskCompletionSource<Location>();var token:CancellationToken?=null
        val source=FusedCurrentVisitLocationSource({true}) {_,value->token=value;completion.task}
        val result=async {source.locate()};yield();assertFalse(token!!.isCancellationRequested)
        result.cancelAndJoin();assertTrue(token!!.isCancellationRequested);completion.setResult(fix())
        assertTrue(result.isCancelled)
    }
    @Test fun missingAccuracyNeverBecomesAReadyFixAndStaleOrPoorFixesRemainUnusable() {
        val value=fix();value.removeAccuracy();assertNull(currentVisitLocation(value))
        value.accuracy=101f;assertFalse(currentVisitLocation(value)!!.isUsable(SystemClock.elapsedRealtime()))
        value.accuracy=1f;value.elapsedRealtimeNanos=SystemClock.elapsedRealtimeNanos()-301_000_000_000
        assertFalse(currentVisitLocation(value)!!.isUsable(SystemClock.elapsedRealtime()))
    }
}
