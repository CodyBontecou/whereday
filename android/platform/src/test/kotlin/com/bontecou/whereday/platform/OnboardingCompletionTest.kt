package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OnboardingCompletionTest {
    @Test fun laterCompletionNeverStartsAndSavesBeforeReportingSuccess():Unit=runBlocking {
        val calls=mutableListOf<String>()
        completeOnboarding(false,{error("Unrequested recording")},{calls+="saved"},{calls+="reported"})
        assertEquals(listOf("saved","reported"),calls)
    }
    @Test fun requestedStartMustBeAcknowledgedBeforeSetupIsCommitted():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();val acknowledgment=CompletableDeferred<Unit>();val calls=mutableListOf<String>()
        val finish=launch {completeOnboarding(true,{entered.complete(Unit);acknowledgment.await();calls+="started"},{calls+="saved"},{calls+="reported"})}
        entered.await();assertTrue(calls.isEmpty())
        acknowledgment.complete(Unit);finish.join()
        assertEquals(listOf("started","saved","reported"),calls)
    }
    @Test fun startFailureAndCancellationCannotSaveOrReportCompletion():Unit=runBlocking {
        try {completeOnboarding(true,{throw IllegalStateException("Synthetic start failure")},{fail("Saved failed start")},{fail("Reported failed start")});fail("Failure hidden")}
        catch(_:OnboardingStartFailed) {}
        val entered=CompletableDeferred<Unit>()
        val finish=launch {completeOnboarding(true,{entered.complete(Unit);awaitCancellation()},{fail("Saved canceled start")},{fail("Reported canceled start")})}
        entered.await();finish.cancelAndJoin()
    }
    @Test fun durableSaveAndCompletionSurviveDisposalAfterAcknowledgment():Unit=runBlocking {
        val saving=CompletableDeferred<Unit>();val commit=CompletableDeferred<Unit>();var saved=false;var reported=false
        val finish=launch {completeOnboarding(true,{},{saving.complete(Unit);commit.await();saved=true},{reported=true})}
        saving.await();finish.cancel();assertFalse(saved);assertFalse(reported)
        commit.complete(Unit);finish.join()
        assertTrue(saved);assertTrue(reported)
    }
    @Test fun storageFailureIsVisibleAndNeverReportsSuccess():Unit=runBlocking {
        try {completeOnboarding(false,{fail("Unexpected start")},{throw java.io.IOException("Synthetic full storage")},{fail("Reported failed save")});fail("Failure hidden")}
        catch(_:OnboardingSaveFailed) {}
    }
}
