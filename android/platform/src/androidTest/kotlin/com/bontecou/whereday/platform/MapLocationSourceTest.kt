package com.bontecou.whereday.platform

import android.location.Location
import com.bontecou.whereday.domain.*
import com.google.android.gms.location.*
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

class MapLocationSourceTest {
    private fun location(time:Long)=Location("synthetic").apply {latitude=18.0;longitude=-66.0;accuracy=2000f;elapsedRealtimeNanos=time*1_000_000}
    @Test fun balancedForegroundRequestHasNoBatchDelayAndEmitsTheNewestMonotonicFix()=runBlocking {
        val completion=TaskCompletionSource<Void>();var callback:LocationCallback?=null;var configuration:LocationRequest?=null;var removed=false
        val entered=CompletableDeferred<Unit>()
        val source=FusedMapLocationSource({true},{request,receiver->configuration=request;callback=receiver;entered.complete(Unit);completion.task},{removed=true})
        val received=CompletableDeferred<MapLocationFix>()
        val job=launch {source.updates().collect {received.complete(it)}}
        entered.await();completion.setResult(null)
        assertEquals(Priority.PRIORITY_BALANCED_POWER_ACCURACY,configuration!!.priority)
        assertEquals(5000L,configuration!!.intervalMillis);assertEquals(2000L,configuration!!.minUpdateIntervalMillis)
        // Play services normalizes delay to at least the interval; that is still unbatched.
        assertFalse(configuration!!.isBatched)
        callback!!.onLocationResult(LocationResult.create(listOf(location(2000),location(1000))))
        assertEquals(MapLocationFix(Coordinate(18.0,-66.0),2000.0,2000),withTimeout(5000) {received.await()})
        job.cancelAndJoin();assertTrue(removed)
    }
    @Test fun deniedPermissionDoesNotRegisterAProvider()=runBlocking {
        var registered=false
        val source=FusedMapLocationSource({false},{_,_->registered=true;error("Unexpected")},{})
        val error=runCatching {source.updates().first()}.exceptionOrNull()
        assertTrue(error is SecurityException);assertFalse(registered)
    }
    @Test fun permissionRevokedDuringRegistrationClosesTheFlowAndRemovesItsCallback()=runBlocking {
        var removed=false
        val source=FusedMapLocationSource({true},{_,_->throw SecurityException("synthetic revoked permission")},{removed=true})
        assertTrue(runCatching {source.updates().first()}.exceptionOrNull() is SecurityException)
        assertTrue(removed)
    }
    @Test fun cancellationRemovesLateRegistrationAndFencesOldCallbacks()=runBlocking {
        val completion=TaskCompletionSource<Void>();val callbacks=CopyOnWriteArrayList<LocationCallback>();val removed=CopyOnWriteArrayList<LocationCallback>()
        val values=CopyOnWriteArrayList<MapLocationFix>()
        val source=FusedMapLocationSource({true},{_,receiver->callbacks+=receiver;completion.task},{removed+=it})
        val job=launch {source.updates().collect {values+=it}}
        withTimeout(5000) {while(callbacks.isEmpty()) yield()}
        job.cancelAndJoin();completion.setResult(null)
        callbacks.single().onLocationResult(LocationResult.create(listOf(location(1000))))
        withTimeout(5000) {while(removed.size<2) yield()}
        assertTrue(values.isEmpty())
    }
    @Test fun registrationFailuresCloseTheFlowAndRemoveItsCallback()=runBlocking {
        val completion=TaskCompletionSource<Void>();val entered=CompletableDeferred<Unit>();var removed=false
        val source=FusedMapLocationSource({true},{_,_->entered.complete(Unit);completion.task},{removed=true})
        val result=async {runCatching {source.updates().first()}}
        entered.await();completion.setException(IOException("synthetic"))
        assertTrue(withTimeout(5000) {result.await()}.exceptionOrNull() is IOException)
        assertTrue(removed)
    }
}
