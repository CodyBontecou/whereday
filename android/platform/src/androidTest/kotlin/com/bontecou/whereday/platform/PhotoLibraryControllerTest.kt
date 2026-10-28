package com.bontecou.whereday.platform

import androidx.room.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class PhotoLibraryControllerTest {
    private lateinit var db:HistoryDatabase
    private lateinit var photos:PhotoRepository
    private lateinit var staging:java.io.File
    private val held=mutableSetOf("content://synthetic/export-folder")
    private val now=Instant.parse("2026-10-27T14:00:00Z")
    private var epoch="original"
    private val revoked=mutableSetOf<String>()
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        db=HistoryDatabase.create(context,null);photos=PhotoRepository(db)
        staging=java.io.File(context.cacheDir,"photo-controller-${java.util.UUID.randomUUID()}")
    }
    @After fun close() {db.close();staging.deleteRecursively()}
    private fun asset(uri:String)=PhotoAssetMetadata(uri,now,Coordinate(48.8584,2.2945))
    private fun controller(reader:suspend(String)->PhotoAssetMetadata={uri->if(uri in revoked) throw SecurityException("Synthetic revocation") else asset(uri)},grant:(String)->Unit={held.add(it);Unit})=PhotoLibraryController(db,photos,reader,{held.toSet()},grant,{held.remove(it);Unit},stagingDirectory=staging,now={now},selectionEpoch={epoch},invalidateSelections={epoch="cleared"})
    @Test fun selectionRefreshRevocationReselectionAndRemovalKeepOtherGrantsAndHistory():Unit=runBlocking {
        val controller=controller();val uri="content://synthetic/photo"
        controller.addSelected(listOf(uri,uri),controller.captureSelection())
        assertEquals(1L,db.history().photoAssetCount());assertTrue(uri in held)
        revoked.add(uri);controller.refresh();assertTrue(controller.state.value.validated)
        assertFalse(db.history().photoAsset(uri)!!.available)
        assertEquals(0L,photos.window(HistoryRange.all).count)
        revoked.clear();controller.addSelected(listOf(uri),controller.captureSelection())
        assertEquals(1L,photos.window(HistoryRange.all).count)
        controller.remove(uri)
        assertNull(db.history().photoAsset(uri));assertFalse(uri in held)
        assertEquals(setOf("content://synthetic/export-folder"),held)
        assertEquals(0L,db.history().totalPointCount());assertEquals(0L,db.history().totalVisitCount())
    }
    @Test fun failedGrantOrMetadataDoesNotInventSavedReferences():Unit=runBlocking {
        val controller=controller(grant={throw SecurityException("Synthetic non-persistent provider")})
        controller.addSelected(listOf("content://synthetic/not-persistent"))
        assertEquals(0L,db.history().photoAssetCount());assertFalse(controller.state.value.running)
        assertTrue(controller.state.value.message!!.contains("1 unavailable"))
        val broken=PhotoLibraryController(db,photos,{asset(it)},{throw IllegalStateException("Synthetic grant inventory failure")},{},{},stagingDirectory=staging)
        broken.addSelected(listOf("content://synthetic/other"))
        assertFalse(broken.state.value.running);assertFalse(broken.state.value.validated);assertEquals(0L,db.history().photoAssetCount())
    }
    @Test fun cancellationReleasesNewUncommittedGrants():Unit=runBlocking {
        val reached=CompletableDeferred<Unit>()
        val controller=controller(reader={uri->if(uri.endsWith("waiting")) {reached.complete(Unit);awaitCancellation()} else asset(uri)})
        val job=async {controller.addSelected(listOf("content://synthetic/first","content://synthetic/waiting"))}
        try {
            withTimeout(5000) {reached.await()};withTimeout(5000) {job.cancelAndJoin()}
            assertEquals(0L,db.history().photoAssetCount());assertEquals(setOf("content://synthetic/export-folder"),held)
            assertFalse(controller.state.value.running)
        } finally {job.cancel()}
    }
    @Test fun clearCancelsWorkExcludesNewJobsAndInvalidatesLatePickerCallbacks():Unit=runBlocking {
        val reached=CompletableDeferred<Unit>();val clearing=CompletableDeferred<Unit>();val releaseClear=CompletableDeferred<Unit>()
        val controller=controller(reader={uri->if(uri.endsWith("waiting")) {reached.complete(Unit);awaitCancellation()} else asset(uri)})
        val token=controller.captureSelection();controller.addSelected(listOf("content://synthetic/saved"),token)
        val sync=async {controller.addSelected(listOf("content://synthetic/waiting"),token)}
        val clear=async {
            reached.await();controller.clearAll {withContext(Dispatchers.IO) {db.clearAllTables()};clearing.complete(Unit);releaseClear.await()}
        }
        try {
            withTimeout(5000) {clearing.await()}
            try {controller.addSelected(listOf("content://synthetic/forbidden"));fail()} catch(_:IllegalStateException) {}
            releaseClear.complete(Unit);withTimeout(5000) {clear.await();sync.join()}
            assertTrue(sync.isCancelled);assertEquals(setOf("content://synthetic/export-folder"),held)
            try {controller.addSelected(listOf("content://synthetic/late"),token);fail()} catch(_:IllegalStateException) {}
            controller.addSelected(listOf("content://synthetic/new"),controller.captureSelection())
            assertEquals(1L,db.history().photoAssetCount())
        } finally {releaseClear.complete(Unit);clear.cancel();sync.cancel()}
    }
    @Test fun failedBatchReleasesOnlyNewGrantsAndPostCommitClearFailureStillReleasesOwnedOnes():Unit=runBlocking {
        db.useWriterConnection {connection->connection.usePrepared("CREATE TRIGGER photo_failure BEFORE INSERT ON photo_moments BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") {it.step()}}
        val controller=controller();controller.addSelected(listOf("content://synthetic/fail"))
        assertEquals(0L,db.history().photoAssetCount());assertEquals(setOf("content://synthetic/export-folder"),held)
        db.useWriterConnection {connection->connection.usePrepared("DROP TRIGGER photo_failure") {it.step()}}
        controller.addSelected(listOf("content://synthetic/saved"))
        try {controller.clearAll {withContext(Dispatchers.IO) {db.clearAllTables()};error("Synthetic failure after deletion")};fail()} catch(_:IllegalStateException) {}
        assertEquals(setOf("content://synthetic/export-folder"),held)
        controller.addSelected(listOf("content://synthetic/new"))
    }
    @Test fun imageJobsAreCanceledByClearAndUnknownTimeCursorsReachDatedAssets():Unit=runBlocking {
        val imageStarted=CompletableDeferred<Unit>()
        val controller=PhotoLibraryController(db,photos,{uri->asset(uri).copy(takenAt=if(uri.contains("unknown")) null else now)},{held.toSet()},{held.add(it);Unit},{held.remove(it);Unit},stagingDirectory=staging,decode={_,_->imageStarted.complete(Unit);awaitCancellation()})
        controller.addSelected((0..50).map {"content://synthetic/unknown-%03d".format(it)}+listOf("content://synthetic/dated-a","content://synthetic/dated-b"))
        val first=photos.observeLibrary().first();assertEquals(50,first.entries.size);assertTrue(first.entries.all {it.asset.capturedAt==null})
        val second=photos.observeLibrary(first.nextCursor!!).first();assertEquals(3,second.entries.size);assertFalse(second.hasMore)
        assertNull(second.entries.first().asset.capturedAt);assertEquals(now,second.entries.last().asset.capturedAt)
        val image=async {controller.image("content://synthetic/dated-a",128)}
        try {
            withTimeout(5000) {imageStarted.await()}
            withTimeout(5000) {controller.clearAll {withContext(Dispatchers.IO) {db.clearAllTables()}}}
            assertTrue(image.isCancelled);assertEquals(0L,db.history().photoAssetCount())
            assertEquals(setOf("content://synthetic/export-folder"),held)
        } finally {image.cancel()}
    }
    @Test fun galleryPagesRetainEveryReferenceAndTheEpochSurvivesPreferenceRecreation():Unit=runBlocking {
        val controller=controller(reader={uri->asset(uri).copy(takenAt=now.minusSeconds(uri.substringAfterLast('-').toLong()))});controller.addSelected((0..50).map {"content://synthetic/photo-%03d".format(it)})
        val first=photos.observeLibrary().first();assertEquals(51L,first.count);assertEquals(50,first.entries.size);assertTrue(first.hasMore)
        assertEquals("content://synthetic/photo-050",first.entries.first().asset.uri)
        val second=photos.observeLibrary(first.nextCursor!!).first();assertEquals(1,second.entries.size);assertFalse(second.hasMore)
        assertTrue(first.entries.none {it.asset.uri==second.entries.single().asset.uri})
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val preferences=AppPreferences(context);val original=preferences.settings.first()
        try {
            preferences.update {it.copy(photoEpoch="fixture-epoch")}
            val other=PhotoLibraryController(db,photos,{asset(it)},{held.toSet()},{held.add(it);Unit},{held.remove(it);Unit},stagingDirectory=staging,selectionEpoch={AppPreferences(context).settings.first().photoEpoch},invalidateSelections={preferences.update {it.copy(photoEpoch="cleared-epoch")}})
            val token=other.captureSelection();assertEquals("fixture-epoch",token)
            other.clearAll {withContext(Dispatchers.IO) {db.clearAllTables()}}
            assertEquals("cleared-epoch",AppPreferences(context).settings.first().photoEpoch)
            try {other.addSelected(listOf("content://synthetic/stale"),token);fail()} catch(_:IllegalStateException) {}
        } finally {preferences.update {original}}
    }
    @Test fun thousandsOfReferencesAreReadOnceAndRetainFullRangeOutlierPolicyAcrossBatches():Unit=runBlocking {
        fun uri(index:Int)="content://synthetic/large-%05d".format(index)
        db.writeTransaction {
            repeat(2001) {db.history().put(PhotoAssetRow(uri(it),null,null,true,true,now))}
            db.history().put(PointRow(TrackPoint(id="bad",coordinate=Coordinate(0.0,0.0),time=now,accuracy=5.0,outlier=true),null))
            db.history().put(PointRow(TrackPoint(id="good",coordinate=Coordinate(1.0,1.0),time=now.plusSeconds(2000),accuracy=5.0),null))
            db.history().put(VisitRow(Visit(id="visit",coordinate=Coordinate(2.0,2.0),arrivedAt=now.minusSeconds(60),departedAt=now.plusSeconds(60))))
        }
        var reads=0
        val controller=controller(reader={reference->reads++;PhotoAssetMetadata(reference,now.plusSeconds(reference.substringAfterLast('-').toLong()))})
        controller.refresh()
        assertTrue(controller.state.value.validated);assertEquals(2001,reads)
        assertEquals("inferred_visit",db.history().photoMoment(uri(0))!!.coordinateSource)
        assertEquals(Coordinate(2.0,2.0),db.history().photoMoment(uri(0))!!.coordinate)
        assertEquals(2001L,db.history().photoAssetCount());assertEquals(2L,db.history().totalPointCount());assertEquals(1L,db.history().totalVisitCount())
        assertTrue(staging.listFiles().orEmpty().isEmpty());assertEquals(setOf("content://synthetic/export-folder"),held)
    }
    @Test fun cancellationAtGrantReturnReleasesTheAcquiredUncommittedGrant():Unit=runBlocking {
        lateinit var job:Job
        val controller=controller(grant={held.add(it);job.cancel()})
        job=launch(start=CoroutineStart.LAZY) {controller.addSelected(listOf("content://synthetic/late-cancel"))}
        job.start();withTimeout(5000) {job.join()}
        assertTrue(job.isCancelled);assertEquals(0L,db.history().photoAssetCount())
        assertEquals(setOf("content://synthetic/export-folder"),held)
        assertTrue(staging.listFiles().orEmpty().isEmpty());assertFalse(controller.state.value.running)
    }
    @Test fun failedStagingDirectoryDoesNotReadAssetsOrRetainGrants():Unit=runBlocking {
        staging.parentFile!!.mkdirs();staging.writeText("Synthetic file blocks directory creation")
        var reads=0
        val controller=controller(reader={reads++;asset(it)})
        controller.addSelected(listOf("content://synthetic/blocked"))
        assertEquals(0,reads);assertFalse(controller.state.value.validated);assertFalse(controller.state.value.running)
        assertEquals(0L,db.history().photoAssetCount());assertEquals(setOf("content://synthetic/export-folder"),held)
    }
    @Test fun failedLaterBatchKeepsCompletedReferencesAndRemovesItsSpool():Unit=runBlocking {
        fun uri(index:Int)="content://synthetic/batch-%04d".format(index)
        db.writeTransaction {repeat(1001) {db.history().put(PhotoAssetRow(uri(it),null,null,true,false,now))}}
        db.useWriterConnection {connection->connection.usePrepared("CREATE TRIGGER late_photo_failure BEFORE INSERT ON photo_moments WHEN new.uri='content://synthetic/batch-0700' BEGIN SELECT RAISE(ABORT,'synthetic later batch failure'); END") {it.step()}}
        val controller=controller();controller.refresh()
        assertFalse(controller.state.value.validated);assertFalse(controller.state.value.running)
        assertEquals(500L,photos.window(HistoryRange.all).count)
        assertTrue(db.history().photoAsset(uri(0))!!.available);assertFalse(db.history().photoAsset(uri(700))!!.available)
        assertTrue(staging.listFiles().orEmpty().isEmpty());assertEquals(setOf("content://synthetic/export-folder"),held)
    }

    @Test fun libraryScanKeepsPickerGrantsSeparateAndRevalidatesUnavailableSavedReferences():Unit=runBlocking {
        val existing="content://synthetic/selected"
        controller().addSelected(listOf(existing));revoked.add(existing)
        var retained=0
        val scanner=PhotoLibraryController(db,photos,{uri->if(uri in revoked) throw SecurityException() else asset(uri)},{held.toSet()},{retained++;held.add(it);Unit},{held.remove(it);Unit},stagingDirectory=staging,enumerateLibrary={consume->repeat(5) {consume("content://synthetic/library-$it")}},now={now})
        scanner.syncLibrary()
        assertTrue(scanner.state.value.validated);assertEquals(0,retained)
        assertFalse(db.history().photoAsset(existing)!!.available)
        repeat(5) {assertFalse(db.history().photoAsset("content://synthetic/library-$it")!!.selected)}
        assertEquals(5L,photos.window(HistoryRange.all).count)
        assertEquals(setOf("content://synthetic/export-folder",existing),held)
        scanner.clearAll {withContext(Dispatchers.IO) {db.clearAllTables()}}
        assertEquals(setOf("content://synthetic/export-folder"),held)
    }
    @Test fun failedOrCanceledEnumerationNeverCommitsItsStagedNewReferences():Unit=runBlocking {
        val scanner=PhotoLibraryController(db,photos,{asset(it)},{held.toSet()},{fail("Enumeration must not acquire persistent grants")},{},stagingDirectory=staging,enumerateLibrary={consume->consume("content://synthetic/discovered");throw SecurityException("Synthetic scope change")})
        scanner.syncLibrary();assertFalse(scanner.state.value.validated)
        assertEquals(0L,db.history().photoAssetCount());assertTrue(staging.listFiles().orEmpty().isEmpty())
        val entered=CompletableDeferred<Unit>()
        val canceled=PhotoLibraryController(db,photos,{asset(it)},{held.toSet()},{},{},stagingDirectory=staging,enumerateLibrary={consume->consume("content://synthetic/interrupted");entered.complete(Unit);awaitCancellation()})
        val job=launch {canceled.syncLibrary()};entered.await();job.cancelAndJoin()
        assertEquals(0L,db.history().photoAssetCount());assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test fun automaticScanRechecksMetadataRangeAndUsesTheFullRequestedCorrelationWindow():Unit=runBlocking {
        val range=HistoryRange(now.minusSeconds(3600),now.plusSeconds(3600))
        db.history().put(PointRow(TrackPoint(id="bad",coordinate=Coordinate(0.0,0.0),time=now,accuracy=5.0,outlier=true),null))
        db.history().put(PointRow(TrackPoint(id="far-good",coordinate=Coordinate(1.0,1.0),time=now.plusSeconds(3000),accuracy=5.0),null))
        db.history().put(VisitRow(Visit(id="visit",coordinate=Coordinate(2.0,2.0),arrivedAt=now.minusSeconds(60),departedAt=now.plusSeconds(60))))
        val scanner=PhotoLibraryController(db,photos,{uri->PhotoAssetMetadata(uri,if(uri.endsWith("outside")) range.until else now)}, {held.toSet()},{fail("Automatic scan must not retain picker grants")},{},stagingDirectory=staging,enumerateRange={requested,consume->assertEquals(range,requested);consume("content://synthetic/inside");consume("content://synthetic/outside")},now={now})
        scanner.syncLibrary(range)
        assertTrue(scanner.state.value.validated);assertEquals(1L,db.history().photoAssetCount())
        assertNull(db.history().photoAsset("content://synthetic/outside"))
        assertEquals("inferred_visit",db.history().photoMoment("content://synthetic/inside")!!.coordinateSource)
    }

    @Test fun removingAnIdentityReleasesAllItsSelectedAliasesAndPreservesOtherGrants():Unit=runBlocking {
        val one="content://synthetic/one";val two="content://synthetic/two";val scan="content://synthetic/scan";val other="content://synthetic/other"
        val controller=controller(reader={uri->asset(uri).copy(identity=if(uri==other) null else "shared-identity")})
        controller.addSelected(listOf(one,two,other))
        photos.sync(listOf(asset(scan).copy(identity="shared-identity")),HistoryRange(now.minusSeconds(60),now.plusSeconds(60)),false)
        assertEquals(2L,db.history().photoLibraryCount());assertTrue(one in held);assertTrue(two in held)
        controller.remove(scan)
        assertEquals(setOf("content://synthetic/export-folder",other),held)
        assertEquals(1L,db.history().photoAssetCount());assertNotNull(db.history().photoAsset(other))
        assertNull(db.history().photoMoment(one));assertNull(db.history().photoMoment(two));assertNull(db.history().photoMoment(scan))
    }

}
