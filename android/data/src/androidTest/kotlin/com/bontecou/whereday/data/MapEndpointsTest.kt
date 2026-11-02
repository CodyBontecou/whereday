package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MapEndpointsTest {
    private val start=Instant.parse("2026-10-27T10:00:00Z")
    private fun point(i:Int,bad:Boolean=false)=TrackPoint("point-%06d".format(i),Coordinate(0.0,i*.000001),start.plusSeconds(i.toLong()),5.0,outlier=bad)
    @Test fun qualityFilteringPrecedesSamplingAndFullEndpointMetadataSurvivesTinyDisplayLimits()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName!="com.bontecou.whereday")
        val db=HistoryDatabase.create(context,null);val repo=HistoryRepository(db)
        try {
            db.writeTransaction {db.history().putPoints((0..5000).map {PointRow(point(it,it==0||it==5000),"exact")});db.history().put(SessionRow(RecordingSession("exact",start,start.plusSeconds(5000))))}
            val range=HistoryRange(start,start.plusSeconds(5001))
            val good=repo.readWindow(range,displayLimit=2,includeOutliers=false)
            assertEquals(listOf(point(1),point(4999)),good.points);assertEquals(5001L,good.summary.count);assertEquals(2L,good.summary.outlierCount)
            assertEquals(point(0,true),good.endpoints.first);assertEquals(point(5000,true),good.endpoints.last)
            assertEquals(point(1) to point(4999),good.endpoints.selected())
            val raw=repo.readWindow(range,displayLimit=2,includeOutliers=true)
            assertEquals(listOf(point(0,true),point(5000,true)),raw.points);assertEquals(good.summary,raw.summary)
            val detail=repo.outingDetail("exact",displayLimit=2)
            assertEquals(good.points,detail.points);assertEquals(good.endpoints,detail.endpoints)
            db.history().deleteSession("exact")
            val inferred=repo.inferredDetail(InferredOutingHeader("inferred",start,start.plusSeconds(5000),good.summary),displayLimit=2)
            assertEquals(good.points,inferred.points);assertEquals(good.endpoints,inferred.endpoints)
        } finally {db.close()}
    }
    @Test fun allOutlierOutingsKeepFallbackWhileMainMapCanHideEveryFix()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName!="com.bontecou.whereday")
        val db=HistoryDatabase.create(context,null);val repo=HistoryRepository(db)
        try {
            val points=(0..3).map {point(it,true)}
            db.writeTransaction {db.history().putPoints(points.map {PointRow(it,"exact")});db.history().put(SessionRow(RecordingSession("exact",start,start.plusSeconds(3))))}
            assertTrue(repo.readWindow(HistoryRange(start,start.plusSeconds(4)),includeOutliers=false).points.isEmpty())
            val detail=repo.outingDetail("exact");assertEquals(points,detail.points);assertNull(detail.endpoints.firstGood)
            assertEquals(points.first() to points.last(),detail.endpoints.selected(fallback=true))
        } finally {db.close()}
    }
    @Test fun outlierPreferenceSurvivesOtherSettingsChangesAndCanBeRestored()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName!="com.bontecou.whereday")
        val preferences=AppPreferences(context);val baseline=preferences.settings.first()
        try {
            preferences.update {it.copy(showOutliers=true)};preferences.update {it.copy(metric=!it.metric)}
            assertTrue(AppPreferences(context).settings.first().showOutliers)
            preferences.update {it.copy(showOutliers=false)};assertFalse(preferences.settings.first().showOutliers)
        } finally {preferences.update {baseline}}
    }
}
