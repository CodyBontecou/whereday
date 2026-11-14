package com.bontecou.whereday.data

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class JournalItemNotFound:IllegalStateException("That Whereday item could not be found.")
enum class ManualVisitProblem { missingPlace, overlap, noOpenVisit, ambiguousVisit, closedVisit }
class ManualVisitCommandFailed(val problem:ManualVisitProblem):IllegalStateException("The manual visit command could not complete: ${problem.name}.")
class NoCurrentOuting:IllegalStateException("There is no open Whereday outing.")

/** Inclusive event-start bounds match the iOS read intents, rather than map overlap rules. */
data class JournalDateRange(val from:Instant,val through:Instant) {
    init {require(from<=through) {"The start date must not be after the end date."}}
    // Room stores epoch milliseconds. Round inward so sub-millisecond bounds cannot leak a row.
    internal val storageFrom:Instant get()=Instant.ofEpochMilli(from.toEpochMilli()).let {
        if(it<from) it.plusMillis(1) else it
    }
    internal val storageThrough:Instant get()=Instant.ofEpochMilli(through.toEpochMilli())
    companion object {
        fun day(date:LocalDate,zone:ZoneId)=JournalDateRange(
            date.atStartOfDay(zone).toInstant(),date.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1))
    }
}

data class JournalStatistics(val points:PointSummary,val visitCount:Long)

/** Internal, account-free journal command seam. No exported Android component or permission bypass.
 * Each streaming query keeps one committed WAL snapshot and retains at most 500 entities per page.
 * Consumers must not start a write transaction inside the callback (collect results first if editing).
 */
class JournalCommands(private val history:HistoryRepository,private val clock:Clock=Clock.systemUTC()) {
    fun today(zone:ZoneId)=JournalDateRange.day(LocalDate.now(clock.withZone(zone)),zone)

    suspend fun currentOuting():RecordingSession = history.database.readSnapshot {dao ->
        dao.latestOpenSession()?.value ?: throw NoCurrentOuting()
    }

    suspend fun forEachVisit(range:JournalDateRange,consume:suspend (Visit)->Unit) {
        history.database.readSnapshot {dao ->
            var at:Instant?=null;var id=""
            while(true) {
                currentCoroutineContext().ensureActive()
                val page=dao.commandVisits(range.storageFrom,range.storageThrough,at,id)
                if(page.isEmpty()) break
                for(row in page) {currentCoroutineContext().ensureActive();consume(row.value)}
                at=page.last().value.arrivedAt;id=page.last().value.id
            }
        }
    }

    /** Exact stored movements only; inferred outings have no mutable stored identity. */
    suspend fun forEachOuting(range:JournalDateRange,consume:suspend (RecordingSession)->Unit) {
        history.database.readSnapshot {dao ->
            var at:Instant?=null;var id=""
            while(true) {
                currentCoroutineContext().ensureActive()
                val page=dao.commandOutings(range.storageFrom,range.storageThrough,at,id)
                if(page.isEmpty()) break
                for(row in page) {currentCoroutineContext().ensureActive();consume(row.value)}
                at=page.last().value.startedAt;id=page.last().value.id
            }
        }
    }

    suspend fun statistics(range:JournalDateRange):JournalStatistics=history.database.readSnapshot {dao ->
        val summary=PointSummaryBuilder()
        var page=dao.exportFirstPoints(range.storageFrom,range.storageThrough,null)
        while(page.isNotEmpty()) {
            for(row in page) {currentCoroutineContext().ensureActive();summary.add(row.value)}
            val last=page.last().value
            page=dao.exportNextPoints(last.time,last.id,range.storageThrough,null)
        }
        JournalStatistics(summary.build(),dao.commandVisitCount(range.storageFrom,range.storageThrough))
    }

    suspend fun forEachSavedPlace(consume:suspend (SavedPlace)->Unit) = history.database.readSnapshot {dao->
        var name:String?=null;var id=""
        while(true) {
            currentCoroutineContext().ensureActive()
            val page=dao.commandSavedPlaces(name,id);if(page.isEmpty()) break
            for(row in page) {currentCoroutineContext().ensureActive();consume(row.value)}
            name=page.last().value.name;id=page.last().value.id
        }
    }
    suspend fun forEachOpenManualVisit(consume:suspend (Visit)->Unit) = history.database.readSnapshot {dao->
        var at:Instant?=null;var id=""
        while(true) {
            currentCoroutineContext().ensureActive()
            val page=dao.commandOpenManualVisits(at,id);if(page.isEmpty()) break
            for(row in page) {currentCoroutineContext().ensureActive();consume(row.value)}
            at=page.last().value.arrivedAt;id=page.last().value.id
        }
    }
    suspend fun checkIn(name:String?,savedPlaceId:String?,coordinate:Coordinate?,arrivedAt:Instant,allowOverlap:Boolean=false)=
        history.checkIn(name,savedPlaceId,coordinate,arrivedAt,allowOverlap)
    suspend fun checkOut(id:String?,departedAt:Instant?=null)=history.checkOut(id,departedAt)

    suspend fun renameVisit(id:String,name:String):Visit=history.renameVisit(id,name)
    suspend fun renameOuting(id:String,name:String):RecordingSession=history.renameOuting(id,name)
    suspend fun renameCurrentOuting(name:String):RecordingSession=history.renameOuting(null,name)

    /** Native editor also supports clearing a name and changing notes; automation rename does not. */
    suspend fun editOuting(id:String,name:String?,notes:String?)=history.editSession(id,name,notes)
}
