package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import java.util.Locale
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.Instant
import java.util.UUID

class WheredayHistoryImportTest {
    private val at=Instant.parse("2020-01-01T00:00:00Z")
    private fun input(text:String)=object:HistoryImportInput {override fun open()=text.byteInputStream()}
    private suspend fun fixture(block:suspend(HistoryDatabase,HistoryImportController)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        val root=File(context.cacheDir,"whereday-import-${UUID.randomUUID()}");val db=HistoryDatabase.create(context,null)
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),DiskHistoryImportWorkspaceFactory(root))
        try {block(db,controller)} finally {controller.clearAll {};controller.close();db.close();root.deleteRecursively()}
    }
    private suspend fun joint(visits:Boolean,format:ExportFormat=ExportFormat.json,late:Boolean=false)=fixture {db,controller->
        val step=if(visits) 0.00072 else 0.000072
        val localVisit=Visit(coordinate=Coordinate(18-step,-66.0),arrivedAt=at,departedAt=at.plusSeconds(3600),notes="Keep local")
        val localPoint=TrackPoint(coordinate=Coordinate(18-step,-66.0),time=at,accuracy=99.0)
        suspend fun seed() {if(visits) db.history().put(VisitRow(localVisit)) else db.history().put(PointRow(localPoint,"preserved"))}
        if(!late) seed()
        val rows=(0..2).joinToString(",") {index->val lat=18+index*step
            if(visits) """{"latitude":$lat,"longitude":-66,"arrivedAt":"2020-01-01T00:00:00Z","departedAt":"2020-01-01T01:00:00Z"}"""
            else """{"latitude":$lat,"longitude":-66,"timestamp":"2020-01-01T00:00:00Z","horizontalAccuracy":5}"""
        }
        val kind=if(visits) "visits" else "points"
        val text=if(format==ExportFormat.json) """{"$kind":[$rows]}""" else HistoryExporter.render(format,
            if(visits) (0..2).map {Visit(coordinate=Coordinate(18+it*step,-66.0),arrivedAt=at,departedAt=at.plusSeconds(3600))} else emptyList(),
            if(visits) emptyList() else (0..2).map {TrackPoint(coordinate=Coordinate(18+it*step,-66.0),time=at,accuracy=5.0)},ExportOptions(zone=ZoneOffset.UTC),locale=Locale.US)
        controller.select(input(text),Locale.US,ZoneOffset.UTC)!!.join();val state=controller.state.value
        assertEquals(state.error,HistoryImportPhase.review,state.phase);assertEquals(0,state.review!!.replaceable)
        assertNull(controller.commit(state.review!!.id,HistoryImportPolicy.replaceExisting))
        if(late) seed()
        controller.commit(state.review!!.id,HistoryImportPolicy.keepExisting)!!.join()
        assertEquals(if(visits) HistoryImportCounts(visits=1,kept=2) else HistoryImportCounts(points=1,kept=2),controller.state.value.counts)
        if(visits) {
            val added=db.history().rangeVisits(at,at.plusSeconds(7200)).map {it.value}.single {it.id!=localVisit.id}
            assertEquals(Coordinate(18+step,-66.0),added.coordinate);assertEquals(localVisit,db.history().visit(localVisit.id)!!.value)
        } else {
            val added=db.history().points(at,at.plusSeconds(7200)).map {it.value}.single {it.id!=localPoint.id}
            assertEquals(Coordinate(18+step,-66.0),added.coordinate);assertEquals(PointRow(localPoint,"preserved"),db.history().point(localPoint.id))
        }
    }
    @Test fun jsonVisitsUseJointJournalAndSourceAdmission():Unit=runBlocking {joint(true)}
    @Test fun jsonPointsUseJointJournalAndSourceAdmission():Unit=runBlocking {joint(false)}
    @Test fun anonymousVisitsWithDistinctDepartureIntervalsDoNotShareIdentity():Unit=runBlocking {fixture {db,controller->
        val rows=listOf("01","02").joinToString(",") {hour->"""{"latitude":18,"longitude":-66,"arrivedAt":"2020-01-01T00:00:00Z","departedAt":"2020-01-01T$hour:00:00Z"}"""}
        controller.select(input("""{"visits":[$rows]}"""))!!.join();val state=controller.state.value
        assertEquals(state.error,HistoryImportPhase.review,state.phase)
        assertEquals(2,state.review!!.visits)
        controller.commit(state.review!!.id,HistoryImportPolicy.keepExisting)!!.join()
        val values=db.history().rangeVisits(at,at.plusSeconds(10800)).map {it.value}
        assertEquals(2,values.size);assertEquals(2,values.map {it.id}.toSet().size)
        assertEquals(setOf(at.plusSeconds(3600),at.plusSeconds(7200)),values.map {it.departedAt}.toSet())
    }}
    @Test fun csvAndMarkdownUseTheSameVisitAndPointAdmission():Unit=runBlocking {
        for(format in listOf(ExportFormat.csv,ExportFormat.markdown)) for(visits in listOf(false,true)) joint(visits,format)
    }
    @Test fun lateHistoryRechecksEveryWheredayFormat():Unit=runBlocking {
        for(format in listOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown)) for(visits in listOf(false,true)) joint(visits,format,true)
    }
    private fun explicitJson(visits:List<Visit>,points:List<TrackPoint>):String {
        val root=com.google.gson.JsonParser.parseString(HistoryExporter.render(ExportFormat.json,visits,points)).asJsonObject
        root.getAsJsonArray("visits").zip(visits).forEach {(row,value)->row.asJsonObject.addProperty("id",value.id)}
        root.getAsJsonArray("points").zip(points).forEach {(row,value)->row.asJsonObject.addProperty("id",value.id)}
        return root.toString()
    }
    @Test fun explicitReplacementChangesMatchingIdsAndKeepsNearMatches():Unit=runBlocking {fixture {db,controller->
        val visit=Visit(coordinate=Coordinate(18.0,-66.0),arrivedAt=at,departedAt=at.plusSeconds(3600),notes="Keep notes")
        val point=TrackPoint(coordinate=Coordinate(21.0,-66.0),time=at.plusSeconds(7200),accuracy=99.0)
        db.history().put(VisitRow(visit));db.history().put(PointRow(point,"preserved-session"))
        val nearVisit=visit.copy(id=UUID.randomUUID().toString(),coordinate=Coordinate(18.0001,-66.0),notes="Must not replace")
        val nearPoint=point.copy(id=UUID.randomUUID().toString(),coordinate=Coordinate(21.00001,-66.0),accuracy=7.0)
        controller.select(input(explicitJson(listOf(nearVisit),listOf(point.copy(accuracy=5.0),nearPoint))))!!.join()
        val review=controller.state.value.review!!;assertEquals(1,review.replaceable);assertEquals(3,review.existing)
        controller.commit(review.id,HistoryImportPolicy.replaceExisting)!!.join()
        assertEquals(HistoryImportCounts(points=1,kept=2),controller.state.value.counts)
        assertEquals(visit,db.history().visit(visit.id)!!.value);assertNull(db.history().visit(nearVisit.id));assertNull(db.history().point(nearPoint.id))
        assertEquals(PointRow(point.copy(accuracy=5.0),"preserved-session"),db.history().point(point.id))
    }}
    @Test fun legacyAnonymousIdentityIsKeptWithoutAssigningItsIdToAnotherInterval():Unit=runBlocking {fixture {db,controller->
        val coordinate=Coordinate(18.0,-66.0)
        val oldId=UUID.nameUUIDFromBytes("visit:$at:$coordinate".toByteArray()).toString()
        val local=Visit(oldId,coordinate,at,at.plusSeconds(3600),notes="Keep old import")
        db.history().put(VisitRow(local))
        val text="""{"visits":[{"latitude":18,"longitude":-66,"arrivedAt":"2020-01-01T00:00:00Z","departedAt":"2020-01-01T01:00:00Z"}]}"""
        controller.select(input(text))!!.join();val review=controller.state.value.review!!
        assertEquals(1,review.existing);assertEquals(0,review.replaceable)
        controller.commit(review.id,HistoryImportPolicy.keepExisting)!!.join()
        assertEquals(HistoryImportCounts(kept=1),controller.state.value.counts);assertEquals(local,db.history().visit(oldId)!!.value);assertEquals(1L,db.history().totalVisitCount())
    }}
    @Test fun anonymousMetadataKeepsFirstWhileExplicitAndMixedIdentityConflictsFail():Unit=runBlocking {fixture {db,controller->
        fun point(time:Instant=at,accuracy:Int=5,id:String?=null):String {
            val identity=id?.let { """ "id":"$it", """ }.orEmpty()
            return """{$identity"latitude":18,"longitude":-66,"timestamp":"$time","horizontalAccuracy":$accuracy}"""
        }
        controller.select(input("""{"points":[${point()},${point(accuracy=99)}]}"""))!!.join();val review=controller.state.value.review!!
        assertEquals(1,review.points);assertEquals(1,review.repeated)
        controller.commit(review.id,HistoryImportPolicy.keepExisting)!!.join()
        val stored=db.history().points(at,at.plusSeconds(1)).single().value;assertEquals(5.0,stored.accuracy,0.0)
        controller.discard()
        val explicit=UUID.randomUUID().toString()
        controller.select(input("""{"points":[${point(id=explicit)},${point(accuracy=99,id=explicit)}]}"""))!!.join()
        assertEquals(HistoryImportPhase.failed,controller.state.value.phase);controller.discard()
        val generated=UUID.nameUUIDFromBytes("point:$at:${Coordinate(18.0,-66.0)}".toByteArray()).toString()
        val firstAndFlush=(0..200).joinToString(",") {point(at.plusSeconds(it.toLong()))}
        controller.select(input("""{"points":[$firstAndFlush,${point(id=generated)},${point(accuracy=99)}]}"""))!!.join()
        assertEquals(HistoryImportPhase.failed,controller.state.value.phase);assertEquals(1L,db.history().totalPointCount());assertEquals(stored,db.history().point(stored.id)!!.value)
    }}
    @Test fun anonymousOpenAndClosedVisitsRemainDistinct():Unit=runBlocking {fixture {db,controller->
        val common=""" "latitude":18,"longitude":-66,"arrivedAt":"2020-01-01T00:00:00Z" """
        controller.select(input("""{"visits":[{$common},{$common,"departedAt":"2020-01-01T01:00:00Z"}]}"""))!!.join()
        val review=controller.state.value.review!!;assertEquals(2,review.visits)
        controller.commit(review.id,HistoryImportPolicy.keepExisting)!!.join()
        assertEquals(2L,db.history().totalVisitCount());assertEquals(setOf(null,at.plusSeconds(3600)),db.history().rangeVisits(at,at.plusSeconds(7200)).map {it.value.departedAt}.toSet())
    }}
}
