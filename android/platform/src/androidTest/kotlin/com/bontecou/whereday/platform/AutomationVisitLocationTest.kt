package com.bontecou.whereday.platform

import android.location.Location
import com.bontecou.whereday.domain.*
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutomationVisitLocationTest {
    private val pin=Coordinate(18.0,-66.0)
    private suspend inline fun <reified T:Throwable> fails(noinline action:suspend ()->Unit) {
        try {action();fail("Expected ${T::class.java.simpleName}")} catch(e:Throwable) {if(e !is T) throw e}
    }
    @Test fun foregroundAndPermissionAreRequiredBeforeTheSourceCanRun()=runBlocking {
        var calls=0;val source=CurrentVisitLocationSource {calls++;CurrentVisitLocation(pin,5.0,100_000)}
        fails<RecordingNeedsForeground> {AutomationVisitLocation({false},{true},source,{100_000}).locate();Unit}
        fails<RecordingNeedsLocation> {AutomationVisitLocation({true},{false},source,{100_000}).locate();Unit}
        assertEquals(0,calls)
    }
    @Test fun currentFixRequiresThirtySecondFreshnessAndAtMostOneHundredMeterAccuracy()=runBlocking {
        for((accuracy,time,usable) in listOf(Triple(100.0,70_000L,true),Triple(5.0,100_000L,true),
            Triple(100.01,100_000L,false),Triple(-1.0,100_000L,false),Triple(Double.NaN,100_000L,false),
            Triple(5.0,69_999L,false),Triple(5.0,100_001L,false),Triple(5.0,-1L,false))) {
            val locator=AutomationVisitLocation({true},{true},CurrentVisitLocationSource {CurrentVisitLocation(pin,accuracy,time)},{100_000})
            if(usable) assertEquals(pin,locator.locate()) else fails<AutomationLocationUnavailable> {locator.locate();Unit}
        }
    }
    @Test fun visibilityAndPermissionsAreCheckedAgainAfterTheAsynchronousFix()=runBlocking {
        var visible=true;var permitted=true
        val hidden=AutomationVisitLocation({visible},{permitted},CurrentVisitLocationSource {visible=false;CurrentVisitLocation(pin,5.0,100_000)},{100_000})
        fails<RecordingNeedsForeground> {hidden.locate();Unit}
        visible=true
        val denied=AutomationVisitLocation({visible},{permitted},CurrentVisitLocationSource {permitted=false;CurrentVisitLocation(pin,5.0,100_000)},{100_000})
        fails<RecordingNeedsLocation> {denied.locate();Unit}
    }
    @Test fun twelveSecondDeadlineCancelsTheActualFusedRequestTokenAndIgnoresLateResult()=runTest {
        val completion=TaskCompletionSource<Location>();val started=CompletableDeferred<Unit>();var token:CancellationToken?=null
        val source=FusedCurrentVisitLocationSource({true}) {_,value->token=value;started.complete(Unit);completion.task}
        val locator=AutomationVisitLocation({true},{true},source,{100_000})
        supervisorScope {
            val result=async {locator.locate()};started.await();assertFalse(token!!.isCancellationRequested)
            advanceTimeBy(12_001);runCurrent()
            fails<AutomationLocationUnavailable> {result.await();Unit}
            assertTrue(token!!.isCancellationRequested)
            completion.setResult(Location("synthetic").apply {latitude=18.0;longitude=-66.0;accuracy=5f;elapsedRealtimeNanos=100_000_000_000})
            assertTrue(result.isCompleted)
        }
    }
    @Test fun sourceErrorsAreSanitizedAndCallerCancellationPropagates()=runTest {
        val unavailable=AutomationVisitLocation({true},{true},CurrentVisitLocationSource {error("Synthetic provider details")},{100_000})
        fails<AutomationLocationUnavailable> {unavailable.locate();Unit}
        val denied=AutomationVisitLocation({true},{true},CurrentVisitLocationSource {throw SecurityException("Synthetic provider details")},{100_000})
        fails<RecordingNeedsLocation> {denied.locate();Unit}
        val started=CompletableDeferred<Unit>();var cancelled=false
        val source=CurrentVisitLocationSource {try {started.complete(Unit);awaitCancellation()} finally {cancelled=true}}
        val job=async {AutomationVisitLocation({true},{true},source,{100_000}).locate()}
        started.await();job.cancelAndJoin();assertTrue(cancelled)
    }
}
