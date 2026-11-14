package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.*
import org.junit.Assert.*
import java.time.*

class ActiveRecordingMapTest {
    private lateinit var db:HistoryDatabase
    private lateinit var repo:HistoryRepository
    private val now=Instant.parse("2026-10-29T12:00:00Z")
    private val session=RecordingSession("current",now.minusSeconds(10_000))
    private fun point(index:Int,outlier:Boolean=false)=TrackPoint("current-%05d".format(index),Coordinate(18.0+index*.000001,-66.0),now.minusSeconds(9000-index/2L),5.0,outlier=outlier)
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        db=HistoryDatabase.create(context,null);repo=HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC))
    }
    @After fun close() {db.close()}
    private suspend fun activate() {repo.dao.put(SessionRow(session));repo.dao.put(RecordingRow(requested=true,sessionId=session.id))}
    @Test fun missingStoppedEndedAndUnownedSessionsNeverReturnALiveRoute()=runBlocking {
        assertNull(repo.readActiveRecordingMap())
        repo.dao.put(SessionRow(session));assertNull(repo.readActiveRecordingMap())
        repo.dao.put(RecordingRow(requested=false,sessionId=session.id));assertNull(repo.readActiveRecordingMap())
        repo.dao.put(RecordingRow(requested=true,sessionId="missing"));assertNull(repo.readActiveRecordingMap())
        repo.dao.put(RecordingRow(requested=true,sessionId=session.id));repo.dao.put(SessionRow(session.copy(endedAt=now)))
        assertNull(repo.readActiveRecordingMap())
        for(limit in listOf(0,1,2501)) try {repo.readActiveRecordingMap(displayLimit=limit);fail()} catch(_:IllegalArgumentException) {}
    }
    @Test fun emptyRecordingIsRepresentedWithoutInventingMarkers()=runBlocking {
        activate();val value=repo.readActiveRecordingMap()!!
        assertEquals(session,value.session);assertEquals(0L,value.count);assertTrue(value.points.isEmpty());assertEquals(RouteEndpoints(),value.endpoints)
    }
    @Test fun sessionOwnershipFilteringAndEndpointsPrecedeSamplingAcrossEqualTimePages()=runBlocking {
        activate()
        repo.dao.putPoints((0..6000).map {PointRow(point(it,it%2==0),session.id)})
        repo.dao.put(PointRow(point(200).copy(id="overlapping-import"),null))
        repo.dao.put(PointRow(point(201).copy(id="other-session"),"other"))
        val filtered=repo.readActiveRecordingMap()!!
        assertEquals(3000L,filtered.count);assertEquals(2500,filtered.points.size)
        assertTrue(filtered.points.none {it.outlier || !it.id.startsWith("current-")})
        assertEquals(point(1),filtered.endpoints.first);assertEquals(point(5999),filtered.endpoints.last)
        assertEquals(filtered.endpoints.first,filtered.points.first());assertEquals(filtered.endpoints.last,filtered.points.last())
        val all=repo.readActiveRecordingMap(includeOutliers=true,displayLimit=200)!!
        assertEquals(6001L,all.count);assertEquals(200,all.points.size)
        assertEquals(point(0,true),all.endpoints.first);assertEquals(point(6000,true),all.endpoints.last)
        assertEquals(6003L,repo.dao.totalPointCount())
    }
    @Test fun allOutliersStayHiddenUntilTheExplicitOutlierChoice()=runBlocking {
        activate();repo.dao.putPoints((0..2).map {PointRow(point(it,true),session.id)})
        val hidden=repo.readActiveRecordingMap()!!
        assertEquals(0L,hidden.count);assertTrue(hidden.points.isEmpty());assertNull(hidden.endpoints.first)
        assertEquals(3L,repo.readActiveRecordingMap(includeOutliers=true)!!.count)
    }
    @Test fun sparseDisplaySamplesDoNotInventGapsInAContinuousLongRecording()=runBlocking {
        activate();repo.dao.put(SessionRow(session.copy(startedAt=now.minusSeconds(6000*600L))))
        repo.dao.putPoints((0..6000).map {PointRow(point(it).copy(time=now.minusSeconds((6000-it)*600L)),session.id)})
        val map=repo.readActiveRecordingMap()!!
        assertEquals(6001L,map.count);assertEquals(2500,map.points.size)
        assertTrue(Duration.between(map.points[0].time,map.points[1].time)>Duration.ofMinutes(10))
        assertEquals(listOf(map.points),map.segments)
    }
    @Test fun realGapsRemainBrokenEvenWhenSamplingSkipsEveryIntermediatePoint()=runBlocking {
        activate()
        val values=listOf(0L,1,2000,2001).mapIndexed {index,seconds->point(index).copy(time=now.minusSeconds(4000).plusSeconds(seconds))}
        repo.dao.putPoints(values.map {PointRow(it,session.id)})
        assertEquals(listOf(values.take(2),values.takeLast(2)),repo.readActiveRecordingMap()!!.segments)
        val sparse=repo.readActiveRecordingMap(displayLimit=2)!!
        assertEquals(listOf(values.first(),values.last()),sparse.points)
        assertTrue(sparse.segments.isEmpty())
    }
    @Test fun observerTracksCommittedFixesStopNewSessionAndClear()=runBlocking {
        val events=Channel<ActiveRecordingMap?>(Channel.UNLIMITED)
        val collecting=launch {repo.observeActiveRecordingMap().collect {events.send(it)}}
        suspend fun next(predicate:(ActiveRecordingMap?)->Boolean):ActiveRecordingMap?=withTimeout(5000) {
            var value=events.receive();while(!predicate(value)) value=events.receive();value
        }
        try {
            next {it==null}
            db.writeTransaction {activate();repo.dao.put(PointRow(point(0),session.id))}
            assertEquals(session.id,next {it?.count==1L}!!.session.id)
            repo.stop();next {it==null}
            val nextSession=repo.start(0)
            repo.ingest(listOf(TrackPoint("fresh-session",Coordinate(1.0,2.0),now,5.0)))
            val fresh=next {it!=null && it.session.id==nextSession.sessionId && it.count==1L}!!
            assertEquals("fresh-session",fresh.points.single().id)
            withContext(Dispatchers.IO) {db.clearAllTables()};next {it==null}
        } finally {collecting.cancelAndJoin();events.close()}
        Unit
    }
}
