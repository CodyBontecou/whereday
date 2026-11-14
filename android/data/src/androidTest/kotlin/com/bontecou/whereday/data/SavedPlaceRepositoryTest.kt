package com.bontecou.whereday.data

import androidx.room.useWriterConnection
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SavedPlaceRepositoryTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    private suspend fun fixture(block:suspend (HistoryDatabase,SavedPlaceRepository)->Unit) {
        check(context.packageName!="com.bontecou.whereday")
        val name="places-${UUID.randomUUID()}.db";val db=HistoryDatabase.create(context,name)
        try {
            db.history().put(PointRow(TrackPoint(id="journal-point",coordinate=Coordinate(0.0,0.0),time=Instant.EPOCH,accuracy=5.0),null))
            block(db,SavedPlaceRepository(db))
            assertEquals(1L,db.history().totalPointCount())
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun sharedEllipsoidAndUnicodePlannerMatchEighteenActualIosMergeCases()=runBlocking {
        fixture {_,repository ->
            val cases=instrumentation.context.assets.open("ios-saved-place-plans.json").bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
            assertEquals(18,cases.size())
            for(value in cases) {
                val c=value.asJsonObject
                fun places(key:String)=c[key].asJsonArray.map {it.asJsonObject.let {row ->SavedPlace(row["id"].asString,row["name"].asString,Coordinate(row["latitude"].asDouble,row["longitude"].asDouble),row["address"].asString,row["radius"].asDouble)}}
                val plan=repository.preview(places("incoming"),places("existing"),SavedPlaceDuplicatePolicy.valueOf(c["policy"].asString))
                assertEquals(c["name"].asString,c["entries"].asJsonArray.map {it.asJsonObject["targetId"].asString},plan.entries.map {it.targetId})
                assertEquals(c["name"].asString,c["entries"].asJsonArray.map {it.asJsonObject["action"].asString},plan.entries.map {it.action.name})
                assertEquals(c["name"].asString,c["summary"].asJsonObject.let {SavedPlaceImportSummary(it["added"].asInt,it["updated"].asInt,it["skipped"].asInt)},plan.summary)
            }
            assertTrue(repository.distance(Coordinate(0.0,0.0),Coordinate(.00135,0.0))<150)
            assertEquals(repository.distance(Coordinate(0.0,0.0),Coordinate(.00135,0.0)),Coordinate(0.0,0.0).distanceTo(Coordinate(.00135,0.0)),.000000001)
        }
    }
    @Test fun keepExistingPreviewAndCommitAgreeAndSkipWithinFileDuplicates()=runBlocking {
        fixture {db,repository ->
            val home=SavedPlace("home","Home",Coordinate(38.0,-9.0),"Original")
            repository.save(home,null)
            val incoming=listOf(SavedPlace("one"," home ",Coordinate(38.0001,-9.0),"Ignored"),SavedPlace("two","Park",Coordinate(38.5,-9.0),"First"),SavedPlace("three","park",Coordinate(38.5001,-9.0),"Ignored"))
            val plan=repository.preview(incoming,listOf(home),SavedPlaceDuplicatePolicy.keepExisting)
            assertEquals(plan.summary,repository.import(incoming,listOf(home),SavedPlaceDuplicatePolicy.keepExisting))
            assertEquals(SavedPlaceImportSummary(1,0,2),plan.summary)
            assertEquals(home,db.history().place("home")!!.value);assertEquals("First",db.history().place("two")!!.value.address)
            assertEquals(2,db.history().places().size)
        }
    }
    @Test fun sequentialUpdatesPreserveIdentityAndRejectAStaleImportSnapshot()=runBlocking {
        fixture {db,repository ->
            val old=SavedPlace("home","Home",Coordinate(38.0,-9.0),"Original");repository.save(old,null)
            val incoming=listOf(SavedPlace("one","home",Coordinate(38.0001,-9.0),"First",200.0),SavedPlace("two","HOME",Coordinate(38.0002,-9.0),"Last",250.0))
            val expected=repository.preview(incoming,listOf(old),SavedPlaceDuplicatePolicy.updateExisting)
            assertEquals(expected.summary,repository.import(incoming,listOf(old),SavedPlaceDuplicatePolicy.updateExisting))
            assertEquals(SavedPlaceImportSummary(0,2,0),expected.summary)
            val updated=db.history().place("home")!!.value;assertEquals("HOME",updated.name);assertEquals("Last",updated.address);assertEquals(250.0,updated.radius,0.0)
            try {repository.import(incoming,listOf(old),SavedPlaceDuplicatePolicy.keepExisting);fail()} catch(_:SavedPlacesChanged) {}
            assertEquals(updated,db.history().place("home")!!.value)
        }
    }
    @Test fun csvUpdatesKeepTheMatchedPlacesKindAndNote()=runBlocking {
        fixture {db,repository ->
            val home=SavedPlace("home","Home",Coordinate(38.0,-9.0),"Original",kind=SavedPlaceKind.home,notes="Blue door");repository.save(home,null)
            val incoming=listOf(SavedPlace("one","home",Coordinate(38.0001,-9.0),"Updated",200.0))
            repository.import(incoming,listOf(home),SavedPlaceDuplicatePolicy.updateExisting)
            assertEquals(home.copy(name="home",coordinate=Coordinate(38.0001,-9.0),address="Updated",radius=200.0),db.history().place("home")!!.value)
        }
    }
    @Test fun mergeFoldsVisitsKindAndNoteIntoTheKeptPlaceAndRefusesAStalePlan()=runBlocking {
        fixture {db,repository ->
            val kept=SavedPlace("kept","Cafe Luna",Coordinate(40.70,-74.00),radius=100.0,notes="Window seat")
            val removed=SavedPlace("removed","Studio",Coordinate(40.7005,-74.00),"12 Mill St",150.0,SavedPlaceKind.work,"Back door")
            repository.save(kept,null);repository.save(removed,null)
            val inside=Visit("inside",Coordinate(40.7005,-74.00),Instant.ofEpochSecond(1000),Instant.ofEpochSecond(2000),name="Studio",placeSource="detected")
            val elsewhere=Visit("elsewhere",Coordinate(10.0,10.0),Instant.ofEpochSecond(3000),Instant.ofEpochSecond(4000),name="Studio",placeSource="detected")
            db.history().put(VisitRow(inside));db.history().put(VisitRow(elsewhere))
            val plan=repository.mergePlan(kept,removed)
            assertEquals(setOf("inside"),plan.renamedVisitIds)
            // A visit renamed by hand after planning changes the plan, so the old one is refused.
            db.history().put(VisitRow(elsewhere.copy(placeSource="userEntered")))
            try {repository.merge(plan,kept,removed);fail()} catch(_:SavedPlacesChanged) {}
            assertEquals(2,db.history().places().size)
            val fresh=repository.mergePlan(kept,removed)
            val now=Instant.ofEpochSecond(9000)
            repository.merge(fresh,kept,removed,now)
            assertNull(db.history().place("removed"))
            assertEquals(SavedPlaceMergePlan.merged(fresh,kept,removed),db.history().place("kept")!!.value)
            assertEquals("Window seat\n\nBack door",db.history().place("kept")!!.value.notes)
            assertEquals(SavedPlaceKind.work,db.history().place("kept")!!.value.kind)
            assertEquals("Cafe Luna",db.history().visit("inside")!!.value.name);assertEquals(now,db.history().visit("inside")!!.value.updatedAt)
            assertEquals("Cafe Luna",db.history().visit("elsewhere")!!.value.name)
            try {repository.merge(fresh,kept,removed);fail()} catch(_:SavedPlacesChanged) {}
        }
    }
    @Test fun sqliteFailureRollsBackEarlierImportsAndExistingUpdates()=runBlocking {
        fixture {db,repository ->
            val old=SavedPlace("old","Home",Coordinate(0.0,0.0),"Original");repository.save(old,null)
            db.useWriterConnection {connection ->connection.usePrepared("CREATE TRIGGER synthetic_failure BEFORE INSERT ON places WHEN new.value_name='Fail' BEGIN SELECT RAISE(ABORT,'synthetic place failure'); END") {it.step()}}
            val incoming=listOf(SavedPlace("one","Home",Coordinate(0.0,0.0),"Changed"),SavedPlace("two","Park",Coordinate(1.0,1.0)),SavedPlace("three","Fail",Coordinate(2.0,2.0)))
            try {repository.import(incoming,listOf(old),SavedPlaceDuplicatePolicy.updateExisting);fail()} catch(_:Exception) {}
            assertEquals(listOf(old),db.history().places().map {it.value})
        }
    }
    @Test fun editorRetriesAreIdempotentAndStaleSaveOrDeleteCannotOverwriteAnotherEdit()=runBlocking {
        fixture {db,repository ->
            val original=SavedPlace("one","Home",Coordinate(0.0,0.0));repository.save(original,null);repository.save(original,null)
            assertEquals(1,db.history().places().size)
            val edited=original.copy(name="Updated",radius=500.0);repository.save(edited,original)
            try {repository.save(original.copy(name="Stale"),original);fail()} catch(_:SavedPlacesChanged) {}
            try {repository.delete(original);fail()} catch(_:SavedPlacesChanged) {}
            assertEquals(edited,db.history().place(original.id)!!.value)
            repository.delete(edited);assertTrue(db.history().places().isEmpty())
        }
    }
    @Test fun aReusedIncomingIdentityCannotReplaceAnUnrelatedPlace()=runBlocking {
        fixture {db,repository ->
            val original=SavedPlace("one","Original",Coordinate(0.0,0.0));repository.save(original,null)
            val incoming=listOf(SavedPlace("new","First",Coordinate(2.0,2.0)),SavedPlace("one","Different",Coordinate(3.0,3.0)))
            try {repository.import(incoming,listOf(original),SavedPlaceDuplicatePolicy.keepExisting);fail()} catch(_:SavedPlacesChanged) {}
            assertEquals(listOf(original),db.history().places().map {it.value})
        }
    }
}
