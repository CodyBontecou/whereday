package com.bontecou.whereday.data

import androidx.room.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class PhotoRepositoryTest {
    private lateinit var db:HistoryDatabase
    private lateinit var photos:PhotoRepository
    private val now=Instant.parse("2026-10-27T14:00:00Z")
    private val range=HistoryRange(now.minusSeconds(3600),now.plusSeconds(3600))
    private val coordinate=Coordinate(48.8584,2.2945)
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        db=HistoryDatabase.create(context,null);photos=PhotoRepository(db,Clock.fixed(now,ZoneOffset.UTC))
    }
    @After fun close() {db.close()}
    @Test fun actualSwiftCorrelationFixturesMatchDatabaseResolution()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().context
        val cases=JsonParser.parseString(context.assets.open("ios-photo-correlation.json").bufferedReader().use {it.readText()}).asJsonObject.getAsJsonArray("cases")
        fun instant(seconds:Double)=Instant.ofEpochMilli(kotlin.math.round(seconds*1000).toLong())
        for(value in cases) {
            withContext(Dispatchers.IO) {db.clearAllTables()}
            val item=value.asJsonObject;val name=item["name"].asString
            val testNow=instant(item["now"].asDouble)
            val repository=PhotoRepository(db,Clock.fixed(testNow,ZoneOffset.UTC))
            for(p in item.getAsJsonArray("points")) {val point=p.asJsonObject
                db.history().put(PointRow(TrackPoint(id=point["id"].asString,coordinate=Coordinate(point["lat"].asDouble,point["lon"].asDouble),time=instant(point["time"].asDouble),accuracy=5.0,outlier=point["outlier"].asBoolean),null))
            }
            for(v in item.getAsJsonArray("visits")) {val visit=v.asJsonObject
                db.history().put(VisitRow(Visit(id=visit["id"].asString,coordinate=Coordinate(visit["lat"].asDouble,visit["lon"].asDouble),arrivedAt=instant(visit["arrival"].asDouble),departedAt=visit["departure"].takeUnless {it.isJsonNull}?.let {instant(it.asDouble)})))
            }
            val gps=item["gps"].takeUnless {it.isJsonNull}?.asJsonArray?.let {runCatching {Coordinate(it[0].asDouble,it[1].asDouble)}.getOrNull()}
            val asset=PhotoAssetMetadata(item["uri"].asString,instant(item["time"].asDouble),gps)
            val bounds=HistoryRange(instant(item["from"].asDouble),instant(item["until"].asDouble))
            repository.sync(listOf(asset),bounds,true)
            val actual=repository.window(bounds).photos.singleOrNull()
            val expected=item["result"]
            if(expected.isJsonNull) assertNull(name,actual)
            else {
                assertNotNull(name,actual);val result=expected.asJsonObject
                assertEquals(name,result["source"].asString,actual!!.source.wireValue)
                assertEquals(name,result["lat"].asDouble,actual.coordinate.latitude,0.000000001)
                assertEquals(name,result["lon"].asDouble,actual.coordinate.longitude,0.000000001)
            }
        }
    }
    @Test fun actualSwiftClustersMatchSharedSurfaceDistanceAndAreaGrouping()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().context
        val cases=JsonParser.parseString(context.assets.open("ios-photo-clusters.json").bufferedReader().use {it.readText()}).asJsonObject.getAsJsonArray("cases")
        for(value in cases) {
            val c=value.asJsonObject
            val photos=c.getAsJsonArray("photos").map {it.asJsonObject.let {p->PhotoMoment(p["uri"].asString,Instant.ofEpochSecond(p["time"].asLong),Coordinate(p["lat"].asDouble,p["lon"].asDouble),PhotoCoordinateSource.photoGPS)}}
            val result=PhotoClusters.build(photos,c["threshold"].asDouble)
            val expected=c.getAsJsonArray("clusters")
            assertEquals(c["name"].asString,expected.size(),result.size)
            expected.forEachIndexed {index,item->val e=item.asJsonObject;val r=result[index]
                assertEquals(e.getAsJsonArray("photos").map {it.asString},r.photos.map {it.uri})
                assertEquals(e["lat"].asDouble,r.coordinate.latitude,1e-9);assertEquals(e["lon"].asDouble,r.coordinate.longitude,1e-9)
            }
            val annotations=PhotoClusters.annotations(result,c["maximum"].asInt)
            val expectedAnnotations=c.getAsJsonArray("annotations")
            assertEquals(c["name"].asString,expectedAnnotations.size(),annotations.size)
            expectedAnnotations.forEachIndexed {index,item ->
                val e=item.asJsonObject;val r=annotations[index]
                assertEquals(e["isArea"].asBoolean,r.isArea)
                assertEquals(e.getAsJsonArray("photos").map {it.asString},r.photos.map {it.uri})
                assertEquals(e["lat"].asDouble,r.coordinate.latitude,1e-9)
                assertEquals(e["lon"].asDouble,r.coordinate.longitude,1e-9)
                assertEquals(e.getAsJsonArray("places").map {group->group.asJsonArray.map {it.asString}},r.places.map {group->group.photos.map {it.uri}})
            }
        }
    }
    @Test fun unavailableMetadataIsHiddenAndReselectionRestoresTheReferenceWithoutChangingJournal()=runBlocking {
        val asset=PhotoAssetMetadata("content://synthetic/photo",now,coordinate)
        assertEquals(1,photos.sync(listOf(asset),range,true))
        val initial=photos.window(range).photos.single()
        photos.unavailable(asset.uri)
        assertEquals(0L,photos.window(range).count)
        assertNotNull(db.history().photoAsset(asset.uri));assertNotNull(db.history().photoMoment(asset.uri))
        assertEquals(1,photos.sync(listOf(asset),range,false))
        assertTrue(db.history().photoAsset(asset.uri)!!.selected)
        assertEquals(initial,photos.window(range).photos.single())
        photos.remove(asset.uri)
        assertNull(db.history().photoAsset(asset.uri));assertNull(db.history().photoMoment(asset.uri))
        assertEquals(0L,db.history().totalPointCount());assertEquals(0L,db.history().totalVisitCount())
    }
    @Test fun missingCaptureTimeAndUnresolvedPhotosKeepReferencesWithoutInventedLocations()=runBlocking {
        val unknown=PhotoAssetMetadata("content://synthetic/unknown",null,coordinate)
        val unlocated=PhotoAssetMetadata("content://synthetic/unlocated",now)
        assertEquals(0,photos.sync(listOf(unknown,unlocated),range,true))
        assertEquals(2,db.history().photoAssetPage().size)
        assertEquals(0L,photos.window(range).count)
    }
    @Test fun boundedWindowSamplesAcrossEqualTimestampPagesAndRetainsFullCount()=runBlocking {
        repeat(5) {batch->
            val assets=(batch*500 until (batch+1)*500).map {PhotoAssetMetadata("content://synthetic/photo-%05d".format(it),now,coordinate)}
            assertEquals(500,photos.sync(assets,range,true))
        }
        val result=photos.window(range)
        assertEquals(2500L,result.count);assertEquals(500,result.photos.size)
        assertEquals("content://synthetic/photo-00000",result.photos.first().uri)
        assertEquals("content://synthetic/photo-02499",result.photos.last().uri)
        assertEquals(500,result.photos.map {it.uri}.distinct().size)
        assertEquals(0L,photos.window(HistoryRange(now.plusMillis(1),now.plusSeconds(1))).count)
    }
    @Test fun completePagingRetainsEveryPhotoBeyondMapSampleAndEqualTimestampBoundaries()=runBlocking {
        repeat(3) {batch->
            photos.sync((batch*400 until (batch+1)*400).map {PhotoAssetMetadata("content://synthetic/page-%05d".format(it),now,coordinate)},range,true)
        }
        val source=photos.pagingSource(range)
        val actual=mutableListOf<String>()
        var page=source.load(androidx.paging.PagingSource.LoadParams.Refresh(null,50,true)) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(0,page.itemsBefore);assertEquals(1150,page.itemsAfter)
        actual+=page.data.map {it.uri}
        while(page.nextKey!=null) {
            page=source.load(androidx.paging.PagingSource.LoadParams.Append(page.nextKey!!,50,true)) as androidx.paging.PagingSource.LoadResult.Page
            assertTrue(page.data.size<=50)
            actual+=page.data.map {it.uri}
        }
        assertEquals((0 until 1200).map {"content://synthetic/page-%05d".format(it)},actual)
        assertEquals(600,photos.rank(range,actual[600]))
        assertEquals(1199,photos.rank(range,actual.last()))
        val distant=photos.pagingSource(range).load(androidx.paging.PagingSource.LoadParams.Refresh(600,50,true)) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(actual[600],distant.data.first().uri);assertEquals(600,distant.itemsBefore)
        assertEquals(500,photos.window(range).photos.size)
        assertNull(photos.rank(HistoryRange(now.plusMillis(1),range.until),actual[600]))
    }
    @Test fun revocationRemovalAndClearInvalidatePagingAndRecomputeRanks()=runBlocking {
        val assets=(0..2).map {PhotoAssetMetadata("content://synthetic/rank-$it",now,coordinate)}
        photos.sync(assets,range,true)
        suspend fun liveSource():Pair<androidx.paging.PagingSource<Int,PhotoRow>,CompletableDeferred<Unit>> {
            val source=photos.pagingSource(range)
            val changed=CompletableDeferred<Unit>()
            source.registerInvalidatedCallback {changed.complete(Unit)}
            source.load(androidx.paging.PagingSource.LoadParams.Refresh(null,50,true))
            return source to changed
        }
        var (source,changed)=liveSource()
        photos.unavailable(assets[0].uri)
        withTimeout(5000) {changed.await()};assertTrue(source.invalid)
        assertNull(photos.rank(range,assets[0].uri));assertEquals(0,photos.rank(range,assets[1].uri))
        val next=liveSource();source=next.first;changed=next.second
        photos.remove(assets[1].uri)
        withTimeout(5000) {changed.await()};assertTrue(source.invalid)
        assertNull(photos.rank(range,assets[1].uri));assertEquals(0,photos.rank(range,assets[2].uri))
        val finalSource=liveSource()
        withContext(Dispatchers.IO) {db.clearAllTables()}
        withTimeout(5000) {finalSource.second.await()};assertTrue(finalSource.first.invalid)
        assertNull(photos.rank(range,assets[2].uri))
    }
    @Test fun fullLibraryPagingAndRanksIncludeUnknownDatesUnavailableAndUnmatchedReferences()=runBlocking {
        val unknown=(0 until 65).map {PhotoAssetMetadata("content://synthetic/unknown-%03d".format(it),null)}
        val known=(0 until 60).map {PhotoAssetMetadata("content://synthetic/known-%03d".format(it),now,coordinate)}
        photos.sync(unknown+known,range,true)
        photos.unavailable(known[10].uri)
        val expected=unknown.map {it.uri}+known.map {it.uri}
        val source=photos.libraryPagingSource()
        val seen=mutableListOf<PhotoLibraryEntry>()
        var page=source.load(androidx.paging.PagingSource.LoadParams.Refresh(null,50,true)) as androidx.paging.PagingSource.LoadResult.Page
        seen+=page.data
        while(page.nextKey!=null) {
            page=source.load(androidx.paging.PagingSource.LoadParams.Append(page.nextKey!!,50,true)) as androidx.paging.PagingSource.LoadResult.Page
            assertTrue(page.data.size<=50);seen+=page.data
        }
        assertEquals(expected,seen.map {it.asset.uri})
        for((index,uri) in expected.withIndex()) assertEquals(index,photos.libraryRank(uri))
        assertNull(seen[0].coordinateSource);assertFalse(seen[75].asset.available);assertNull(seen[75].coordinateSource)
        assertEquals("photo_gps",seen.last().coordinateSource)
        val changed=CompletableDeferred<Unit>();source.registerInvalidatedCallback {changed.complete(Unit)}
        photos.remove(unknown[0].uri)
        withTimeout(5000) {changed.await()}
        assertNull(photos.libraryRank(unknown[0].uri));assertEquals(74,photos.libraryRank(known[10].uri))
        val distant=photos.libraryPagingSource().load(androidx.paging.PagingSource.LoadParams.Refresh(100,50,true)) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(expected[101],distant.data[100-distant.itemsBefore].asset.uri)
    }
    @Test fun callerCancellationRollsBackPartialMetadataAndReleasesTheWriter()=runBlocking {
        val reached=CompletableDeferred<Unit>()
        val asset=PhotoAssetMetadata("content://synthetic/canceled",now,coordinate)
        val pending=async {photos.sync(listOf(asset),range,true) {reached.complete(Unit);awaitCancellation()}}
        try {
            withTimeout(5000) {reached.await()}
            withTimeout(5000) {pending.cancelAndJoin()}
            assertTrue(db.history().photoAssetPage().isEmpty())
            assertEquals(0L,photos.window(range).count)
            assertEquals(1,photos.sync(listOf(asset),range,true))
        } finally {pending.cancel()}
    }
    @Test fun failureRollsBackAssetsAndMomentsTogether()=runBlocking {
        val good=PhotoAssetMetadata("content://synthetic/good",now,coordinate)
        val bad=PhotoAssetMetadata("content://synthetic/bad",now,coordinate)
        db.useWriterConnection {connection->connection.usePrepared("CREATE TRIGGER photo_failure BEFORE INSERT ON photo_moments WHEN new.uri='content://synthetic/bad' BEGIN SELECT RAISE(ABORT,'synthetic photo failure'); END") {it.step()}}
        try {photos.sync(listOf(good,bad),range,true);fail("Expected write failure")} catch(_:androidx.sqlite.SQLiteException) {}
        assertTrue(db.history().photoAssetPage().isEmpty());assertEquals(0L,photos.window(range).count)
    }
    @Test fun automaticRangeIncludesEveryHistoryKindWithoutLoadingHistoryAndAllSyncBoundsRemainRepresentable()=runBlocking {
        val first=now.minusSeconds(10*86400L);val last=now.plusSeconds(5*86400L)
        db.history().put(PointRow(TrackPoint(id="point",coordinate=coordinate,time=now,accuracy=5.0),null))
        db.history().put(VisitRow(Visit(id="visit",coordinate=coordinate,arrivedAt=first,departedAt=first.plusSeconds(60))))
        db.history().put(SessionRow(RecordingSession(id="session",startedAt=now,endedAt=last)))
        val zone=ZoneId.of("America/Puerto_Rico")
        assertEquals(HistoryRange.days(first.atZone(zone).toLocalDate(),last.atZone(zone).toLocalDate(),zone),photos.automaticRange(range,now,zone))
        assertEquals(1,photos.sync(listOf(PhotoAssetMetadata("content://synthetic/all",now,coordinate)),HistoryRange.all,false))
    }

    @Test fun provenAliasesHaveOneRepresentativeAcrossCountsPagingRanksAndRevocation():Unit=runBlocking {
        val selected=PhotoAssetMetadata("content://synthetic/selected",now,coordinate,"same-provider-asset")
        val scanned=selected.copy(uri="content://synthetic/scanned")
        val unrelated=selected.copy(uri="content://synthetic/lookalike",identity=null)
        photos.sync(listOf(selected),range,true)
        photos.sync(listOf(scanned,unrelated),range,false)
        assertEquals(3L,db.history().photoAssetCount());assertEquals(2L,db.history().photoLibraryCount())
        assertEquals(listOf(unrelated.uri,selected.uri),photos.window(range).photos.map {it.uri})
        assertNull(photos.rank(range,scanned.uri));assertNull(photos.libraryRank(scanned.uri))
        assertEquals(1,photos.rank(range,selected.uri));assertEquals(1,photos.libraryRank(selected.uri))
        val source=photos.libraryPagingSource()
        val page=source.load(androidx.paging.PagingSource.LoadParams.Refresh(null,50,true)) as androidx.paging.PagingSource.LoadResult.Page
        assertEquals(listOf(unrelated.uri,selected.uri),page.data.map {it.asset.uri})
        val invalidated=CompletableDeferred<Unit>();source.registerInvalidatedCallback {invalidated.complete(Unit)}
        photos.unavailable(selected.uri);withTimeout(5000) {invalidated.await()}
        assertEquals(listOf(unrelated.uri,scanned.uri),photos.window(range).photos.map {it.uri})
        assertNull(photos.libraryRank(selected.uri));assertEquals(1,photos.libraryRank(scanned.uri))
        photos.unavailable(scanned.uri)
        assertEquals(1L,photos.window(range).count);assertEquals(2L,db.history().photoLibraryCount())
        photos.remove(scanned.uri)
        assertNull(db.history().photoAsset(selected.uri));assertNull(db.history().photoMoment(selected.uri))
        assertEquals(1L,db.history().photoAssetCount());assertNotNull(db.history().photoAsset(unrelated.uri))
    }
    @Test fun aliasGpsPreferenceAndBoundedPagesRemainConsistentAcrossGroups():Unit=runBlocking {
        repeat(3) {batch->
            val rows=(0 until 200).flatMap {offset->val n=batch*200+offset
                listOf(PhotoAssetMetadata("content://synthetic/a-%04d".format(n),now,coordinate,"group-$n"),PhotoAssetMetadata("content://synthetic/b-%04d".format(n),now,coordinate,"group-$n"))}
            photos.sync(rows,range,false)
        }
        assertEquals(1200L,db.history().photoAssetCount());assertEquals(600L,db.history().photoLibraryCount())
        assertEquals(600L,photos.window(range).count);assertEquals(500,photos.window(range).photos.size)
        val source=photos.pagingSource(range);var seen=0
        var page=source.load(androidx.paging.PagingSource.LoadParams.Refresh(null,50,true)) as androidx.paging.PagingSource.LoadResult.Page
        while(true) {
            assertTrue(page.data.size<=50)
            page.data.forEach {assertTrue(it.uri.contains("/a-"));assertEquals(seen++,photos.rank(range,it.uri))}
            if(page.nextKey==null) break
            page=source.load(androidx.paging.PagingSource.LoadParams.Append(page.nextKey!!,50,true)) as androidx.paging.PagingSource.LoadResult.Page
        }
        assertEquals(600,seen)
        val uri="content://synthetic/selected-without-gps"
        photos.sync(listOf(PhotoAssetMetadata(uri,now,identity="group-0")),range,true)
        assertFalse(db.history().isVisiblePhoto(uri))
        assertTrue(db.history().isVisiblePhoto("content://synthetic/a-0000"))
        val noTime=PhotoAssetMetadata("content://synthetic/selected-without-time",null,coordinate,"missing-date")
        val timed=PhotoAssetMetadata("content://synthetic/scanned-with-time",now,identity="missing-date")
        photos.sync(listOf(noTime),range,true);photos.sync(listOf(timed),range,false)
        assertFalse(db.history().isVisiblePhoto(noTime.uri));assertTrue(db.history().isVisiblePhoto(timed.uri))
    }

}
