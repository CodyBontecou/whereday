package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/**
 * Mirrors iOS ManualVisitStore.overlapCheck / createManualVisitTrimmingOverlaps. iOS has no dedicated unit test
 * for these rules, so each case below pins one branch of the Swift implementation.
 */
class VisitOverlapsTest {
    private val t0=Instant.ofEpochSecond(1_700_000_000)
    private fun at(minutes:Long):Instant=t0.plusSeconds(minutes*60)
    private val now=at(24*60)
    private val here=Coordinate(37.7750,-122.4194)
    private fun visit(id:String,from:Long,until:Long?,source:VisitSource=VisitSource.automatic)=
        Visit(id,here,at(from),until?.let(::at),name=id,source=source,departureInferred=true)
    private fun check(stored:List<Visit>,from:Long,until:Long?)=VisitOverlaps.check(stored,at(from),until?.let(::at),now=now)
    private fun trims(result:VisitOverlapCheck)=(result as VisitOverlapCheck.Trims).resolutions

    @Test fun noStoredStaysOrDisjointStaysNeedNothing() {
        assertEquals(VisitOverlapCheck.None,check(emptyList(),60,120))
        assertEquals(VisitOverlapCheck.None,check(listOf(visit("a",0,30),visit("b",200,300)),60,120))
    }

    @Test fun adjacentStaysSharingABoundaryDoNotOverlap() {
        assertEquals(VisitOverlapCheck.None,check(listOf(visit("before",0,60),visit("after",120,180)),60,120))
    }

    @Test fun earlierStayEndsAtTheNewArrival() {
        val office=visit("office",0,75)
        val resolution=trims(check(listOf(office),60,120)).single()
        assertEquals(VisitOverlapResolution.Change.EndsAt(at(60)),resolution.change)
        assertEquals(Duration.ofMinutes(15),resolution.overlap)
        assertEquals(15L,VisitOverlaps.noticeMinutes(resolution))
        assertEquals(office.copy(departedAt=at(60),departureInferred=false,updatedAt=now),resolution.trimmed(now))
    }

    @Test fun laterStayStartsAtTheNewDeparture() {
        val cafe=visit("cafe",100,180)
        val resolution=trims(check(listOf(cafe),60,120)).single()
        assertEquals(VisitOverlapResolution.Change.StartsAt(at(120)),resolution.change)
        assertEquals(Duration.ofMinutes(20),resolution.overlap)
        // Only the start moves; the inferred-departure flag belongs to the untouched departure.
        assertEquals(cafe.copy(arrivedAt=at(120),updatedAt=now),resolution.trimmed(now))
    }

    @Test fun stopBetweenTwoStaysTrimsBoth() {
        val result=trims(check(listOf(visit("later",100,180),visit("earlier",0,75)),60,120))
        assertEquals(listOf("earlier","later"),result.map {it.visit.id})
        assertEquals(listOf(VisitOverlapResolution.Change.EndsAt(at(60)),VisitOverlapResolution.Change.StartsAt(at(120))),result.map {it.change})
    }

    @Test fun stopInsideALongerStayEndsThatStayAtTheArrival() {
        // iOS never splits the enclosing stay: the earlier-arriving branch wins, so it ends at the stop's arrival.
        val resolution=trims(check(listOf(visit("home",0,300)),60,120)).single()
        assertEquals(VisitOverlapResolution.Change.EndsAt(at(60)),resolution.change)
        assertEquals(Duration.ofMinutes(60),resolution.overlap)
    }

    @Test fun stayInsideTheStopIsAConflict() {
        val inside=visit("inside",70,100)
        assertEquals(VisitOverlapCheck.Conflict(inside),check(listOf(visit("earlier",0,75),inside),60,120))
    }

    @Test fun stayArrivingWithTheStopAndEndingFirstIsAConflict() {
        val sameStart=visit("same",60,90)
        assertEquals(VisitOverlapCheck.Conflict(sameStart),check(listOf(sameStart),60,120))
    }

    @Test fun stayArrivingWithTheStopAndOutlastingItStartsAtTheDeparture() {
        val resolution=trims(check(listOf(visit("same",60,180)),60,120)).single()
        assertEquals(VisitOverlapResolution.Change.StartsAt(at(120)),resolution.change)
    }

    @Test fun identicalTimesAreAConflict() {
        val twin=visit("twin",60,120)
        assertEquals(VisitOverlapCheck.Conflict(twin),check(listOf(twin),60,120))
    }

    @Test fun identicalZeroLengthRecordIsAConflictButAdjacentZeroLengthIsNot() {
        val point=visit("point",60,60)
        assertEquals(VisitOverlapCheck.Conflict(point),check(listOf(point),60,60))
        assertEquals(VisitOverlapCheck.None,check(listOf(visit("edge",120,120)),60,120))
    }

    @Test fun openStayStartedEarlierEndsAtTheArrivalAndMeasuresAgainstNow() {
        val ongoing=visit("ongoing",0,null)
        val past=trims(check(listOf(ongoing),60,120)).single()
        assertEquals(VisitOverlapResolution.Change.EndsAt(at(60)),past.change)
        assertEquals(Duration.ofMinutes(60),past.overlap)
        assertEquals(ongoing.copy(departedAt=at(60),departureInferred=false,updatedAt=now),past.trimmed(now))

        // A current (open) stop over an open stay: both run to now.
        val current=trims(check(listOf(ongoing),60,null)).single()
        assertEquals(VisitOverlapResolution.Change.EndsAt(at(60)),current.change)
        assertEquals(Duration.between(at(60),now),current.overlap)
    }

    @Test fun openStayStartingInsideAClosedStopStartsAtItsDeparture() {
        val resolution=trims(check(listOf(visit("ongoing",90,null)),60,120)).single()
        assertEquals(VisitOverlapResolution.Change.StartsAt(at(120)),resolution.change)
        assertEquals(Duration.ofMinutes(30),resolution.overlap)
    }

    @Test fun openStopOverALaterStayIsAConflict() {
        val later=visit("later",90,150)
        assertEquals(VisitOverlapCheck.Conflict(later),check(listOf(later),60,null))
        val laterOpen=visit("laterOpen",90,null)
        assertEquals(VisitOverlapCheck.Conflict(laterOpen),check(listOf(laterOpen),60,null))
    }

    @Test fun openStopOverAnOpenStayArrivingTogetherIsAConflict() {
        val same=visit("same",60,null)
        assertEquals(VisitOverlapCheck.Conflict(same),check(listOf(same),60,null))
    }

    @Test fun twoStaysEndingAtTheArrivalConflictOnTheSecond() {
        val first=visit("first",0,90);val second=visit("second",30,100)
        assertEquals(VisitOverlapCheck.Conflict(second),check(listOf(second,first),60,120))
    }

    @Test fun twoStaysStartingAtTheDepartureConflictOnTheFirst() {
        val first=visit("first",100,150);val second=visit("second",110,200)
        assertEquals(VisitOverlapCheck.Conflict(first),check(listOf(second,first),60,120))
    }

    @Test fun manualAndRecordedStaysAreTrimmedAlike() {
        val manual=visit("manual",0,75,VisitSource.manual);val recorded=visit("recorded",100,180,VisitSource.automatic)
        val result=trims(check(listOf(manual,recorded),60,120))
        assertEquals(listOf(VisitSource.manual,VisitSource.automatic),result.map {it.trimmed(now).source})
        assertEquals(at(60),result[0].trimmed(now).departedAt);assertEquals(at(120),result[1].trimmed(now).arrivedAt)
    }

    @Test fun excludedIdentityAndInvalidTimesAreIgnored() {
        assertEquals(VisitOverlapCheck.None,VisitOverlaps.check(listOf(visit("self",0,300)),at(60),at(120),excludingId="self",now=now))
        assertEquals(VisitOverlapCheck.None,check(listOf(visit("a",0,300)),120,60))
    }

    @Test fun noticeMinutesRoundAndNeverShowZero() {
        val brief=trims(check(listOf(visit("brief",0,60)),59,120)).single().copy(overlap=Duration.ofSeconds(10))
        assertEquals(1L,VisitOverlaps.noticeMinutes(brief))
        assertEquals(2L,VisitOverlaps.noticeMinutes(brief.copy(overlap=Duration.ofSeconds(90))))
        assertEquals(1L,VisitOverlaps.noticeMinutes(brief.copy(overlap=Duration.ofSeconds(89))))
    }
}
