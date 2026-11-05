package com.bontecou.whereday.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PhotoPlaceArchiveTest {
    private lateinit var database:HistoryDatabase
    private lateinit var databaseName:String
    private lateinit var root:File
    private lateinit var repository:PhotoRepository
    private val range=HistoryRange(Instant.ofEpochMilli(-1000),Instant.ofEpochMilli(10000000))
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        databaseName="photo-directory-${java.util.UUID.randomUUID()}.db"
        database=HistoryDatabase.create(context,databaseName);repository=PhotoRepository(database)
        root=Files.createTempDirectory(context.cacheDir.toPath(),"photo-archive-tests-").toFile()
    }
    @After fun cleanup() {database.close();InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName);root.deleteRecursively()}
    private suspend fun seed(photos:List<PhotoMoment>) {
        database.writeTransaction {
            photos.forEach {p->
                database.history().put(PhotoAssetRow(p.uri,p.takenAt,p.coordinate,true,true,Instant.EPOCH,null))
                database.history().put(PhotoRow(p.uri,p.takenAt,p.coordinate,p.source.wireValue,Instant.EPOCH))
            }
        }
    }
    private fun allPlaces(archive:PhotoPlaceArchive,area:String?=null):List<PhotoPlaceSummary> = buildList {
        var after=0L
        while(true) {val page=archive.places(after,17,area);if(page.isEmpty()) break;addAll(page);after=page.last().id}
    }
    private fun allPhotos(archive:PhotoPlaceArchive,id:Long):List<PhotoMoment> = buildList {
        var after=0L
        while(true) {val page=archive.photos(id,after,13);if(page.isEmpty()) break;addAll(page.map {it.photo});after=page.last().sequence}
    }
    @Test fun identityIsStableForEqualInputsAndChangesWhenReusedIdsDescribeDifferentPhotos()=runBlocking {
        val photo=PhotoMoment("content://synthetic/identity-a",Instant.EPOCH,Coordinate(18.0,-66.0),PhotoCoordinateSource.photoGPS)
        seed(listOf(photo))
        val first=PhotoPlaceArchive.prepare(root,repository,range)
        val same=PhotoPlaceArchive.prepare(root,repository,range)
        try {assertEquals(64,first.identity.length);assertEquals(first.identity,same.identity)} finally {same.close()}
        repository.remove(photo.uri);seed(listOf(photo.copy(uri="content://synthetic/identity-b")))
        val changed=PhotoPlaceArchive.prepare(root,repository,range)
        try {assertEquals(first.places().single().id,changed.places().single().id);assertNotEquals(first.identity,changed.identity)} finally {first.close();changed.close()}
    }
    @Test fun boundedPreviousCursorsAndRestoredPageValidationRespectAreaAndCollectionScopes()=runBlocking {
        val photos=(0 until 125).map {i->PhotoMoment("content://synthetic/cursor-$i",Instant.ofEpochMilli(i.toLong()),Coordinate(18.0,-66.0+i*.01),PhotoCoordinateSource.photoGPS)}
        seed(photos)
        val places=PhotoPlaceArchive.prepare(root,repository,range)
        try {
            val a=places.places(0,50).last().id;val b=places.places(a,50).last().id
            assertEquals(0L,places.previousPlaceCursor(a));assertEquals(a,places.previousPlaceCursor(b))
            assertTrue(places.validPlacePage(2,b));assertFalse(places.validPlacePage(1,b))
            val areas=places.markers().take(75).map {it.id}.toSet()
            val boundary=places.places(0,50,areas=areas).last().id
            assertTrue(places.validPlacePage(1,boundary,areas));assertEquals(0L,places.previousPlaceCursor(boundary,areas))
        } finally {places.close()}
        withContext(Dispatchers.IO) {database.clearAllTables()};seed(photos.map {it.copy(coordinate=Coordinate(18.0,-66.0))})
        val collection=PhotoPlaceArchive.prepare(root,repository,range)
        try {
            val id=collection.places().single().id
            val a=collection.photos(id,0,50).last().sequence;val b=collection.photos(id,a,50).last().sequence
            assertEquals(0L,collection.previousPhotoCursor(id,a));assertEquals(a,collection.previousPhotoCursor(id,b))
            assertTrue(collection.validPhotoPage(id,2,b));assertFalse(collection.validPhotoPage(id,1,b))
        } finally {collection.close()}
    }
    @Test fun completeDiskDirectoryMatchesAllActualSwiftPlaceAndAreaReferences()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().context
        val fixtures=JsonParser.parseString(context.assets.open("ios-photo-clusters.json").bufferedReader().use {it.readText()}).asJsonObject.getAsJsonArray("cases")
        for(value in fixtures) {
            withContext(Dispatchers.IO) {database.clearAllTables()}
            val c=value.asJsonObject;val name=c["name"].asString
            val photos=c.getAsJsonArray("photos").map {item->val p=item.asJsonObject;PhotoMoment(p["uri"].asString,Instant.ofEpochSecond(p["time"].asLong),Coordinate(p["lat"].asDouble,p["lon"].asDouble),PhotoCoordinateSource.photoGPS)}
            seed(photos)
            PhotoPlaceArchive.prepare(root,repository,range,c["maximum"].asInt,c["threshold"].asDouble).use {archive->
                val places=allPlaces(archive)
                assertEquals(name,photos.size.toLong(),archive.photoCount)
                assertEquals(name,c.getAsJsonArray("clusters").size().toLong(),archive.placeCount)
                c.getAsJsonArray("clusters").forEachIndexed {index,item->
                    val e=item.asJsonObject;val actual=places[index]
                    assertEquals(name,e.getAsJsonArray("photos").map {it.asString},allPhotos(archive,actual.id).map {it.uri})
                    assertEquals(e["lat"].asDouble,actual.coordinate.latitude,1e-9);assertEquals(e["lon"].asDouble,actual.coordinate.longitude,1e-9)
                    assertEquals(actual.photoCount,allPhotos(archive,actual.id).size.toLong())
                }
                val markers=archive.markers()
                val expected=c.getAsJsonArray("annotations")
                assertEquals(name,expected.size(),markers.size)
                expected.forEachIndexed {index,item->
                    val e=item.asJsonObject;val marker=markers[index]
                    val members=allPlaces(archive,marker.id)
                    assertEquals(e["lat"].asDouble,marker.coordinate.latitude,1e-9);assertEquals(e["lon"].asDouble,marker.coordinate.longitude,1e-9)
                    val expectedMembers=if(e["isArea"].asBoolean) e.getAsJsonArray("places").map {it.asJsonArray.map {uri->uri.asString}} else listOf(e.getAsJsonArray("photos").map {it.asString})
                    assertEquals(name,expectedMembers,members.map {allPhotos(archive,it.id).map {p->p.uri}})
                    assertEquals(e.getAsJsonArray("photos").size().toLong(),marker.photoCount)
                    assertEquals(e["representative"].asString,marker.representativeUri)
                    assertEquals(members.size.toLong(),marker.placeCount)
                    assertEquals(members.first().id,marker.anchor)
                    assertEquals(members.first().coordinate,marker.coordinate)
                }
            }
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }
    @Test fun equalTimePageBoundariesAndLargePlaceRetainEveryMomentBeyondMapSample()=runBlocking {
        val photos=(0..<2501).map {i->PhotoMoment("content://synthetic/${i.toString().padStart(6,'0')}",Instant.EPOCH,Coordinate(18.0,-66.0),PhotoCoordinateSource.photoGPS)}
        seed(photos)
        assertEquals(500,repository.window(range).photos.size)
        val progress=mutableListOf<Long>()
        val archive=PhotoPlaceArchive.prepare(root,repository,range,progress={progress.add(it)})
        assertEquals(listOf(500L,1000L,1500L,2000L,2500L,2501L),progress)
        assertEquals(1L,archive.placeCount)
        val place=allPlaces(archive).single()
        assertEquals(2501L,place.photoCount)
        assertEquals(photos,allPhotos(archive,place.id))
        assertEquals(photos.last().uri,place.representativeUri)
        assertEquals(2501L,archive.markers().single().photoCount)
        archive.close();archive.close()
        assertTrue(root.listFiles()!!.isEmpty())
        try {archive.places();fail("Closed metadata must not remain readable")} catch(_:IllegalStateException) {}
        assertEquals(2501L,repository.window(range).count)
    }
    @Test fun completeDistinctPlaceDirectoryCoarsensWithoutDroppingOffscreenPlaces()=runBlocking {
        val photos=(0..<12001).map {i->PhotoMoment("place-${i.toString().padStart(6,'0')}",Instant.ofEpochMilli(i.toLong()),Coordinate(-70.0+(i/200)*1.4,-179.0+(i%200)*1.79),PhotoCoordinateSource.photoGPS)}
        seed(photos)
        PhotoPlaceArchive.prepare(root,repository,range).use {archive->
            assertEquals(12001L,archive.placeCount);assertEquals(12001L,archive.photoCount)
            val markers=archive.markers()
            assertTrue(markers.size<=500)
            assertTrue(markers.all {it.isArea})
            assertEquals(12001L,markers.sumOf {it.placeCount})
            assertEquals(12001L,markers.sumOf {it.photoCount})
            val places=allPlaces(archive)
            assertEquals(photos.map {it.uri},places.map {it.representativeUri})
            assertEquals(places.map {it.id}.toSet(),markers.flatMap {allPlaces(archive,it.id)}.map {it.id}.toSet())
            markers.forEach {marker->
                val anchor=places.first {it.id==marker.anchor}
                assertEquals(anchor.coordinate,marker.coordinate)
            }
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }
    @Test fun interruptedPreparationDeletesCommittedPartialCacheAndKeepsOtherLiveArchives()=runBlocking {
        seed((0..<1001).map {i->PhotoMoment("photo-$i",Instant.ofEpochMilli(i.toLong()),Coordinate(0.0,i*.01),PhotoCoordinateSource.photoGPS)})
        val live=PhotoPlaceArchive.prepare(root,repository,range)
        val before=root.listFiles()!!.map {it.name}.toSet()
        try {
            PhotoPlaceArchive.prepare(root,repository,range,progress={throw CancellationException("Synthetic cancellation after committed page")})
            fail("Canceled snapshot must not be published")
        } catch(_:CancellationException) {}
        assertEquals(before,root.listFiles()!!.map {it.name}.toSet())
        assertEquals(1001L,live.photoCount)
        live.close()
        assertTrue(root.listFiles()!!.isEmpty())
        assertEquals(1001L,repository.window(range).count)
    }
    @Test fun cancellationAtFinishedResultHandoffDeletesTheUnreceivedArchive()=runBlocking {
        seed(listOf(PhotoMoment("handoff-photo",Instant.EPOCH,Coordinate(18.0,-66.0),PhotoCoordinateSource.photoGPS)))
        val queue=java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher=object:CoroutineDispatcher() {
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable) {queue.add(block)}
        }
        val scope=CoroutineScope(SupervisorJob()+dispatcher)
        val pending=scope.async {PhotoPlaceArchive.prepare(root,repository,range)}
        try {
            checkNotNull(queue.poll(10,TimeUnit.SECONDS)).run() // Enter prepare and dispatch its IO work.
            val handoff=withContext(Dispatchers.IO) {checkNotNull(queue.poll(20,TimeUnit.SECONDS))}
            assertEquals(1,root.listFiles()!!.size) // Complete cache exists, caller has not received it.
            pending.cancel()
            handoff.run()
            withContext(Dispatchers.IO) {
                while(!pending.isCompleted) {queue.poll(10,TimeUnit.SECONDS)?.run() ?: check(pending.isCompleted)}
            }
            assertTrue(pending.isCancelled)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {scope.cancel()}
    }
    @Test fun directoryUsesOneStableReaderSnapshotWhileAvailabilityChanges()=runBlocking {
        val photos=(0..<1001).map {i->PhotoMoment("photo-${i.toString().padStart(5,'0')}",Instant.ofEpochMilli(i.toLong()),Coordinate(0.0,i*.01),PhotoCoordinateSource.photoGPS)}
        seed(photos)
        val read=CountDownLatch(1);val proceed=CountDownLatch(1)
        val pending=async(Dispatchers.IO) {PhotoPlaceArchive.prepare(root,repository,range,progress={count->if(count==500L) {read.countDown();check(proceed.await(10,TimeUnit.SECONDS))}})}
        try {
            withContext(Dispatchers.IO) {check(read.await(10,TimeUnit.SECONDS))}
            repository.unavailable(photos.last().uri)
            proceed.countDown()
            pending.await().use {assertEquals(1001L,it.photoCount)}
            assertEquals(1000L,repository.window(range).count)
            PhotoPlaceArchive.prepare(root,repository,range).use {assertEquals(1000L,it.photoCount)}
        } finally {proceed.countDown();pending.cancelAndJoin()}
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
