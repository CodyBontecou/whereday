package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LocationRecoveryControllerTest {
    @Test fun cancellingFirstStartWaiterDoesNotCancelOrRepeatProcessRecovery() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        var cancellations = 0
        val owner = LocationRecoveryController({
            runs++
            entered.complete(Unit)
            try { release.await() } catch (error: CancellationException) { cancellations++; throw error }
        }, {}, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        try {
            val waiter = launch(start = CoroutineStart.UNDISPATCHED) { owner.awaitReady() }
            entered.await()
            owner.start()
            waiter.cancelAndJoin()
            assertEquals("The Activity must not own process recovery", 0, cancellations)
            release.complete(Unit)
            owner.awaitReady()
            assertEquals(1, runs)
        } finally { owner.close() }
    }

    @Test fun failedRecoveryRemainsFailedAndNextAttemptRetries() = runBlocking {
        var runs = 0
        var failures = 0
        val owner = LocationRecoveryController({
            if (++runs == 1) throw IOException("synthetic storage failure")
        }, { failures++ }, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        try {
            try { owner.awaitReady(); fail("A failed persistence operation must reach the caller") }
            catch (_: IOException) {}
            assertEquals(1, failures)
            owner.awaitReady()
            owner.awaitReady()
            assertEquals(2, runs)
        } finally { owner.close() }
    }

    @Test fun concurrentLifecycleChecksShareWorkButLaterCheckRunsAgain() = runBlocking {
        var runs = 0
        val release = CompletableDeferred<Unit>()
        val owner = LocationRecoveryController({ runs++; release.await() }, {},
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        try {
            owner.start()
            val first = async(start = CoroutineStart.UNDISPATCHED) { owner.reconcile() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { owner.awaitReady() }
            assertEquals(1, runs)
            release.complete(Unit)
            first.await(); second.await()
            owner.reconcile()
            assertEquals(2, runs)
        } finally { owner.close() }
    }
}
