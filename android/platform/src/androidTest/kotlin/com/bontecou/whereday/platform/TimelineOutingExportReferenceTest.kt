package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.google.gson.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.*

class TimelineOutingExportReferenceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private fun database():HistoryDatabase {
        check(instrumentation.targetContext.packageName=="com.bontecou.whereday.platform.test")
        return HistoryDatabase.create(instrumentation.targetContext,null)
    }
    private fun JsonObject.instant(key:String)=get(key)?.takeUnless {it.isJsonNull}?.asLong?.let(Instant::ofEpochMilli)
    @Test fun roomSnapshotBulkAndIndividualExportsMatchThirtyTwoReachableSwiftHistories():Unit=runBlocking {
        val cases=instrumentation.context.assets.open("ios-timeline-statistics.json").bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
        var checked=0
        for(raw in cases) {
            val input=raw.asJsonObject["input"].asJsonObject;val expected=raw.asJsonObject["expected"].asJsonObject
            if(input.instant("activeStart")!=null) continue // Independent iOS runtime starts are host references.
            val db=database()
            try {
                val label=input["id"].asString;val now=input.instant("now")!!;val dao=db.history()
                input["sessions"].asJsonArray.forEach {it.asJsonObject.let {s->dao.put(SessionRow(RecordingSession(s["id"].asString.lowercase(),s.instant("start")!!,s.instant("end"))))}}
                dao.putPoints(input["points"].asJsonArray.map {it.asJsonObject.let {p->PointRow(TrackPoint(p["id"].asString.lowercase(),Coordinate(p["lat"].asDouble,p["lon"].asDouble),p.instant("time")!!,5.0,outlier=p["outlier"].asBoolean),null)}})
                input["visits"]?.asJsonArray?.forEach {it.asJsonObject.let {v->dao.put(VisitRow(Visit(v["id"].asString,Coordinate(0.0,0.0),v.instant("start")!!,v.instant("end"))))}}
                val rules=if(input["enabled"].asBoolean) InferenceRules(Duration.ofSeconds(input["gapSeconds"].asLong),Duration.ofSeconds(input["minimumSeconds"].asLong),input["minimumPoints"].asInt) else null
                db.readSnapshot {
                    val snapshot=SnapshotOutingExport(dao,now,runtimeActive=false) {}
                    val all=snapshot.selected(ExportOptions(),rules,split=true)
                    val wanted=expected["allExport"].asJsonObject["json"].asJsonObject["outings"].asJsonArray
                    assertEquals(label,wanted.size(),all.size)
                    wanted.zip(all).forEach {(e,a)->
                        val row=e.asJsonObject;val s=a.summary
                        assertEquals(label,row["id"].asString,s.id);assertEquals(label,row["sequenceNumber"].asInt,s.sequenceNumber)
                        assertEquals(label,row["pointCount"].asLong,s.pointCount);assertEquals(label,row["visitCount"].asLong,s.visitCount)
                        assertEquals(label,row["isActive"].asBoolean,s.isActive);assertEquals(label,row["isInferred"].asBoolean,s.isInferred)
                        assertEquals(label,row["endedAt"]?.asString?.let(Instant::parse),s.endedAt)
                        assertEquals(label,Instant.parse(row["effectiveEndAt"].asString),s.effectiveEndAt)
                        assertEquals(label,row["durationSeconds"].asDouble,s.durationSeconds,0.000001)
                        assertEquals(label,row["distanceMeters"].asDouble,s.distanceMeters,.02)
                        val ids=mutableListOf<String>();a.source.points {ids+=it.id};assertEquals(label,s.pointCount,ids.size.toLong())
                        val sourceRow=expected["all"].asJsonArray.single {it.asJsonObject["id"].asString==s.id}.asJsonObject
                        assertEquals(label,sourceRow["points"].asJsonArray.map {it.asString.lowercase()}.sorted(),ids.sorted())
                        val stored=sourceRow["storedID"].takeUnless {it.isJsonNull}?.asString?.lowercase()
                        if(stored!=null) assertEquals(label,s,snapshot.individual(stored,s.sequenceNumber,null,ExportOptions()).summary)
                    }
                    val unionIDs=mutableListOf<String>();snapshot.union(all).points {unionIDs+=it.id}
                    val wantedIDs=expected["all"].asJsonArray.flatMap {it.asJsonObject["points"].asJsonArray.map {p->p.asString.lowercase()}}.distinct().sorted()
                    assertEquals("$label/union",wantedIDs,unionIDs.sorted())
                    val csv=StringWriter();StreamingOutingExporter.write(csv,all.map {it.summary},snapshot.union(all),ExportFormat.csv,generatedAt=now)
                    val rows=CsvReader.read(csv.toString());val reference=CsvReader.read(expected["allExport"].asJsonObject["csv"].asString)
                    assertEquals(label,reference.size,rows.size)
                    reference.zip(rows).forEachIndexed {i,(e,a)->e.zip(a).forEachIndexed {j,(x,y)->
                        if(i>0 && j in listOf(7,8) && x.isNotEmpty()) assertEquals("$label/$i/$j",x.toDouble(),y.toDouble(),if(j==7) .03 else .002)
                        else assertEquals("$label/$i/$j",x,y)
                    }}
                }
                checked++
            } finally {db.close()}
        }
        assertEquals(32,checked)
    }

    @Test fun activeInferredIndividualRetainsCapturedWindowAndDoesNotCreateAStoredSession():Unit=runBlocking {
        val db=database()
        try {
            val dao=db.history();val start=Instant.parse("2026-11-01T00:00:00Z");val last=start.plusSeconds(60)
            val firstID="00000000-0000-0000-0000-000000000001";val lastID="00000000-0000-0000-0000-000000000002"
            for((id,time) in listOf(firstID to start,lastID to last,"later" to last.plusMillis(1))) dao.put(PointRow(TrackPoint(id,Coordinate(18.0,-66.0),time,5.0),null))
            val header=InferredOutingHeader("inferred:$firstID",start,last,PointSummary(),active=true)
            db.readSnapshot {
                val outing=SnapshotOutingExport(dao,start.plusSeconds(600),runtimeActive=false) {}.individual(header.id,7,header,ExportOptions())
                assertTrue(outing.summary.isActive);assertNull(outing.summary.endedAt);assertEquals(last,outing.summary.effectiveEndAt)
                assertEquals("inferred-${firstID.uppercase()}-${lastID.uppercase()}-2",outing.summary.id)
                val ids=mutableListOf<String>();outing.source.points {ids+=it.id};assertEquals(listOf(firstID,lastID),ids)
            }
            assertTrue(dao.allSessions().isEmpty());assertEquals(3L,dao.totalPointCount())
        } finally {db.close()}
    }

    @Test fun bulkActiveQualificationRequiresBothRuntimeAndTheDurableOpenOwner():Unit=runBlocking {
        val db=database()
        try {
            val dao=db.history();val start=Instant.parse("2026-11-01T00:00:00Z");val now=start.plusSeconds(60)
            dao.put(SessionRow(RecordingSession("owner",start)))
            dao.put(RecordingRow(requested=true,sessionId="owner"))
            // Imported future fixes are outside the stored window through captured now.
            // The live start admits this otherwise unqualified inferred chunk, like Swift.
            dao.put(PointRow(TrackPoint("future",Coordinate(18.0,-66.0),now.plusSeconds(1),5.0),null))
            val rules=InferenceRules(minimumPoints=1000)
            suspend fun selected(active:Boolean)=db.readSnapshot {
                SnapshotOutingExport(dao,now,runtimeActive=active) {}.selected(ExportOptions(),rules,split=true)
            }
            assertEquals(1,selected(false).size)
            val live=selected(true);assertEquals(2,live.size)
            val inferred=live.single {it.summary.isInferred}.summary
            assertTrue(inferred.isActive);assertNull(inferred.endedAt);assertEquals(1L,inferred.pointCount)
            assertEquals("inferred-FUTURE-FUTURE-1",inferred.id)
            dao.put(RecordingRow(requested=false,sessionId="owner"));assertEquals(1,selected(true).size)
            dao.put(RecordingRow(requested=true,sessionId="missing"));assertEquals(1,selected(true).size)
            assertEquals(1,dao.allSessions().size);assertEquals(1L,dao.totalPointCount())
        } finally {db.close()}
    }
}
