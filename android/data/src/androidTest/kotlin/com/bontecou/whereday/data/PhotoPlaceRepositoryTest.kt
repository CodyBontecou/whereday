package com.bontecou.whereday.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PhotoPlaceRepositoryTest {
    private lateinit var database:HistoryDatabase
    private lateinit var databaseName:String
    private lateinit var root:File
    private lateinit var repository:PhotoPlaceRepository
    private lateinit var photos:PhotoRepository
    private val range=HistoryRange(Instant.ofEpochMilli(-1),Instant.ofEpochMilli(10000000))
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        databaseName="photo-place-owner-${java.util.UUID.randomUUID()}.db"
        database=HistoryDatabase.create(context,databaseName);photos=PhotoRepository(database)
        root=Files.createTempDirectory(context.cacheDir.toPath(),"photo-owner-tests-").toFile()
        repository=PhotoPlaceRepository(root,photos)
    }
    @After fun cleanup() {runBlocking {
        repository.clearCache()
        database.close();InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
        root.deleteRecursively()
    }}
    private suspend fun seed(count:Int=1) {
        database.writeTransaction {
            repeat(count) {i->
                val uri="photo-${i.toString().padStart(5,'0')}";val time=Instant.ofEpochMilli(i.toLong());val coordinate=Coordinate(18.0,-66.0)
                database.history().put(PhotoAssetRow(uri,time,coordinate,true,true,Instant.EPOCH,null))
                database.history().put(PhotoRow(uri,time,coordinate,PhotoCoordinateSource.photoGPS.wireValue,Instant.EPOCH))
            }
        }
    }
    private suspend fun Channel<PhotoPlaceDirectoryState>.ready():PhotoPlaceDirectory=withTimeout(10000) {
        while(true) {val state=receive();if(state is PhotoPlaceDirectoryState.Ready) return@withTimeout state.directory}
        @Suppress("UNREACHABLE_CODE") error("No directory")
    }
    @Test fun firstPreparationReapsAbandonedCachesAndLaterPreparationsPreserveLiveDirectories()=runBlocking {
        val abandoned=File(root,"places-abandoned").apply {mkdir()};File(abandoned,"metadata").writeText("synthetic previous process")
        seed()
        val first=repository.prepare(range)
        assertFalse(abandoned.exists());assertEquals(1,root.listFiles()!!.size)
        val second=repository.prepare(range)
        assertEquals(2,root.listFiles()!!.size)
        first.close();first.close()
        assertFalse(first.active.value);assertTrue(second.active.value)
        assertEquals(PhotoPlaceCounts(1,1,1),second.counts())
        assertEquals(1,root.listFiles()!!.size)
        try {first.places();fail("Closed directory must not remain readable")} catch(_:IllegalStateException) {}
        repository.clearCache()
        assertFalse(second.active.value);assertTrue(root.listFiles()!!.isEmpty())
        assertEquals(1L,photos.window(range).count)
    }
    @Test fun clearCancelsPreparationClosesPublishedDirectoriesAndExcludesNewWorkThroughHistoryDeletion()=runBlocking {
        seed(1001)
        val active=repository.prepare(range)
        val entered=CountDownLatch(1);val proceed=CountDownLatch(1)
        val pending=async(Dispatchers.IO) {repository.prepare(range,progress={count->if(count==500L) {entered.countDown();check(proceed.await(10,TimeUnit.SECONDS))}})}
        try {
            withContext(Dispatchers.IO) {check(entered.await(10,TimeUnit.SECONDS))}
            val clear=async(Dispatchers.IO) {repository.clearCache {
                assertFalse(active.active.value);assertTrue(root.listFiles()!!.isEmpty())
                try {repository.prepare(range);fail("Clear must exclude snapshots until deletion completes")} catch(_:IllegalStateException) {}
                withContext(Dispatchers.IO) {database.clearAllTables()}
            }}
            withTimeout(5000) {repository.clearing.first {it}}
            try {repository.prepare(range);fail("Clear must exclude concurrent preparation")} catch(_:IllegalStateException) {}
            proceed.countDown();clear.await()
            pending.join();assertTrue(pending.isCancelled)
            assertFalse(repository.clearing.value);assertTrue(root.listFiles()!!.isEmpty())
            assertEquals(0L,photos.window(range).count)
            seed()
            val fresh=repository.prepare(range)
            assertEquals(1L,fresh.counts().photos);fresh.close()
        } finally {proceed.countDown();pending.cancelAndJoin()}
    }
    @Test fun observationReplacesChangedMetadataAndClosesBeforeAccessOrForegroundEligibilityIsRemoved()=runBlocking {
        seed(2)
        val available=MutableStateFlow(true)
        val events=Channel<PhotoPlaceDirectoryState>(Channel.UNLIMITED)
        val collector=launch {repository.observe(range,available).collect {events.send(it)}}
        try {
            val first=events.ready();assertEquals(2L,first.counts().photos)
            photos.unavailable("photo-00001")
            val second=events.ready();assertFalse(first.active.value);assertEquals(1L,second.counts().photos)
            assertEquals(1,root.listFiles()!!.size)
            available.value=false
            withTimeout(5000) {while(events.receive()!=PhotoPlaceDirectoryState.Unavailable) {}}
            assertFalse(second.active.value);assertTrue(root.listFiles()!!.isEmpty())
            available.value=true
            val resumed=events.ready();assertEquals(1L,resumed.counts().photos)
            collector.cancelAndJoin()
            assertFalse(resumed.active.value);assertTrue(root.listFiles()!!.isEmpty())
        } finally {collector.cancelAndJoin();events.close()}
    }
    @Test fun observationCannotRetainReadableReadyAfterClearAndRecoversWithEmptyHistory()=runBlocking {
        seed()
        val events=Channel<PhotoPlaceDirectoryState>(Channel.UNLIMITED)
        val collector=launch {repository.observe(range,flowOf(true)).collect {events.send(it)}}
        try {
            val old=events.ready()
            repository.clearCache {withContext(Dispatchers.IO) {database.clearAllTables()}}
            assertFalse(old.active.value)
            var fresh=events.ready()
            // A buffered Ready may already have been closed by the clear transition.
            while(!fresh.active.value) fresh=events.ready()
            assertEquals(0L,fresh.counts().photos)
            collector.cancelAndJoin()
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {collector.cancelAndJoin();events.close()}
    }
    @Test fun completedRepositoryResultRejectedAtDispatcherHandoffReleasesItsRegistrationAndFiles()=runBlocking {
        seed()
        val queue=LinkedBlockingQueue<Runnable>()
        val dispatcher=object:CoroutineDispatcher() {
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) {queue.add(block)}
        }
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val pending=scope.async {repository.prepare(range)}
        try {
            checkNotNull(queue.poll(10,TimeUnit.SECONDS)).run()
            val handoff=withContext(Dispatchers.IO) {checkNotNull(queue.poll(20,TimeUnit.SECONDS))}
            assertEquals(1,root.listFiles()!!.size)
            pending.cancel();handoff.run()
            withContext(Dispatchers.IO) {while(!pending.isCompleted) {queue.poll(10,TimeUnit.SECONDS)?.run() ?: check(pending.isCompleted)}}
            assertTrue(pending.isCancelled);assertTrue(root.listFiles()!!.isEmpty())
            repository.clearCache()
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {scope.cancel()}
    }
    @Test fun perPlacePagingOpensUnsampledRankAndInvalidatesOnDirectoryClose()=runBlocking {
        seed(2501)
        val directory=repository.prepare(range)
        val place=directory.places().single()
        assertEquals(2490,directory.photoRank(place.id,"photo-02490"))
        assertNull(directory.photoRank(place.id,"missing"))
        val source=directory.photoPagingSource(place.id)
        val result=source.load(androidx.paging.PagingSource.LoadParams.Refresh(2490,50,true)) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(2490,result.itemsBefore);assertEquals(0,result.itemsAfter)
        assertEquals(11,result.data.size);assertEquals("photo-02490",result.data.first().photo.uri)
        assertEquals("photo-02500",result.data.last().photo.uri)
        assertNull(result.nextKey);assertEquals(2440,result.prevKey)
        val previous=source.load(androidx.paging.PagingSource.LoadParams.Prepend(2440,50,true)) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals("photo-02440",previous.data.first().photo.uri)
        assertEquals("photo-02489",previous.data.last().photo.uri)
        directory.close()
        assertTrue(source.invalid)
        assertTrue(directory.photoPagingSource(place.id).invalid)
    }
    @Test fun exactPlaceLookupAndMultipleMarkerFiltersRetainOnlyTheirMembers()=runBlocking {
        seed(3)
        database.history().put(PhotoRow("photo-00001",Instant.ofEpochMilli(1),Coordinate(48.0,2.0),"photo_gps",Instant.EPOCH))
        database.history().put(PhotoRow("photo-00002",Instant.ofEpochMilli(2),Coordinate(-48.0,-2.0),"photo_gps",Instant.EPOCH))
        val directory=repository.prepare(range)
        val markers=directory.markers();assertEquals(3,markers.size)
        val wanted=markers.take(2)
        val page=directory.places(limit=1,areas=wanted.map {it.id}.toSet())
        assertEquals(wanted.first().anchor,page.single().id)
        val next=directory.places(after=page.single().id,areas=wanted.map {it.id}.toSet())
        assertEquals(listOf(wanted[1].anchor),next.map {it.id})
        assertTrue(directory.places(areas=emptySet()).isEmpty())
        assertEquals(page.single(),directory.place(page.single().id))
        assertNull(directory.place(Long.MAX_VALUE))
        assertEquals("photo-00000",markers.first().representativeUri)
        directory.close()
    }
    @Test fun countObservationFollowsAvailabilityAndRemovalWithoutDisplayWindowMaterialization()=runBlocking {
        seed(3)
        val values=Channel<Long>(Channel.UNLIMITED)
        val collector=launch {photos.observeCount(range).collect {values.send(it)}}
        try {
            assertEquals(3L,withTimeout(5000) {values.receive()})
            photos.unavailable("photo-00001")
            assertEquals(2L,withTimeout(5000) {values.receive()})
            photos.remove("photo-00002")
            assertEquals(1L,withTimeout(5000) {values.receive()})
        } finally {collector.cancelAndJoin();values.close()}
    }
    @Test fun deletionCallbackFailureReleasesClearGateAndLeavesFuturePreparationUsable()=runBlocking {
        seed()
        val active=repository.prepare(range)
        try {repository.clearCache {error("Synthetic history deletion failure")};fail()} catch(error:IllegalStateException) {assertEquals("Synthetic history deletion failure",error.message)}
        assertFalse(active.active.value);assertFalse(repository.clearing.value);assertTrue(root.listFiles()!!.isEmpty())
        val next=repository.prepare(range)
        assertEquals(1L,next.counts().photos);next.close()
    }
}
