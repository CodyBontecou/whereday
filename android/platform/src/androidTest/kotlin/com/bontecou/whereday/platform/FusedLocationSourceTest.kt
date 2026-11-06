package com.bontecou.whereday.platform

import android.location.Location
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bontecou.whereday.domain.TrackPoint
import com.google.android.gms.location.*
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class FusedLocationSourceTest {
    private fun fix(time:Long)=Location("synthetic").apply {latitude=18.0;longitude=-66.0;this.time=time;accuracy=5f}
    @Test fun visitsOnlyUsesBalancedPowerAndStationarySamplesAfterRegistrationAcknowledgment()=runBlocking {
        val completion=TaskCompletionSource<Void>();var requested:LocationRequest?=null
        val entered=CompletableDeferred<Unit>()
        val source=FusedLocationSource({true},{request,_ ->requested=request;entered.complete(Unit);completion.task},{},LocationSampling.visitsOnly)
        val start=async {source.start(500,{}, {})}
        entered.await();assertFalse(start.isCompleted)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY,requested!!.priority)
        assertEquals(0f,requested!!.minUpdateDistanceMeters,0f)
        assertEquals(120_000L,requested!!.intervalMillis)
        assertEquals(60_000L,requested!!.minUpdateIntervalMillis)
        assertEquals(300_000L,requested!!.maxUpdateDelayMillis)
        completion.setResult(null);withTimeout(5000) {start.await()};source.stop()
    }
    @Test fun registrationMustSucceedBeforeStartReturnsAndRequestOptionsArePreserved()=runBlocking {
        val completion=TaskCompletionSource<Void>();var requested:LocationRequest?=null;val entered=CompletableDeferred<Unit>()
        val source=FusedLocationSource({true},{request,_ ->requested=request;entered.complete(Unit);completion.task},{})
        val start=async {source.start(25,{}, {})}
        entered.await();assertFalse(start.isCompleted)
        assertEquals(25f,requested!!.minUpdateDistanceMeters,0f)
        assertEquals(5_000L,requested!!.intervalMillis);assertEquals(1_000L,requested!!.minUpdateIntervalMillis)
        completion.setResult(null);withTimeout(5000) {start.await()};source.stop()
    }
    @Test fun deniedAndFailedRegistrationNeverReportSuccess()=runBlocking {supervisorScope {
        var registered=false
        val denied=FusedLocationSource({false},{_,_ ->registered=true;error("Forbidden")},{})
        try {denied.start(5,{},{});fail("Denied start succeeded")} catch(_:SecurityException) { }
        assertFalse(registered)
        val completion=TaskCompletionSource<Void>();val entered=CompletableDeferred<Unit>();var removed=false
        val source=FusedLocationSource({true},{_,_ ->entered.complete(Unit);completion.task},{removed=true})
        val start=async {runCatching {source.start(5,{}, {})}}
        entered.await();completion.setException(IOException("synthetic registration failure"))
        assertTrue(withTimeout(5000) {start.await()}.exceptionOrNull() is IOException);assertTrue(removed)
    }}
    @Test fun cancelledLateRegistrationIsRemovedAndCannotDeliverIntoANewerSource()=runBlocking {
        for(sampling in LocationSampling.entries) {
            val first=TaskCompletionSource<Void>();val second=TaskCompletionSource<Void>()
            val callbacks=CopyOnWriteArrayList<LocationCallback>();val removed=CopyOnWriteArrayList<LocationCallback>();val points=mutableListOf<TrackPoint>()
            val source=FusedLocationSource({true},{_,callback ->callbacks+=callback;if(callbacks.size==1) first.task else second.task},{removed+=it},sampling)
            val old=launch {source.start(5,{points+=it},{})}
            withTimeout(5000) {while(callbacks.size!=1) yield()}
            old.cancelAndJoin()
            val current=async {source.start(10,{points+=it},{})}
            withTimeout(5000) {while(callbacks.size!=2) yield()}
            second.setResult(null);withTimeout(5000) {current.await()}
            first.setResult(null)
            callbacks[0].onLocationResult(LocationResult.create(listOf(fix(1000))))
            callbacks[1].onLocationResult(LocationResult.create(listOf(fix(2000))))
            assertEquals(listOf(2000L),points.map {it.time.toEpochMilli()})
            withTimeout(5000) {while(removed.count {it===callbacks[0]}<2) yield()}
            assertFalse(removed.any {it===callbacks[1]})
            source.stop()
            callbacks[1].onLocationResult(LocationResult.create(listOf(fix(3000))))
            assertEquals(listOf(2000L),points.map {it.time.toEpochMilli()})
            assertTrue(removed.any {it===callbacks[1]})
        }
    }
    @Test fun cancellingTheProviderTaskCancelsStartAndUnregistersItsCallback()=runBlocking {supervisorScope {
        val cancellation=CancellationTokenSource();val completion=TaskCompletionSource<Void>(cancellation.token)
        val entered=CompletableDeferred<Unit>();var removed=false
        val source=FusedLocationSource({true},{_,_ ->entered.complete(Unit);completion.task},{removed=true})
        val start=async {runCatching {source.start(5,{}, {})}}
        entered.await();cancellation.cancel()
        assertTrue(withTimeout(5000) {start.await()}.exceptionOrNull() is CancellationException)
        assertTrue(removed)
    }}
}
