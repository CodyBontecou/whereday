package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant

class SurfaceDistancePersistenceTest {
    @Test fun fullHistorySampledMapAndReplayShareTheCoreLocationRouteContract():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        val routes=instrumentation.context.assets.open("ios-distance-routes.json").bufferedReader().use {JsonParser.parseReader(it).asJsonArray}
        for(entry in routes) {
            val c=entry.asJsonObject;val name=c["name"].asString;val at=Instant.parse("2026-10-28T12:00:00Z")
            val points=c["points"].asJsonArray.mapIndexed {i,p->TrackPoint("point-$i",Coordinate(p.asJsonArray[0].asDouble,p.asJsonArray[1].asDouble),at.plusSeconds(i.toLong()*10),5.0)}
            val db=HistoryDatabase.create(context,null);val history=HistoryRepository(db);val root=File(context.cacheDir,"distance-${java.util.UUID.randomUUID()}")
            try {
                history.dao.putPoints(points.map {PointRow(it,null)})
                val range=HistoryRange(at,points.last().time.plusSeconds(1));val window=history.readWindow(range,displayLimit=2)
                assertEquals(name,points.size.toLong(),window.summary.count);assertEquals(2,window.points.size)
                assertEquals(name,c["expected"].asDouble,window.summary.distanceMeters,.25)
                val replay=ReplayRepository(root,history).prepare(ReplayRequest.Range(range),displayLimit=2)
                try {val end=replay.frame(Long.MAX_VALUE);assertEquals(name,window.summary.distanceMeters,end.distanceMeters,.000001);assertEquals(2,end.visiblePoints.size)} finally {replay.close()}
                val persisted=mutableListOf<TrackPoint>();history.forEachPoint(range) {persisted+=it}
                assertEquals(points,persisted)
            } finally {db.close();root.deleteRecursively()}
        }
        assertEquals(8,routes.size())
    }
}
