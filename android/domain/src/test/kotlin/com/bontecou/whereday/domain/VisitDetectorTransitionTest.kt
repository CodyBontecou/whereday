package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VisitDetectorTransitionTest {
    private val start=Instant.parse("2026-10-29T12:00:00Z")
    private val first=Coordinate(18.0,-66.0)
    private val second=Coordinate(18.01,-66.0)
    private val third=Coordinate(18.02,-66.0)
    private fun point(seconds:Long,where:Coordinate=first)=TrackPoint(coordinate=where,time=start.plusSeconds(seconds),accuracy=5.0)
    private fun open(engine:VisitDetector)=engine.consume(engine.consume(DwellState(),point(0)).state,point(300)).state

    @Test fun adjacentStopKeepsItsFirstSupportingSampleThroughDepartureConfirmation() {
        val engine=VisitDetector()
        val old=open(engine)
        val leaving=engine.consume(old,point(360,second))
        val departed=engine.consume(leaving.state,point(480,second))
        assertEquals(old.visitId,departed.departedId)
        assertEquals(start.plusSeconds(360),departed.departedAt)
        val arrived=engine.consume(departed.state,point(660,second))
        assertNotNull("Five minutes at the new stop must include departure evidence",arrived.arrived)
        assertEquals(start.plusSeconds(360),arrived.arrived!!.arrivedAt)
        assertEquals(second,arrived.arrived.coordinate)
    }

    @Test fun delayedBatchCanConfirmDepartureAndNextArrivalInOneTransition() {
        val engine=VisitDetector()
        val old=open(engine)
        val result=engine.consume(engine.consume(old,point(360,second)).state,point(660,second))
        assertEquals(old.visitId,result.departedId)
        assertEquals(start.plusSeconds(360),result.departedAt)
        assertNotNull(result.arrived)
        assertEquals(start.plusSeconds(360),result.arrived!!.arrivedAt)
        assertEquals(result.arrived.id,result.state.visitId)
        assertNotEquals(old.visitId,result.arrived.id)
    }

    @Test fun movementOutsideOldPlaceDoesNotBackdateAnUnrelatedNextStop() {
        val engine=VisitDetector()
        val departure=engine.consume(engine.consume(open(engine),point(360,second)).state,point(480,third))
        assertEquals(start.plusSeconds(360),departure.departedAt)
        assertNull(engine.consume(departure.state,point(660,third)).arrived)
        val arrived=engine.consume(departure.state,point(780,third))
        assertEquals(start.plusSeconds(480),arrived.arrived!!.arrivedAt)
        assertEquals(third,arrived.arrived.coordinate)
    }

    @Test fun returnToOpenPlaceDiscardsTheExcursionsCandidate() {
        val engine=VisitDetector()
        val excursion=engine.consume(open(engine),point(360,second)).state
        val returned=engine.consume(excursion,point(420)).state
        val left=engine.consume(engine.consume(returned,point(700,second)).state,point(820,second))
        assertEquals(start.plusSeconds(700),left.departedAt)
        assertNull(engine.consume(left.state,point(999,second)).arrived)
        assertEquals(start.plusSeconds(700),engine.consume(left.state,point(1000,second)).arrived!!.arrivedAt)
    }

    @Test fun rejectedSamplesCannotStartOrMoveTheNextStop() {
        val engine=VisitDetector()
        val old=open(engine)
        assertEquals(old,engine.consume(old,point(301,second).copy(outlier=true)).state)
        val leaving=engine.consume(old,point(360,second)).state
        assertEquals(leaving,engine.consume(leaving,point(420,third).copy(accuracy=101.0)).state)
        assertEquals(leaving,engine.consume(leaving,point(359,third)).state)
        val result=engine.consume(leaving,point(660,second))
        assertNotNull(result.arrived)
        assertEquals(start.plusSeconds(360),result.arrived!!.arrivedAt)
    }

    @Test fun samplingGapDoesNotCarryTheDepartureCandidateAcrossMissingPresence() {
        val engine=VisitDetector()
        val old=open(engine)
        val leaving=engine.consume(old,point(360,second)).state
        val afterGap=engine.consume(leaving,point(1800,second))
        assertEquals(old.visitId,afterGap.departedId)
        assertEquals(start.plusSeconds(360),afterGap.departedAt)
        assertNull(afterGap.arrived)
        assertEquals(start.plusSeconds(1800),afterGap.state.candidateSince)
        assertEquals(start.plusSeconds(1800),engine.consume(afterGap.state,point(2100,second)).arrived!!.arrivedAt)
    }
}
