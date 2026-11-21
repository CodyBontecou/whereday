package com.bontecou.whereday.platform

import com.bontecou.whereday.domain.ReviewMilestone
import com.bontecou.whereday.domain.ReviewSuccess
import kotlinx.coroutines.*
import java.time.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class ReviewMilestoneControllerTest {
    private val day=LocalDate.of(2026,10,28)
    private val twoDays=ReviewMilestone(2,day.plusDays(1))
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
    private class TestClock(var time:Instant=Instant.parse("2026-10-29T12:00:00Z")):Clock() {
        override fun getZone()=ZoneOffset.UTC;override fun withZone(zone:ZoneId)=this;override fun instant()=time
    }
    private fun controller(store:Store,clock:Clock=TestClock(),version:String="1.0",unavailable:()->Unit={})=
        ReviewMilestoneController(store,version,clock,{ZoneOffset.UTC},unavailable)
    private suspend fun ReviewMilestoneController.succeedAndWait(source:ReviewSuccess=ReviewSuccess.fileExport) {
        val before=pendingSuccess.value?.id;succeeded(source)
        withTimeout(5000) {while(pendingSuccess.value==null || pendingSuccess.value?.id==before) delay(1)}
    }

    @Test fun appUseAloneNeverCreatesASuccessMoment()=runBlocking {
        val store=Store(twoDays);val clock=TestClock();val controller=controller(store,clock);val host=Host()
        try {
            clock.time=clock.time.plus(Duration.ofDays(1));controller.appUsed()
            withTimeout(5000) {while(controller.state.value.useDays!=3) delay(1)}
            controller.maybeRequest(host)
            assertNull(controller.pendingSuccess.value);assertEquals(0,host.prepares.get());assertNull(store.value.requestedAt)
        } finally {controller.close()}
    }
    @Test fun excludedSourcesNeverRequestOrCountADay()=runBlocking {
        val store=Store(ReviewMilestone(1,day));val clock=TestClock();val controller=controller(store,clock);val host=Host()
        try {
            controller.succeeded(ReviewSuccess.backup);controller.succeeded(ReviewSuccess.scheduledExport)
            delay(100);controller.maybeRequest(host)
            assertNull(controller.pendingSuccess.value);assertEquals(1,store.value.useDays);assertEquals(0,host.launches.get())
        } finally {controller.close()}
    }
    @Test fun qualifyingSuccessOnTheSecondDayRequestsAndRecordsVersionAndTime()=runBlocking {
        val store=Store(ReviewMilestone(1,day));val clock=TestClock();val controller=controller(store,clock);val host=Host()
        try {
            controller.succeedAndWait(ReviewSuccess.countryDaysExport)
            assertEquals(2,store.value.useDays)
            controller.maybeRequest(host)
            assertEquals(1,host.launches.get());assertEquals("1.0",store.value.requestedVersion);assertEquals(clock.time,store.value.requestedAt)
            assertNull(controller.pendingSuccess.value)
            controller.succeedAndWait(ReviewSuccess.purchase);controller.maybeRequest(host)
            assertEquals("once per version",1,host.launches.get())
        } finally {controller.close()}
    }
    @Test fun aNewVersionAsksAgainOnlyAfterTheCooldown()=runBlocking {
        val clock=TestClock();val store=Store(twoDays.claim("1.0",clock.time));val host=Host()
        val controller=controller(store,clock,version="1.1")
        try {
            clock.time=clock.time.plus(Duration.ofDays(119));controller.succeedAndWait();controller.maybeRequest(host)
            assertEquals(0,host.prepares.get());assertNull("an ineligible success is dropped",controller.pendingSuccess.value)
            clock.time=clock.time.plus(Duration.ofDays(1));controller.succeedAndWait();controller.maybeRequest(host)
            assertEquals(1,host.launches.get());assertEquals("1.1",store.value.requestedVersion)
        } finally {controller.close()}
    }
    @Test fun aSuccessBehindAnotherWindowWaitsBrieflyThenExpires()=runBlocking {
        val store=Store(twoDays);val clock=TestClock();val controller=controller(store,clock);val host=Host().apply {active=false}
        try {
            controller.succeedAndWait();controller.maybeRequest(host)
            assertEquals(0,host.prepares.get());assertNotNull("kept while another window has focus",controller.pendingSuccess.value)
            clock.time=clock.time.plusSeconds(31);host.active=true;controller.maybeRequest(host)
            assertEquals(0,host.prepares.get());assertNull(controller.pendingSuccess.value);assertNull(store.value.requestedAt)
            controller.succeedAndWait();controller.maybeRequest(host);assertEquals("the next success can ask",1,host.launches.get())
        } finally {controller.close()}
    }
    @Test fun concurrentActivitiesAndReconstructionConsumeOnlyOneDurableAttempt()=runBlocking {
        val store=Store(twoDays);val host=Host();val controller=controller(store)
        try {
            controller.succeedAndWait()
            (1..20).map {async(Dispatchers.Default) {controller.maybeRequest(host)}}.awaitAll()
            assertEquals(1,host.prepares.get());assertEquals(1,host.launches.get());assertNotNull(store.value.requestedAt)
        } finally {controller.close()}
        val restored=controller(store)
        try {restored.succeedAndWait();restored.maybeRequest(host);assertEquals(1,host.launches.get())} finally {restored.close()}
    }
    @Test fun focusLossDuringPreparationDoesNotClaimOrLaunchAndLaterFocusCanRetry()=runBlocking {
        val store=Store(twoDays);val controller=controller(store);val host=Host().apply {prepareGate=CompletableDeferred()}
        try {
            controller.succeedAndWait()
            val request=launch {controller.maybeRequest(host)};host.preparing.await();host.active=false;host.prepareGate!!.complete(Unit);request.join()
            assertNull(store.value.requestedAt);assertEquals(0,host.launches.get())
            host.active=true;controller.maybeRequest(host);assertEquals(1,host.launches.get())
        } finally {controller.close()}
    }
    @Test fun clearingWhilePreparingFencesTheLateResultAndErasesPendingMilestones()=runBlocking {
        val store=Store(twoDays);val controller=controller(store);val host=Host().apply {prepareGate=CompletableDeferred()}
        try {
            controller.succeedAndWait()
            val request=launch {controller.maybeRequest(host)};host.preparing.await();controller.clearJournalMilestones()
            host.prepareGate!!.complete(Unit);request.join()
            assertEquals(ReviewMilestone(),store.value);assertEquals(0,host.launches.get());assertNull(controller.pendingSuccess.value)
        } finally {controller.close()}
    }
    @Test fun cancellationDuringPreparationDoesNotPublishAStaleLaunchOrConsumeTheAttempt()=runBlocking {
        val store=Store(twoDays);val controller=controller(store);val host=Host().apply {prepareGate=CompletableDeferred()}
        try {
            controller.succeedAndWait()
            val request=launch {controller.maybeRequest(host)};host.preparing.await();request.cancelAndJoin();host.prepareGate!!.complete(Unit)
            assertNull(store.value.requestedAt);assertEquals(0,host.launches.get())
        } finally {controller.close()}
    }
    @Test fun claimWriteFailureCannotLaunchAndQuarantinesFurtherAttempts()=runBlocking {
        val store=Store(twoDays);val errors=AtomicInteger();val controller=controller(store,unavailable={errors.incrementAndGet()});val host=Host()
        try {
            controller.succeedAndWait();store.fails=true
            controller.maybeRequest(host);assertEquals(0,host.launches.get());assertNull(store.value.requestedAt)
            store.fails=false;controller.maybeRequest(host);assertEquals(1,host.prepares.get());assertEquals(1,errors.get())
        } finally {controller.close()}
    }
    @Test fun providerFailureRemainsRetryableWithinTheSuccessWindow()=runBlocking {
        val store=Store(twoDays);val controller=controller(store);val host=Host().apply {error=true}
        try {
            controller.succeedAndWait()
            controller.maybeRequest(host);assertNull(store.value.requestedAt)
            host.error=false;controller.maybeRequest(host);assertNotNull(store.value.requestedAt);assertEquals(1,host.launches.get())
        } finally {controller.close()}
    }
    @Test fun exportSourcesAreClassifiedAndBackupsNeverQualify() {
        assertEquals(ReviewSuccess.backup,reviewSourceOf(listOf("Whereday Backup.json")))
        assertEquals(ReviewSuccess.backup,reviewSourceOf(listOf("Whereday Backup 1 of 2.json","Whereday Backup 2 of 2.json")))
        assertEquals(ReviewSuccess.countryDaysExport,reviewSourceOf(listOf("iso-me-country-days.csv")))
        assertEquals(ReviewSuccess.fileExport,reviewSourceOf(listOf("2026/whereday-visits.json")))
        assertEquals(ReviewSuccess.fileExport,reviewSourceOf(emptyList()))
        assertFalse(ReviewSuccess.backup.qualifies)
    }
}
