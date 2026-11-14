package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.Instant
import java.util.UUID

class GoogleHistoryImportTest {
    private val at=Instant.parse("2020-01-01T00:00:00Z")
    private val coordinate=Coordinate(18.0,-66.0)
    private fun input(text:String)=object:HistoryImportInput {override fun open()=text.byteInputStream()}
    private suspend fun fixture(block:suspend(HistoryDatabase,DiskHistoryImportWorkspaceFactory,File)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        val root=File(context.cacheDir,"google-import-test-${UUID.randomUUID()}");val db=HistoryDatabase.create(context,null)
        try {block(db,DiskHistoryImportWorkspaceFactory(root),root)} finally {db.close();root.deleteRecursively()}
    }
    private fun device(start:String="2020-01-01T00:00:00Z",end:String="2020-01-01T01:00:00Z")="""{"startTime":"$start","endTime":"$end","visit":{"topCandidate":{"placeLocation":"geo:18,-66","placeName":"Google name"}},"activity":{"start":"geo:18,-66","end":"geo:18.1,-66"}}"""
    @Test fun allThreeGoogleSourcesReviewWithoutWritesAndRepeatImportKeepsCorrectionsAndSessions():Unit=runBlocking {
        val raw="""{"locations":[{"latitudeE7":180000000,"longitudeE7":-660000000,"timestampMs":"1577836800000","accuracy":8}]}"""
        val semantic="""{"timelineObjects":[{"placeVisit":{"location":{"latitudeE7":180000000,"longitudeE7":-660000000,"name":"Google name"},"duration":{"startTimestamp":"2020-01-01T00:00:00Z","endTimestamp":"2020-01-01T01:00:00Z"}}},{"activitySegment":{"startLocation":{"latLng":"geo:18,-66"},"endLocation":{"latLng":"geo:18.1,-66"},"duration":{"startTimestamp":"2020-01-01T00:00:00Z","endTimestamp":"2020-01-01T01:00:00Z"}}}]}"""
        for((source,text) in listOf(HistoryImportSource.googleRaw to raw,HistoryImportSource.googleSemantic to semantic,HistoryImportSource.googleDevice to "[${device()}]")) fixture {db,factory,_->
            val visit=Visit(coordinate=coordinate,arrivedAt=at,departedAt=at.plusSeconds(3600),name="Local correction",notes="Preserve my notes",confirmation=Confirmation.corrected)
            val point=TrackPoint(coordinate=coordinate,time=at,accuracy=99.0)
            db.history().put(VisitRow(visit));db.history().put(PointRow(point,"existing-session"))
            val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
            try {
                repeat(2) {attempt->
                    controller.select(input(text))!!.join();val state=controller.state.value
                    assertEquals(state.error,HistoryImportPhase.review,state.phase);assertEquals(source,state.review!!.source)
                    assertEquals(1L,db.history().totalVisitCount());assertEquals(if(attempt==0 || source==HistoryImportSource.googleRaw) 1L else 2L,db.history().totalPointCount())
                    controller.commit(state.review!!.id,HistoryImportPolicy.keepExisting)!!.join()
                    assertEquals(controller.state.value.error,HistoryImportPhase.complete,controller.state.value.phase)
                    assertEquals(0,controller.state.value.counts!!.visits)
                    assertEquals(visit,db.history().visit(visit.id)!!.value)
                    assertEquals(PointRow(point,"existing-session"),db.history().point(point.id))
                    controller.discard()
                }
                assertEquals(if(source==HistoryImportSource.googleRaw) 1L else 2L,db.history().totalPointCount())
            } finally {controller.close()}
        }
    }
    @Test fun diskStagingReconcilesNontransitiveVisitMatchesInChronologicalOrder():Unit=runBlocking {fixture {db,factory,_->
        val text=listOf("02","01","00").joinToString(",") {minute->"""{"startTime":"2020-01-01T00:$minute:00Z","endTime":"2020-01-01T01:00:00Z","visit":{"topCandidate":{"placeLocation":"geo:18,-66"}}}"""}
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            controller.select(input("[$text]"))!!.join();val review=controller.state.value.review!!
            assertEquals(2,review.visits);assertEquals(1,review.repeated);assertEquals(0L,db.history().totalVisitCount())
            controller.commit(review.id,HistoryImportPolicy.keepExisting)!!.join()
            assertEquals(HistoryImportCounts(visits=2,kept=1),controller.state.value.counts)
            assertEquals(listOf(at,at.plusSeconds(120)),db.history().rangeVisits(at,at.plusSeconds(7200)).map {it.value.arrivedAt})
        } finally {controller.close()}
    }}
    @Test fun selectedPointsRecheckNewMatchingHistoryInsideTheCommit():Unit=runBlocking {fixture {db,factory,_->
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            controller.select(input("[${device()}]"))!!.join();val review=controller.state.value.review!!;assertEquals(0,review.existing)
            val local=TrackPoint(coordinate=coordinate,time=at,accuracy=42.0);db.history().put(PointRow(local,"new-session"))
            assertNull(controller.commit(review.id,HistoryImportPolicy.replaceExisting))
            assertNull(controller.commit(review.id,HistoryImportPolicy.keepExisting,HistoryImportSelection(false,false)))
            controller.commit(review.id,HistoryImportPolicy.keepExisting,HistoryImportSelection(false,true))!!.join()
            assertEquals(HistoryImportCounts(points=1,kept=1),controller.state.value.counts)
            assertEquals(0L,db.history().totalVisitCount());assertEquals(2L,db.history().totalPointCount());assertEquals(PointRow(local,"new-session"),db.history().point(local.id))
        } finally {controller.close()}
    }}
    @Test fun cancellationAfterFirstWriteBatchRollsBackTheWholeGoogleImport():Unit=runBlocking {fixture {db,factory,_->
        val text=(0..200).joinToString(",") {"""{"latitudeE7":180000000,"longitudeE7":-660000000,"timestampMs":${1577836800000L+it*1000}}"""}
        factory.create().use {workspace->
            workspace.capture("{\"locations\":[$text]}".byteInputStream()) {};workspace.prepare(java.util.Locale.US,java.time.ZoneOffset.UTC) {}
            val target=DatabaseHistoryImportTarget(db);assertEquals(0,target.inspect(workspace.stage) {});workspace.stage.seal {}
            var pages=0
            val interrupted=object:HistoryImportStage by workspace.stage {
                override fun chronologicalPage(after:ImportChronologyCursor?,limit:Int):List<ImportChronologyRow> {if(++pages==2) throw HistoryImportCanceled();return workspace.stage.chronologicalPage(after,limit)}
            }
            try {target.apply(interrupted,HistoryImportPolicy.keepExisting) {};fail("Cancellation was ignored")} catch(_:HistoryImportCanceled) {}
            assertEquals(0L,db.history().totalPointCount());assertEquals(0L,db.history().totalVisitCount())
        }
    }}
    @Test fun matchingPagesPastTwoHundredSameSecondRowsWithoutReadingTheirNotes():Unit=runBlocking {fixture {db,factory,_->
        repeat(200) {db.history().put(PointRow(TrackPoint(coordinate=Coordinate(0.0,0.0),time=at,accuracy=5.0),null))}
        val matching=TrackPoint(coordinate=coordinate,time=at,accuracy=99.0);db.history().put(PointRow(matching,"preserved"))
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            controller.select(input("""{"locations":[{"latLng":"geo:18,-66","timestamp":"2020-01-01T00:00:00Z"}]}"""))!!.join()
            val review=controller.state.value.review!!;assertEquals(1,review.existing)
            controller.commit(review.id,HistoryImportPolicy.keepExisting)!!.join();assertEquals(HistoryImportCounts(kept=1),controller.state.value.counts)
            assertEquals(201L,db.history().totalPointCount());assertEquals(PointRow(matching,"preserved"),db.history().point(matching.id))
        } finally {controller.close()}
    }}
    @Test fun malformedLateGoogleContentCannotPublishAReviewOrLeaveScratch():Unit=runBlocking {fixture {db,factory,root->
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            controller.select(input("[{\"startTime\":\"2020-01-01T00:00:00Z\",\"endTime\":\"2020-01-01T01:00:00Z\",\"activity\":{\"start\":\"geo:18,-66\"}}, {"))!!.join()
            assertEquals(HistoryImportPhase.failed,controller.state.value.phase);assertNull(controller.state.value.review)
            assertEquals(0L,db.history().totalPointCount());assertEquals(0L,db.history().totalVisitCount())
        } finally {controller.clearAll {};controller.close()}
        factory.reap();assertFalse(root.walkTopDown().any {it.name=="source" || it.name.startsWith("google-row-")})
    }}
    @Test fun tiedSemanticPathsRetainSwiftOrderInsteadOfJsonPropertyOrder():Unit=runBlocking {fixture {db,factory,_->
        val text="""{"timelineObjects":[{"activitySegment":{"duration":{"startTimestamp":"2020-01-01T00:00:00Z","endTimestamp":"2020-01-01T01:00:00Z"},"waypointPath":{"waypoints":[{"latLng":"geo:18.000001,-66","timestampMs":"1577836801000","accuracy":99}]},"simplifiedRawPath":{"points":[{"latLng":"geo:18,-66","timestampMs":"1577836801000","accuracy":5}]}}}]}"""
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            controller.select(input(text))!!.join();val review=controller.state.value.review!!
            assertEquals(1,review.points);assertEquals(1,review.repeated)
            controller.commit(review.id,HistoryImportPolicy.keepExisting)!!.join();assertEquals(HistoryImportCounts(points=1,kept=1),controller.state.value.counts)
            val point=db.history().points(at,at.plusSeconds(3600)).single().value
            assertEquals(coordinate,point.coordinate);assertEquals(5.0,point.accuracy,0.0)
        } finally {controller.close()}
    }}
    @Test fun sameArrivalWithDistinctDeparturesSurvivesStagingAndRoomCommit():Unit=runBlocking {fixture {db,factory,_->
        val rows=listOf("01","02").joinToString(",") {hour->device(end="2020-01-01T$hour:00:00Z")}
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            controller.select(input("[$rows]"))!!.join();val review=controller.state.value.review!!
            assertEquals(2,review.visits);assertEquals(0L,db.history().totalVisitCount())
            controller.commit(review.id,HistoryImportPolicy.keepExisting,HistoryImportSelection(true,false))!!.join()
            assertEquals(HistoryImportCounts(visits=2),controller.state.value.counts)
            val visits=db.history().rangeVisits(at,at.plusSeconds(10800)).map {it.value}
            assertEquals(2,visits.map {it.id}.toSet().size);assertEquals(setOf(at.plusSeconds(3600),at.plusSeconds(7200)),visits.map {it.departedAt}.toSet())
        } finally {controller.close()}
    }}
    @Test fun existingHistoryChangesWhichIncomingVisitIsAdmitted():Unit=runBlocking {fixture {db,factory,_->
        val local=Visit(coordinate=Coordinate(17.99928,-66.0),arrivedAt=at,departedAt=at.plusSeconds(3600),name="Keep existing",notes="Unchanged")
        db.history().put(VisitRow(local))
        val rows=listOf(18.0,18.00072,18.00144).joinToString(",") {latitude->"""{"startTime":"2020-01-01T00:00:00Z","endTime":"2020-01-01T01:00:00Z","visit":{"topCandidate":{"placeLocation":"geo:$latitude,-66"}}}"""}
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            controller.select(input("[$rows]"))!!.join();val review=controller.state.value.review!!
            controller.commit(review.id,HistoryImportPolicy.keepExisting)!!.join()
            assertEquals(1,controller.state.value.counts!!.visits)
            val imported=db.history().rangeVisits(at,at.plusSeconds(7200)).map {it.value}.filter {it.id!=local.id}.single()
            assertEquals(Coordinate(18.00072,-66.0),imported.coordinate)
            assertEquals(local,db.history().visit(local.id)!!.value)
        } finally {controller.close()}
    }}
    private suspend fun verifyJointAdmission(visits:Boolean,late:Boolean)=fixture {db,factory,_->
        val step=if(visits) 0.00072 else 0.000072
        val localVisit=Visit(coordinate=Coordinate(18-step,-66.0),arrivedAt=at,departedAt=at.plusSeconds(3600),notes="Keep local")
        val localPoint=TrackPoint(coordinate=Coordinate(18-step,-66.0),time=at,accuracy=99.0)
        suspend fun seed() {if(visits) db.history().put(VisitRow(localVisit)) else db.history().put(PointRow(localPoint,"preserved-session"))}
        if(!late) seed()
        val rows=(0..2).joinToString(",") {index->val lat=18+index*step
            if(visits) """{"startTime":"2020-01-01T00:00:00Z","endTime":"2020-01-01T01:00:00Z","visit":{"topCandidate":{"placeLocation":"geo:$lat,-66"}}}"""
            else """{"latLng":"geo:$lat,-66","timestamp":"2020-01-01T00:00:00Z","accuracy":5}"""
        }
        val text=if(visits) "[$rows]" else """{"locations":[$rows]}"""
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            controller.select(input(text))!!.join();val review=controller.state.value.review!!
            assertEquals(if(late) 0 else 1,review.existing);assertEquals(1,review.repeated)
            assertEquals(2,if(visits) review.visits else review.points)
            if(late) seed()
            controller.commit(review.id,HistoryImportPolicy.keepExisting)!!.join()
            assertEquals(if(visits) HistoryImportCounts(visits=1,kept=2) else HistoryImportCounts(points=1,kept=2),controller.state.value.counts)
            if(visits) {
                val added=db.history().rangeVisits(at,at.plusSeconds(7200)).map {it.value}.single {it.id!=localVisit.id}
                assertEquals(Coordinate(18+step,-66.0),added.coordinate);assertEquals(localVisit,db.history().visit(localVisit.id)!!.value)
            } else {
                val added=db.history().points(at,at.plusSeconds(7200)).map {it.value}.single {it.id!=localPoint.id}
                assertEquals(Coordinate(18+step,-66.0),added.coordinate);assertEquals(PointRow(localPoint,"preserved-session"),db.history().point(localPoint.id))
            }
        } finally {controller.close()}
    }
    @Test fun existingHistoryChangesWhichIncomingPointIsAdmitted():Unit=runBlocking {verifyJointAdmission(false,false)}
    @Test fun freshVisitAdmissionCanChooseAPreviouslyRejectedSourceRow():Unit=runBlocking {verifyJointAdmission(true,true)}
    @Test fun freshPointAdmissionCanChooseAPreviouslyRejectedSourceRow():Unit=runBlocking {verifyJointAdmission(false,true)}
    @Test fun chronologyMutationAfterReviewCannotChangeAdmissionOrWriteHistory():Unit=runBlocking {fixture {db,factory,root->
        factory.create().use {workspace->
            workspace.capture("[${device()}]".byteInputStream()) {};workspace.prepare(java.util.Locale.US,java.time.ZoneOffset.UTC) {}
            val target=DatabaseHistoryImportTarget(db);target.inspectPlan(workspace.stage) {};workspace.stage.seal {}
            val file=root.walkTopDown().single {it.name=="records.db"}
            android.database.sqlite.SQLiteDatabase.openDatabase(file.absolutePath,null,android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use {index->index.execSQL("UPDATE google_order SET sequence=1000-sequence")}
            var rejected=false
            try {target.apply(workspace.stage,HistoryImportPolicy.keepExisting) {}} catch(_:IllegalStateException) {rejected=true}
            assertTrue("Corrupt chronology was accepted",rejected);assertEquals(0L,db.history().totalVisitCount());assertEquals(0L,db.history().totalPointCount())
        }
    }}
}
