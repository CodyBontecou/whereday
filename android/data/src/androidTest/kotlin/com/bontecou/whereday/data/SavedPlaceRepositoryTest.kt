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
