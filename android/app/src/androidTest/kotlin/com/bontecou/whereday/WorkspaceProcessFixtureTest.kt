package com.bontecou.whereday

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
import java.time.ZoneId

/** Data-only bookends. A different UID owns the UI driver while the QA process is killed. */
class WorkspaceProcessFixtureTest {
    private fun graph():AppGraph {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName=="com.bontecou.whereday.mapqa")
        check(InstrumentationRegistry.getArguments().getString("allowWorkspaceProcessDeath")=="true")
        return AppGraph.from(instrumentation.targetContext)
    }
    @Test fun seed():Unit=runBlocking {
        val graph=graph();graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,networkGeocoding=false,inferredEnabled=false)}
        val historical=InstrumentationRegistry.getArguments().getString("workspaceProcessScenario")=="timeline"
        val start=LocalDate.now().minusDays(if(historical) 3 else 0).atStartOfDay(ZoneId.systemDefault()).toInstant().plusSeconds(1)
        val end=start.plusSeconds(60)
        graph.history.dao.put(VisitRow(Visit("123e4567-e89b-12d3-a456-426614174091",Coordinate(18.0,-66.0),start,end,
            name="Process fixture visit",notes="Unchanged process fixture note",source=VisitSource.manual,confirmation=Confirmation.confirmed)))
        graph.history.dao.put(SessionRow(RecordingSession("process-fixture-session",start,end,name="Unchanged process fixture outing")))
        graph.history.dao.putPoints(listOf(PointRow(TrackPoint("process-fixture-point",Coordinate(18.0,-66.0),start,5.0),"process-fixture-session")))
        graph.savedPlaces.save(place(),null)
        graph.savedPlaces.save(place().copy(id="process-fixture-delete",name="Process deletion fixture place"),null)
        Unit
    }
    @Test fun verify():Unit=runBlocking {
        val graph=graph()
        val scenario=InstrumentationRegistry.getArguments().getString("workspaceProcessScenario") ?: "visit"
        check(scenario in setOf("visit","place","outing","timeline","calendar","calendar-mode"))
        val visit=checkNotNull(graph.history.dao.visit("123e4567-e89b-12d3-a456-426614174091")).value
        assertEquals(if(scenario=="visit") "Process restored approved" else "Process fixture visit",visit.name)
        assertEquals("Unchanged process fixture note",visit.notes)
        assertEquals(Coordinate(18.0,-66.0),visit.coordinate)
        val session=checkNotNull(graph.history.dao.session("process-fixture-session")).value
        assertEquals(session.startedAt,visit.arrivedAt);assertEquals(session.endedAt,visit.departedAt)
        assertEquals(if(scenario=="outing") "Process outing approved" else "Unchanged process fixture outing",session.name)
        assertEquals(if(scenario=="outing") "Process notes approved" else null,session.notes)
        assertEquals(1L,graph.history.dao.totalPointCount())
        assertEquals(VisitSource.manual,visit.source)
        assertEquals(if(scenario=="place") place().copy(name="Process place approved",address="Process address approved") else place(),graph.history.dao.place(place().id)!!.value)
        if(scenario=="place") assertNull(graph.history.dao.place("process-fixture-delete"))
        else assertEquals(place().copy(id="process-fixture-delete",name="Process deletion fixture place"),graph.history.dao.place("process-fixture-delete")!!.value)
        Unit
    }
    private fun place()=SavedPlace("process-fixture-place","Process fixture place",Coordinate(19.0,-67.0),"Original process address",150.0)
}
