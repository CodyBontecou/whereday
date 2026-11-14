package com.bontecou.whereday.platform

import com.bontecou.whereday.data.AppSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

class SettingsOwnershipTest {
    @Test fun duplicateClearRequestsShareOneApplicationOwnedJobAndSurviveCallerCancellation()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val controller=JournalClearController({calls.incrementAndGet();entered.complete(Unit);release.await()})
        try {
            var submitted:Job?=null
            val screen=launch {submitted=controller.clear();awaitCancellation()}
            withTimeout(5000) {entered.await()}
            assertEquals(JournalClearPhase.clearing,controller.state.value.phase)
            assertSame(submitted,controller.clear());screen.cancelAndJoin();assertTrue(controller.state.value.busy)
            release.complete(Unit);withTimeout(5000) {submitted!!.join()}
            assertEquals(1,calls.get());assertEquals(JournalClearState(JournalClearPhase.complete,1),controller.state.value)
        } finally {release.complete(Unit);controller.close()}
    }
    @Test fun completionObserverCanStartANewClearWithoutThePreviousJobDroppingItsSingleFlightGuard()=runBlocking {
        val release=CompletableDeferred<Unit>();val secondEntered=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val controller=JournalClearController({if(calls.incrementAndGet()==2) {secondEntered.complete(Unit);release.await()}})
        var second:Job?=null
        val observer=launch(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) {controller.state.collect {if(it.phase==JournalClearPhase.complete && it.attempt==1L) second=controller.clear()}}
        try {
            controller.clear().join();withTimeout(5000) {secondEntered.await()}
            assertSame(second,controller.clear());assertEquals(2,calls.get())
            controller.dismiss(1);assertTrue(controller.state.value.busy)
            release.complete(Unit);withTimeout(5000) {second!!.join()}
            controller.dismiss(1);assertEquals(JournalClearPhase.complete,controller.state.value.phase)
            controller.dismiss(2);assertEquals(JournalClearState(attempt=2),controller.state.value)
        } finally {release.complete(Unit);observer.cancelAndJoin();controller.close()}
    }
    @Test fun failedClearHasNoExceptionTextAndCanBeRetriedExplicitly()=runBlocking {
        val calls=AtomicInteger();val failures=AtomicInteger()
        val controller=JournalClearController({if(calls.incrementAndGet()==1) error("synthetic-private-location")},{failures.incrementAndGet()})
        try {
            controller.clear().join();assertEquals(JournalClearPhase.failed,controller.state.value.phase)
            assertFalse(controller.state.value.toString().contains("synthetic-private-location"));assertEquals(1,failures.get())
            controller.clear().join();assertEquals(JournalClearState(JournalClearPhase.complete,2),controller.state.value);assertEquals(2,calls.get())
        } finally {controller.close()}
    }
    @Test fun preferenceAndFolderCommandsRunInSubmissionOrderAgainstLatestValues()=runBlocking {
        var saved=AppSettings();val order=mutableListOf<String>();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val controller=SettingsActionController({change->if(order.isEmpty()) {entered.complete(Unit);release.await()};saved=change(saved);order.add("preference")},{uri->saved=saved.copy(exportFolder=uri);order.add("folder")})
        try {
            val first=controller.update {it.copy(distanceFilter=100)};withTimeout(5000) {entered.await()}
            val second=controller.update {it.copy(metric=false)};val folder=controller.folder("content://synthetic/tree/qa")
            assertEquals(3,controller.state.value.pending);release.complete(Unit)
            withTimeout(5000) {joinAll(first,second,folder)}
            assertEquals(listOf("preference","preference","folder"),order)
            assertEquals(100,saved.distanceFilter);assertFalse(saved.metric);assertEquals("content://synthetic/tree/qa",saved.exportFolder)
            assertEquals(SettingsActionState(folderSaved=true),controller.state.value)
        } finally {release.complete(Unit);controller.close()}
    }
    @Test fun failingCommandsKeepCommittedPreferencesAndExposeOnlyTypedFeedback()=runBlocking {
        var saved=AppSettings();var fail=true
        val controller=SettingsActionController({change->if(fail) error("synthetic-private-settings");saved=change(saved)},{error("content://synthetic-private-token/tree/qa")})
        try {
            controller.update {it.copy(metric=false)}.join();assertTrue(saved.metric)
            assertEquals(SettingsFailure.preferences,controller.state.value.failure);assertFalse(controller.state.value.toString().contains("synthetic-private-settings"))
            fail=false;controller.update {it.copy(metric=false)}.join();assertFalse(saved.metric);assertNull(controller.state.value.failure)
            controller.folder("content://synthetic/tree/qa").join();assertNull(saved.exportFolder)
            assertEquals(SettingsFailure.folder,controller.state.value.failure);assertFalse(controller.state.value.toString().contains("synthetic-private-token"))
            controller.dismiss();assertNull(controller.state.value.failure)
        } finally {controller.close()}
    }
    @Test fun settingsWriteSurvivesTheRequestingScreenAndDoesNotRunOnItsCallerThread()=runBlocking {
        val caller=Thread.currentThread();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var saved=AppSettings();var sameThread=true
        val controller=SettingsActionController({change->sameThread=Thread.currentThread()===caller;entered.complete(Unit);release.await();saved=change(saved)},{})
        try {
            var operation:Job?=null
            val screen=launch {operation=controller.update {it.copy(stopAfterHours=12)};awaitCancellation()}
            withTimeout(5000) {entered.await()};screen.cancelAndJoin();assertTrue(controller.state.value.busy)
            release.complete(Unit);withTimeout(5000) {operation!!.join()}
            assertFalse(sameThread);assertEquals(12,saved.stopAfterHours);assertFalse(controller.state.value.busy)
        } finally {release.complete(Unit);controller.close()}
    }
}
