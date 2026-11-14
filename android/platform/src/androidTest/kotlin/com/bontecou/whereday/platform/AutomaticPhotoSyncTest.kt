package com.bontecou.whereday.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.HistoryRange
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.Closeable
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class AutomaticPhotoSyncTest {
    private val enabled=MutableStateFlow(false)
    @Volatile private var access=PhotoLibraryAccess.Full
    @Volatile private var gps=false
    @Volatile private var stamp:String?="v1"
    private val elapsed=AtomicLong(0)
    private val versionChecks=AtomicInteger()
    private val scans=AtomicInteger()
    private val watchers=AtomicInteger()
    private val callbacks=CopyOnWriteArrayList<()->Unit>()
    private var scan:suspend(HistoryRange)->Boolean={scans.incrementAndGet();true}
    private var save:suspend(Boolean)->Unit={enabled.value=it}
    private lateinit var controller:AutomaticPhotoSync
    private val range=HistoryRange(Instant.parse("2026-10-27T00:00:00Z"),Instant.parse("2026-10-28T00:00:00Z"))
    @Before fun setup() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName!="com.bontecou.whereday")
        controller=AutomaticPhotoSync(enabled,{save(it)},{access},{gps},{changed->watchers.incrementAndGet();callbacks+=changed;Closeable {watchers.decrementAndGet()}},{range},{scan(it)},version={versionChecks.incrementAndGet();stamp},elapsed={elapsed.get()},now={Instant.EPOCH.plusMillis(elapsed.get())},debounceMillis=20,dispatcher=Dispatchers.Default)
    }
    @After fun close()=runBlocking {controller.close();assertEquals(0,watchers.get())}
    private suspend fun settled(predicate:(AutomaticPhotoState)->Boolean)=withTimeout(5000) {controller.state.first(predicate)}
    private suspend fun complete(count:Int)=settled {scans.get()>=count && !it.running && it.lastSuccess!=null}
    @Test fun defaultOffAndNoAccessNeverObserveOrScan():Unit=runBlocking {
        controller.foreground();settled {it.loaded};assertEquals(0,watchers.get());assertEquals(0,scans.get());assertEquals(0,versionChecks.get())
        access=PhotoLibraryAccess.None
        try {controller.configure(true);fail()} catch(_:IllegalStateException) {}
        assertFalse(enabled.value);assertEquals(0,watchers.get());assertEquals(0,scans.get())
    }
    @Test fun fullResumeCooldownStillDetectsBackgroundLibraryAndPermissionChanges():Unit=runBlocking {
        controller.foreground();controller.configure(true);complete(1)
        controller.background();settled {!it.watching && !it.running}
        elapsed.set(100);controller.foreground();settled {it.watching && !it.running};assertEquals(1,scans.get())
        controller.background();settled {!it.watching};stamp="v2";controller.foreground();complete(2)
        controller.background();settled {!it.watching};gps=true;controller.foreground();complete(3)
        controller.background();settled {!it.watching};elapsed.set(600_101);controller.foreground();complete(4)
    }
    @Test fun partialResumeAlwaysRefreshesAndRevocationClosesObservation():Unit=runBlocking {
        access=PhotoLibraryAccess.Partial;controller.foreground();controller.configure(true);complete(1)
        controller.background();settled {!it.watching};controller.foreground();complete(2)
        controller.background();settled {!it.watching};access=PhotoLibraryAccess.None;controller.foreground()
        settled {it.message?.contains("Choose photo library access")==true}
        assertEquals(0,watchers.get());assertEquals(2,scans.get())
    }
    @Test fun changeBurstsDuringAScanBecomeOneFollowUpAndStaleCallbacksCannotResurrectDisabledWork():Unit=runBlocking {
        val first=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        scan={val n=scans.incrementAndGet();if(n==1) {first.complete(Unit);release.await()};true}
        controller.foreground();controller.configure(true);first.await()
        val oldCallback:()->Unit=callbacks[callbacks.size-1];repeat(100) {oldCallback.invoke()};delay(100);assertEquals(1,scans.get())
        release.complete(Unit);complete(2)
        controller.configure(false);oldCallback.invoke();delay(100)
        assertEquals(2,scans.get());assertEquals(0,watchers.get());assertFalse(enabled.value)
    }
    @Test fun backgroundAndClearCancelJoinWorkBeforeReturningAndRejectLateWakeups():Unit=runBlocking {
        val started=ChannelPair()
        scan={scans.incrementAndGet();started.entered.complete(Unit);try {awaitCancellation()} finally {started.cleaned.complete(Unit)}}
        controller.foreground();controller.configure(true);started.entered.await()
        controller.background();withTimeout(5000) {started.cleaned.await()};settled {!it.running && !it.watching}
        val next=ChannelPair();scan={scans.incrementAndGet();next.entered.complete(Unit);try {awaitCancellation()} finally {next.cleaned.complete(Unit)}}
        controller.foreground();next.entered.await();val oldCallback:()->Unit=callbacks[callbacks.size-1]
        controller.clearAll();assertTrue(next.cleaned.isCompleted);assertFalse(enabled.value);assertEquals(0,watchers.get())
        oldCallback.invoke();delay(100);assertEquals(2,scans.get())
    }
    @Test fun failuresRetryOnResumeAndDisablingSaveFailureStopsTheCurrentProcess():Unit=runBlocking {
        scan={scans.incrementAndGet();false}
        controller.foreground();controller.configure(true);settled {it.error!=null && !it.running}
        controller.background();settled {!it.watching};scan={scans.incrementAndGet();true};controller.foreground();complete(2)
        save={throw java.io.IOException("Synthetic storage failure")}
        try {controller.configure(false);fail()} catch(_:IllegalStateException) {}
        assertEquals(0,watchers.get());assertTrue(enabled.value)
        controller.refresh();delay(100);assertEquals(2,scans.get())
    }
    @Test fun rangeChangesAndExplicitRefreshBypassTheCooldown():Unit=runBlocking {
        controller.foreground();controller.configure(true);complete(1)
        controller.mapRange(range);complete(2)
        controller.refresh();complete(3)
    }
    @Test fun stopCurrentDiscardsOldWakesButFutureLibraryChangesCanSync():Unit=runBlocking {
        val started=CompletableDeferred<Unit>()
        scan={scans.incrementAndGet();started.complete(Unit);awaitCancellation()}
        controller.foreground();controller.configure(true);started.await()
        val oldCallback:()->Unit=callbacks[callbacks.size-1]
        oldCallback();controller.stopCurrent();settled {it.message?.contains("Photo sync stopped")==true}
        oldCallback();delay(100);assertEquals(1,scans.get());assertEquals(1,watchers.get())
        scan={scans.incrementAndGet();true};callbacks[callbacks.size-1].invoke();complete(2)
    }
    @Test fun unknownGenerationUsesAFreshResumeCheck():Unit=runBlocking {
        stamp=null;controller.foreground();controller.configure(true);complete(1)
        controller.background();settled {!it.watching};controller.foreground();complete(2)
    }
    @Test fun clearJoinsTheActualPhotoPipelineBeforeDeletingAndCannotRepopulateIt():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=com.bontecou.whereday.data.HistoryDatabase.create(context,null)
        val staging=java.io.File(context.cacheDir,"automatic-photo-"+java.util.UUID.randomUUID())
        try {
            val photos=com.bontecou.whereday.data.PhotoRepository(database)
            val entered=CompletableDeferred<Unit>()
            val library=PhotoLibraryController(database,photos,{uri->com.bontecou.whereday.domain.PhotoAssetMetadata(uri,range.from.plusSeconds(60),com.bontecou.whereday.domain.Coordinate(48.8584,2.2945))},{emptySet()},{},{},stagingDirectory=staging,enumerateRange={_,consume->consume("content://synthetic/automatic");entered.complete(Unit);awaitCancellation()})
            scan={scans.incrementAndGet();library.syncLibrary(it);library.state.value.validated}
            controller.foreground();controller.configure(true);entered.await()
            val oldCallback:()->Unit=callbacks[callbacks.size-1]
            controller.clearAll()
            library.clearAll {withContext(Dispatchers.IO) {database.clearAllTables()}}
            oldCallback();delay(100)
            assertEquals(0L,database.history().photoAssetCount());assertEquals(1,scans.get());assertFalse(enabled.value)
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        } finally {database.close();staging.deleteRecursively()}
    }

    private class ChannelPair {val entered=CompletableDeferred<Unit>();val cleaned=CompletableDeferred<Unit>()}
}
