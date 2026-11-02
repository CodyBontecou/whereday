package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class PointMarkerRepositoryTest {
    private val start=Instant.parse("2026-11-02T10:00:00Z")
    private val range=HistoryRange(start,start.plusSeconds(10000))
    private val viewport=MapPointViewport(0.0,0.0,1.0,1.0,960.0,600.0)
    private fun point(i:Int,outlier:Boolean=false)=TrackPoint("point-%06d".format(i),Coordinate(0.0,0.0),start.plusMillis(i.toLong()/2),5.0,outlier=outlier)
    private suspend fun fixture(block:suspend (HistoryRepository,ReplayRepository)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        val db=HistoryDatabase.create(context,null)
        val root=Files.createTempDirectory(context.cacheDir.toPath(),"synthetic-point-markers-").toFile()
        try {val history=HistoryRepository(db);block(history,ReplayRepository(root,history))} finally {db.close();root.deleteRecursively()}
    }
    @Test fun completeHistoryFindsMarkersAndSelectedMetadataOmittedByTheRouteSample()=runBlocking {fixture {history,_->
        val unique=point(1).copy(coordinate=Coordinate(40.0,60.0))
        history.database.writeTransaction {repeat(5001) {history.dao.put(PointRow(if(it==1) unique else point(it),null))}}
        val route=history.readWindow(range,displayLimit=25)
        assertFalse(route.points.any {it.id==unique.id})
        val selected=point(1001)
        val result=history.readPointMarkers(ReplayRequest.Range(range),viewport,selected.id)
        assertEquals(listOf(unique.id,selected.id),result.points.map {it.id})
        assertEquals(selected,result.selected)
        assertEquals(5001L,history.dao.totalPointCount());assertEquals(route,history.readWindow(range,displayLimit=25))
    }}
    @Test fun rangeFilteringAndOutingAllOutlierFallbackHappenBeforeCellSelection()=runBlocking {fixture {history,_->
        history.database.writeTransaction {
            repeat(3) {history.dao.put(PointRow(point(it,true),"outing"))}
            history.dao.put(SessionRow(RecordingSession("outing",start,start.plusSeconds(1))))
        }
        assertTrue(history.readPointMarkers(ReplayRequest.Range(range),viewport,point(1).id).points.isEmpty())
        assertNull(history.readPointMarkers(ReplayRequest.Range(range),viewport,point(1).id).selected)
        for(request in listOf(ReplayRequest.Range(range,true),ReplayRequest.Outing("outing"))) {
            val result=history.readPointMarkers(request,viewport,point(1).id)
            assertEquals(listOf(point(1,true)),result.points);assertEquals(point(1,true),result.selected)
        }
        history.dao.put(PointRow(point(3),"outing"))
        assertEquals(listOf(point(3)),history.readPointMarkers(ReplayRequest.Outing("outing"),viewport).points)
    }}
    @Test fun inferredScopeExcludesInclusiveExactSessionWindowsBeforeSampling()=runBlocking {fixture {history,_->
        history.database.writeTransaction {
            repeat(8) {history.dao.put(PointRow(point(it),null))}
            history.dao.put(SessionRow(RecordingSession("exact",start.plusMillis(1),start.plusMillis(2))))
        }
        val request=ReplayRequest.Inferred(InferredOutingHeader("inferred",start,start.plusSeconds(1),PointSummary()))
        assertNull(history.readPointMarkers(request,viewport,point(4).id).selected)
        assertEquals(listOf(point(6)),history.readPointMarkers(request,viewport,point(6).id).points)
        try {history.readPointMarkers(ReplayRequest.Outing("deleted"),viewport);fail("Deleted outing")}
        catch(_:IllegalStateException) {}
    }}
    @Test fun offscreenSelectionUsesCurrentStoredMetadataAndInvalidationRefreshesTheBatch()=runBlocking {fixture {history,_->
        val original=point(0).copy(coordinate=Coordinate(60.0,120.0))
        history.dao.put(PointRow(original,null))
        val narrow=MapPointViewport(.45,.45,.1,.1,480.0,300.0)
        val initial=CompletableDeferred<Unit>();val updated=CompletableDeferred<MapPointMarkers>()
        val task=launch {
            history.observePointMarkers(ReplayRequest.Range(range),narrow,original.id).collect {result->
                if(result.selected==original) initial.complete(Unit)
                if(result.selected?.accuracy==42.0) updated.complete(result)
            }
        }
        try {
            withTimeout(10000) {initial.await()}
            val fresh=original.copy(accuracy=42.0);history.dao.put(PointRow(fresh,null))
            val result=withTimeout(10000) {updated.await()}
            assertTrue(result.points.isEmpty());assertEquals(fresh,result.selected)
        } finally {task.cancelAndJoin()}
    }}
    @Test fun replayUsesTheCompletePrefixAndRebuildsForBackwardSeeksAndViewportChanges()=runBlocking {fixture {history,replay->
        val unique=point(1).copy(coordinate=Coordinate(40.0,60.0))
        history.database.writeTransaction {repeat(5001) {history.dao.put(PointRow(if(it==1) unique else point(it),null))}}
        val archive=replay.prepare(ReplayRequest.Range(range),displayLimit=25)
        try {
            assertFalse(archive.frame(5000).visiblePoints.any {it.id==unique.id})
            assertEquals(listOf(point(0)),archive.pointMarkers(viewport,0).points)
            val middle=archive.pointMarkers(viewport,1500,point(1001).id)
            assertEquals(listOf(unique,point(1001)),middle.points);assertEquals(point(1001),middle.selected)
            assertEquals(middle,archive.pointMarkers(viewport,5000,point(1001).id))
            val back=archive.pointMarkers(viewport,500,point(1001).id)
            assertNull(back.selected);assertEquals(listOf(point(0),unique),back.points)
            val narrow=MapPointViewport(.45,.45,.1,.1,480.0,300.0)
            assertEquals(listOf(point(1001)),archive.pointMarkers(narrow,5000,point(1001).id).points)
            assertEquals(5000L,archive.frame(5000).index)
            assertEquals(5001L,history.dao.totalPointCount())
        } finally {archive.close()}
        try {archive.pointMarkers(viewport,0);fail("Closed replay")}
        catch(_:IllegalStateException) {}
    }}
}
