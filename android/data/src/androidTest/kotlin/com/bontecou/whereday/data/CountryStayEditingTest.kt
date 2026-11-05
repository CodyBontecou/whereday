package com.bontecou.whereday.data

import androidx.room.useWriterConnection
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class CountryStayEditingTest {
    private val now=Instant.parse("2026-10-29T00:30:00Z")
    private val day=LocalDate.of(2026,10,28)
    private val original=CountryStay("manual","PR",day,day)
    private suspend fun fixture(block:suspend (HistoryDatabase,HistoryRepository)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        val db=HistoryDatabase.create(context,null)
        val visit=Visit("kept",Coordinate(18.0,-66.0),now.minusSeconds(3600),name="Kept journal")
        db.history().put(VisitRow(visit))
        try {block(db,HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC),{ZoneOffset.UTC}));assertEquals(visit,db.history().visit(visit.id)!!.value)} finally {db.close()}
    }
    @Test fun createEditAndExactRetriesKeepOneStableIdentity()=runBlocking {fixture {db,repo->
        repo.saveCountryStay(original);repo.saveCountryStay(original)
        val changed=original.copy(countryCode="ES",firstDay=day.minusDays(2))
        repo.saveCountryStay(changed,original);repo.saveCountryStay(changed,original)
        assertEquals(listOf(changed),db.history().stays().map {it.value})
    }}
    @Test fun concurrentEditsAndDeletesCannotOverwriteTheLatestStay()=runBlocking {fixture {db,repo->
        repo.saveCountryStay(original)
        val latest=original.copy(countryCode="CA");db.history().put(StayRow(latest))
        try {repo.saveCountryStay(original.copy(countryCode="ES"),original);fail("Conflict expected")} catch(_:CountryStayChanged) {}
        try {repo.deleteCountryStay(original);fail("Conflict expected")} catch(_:CountryStayChanged) {}
        assertEquals(latest,db.history().stay(original.id)!!.value)
    }}
    @Test fun deletionDoesNotResurrectAndIdCollisionIsRejected()=runBlocking {fixture {db,repo->
        repo.saveCountryStay(original);repo.deleteCountryStay(original);repo.deleteCountryStay(original)
        try {repo.saveCountryStay(original.copy(countryCode="ES"),original);fail("Conflict expected")} catch(_:CountryStayChanged) {}
        repo.saveCountryStay(original)
        try {repo.saveCountryStay(original.copy(countryCode="CA"));fail("Collision expected")} catch(_:CountryStayChanged) {}
        assertEquals(original,db.history().stay(original.id)!!.value)
    }}
    @Test fun civilDateLimitUsesTheCurrentLocalZoneRatherThanUtc()=runBlocking {fixture {db,_->
        val todayUtc=CountryStay("date-boundary","PR",day.plusDays(1),day.plusDays(1))
        val west=HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC),{ZoneId.of("America/New_York")})
        try {west.saveCountryStay(todayUtc);fail("Local future expected")} catch(_:CountryStayFuture) {}
        assertNull(db.history().stay(todayUtc.id))
        HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC),{ZoneId.of("Pacific/Kiritimati")}).saveCountryStay(todayUtc)
        assertEquals(todayUtc,db.history().stay(todayUtc.id)!!.value)
    }}
    @Test fun futureCreationAndEditAreRejectedWithoutChangingExistingStay()=runBlocking {fixture {db,repo->
        repo.saveCountryStay(original)
        try {repo.saveCountryStay(original.copy(id="future",lastDay=day.plusDays(2)));fail("Future expected")} catch(_:CountryStayFuture) {}
        try {repo.saveCountryStay(original.copy(lastDay=day.plusDays(2)),original);fail("Future expected")} catch(_:CountryStayFuture) {}
        assertEquals(listOf(original),db.history().stays().map {it.value})
    }}
    @Test fun writeFailureAndCanceledCommandLeaveTheSnapshotIntact()=runBlocking {fixture {db,repo->
        repo.saveCountryStay(original)
        db.useWriterConnection {it.usePrepared("CREATE TRIGGER fail_stay BEFORE UPDATE ON country_stays BEGIN SELECT RAISE(ABORT,'synthetic stay failure'); END") {it.step()}}
        try {repo.saveCountryStay(original.copy(countryCode="ES"),original);fail("Failure expected")} catch(_:androidx.sqlite.SQLiteException) {}
        assertEquals(original,db.history().stay(original.id)!!.value)
        val job=launch(start=CoroutineStart.LAZY) {repo.deleteCountryStay(original)};job.cancel();job.join()
        assertEquals(original,db.history().stay(original.id)!!.value)
    }}
    @Test fun deletingManualDatesKeepsOtherStaysAndRecordedPresence()=runBlocking {fixture {db,repo->
        repo.saveCountryStay(original);val other=original.copy(id="other",countryCode="CA");repo.saveCountryStay(other)
        repo.deleteCountryStay(original)
        assertEquals(listOf(other),db.history().stays().map {it.value});assertNotNull(db.history().visit("kept"))
    }}
}
