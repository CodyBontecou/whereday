package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class JournalClearRecoveryTest {
    private class Store(var receipt:JournalClearReceipt?=null):JournalClearReceiptStore {
        var failRead=false;var failBegin=false;var failComplete=false
        override fun read():JournalClearReceipt? {if(failRead) error("synthetic-private-state");return receipt}
        override fun begin(id:String) {if(failBegin) error("synthetic-private-write");receipt=JournalClearReceipt(id,false)}
        override fun complete(id:String) {if(failComplete) error("synthetic-private-completion");check(receipt?.id==id);receipt=JournalClearReceipt(id,true)}
    }
    @Test fun missingAndCompletedReceiptsPermitStartupWithoutDeletingAgain()=runBlocking {
        for(receipt in listOf(null,JournalClearReceipt(UUID.randomUUID().toString(),true))) {
            var calls=0;val controller=JournalClearController(Store(receipt),{calls++})
            try {withTimeout(5000) {controller.awaitReady()};assertEquals(0,calls);assertFalse(controller.state.value.blocked)} finally {controller.close()}
        }
    }
    @Test fun pendingReceiptIsResumedBeforeAdmissionAndCompletionIsRecordedLast()=runBlocking {
        val id=UUID.randomUUID().toString();val store=Store(JournalClearReceipt(id,false))
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val controller=JournalClearController(store,{assertFalse(store.receipt!!.complete);entered.complete(Unit);release.await()})
        try {
            withTimeout(5000) {entered.await()};assertTrue(controller.state.value.blocked)
            val reader=async {controller.awaitReady()};yield();assertFalse(reader.isCompleted)
            assertSame(controller.clear(),controller.clear())
            release.complete(Unit);withTimeout(5000) {reader.await()}
            assertEquals(JournalClearReceipt(id,true),store.receipt)
            assertEquals(JournalClearPhase.complete,controller.state.value.phase)
        } finally {release.complete(Unit);controller.close()}
    }
    @Test fun failedCleanupKeepsTheReceiptAndBlocksAdmissionUntilAnExplicitRetry()=runBlocking {
        val store=Store();val calls=AtomicInteger()
        val controller=JournalClearController(store,{if(calls.incrementAndGet()==1) error("synthetic-private-cleanup")})
        try {
            controller.awaitReady();controller.clear().join()
            assertFalse(store.receipt!!.complete);val id=store.receipt!!.id
            assertTrue(controller.state.value.blocked);controller.dismiss(controller.state.value.attempt);assertTrue(controller.state.value.blocked)
            try {controller.awaitReady();fail("Failed deletion admitted work")} catch(_:JournalClearPending) {}
            assertFalse(controller.state.value.toString().contains("synthetic-private-cleanup"))
            controller.clear().join();controller.awaitReady();assertEquals(JournalClearReceipt(id,true),store.receipt);assertEquals(2,calls.get())
        } finally {controller.close()}
    }
    @Test fun unreadableReceiptNeverDeletesAutomaticallyAndRequiresFreshApproval()=runBlocking {
        val store=Store().apply {failRead=true};var calls=0
        val controller=JournalClearController(store,{calls++})
        try {
            withTimeout(5000) {controller.state.first {!it.busy}}
            assertTrue(controller.state.value.blocked);assertTrue(controller.state.value.needsApproval);assertEquals(0,calls)
            assertFalse(controller.state.value.toString().contains("synthetic-private-state"))
            store.failRead=false;controller.clear().join();controller.awaitReady();assertEquals(1,calls);assertTrue(store.receipt!!.complete)
        } finally {controller.close()}
    }
    @Test fun beginAndCompletionWriteFailuresNeverPublishFalseSuccess()=runBlocking {
        val store=Store();var calls=0
        val controller=JournalClearController(store,{calls++})
        try {
            controller.awaitReady();store.failBegin=true;controller.clear().join();assertEquals(0,calls);assertTrue(controller.state.value.blocked)
            store.failBegin=false;store.failComplete=true;controller.clear().join();assertEquals(1,calls);assertTrue(controller.state.value.blocked);assertFalse(store.receipt!!.complete)
            store.failComplete=false;controller.clear().join();controller.awaitReady();assertEquals(2,calls);assertTrue(store.receipt!!.complete)
        } finally {controller.close()}
    }
    @Test fun interruptedOwnerLeavesPendingConsentForANewController()=runBlocking {
        val store=Store();val entered=CompletableDeferred<Unit>()
        val first=JournalClearController(store,{entered.complete(Unit);awaitCancellation()})
        first.awaitReady();first.clear();withTimeout(5000) {entered.await()};first.close()
        assertFalse(store.receipt!!.complete);val id=store.receipt!!.id
        var calls=0;val second=JournalClearController(store,{calls++})
        try {withTimeout(5000) {second.awaitReady()};assertEquals(1,calls);assertEquals(JournalClearReceipt(id,true),store.receipt)} finally {second.close()}
    }    @Test fun generationTracksTheReceiptAcrossDismissAndACompletedStartup()=runBlocking {
        val store=Store();val controller=JournalClearController(store,{})
        try {
            controller.awaitReady();assertNull(controller.state.value.generation)
            controller.clear().join();val id=store.receipt!!.id
            assertEquals(id,controller.state.value.generation)
            controller.dismiss(controller.state.value.attempt);assertEquals(id,controller.state.value.generation)
            val restored=JournalClearController(store,{fail("Completed receipt replayed deletion")})
            try {restored.awaitReady();assertEquals(id,restored.state.value.generation)} finally {restored.close()}
        } finally {controller.close()}
    }

}
