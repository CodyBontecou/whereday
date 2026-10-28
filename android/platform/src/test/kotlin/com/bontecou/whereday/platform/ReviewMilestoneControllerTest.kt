package com.bontecou.whereday.platform

import com.bontecou.whereday.domain.ReviewMilestone
import kotlinx.coroutines.*
import java.time.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ReviewMilestoneControllerTest {
    private val day=LocalDate.of(2026,10,28)
    private val eligible=ReviewMilestone(setOf(day,day.plusDays(1)),true)
    private class Store(var value:ReviewMilestone):ReviewMilestoneStore {
        var fails=false
        override suspend fun read()=value
        override suspend fun write(value:ReviewMilestone) {if(fails) throw IOException("synthetic");this.value=value}
    }
    private class Host:ReviewPromptHost {
        var active=true;var error=false;var prepareGate:CompletableDeferred<Unit>?=null
        val preparing=CompletableDeferred<Unit>();val prepares=AtomicInteger();val launches=AtomicInteger()
        override fun ready()=active
        override suspend fun prepare():ReviewPromptLaunch {
            prepares.incrementAndGet();preparing.complete(Unit);prepareGate?.await()
            if(error) throw IOException("synthetic")
            return ReviewPromptLaunch {launches.incrementAndGet();Unit}
        }
    }
    @Test fun concurrentActivitiesAndReconstructionConsumeOnlyOneDurableAttempt()=runBlocking {
        val store=Store(eligible);val host=Host();val controller=ReviewMilestoneController(store)
        try {
            (1..20).map {async(Dispatchers.Default) {controller.maybeRequest(host)}}.awaitAll()
            assertEquals(1,host.prepares.get());assertEquals(1,host.launches.get());assertTrue(store.value.requested)
        } finally {controller.close()}
        val restored=ReviewMilestoneController(store)
        try {restored.maybeRequest(host);assertEquals(1,host.launches.get())} finally {restored.close()}
    }
    @Test fun focusLossDuringPreparationDoesNotClaimOrLaunchAndLaterForegroundCanRetry()=runBlocking {
        val store=Store(eligible);val controller=ReviewMilestoneController(store);val host=Host().apply {prepareGate=CompletableDeferred()}
        try {
            val request=launch {controller.maybeRequest(host)};host.preparing.await();host.active=false;host.prepareGate!!.complete(Unit);request.join()
            assertFalse(store.value.requested);assertEquals(0,host.launches.get())
            host.active=true;controller.maybeRequest(host);assertEquals(1,host.launches.get())
        } finally {controller.close()}
    }
    @Test fun clearingWhilePreparingFencesTheLateResultAndErasesPendingMilestones()=runBlocking {
        val store=Store(eligible);val controller=ReviewMilestoneController(store);val host=Host().apply {prepareGate=CompletableDeferred()}
        try {
            val request=launch {controller.maybeRequest(host)};host.preparing.await();controller.clearJournalMilestones()
            host.prepareGate!!.complete(Unit);request.join()
            assertEquals(ReviewMilestone(),store.value);assertEquals(0,host.launches.get())
        } finally {controller.close()}
    }
    @Test fun cancellationDuringPreparationDoesNotPublishAStaleLaunchOrConsumeTheAttempt()=runBlocking {
        val store=Store(eligible);val controller=ReviewMilestoneController(store);val host=Host().apply {prepareGate=CompletableDeferred()}
        try {
            val request=launch {controller.maybeRequest(host)};host.preparing.await();request.cancelAndJoin();host.prepareGate!!.complete(Unit)
            assertFalse(store.value.requested);assertEquals(0,host.launches.get())
        } finally {controller.close()}
    }
    @Test fun claimWriteFailureCannotLaunchAndQuarantinesFurtherAttempts()=runBlocking {
        val store=Store(eligible).apply {fails=true};val errors=AtomicInteger();val controller=ReviewMilestoneController(store,unavailable={errors.incrementAndGet()});val host=Host()
        try {
            controller.maybeRequest(host);assertEquals(0,host.launches.get());assertFalse(store.value.requested)
            store.fails=false;controller.maybeRequest(host);assertEquals(1,host.prepares.get());assertEquals(1,errors.get())
        } finally {controller.close()}
    }
    @Test fun providerFailureRemainsRetryableAndDoesNotMarkARatingOrReviewAsSubmitted()=runBlocking {
        val store=Store(eligible);val controller=ReviewMilestoneController(store);val host=Host().apply {error=true}
        try {
            controller.maybeRequest(host);assertFalse(store.value.requested)
            host.error=false;controller.maybeRequest(host);assertTrue(store.value.requested);assertEquals(1,host.launches.get())
        } finally {controller.close()}
    }
    @Test fun onlyActualUseAndExportSignalsRecordDates()=runBlocking {
        val store=Store(ReviewMilestone());var time=Instant.parse("2026-10-28T12:00:00Z")
        val clock=object:Clock() {override fun getZone()=ZoneOffset.UTC;override fun withZone(zone:ZoneId)=this;override fun instant()=time}
        val controller=ReviewMilestoneController(store,clock,{ZoneOffset.UTC})
        try {
            controller.appUsed();withTimeout(5000) {while(controller.state.value.days.size!=1) delay(1)}
            time=time.plusSeconds(86400);controller.fileExported();withTimeout(5000) {while(!controller.state.value.eligible) delay(1)}
            assertEquals(2,controller.state.value.days.size)
        } finally {controller.close()}
    }
}
