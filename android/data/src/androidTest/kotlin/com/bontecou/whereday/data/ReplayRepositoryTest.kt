package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import com.google.gson.JsonParser

class ReplayRepositoryTest {
    private val start=Instant.parse("2026-10-27T10:00:00Z")
    private val range=HistoryRange(start,start.plusSeconds(10_000))
    private fun point(i:Int,outlier:Boolean=false)=TrackPoint("point-%06d".format(i),Coordinate(48.85+i*.000001,2.29+(i%2)*.00001),start.plusMillis(i.toLong()/2),5.0,altitude=12.0,speed=1.5,outlier=outlier)
    private suspend fun fixture(disk:Boolean=false,block:suspend (HistoryRepository,ReplayRepository,File)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        val name=if(disk) "synthetic-replay-${java.util.UUID.randomUUID()}.db" else null
        val db=HistoryDatabase.create(context,name)
        val root=Files.createTempDirectory(context.cacheDir.toPath(),"synthetic-replay-").toFile()
        try {block(HistoryRepository(db),ReplayRepository(root,HistoryRepository(db)),root)} finally {db.close();if(name!=null) context.deleteDatabase(name);root.deleteRecursively()}
    }
    @Test fun openingSnapshotKeepsItsVisitsAndEndpointsWhenTheLiveRangeAdvances()=runBlocking {fixture {history,replay,_->
        val opening=HistoryRange(start,start.plusSeconds(1))
        val early=Visit("early",Coordinate(48.85,2.29),start,start.plusMillis(100))
        val later=early.copy(id="later",arrivedAt=start.plusSeconds(2),departedAt=start.plusSeconds(3))
        history.dao.put(VisitRow(early));history.dao.put(VisitRow(later))
        history.dao.putPoints(listOf(PointRow(point(0),null),PointRow(point(1),null),PointRow(point(2).copy(time=start.plusSeconds(2)),null)))
        val archive=replay.prepare(ReplayRequest.Range(opening))
        try {
            assertEquals(listOf(early),archive.visits);assertEquals(point(0),archive.endpoints.first);assertEquals(point(1),archive.endpoints.last)
            val live=history.readWindow(HistoryRange(start,start.plusSeconds(4)))
            assertEquals(2,live.visits.size);assertEquals(3L,live.summary.count)
            assertEquals(listOf(early),archive.visits);assertEquals(2L,archive.count);assertEquals(point(1),archive.frame(1).current)
        } finally {archive.close()}
    }}
    @Test fun fingerprintIncludesUnsampledPointsAndMetadataAndIsIndependentOfDisplayLimit()=runBlocking {fixture {history,replay,_->
        history.database.writeTransaction {repeat(7) {history.dao.put(PointRow(point(it),null))}}
        suspend fun fingerprint(limit:Int):String {val archive=replay.prepare(ReplayRequest.Range(range),displayLimit=limit);return try {archive.fingerprint} finally {archive.close()}}
        val original=fingerprint(2);assertEquals(64,original.length);assertEquals(original,fingerprint(7))
        history.dao.put(PointRow(point(3).copy(accuracy=9.0),null))
        assertNotEquals(original,fingerprint(2))
        history.dao.put(PointRow(point(3),null));assertEquals(original,fingerprint(2))
        history.dao.put(PointRow(point(3).copy(coordinate=Coordinate(48.9,2.3)),null))
        assertNotEquals(original,fingerprint(2));assertEquals(7L,history.dao.totalPointCount())
    }}
    @Test fun fullIndexPreservesEqualTimestampPagesAndUnsampledCumulativeDistance()=runBlocking {fixture {history,replay,root->
        history.database.writeTransaction {repeat(5001) {history.dao.put(PointRow(point(it),null))}}
        val archive=replay.prepare(ReplayRequest.Range(range),displayLimit=25)
        try {
            assertEquals(5001L,archive.count)
            val first=archive.frame(0);assertEquals(point(0),first.current);assertEquals(0.0,first.distanceMeters,0.0)
            val middle=archive.frame(2501);assertEquals(point(2501),middle.current);assertEquals(25,middle.visiblePoints.size)
            val end=archive.frame(Long.MAX_VALUE);assertEquals(point(5000),end.current);assertEquals(25,end.visiblePoints.size)
            assertEquals(point(0),end.visiblePoints.first());assertEquals(point(5000),end.visiblePoints.last())
            val expected=history.readWindow(range).summary.distanceMeters
            assertEquals(expected,end.totalDistanceMeters,0.000001);assertEquals(expected,end.distanceMeters,0.000001)
            assertEquals(2500L,end.duration.toMillis());assertTrue(middle.distanceMeters<end.distanceMeters)
            assertEquals(5001L,history.dao.totalPointCount())
        } finally {archive.close()}
        assertTrue(root.listFiles()!!.isEmpty());archive.close()
        try {archive.frame(0);fail()} catch(_:IllegalStateException) {}
    }}
    @Test fun dayOutlierToggleAndOutingAllOutlierFallbackMatchIos()=runBlocking {fixture {history,replay,_->
        history.database.writeTransaction {repeat(3) {history.dao.put(PointRow(point(it,true),"outing"))};history.dao.put(SessionRow(RecordingSession("outing",start,start.plusSeconds(1))))}
        try {replay.prepare(ReplayRequest.Range(range));fail()} catch(_:IllegalArgumentException) {}
        for(request in listOf(ReplayRequest.Range(range,true),ReplayRequest.Outing("outing"))) {
            val archive=replay.prepare(request);try {assertEquals(3L,archive.count);assertTrue(archive.frame(2).current.outlier)} finally {archive.close()}
        }
        history.dao.put(PointRow(point(3),"outing"));history.dao.put(PointRow(point(4),"outing"))
        val archive=replay.prepare(ReplayRequest.Outing("outing"));try {assertEquals(2L,archive.count);assertFalse(archive.frame(1).current.outlier)} finally {archive.close()}
    }}
    @Test fun inferredReplayExcludesExactSessionWindowsAndRejectsDeletedOrEmptyOutings()=runBlocking {fixture {history,replay,root->
        history.database.writeTransaction {repeat(8) {history.dao.put(PointRow(point(it),null))};history.dao.put(SessionRow(RecordingSession("exact",start.plusMillis(1),start.plusMillis(2))))}
        val header=InferredOutingHeader("inferred",start,start.plusSeconds(1),PointSummary())
        val archive=replay.prepare(ReplayRequest.Inferred(header))
        try {assertEquals(4L,archive.count);assertEquals(listOf(0,1,6,7).map {point(it)},archive.frame(3).visiblePoints)} finally {archive.close()}
        try {replay.prepare(ReplayRequest.Outing("deleted"));fail()} catch(_:IllegalStateException) {}
        try {replay.prepare(ReplayRequest.Range(HistoryRange(start.plusSeconds(4),start.plusSeconds(5))));fail()} catch(_:IllegalArgumentException) {}
        assertTrue(root.listFiles()!!.isEmpty())
    }}
    @Test fun cancellationAndConcurrentSeeksDoNotLeakFilesOrChangeHistory()=runBlocking {fixture {history,replay,root->
        history.database.writeTransaction {repeat(1000) {history.dao.put(PointRow(point(it),null))}}
        try {replay.prepare(ReplayRequest.Range(range),progress={throw CancellationException("synthetic cancellation after partial write")});fail()} catch(_:CancellationException) {}
        assertTrue(root.listFiles()!!.isEmpty());assertEquals(1000L,history.dao.totalPointCount())
        val archive=replay.prepare(ReplayRequest.Range(range))
        try {coroutineScope {(0..20).map {i->async(Dispatchers.Default) {assertEquals(point(i*40),archive.frame((i*40).toLong()).current)}}.awaitAll()}}
        finally {archive.close()}
        assertTrue(root.listFiles()!!.isEmpty())
    }}
    @Test fun preparationUsesOneSnapshotWhileTheRecordingWriterRemainsAvailable()=runBlocking {fixture(disk=true) {history,replay,_->
        history.database.writeTransaction {repeat(1000) {history.dao.put(PointRow(point(it),null))}}
        val writeNow=CompletableDeferred<Unit>();val written=CompletableDeferred<Unit>()
        val writer=launch(Dispatchers.IO) {writeNow.await();history.dao.put(PointRow(point(1001),null));written.complete(Unit)}
        val archive=replay.prepare(ReplayRequest.Range(range),progress={if(it==500L) {writeNow.complete(Unit);withTimeout(5000) {written.await()}}})
        try {assertEquals(1000L,archive.count);assertEquals(point(999),archive.frame(999).current);writer.join();assertEquals(1001L,history.dao.totalPointCount())} finally {archive.close()}
    }}
    @Test fun actualSwiftCalculatorFixturesMatchIndicesPacingAndNativeDistanceSnapshots()=runBlocking {fixture {history,replay,_->
        val fixture=InstrumentationRegistry.getInstrumentation().context.assets.open("ios-route-replay.json").bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        fixture.getAsJsonArray("indices").forEach {entry->
            val value=entry.asJsonObject;val count=value["count"].asLong
            assertEquals(if(value["index"].isJsonNull) null else value["index"].asLong,RouteReplay.index(value["progress"].asDouble,count))
            assertEquals(value["step"].asLong,RouteReplay.stepSize(count))
        }
        val points=fixture.getAsJsonArray("coordinates").mapIndexed {i,c->point(i).copy(coordinate=Coordinate(c.asJsonArray[0].asDouble,c.asJsonArray[1].asDouble),time=start.plusSeconds(i*60L))}
        history.database.writeTransaction {points.forEach {history.dao.put(PointRow(it,null))}}
        val archive=replay.prepare(ReplayRequest.Range(range))
        try {fixture.getAsJsonArray("snapshots").forEach {entry->
            val value=entry.asJsonObject;val frame=archive.frame(RouteReplay.index(value["progress"].asDouble,archive.count)!!)
            assertEquals(value["index"].asLong,frame.index);assertEquals(value["elapsed"].asLong,frame.elapsed.seconds);assertEquals(value["duration"].asLong,frame.duration.seconds)
            assertEquals(value["distance"].asDouble,frame.distanceMeters,0.1);assertEquals(value["totalDistance"].asDouble,frame.totalDistanceMeters,0.1)
        }} finally {archive.close()}
    }}
    @Test(timeout=15_000) fun clearClosesActiveArchivesCancelsPreparationAndRejectsNewSnapshotsUntilHistoryIsDeleted()=runBlocking {fixture {history,replay,root->
        history.database.writeTransaction {repeat(1000) {history.dao.put(PointRow(point(it),null))}}
        val active=replay.prepare(ReplayRequest.Range(range))
        val reached=CompletableDeferred<Unit>()
        val preparing=async {replay.prepare(ReplayRequest.Range(range),progress={reached.complete(Unit);awaitCancellation()})}
        reached.await()
        replay.clearCache {
            assertTrue(root.listFiles()!!.isEmpty())
            try {replay.prepare(ReplayRequest.Range(range));fail()} catch(_:IllegalStateException) {}
            history.database.clearAllTables()
        }
        assertTrue(preparing.isCancelled);assertEquals(0L,history.dao.totalPointCount())
        try {active.frame(0);fail()} catch(_:IllegalStateException) {}
        assertTrue(root.listFiles()!!.isEmpty())
        history.dao.put(PointRow(point(0),null));history.dao.put(PointRow(point(1),null))
        val fresh=replay.prepare(ReplayRequest.Range(range));try {assertEquals(2L,fresh.count)} finally {fresh.close()}
    }}
}
