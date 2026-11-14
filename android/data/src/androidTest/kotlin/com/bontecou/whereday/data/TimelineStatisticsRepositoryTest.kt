package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimelineStatisticsRepositoryTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun database():HistoryDatabase {
        check(instrumentation.targetContext.packageName=="com.bontecou.whereday.data.test")
        return HistoryDatabase.create(instrumentation.targetContext,null)
    }
    private fun JsonObject.instant(key:String)=get(key)?.takeUnless {it.isJsonNull}?.asLong?.let(Instant::ofEpochMilli)
    @Test fun realRoomWindowsMatchThirtyTwoReachableSwiftCases():Unit=runBlocking {
        val rows=instrumentation.context.assets.open("ios-timeline-statistics.json").bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
        var count=0
        for(raw in rows) {
            val input=raw.asJsonObject["input"].asJsonObject;val expected=raw.asJsonObject["expected"].asJsonObject
            // These two references supply a live tracking start without a stored recording.
            // Android's actual runtime/start contract is exercised separately below.
            if(input.instant("activeStart")!=null) continue
            val db=database()
            try {
                val repo=HistoryRepository(db,Clock.fixed(input.instant("now")!!,ZoneOffset.UTC))
                val label=input["id"].asString
                for(s in input["sessions"].asJsonArray.map {it.asJsonObject}) repo.dao.put(SessionRow(RecordingSession(s["id"].asString.lowercase(),s.instant("start")!!,s.instant("end"))))
                repo.dao.putPoints(input["points"].asJsonArray.map {it.asJsonObject}.map {p->PointRow(TrackPoint(p["id"].asString.lowercase(),Coordinate(p["lat"].asDouble,p["lon"].asDouble),p.instant("time")!!,5.0,outlier=p["outlier"].asBoolean),null)})
                input["visits"]?.asJsonArray?.forEach {v->val row=v.asJsonObject;repo.dao.put(VisitRow(Visit(row["id"].asString,Coordinate(18.0,-66.0),row.instant("start")!!,row.instant("end"))))}
                val rules=if(input["enabled"].asBoolean) InferenceRules(Duration.ofSeconds(input["gapSeconds"].asLong),Duration.ofSeconds(input["minimumSeconds"].asLong),input["minimumPoints"].asInt) else null
                val range=HistoryRange(expected.instant("from")!!,expected.instant("until")!!)
                val window=repo.readWindow(range,displayLimit=2,inference=rules,includeTimelineActivity=true)
                val index=checkNotNull(window.timeline)
                assertEquals(label,expected["distance"].asDouble,index.distanceMeters,0.04)
                val wanted=expected["selected"].asJsonArray
                assertEquals(label,wanted.size(),index.outings.size)
                for((eRaw,a) in wanted.zip(index.outings)) {
                    val e=eRaw.asJsonObject
                    assertEquals(label,e["sequence"].asInt,a.sequence)
                    assertEquals(label,e["count"].asLong,a.summary.count)
                    assertEquals(label,e["outliers"].asLong,a.summary.outlierCount)
                    assertEquals(label,e["distance"].asDouble,a.summary.distanceMeters,0.02)
                    assertEquals(label,e.instant("effectiveEnd"),a.effectiveEnd)
                    assertEquals(label,e["active"].asBoolean,a.active)
                    val detail=if(a.stored!=null) repo.outingDetail(a.id,2) else repo.inferredDetail(window.inferred.single {it.id==a.id},2)
                    assertEquals(label,a.summary.count,detail.summary.count)
                    assertEquals(label,a.summary.distanceMeters,detail.summary.distanceMeters,0.02)
                    assertEquals(label,e.instant("effectiveEnd"),detail.metrics.effectiveEnd)
                    assertEquals(label,e["duration"].asDouble,detail.metrics.durationSeconds,0.000001)
                    val average=e["average"].takeUnless {it.isJsonNull}?.asDouble
                    if(average==null) assertNull(label,detail.metrics.averageSpeedMetersPerSecond)
                    else assertEquals(label,average,checkNotNull(detail.metrics.averageSpeedMetersPerSecond),0.0001)
                    assertEquals(label,e["visits"].asJsonArray.map {it.asString},detail.visits.map {it.id})
                }
                assertEquals(label,expected["visits"].asJsonArray.map {it.asString},window.visits.map {it.id})
                assertTrue(label,window.points.size<=2)
                assertNull(label,repo.readWindow(range,displayLimit=2).timeline)
                count++
            } finally {db.close()}
        }
        assertEquals(32,count)
    }
    @Test fun openOutingPhotoAndVisitScopeStopsAtLastFixIncludingTheExactBoundary():Unit=runBlocking {
        val db=database()
        try {
            val start=Instant.parse("2026-11-01T00:00:00Z");val now=start.plusSeconds(600)
            val repo=HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC));val coordinate=Coordinate(18.0,-66.0)
            repo.dao.put(SessionRow(RecordingSession("open-scope",start)))
            val last=start.plusSeconds(180)
            repo.dao.putPoints(listOf(PointRow(TrackPoint("last-fix",coordinate,last,5.0),null)))
            for((id,at) in listOf("at-start" to start,"at-end" to last,"after-end" to last.plusMillis(1))) {
                val uri="content://synthetic/$id"
                repo.dao.put(PhotoAssetRow(uri,at,coordinate,true,true,now))
                repo.dao.put(PhotoRow(uri,at,coordinate,PhotoCoordinateSource.photoGPS.wireValue,now))
                repo.dao.put(VisitRow(Visit(id,coordinate,at,at.plusSeconds(1))))
            }
            val detail=repo.outingDetail("open-scope")
            assertEquals(last,detail.metrics.effectiveEnd);assertEquals(180.0,detail.metrics.durationSeconds,0.0)
            assertEquals(listOf("at-start","at-end"),detail.visits.map {it.id})
            val photos=PhotoRepository(db,Clock.fixed(now,ZoneOffset.UTC)).window(detail.metrics.range)
            assertEquals(2L,photos.count)
            assertEquals(setOf("content://synthetic/at-start","content://synthetic/at-end"),photos.photos.map {it.uri}.toSet())
            assertEquals(3L,repo.dao.photoCount(start,now.plusMillis(1)))
        } finally {db.close()}
    }
    @Test fun liveRuntimeStartQualifiesFutureUnassignedChunkOnlyWhileRuntimeIsActive():Unit=runBlocking {
        val db=database()
        try {
            val start=Instant.parse("2026-11-01T00:00:00Z");val now=start.plusSeconds(300)
            val repo=HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC))
            val session=RecordingSession("active",start.plusSeconds(50))
            repo.dao.put(SessionRow(session));repo.dao.put(RecordingRow(requested=true,sessionId=session.id))
            repo.dao.putPoints(listOf(PointRow(TrackPoint("current",Coordinate(18.0,-66.0),start.plusSeconds(100),5.0),session.id),PointRow(TrackPoint("future",Coordinate(18.01,-66.0),start.plusSeconds(500),5.0),null)))
            val range=HistoryRange.day(LocalDate.of(2026,11,1),ZoneOffset.UTC)
            val rules=InferenceRules(minimumPoints=10)
            assertTrue(repo.readWindow(range,inference=rules,includeTimelineActivity=true).inferred.isEmpty())
            val active=repo.readWindow(range,inference=rules,includeTimelineActivity=true,timelineRuntimeActive=true).inferred.single()
            assertTrue(active.active);assertEquals(1L,active.summary.count)
            assertNull(repo.inferredDetail(active).session.endedAt)
        } finally {db.close()}
    }
    @Test fun inclusiveMetricsSpanKeysetPagesAndIgnorePointSessionOwnership():Unit=runBlocking {
        val db=database()
        try {
            val start=Instant.parse("2026-11-01T00:00:00Z");val repo=HistoryRepository(db,Clock.fixed(start.plusSeconds(5000),ZoneOffset.UTC))
            val s=RecordingSession("range",start,start.plusSeconds(1000));repo.dao.put(SessionRow(s))
            repo.dao.putPoints((0..1000).map {i->PointRow(TrackPoint("point-%04d".format(i),Coordinate(18.0+i*0.00001,-66.0),start.plusSeconds(i.toLong()),5.0),"unrelated-owner")})
            val window=repo.readWindow(HistoryRange.day(LocalDate.of(2026,11,1),ZoneOffset.UTC),displayLimit=2,includeTimelineActivity=true)
            val index=checkNotNull(window.timeline);assertEquals(1001L,index.outings.single().summary.count)
            assertEquals(repo.outingDetail(s.id,2).summary.distanceMeters,index.distanceMeters,0.001)
            assertEquals(2,window.points.size)
        } finally {db.close()}
    }
}
