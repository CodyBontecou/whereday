package com.bontecou.whereday

import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.concurrent.CopyOnWriteArrayList

class JournalMapRangeTest {
    private lateinit var graph:AppGraph
    private lateinit var model:JournalViewModel
    private val models=ViewModelStore()
    private val clock=MutableClock(Instant.parse("2026-10-31T18:12:34.125Z"))
    private val zone=ZoneOffset.UTC
    @Before fun setup()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(context);graph.journalClear.awaitReady();graph.journalClear.clear().join()
        model=JournalViewModel(graph,clock,{zone});models.put("journal",model)
    }
    @After fun close() {InstrumentationRegistry.getInstrumentation().runOnMainSync {models.clear()}}
    private suspend fun put(id:String,time:Instant) {
        graph.history.dao.put(PointRow(TrackPoint(id,Coordinate(0.0,0.0),time,5.0),null))
    }
    private suspend fun awaitWindow(predicate:(HistoryWindow)->Boolean)=withTimeout(10_000) {
        model.mapWindow.filterIsInstance<WindowState.Ready>().map {it.window}.first(predicate)
    }
    @Test fun rollingPresetIncludesItsFirstPartialDayAndAdvancesOnRealRoomInvalidation()=runBlocking {
        val now=clock.now
        put("old",now.minus(Duration.ofDays(7)).minusMillis(1))
        put("edge",now.minus(Duration.ofDays(7)))
        put("previously-missing",now.minus(Duration.ofDays(7)).plusSeconds(3600))
        put("now",now);put("future",now.plusSeconds(600))
        model.mapSelection.value=MapSelection("7 days")
        val values=CopyOnWriteArrayList<WindowState>()
        val collector=launch {model.mapWindow.collect {values.add(it)}}
        try {
            val first=awaitWindow {it.summary.count==3L}
            assertEquals(setOf("edge","previously-missing","now"),first.points.map {it.id}.toSet())
            val loadingBefore=values.count {it==WindowState.Loading}
            clock.now=now.plusSeconds(60);put("new-live-fix",clock.now)
            val next=awaitWindow {it.points.any {point->point.id=="new-live-fix"}}
            assertEquals(setOf("previously-missing","now","new-live-fix"),next.points.map {it.id}.toSet())
            assertEquals(clock.now.plusMillis(1),next.range.until)
            assertEquals(loadingBefore,values.count {it==WindowState.Loading})
            assertFalse(next.points.any {it.id=="future"})
        } finally {collector.cancelAndJoin()}
    }
    @Test fun allStopsAtNowWhileExplicitCustomAndOutingWindowsKeepTheirBounds()=runBlocking {
        val now=clock.now;put("past",now.minusSeconds(60));put("future",now.plusSeconds(60))
        model.mapSelection.value=MapSelection("All")
        val collector=launch {model.mapWindow.collect()}
        try {
            assertEquals(listOf("past"),awaitWindow {it.summary.count==1L}.points.map {it.id})
            val day=now.atZone(zone).toLocalDate()
            model.mapSelection.value=MapSelection("Custom",day,day)
            val custom=awaitWindow {it.range==HistoryRange.day(day,zone)}
            assertEquals(2L,custom.summary.count)
            clock.now=now.plusSeconds(300);put("later",clock.now)
            assertEquals(3L,awaitWindow {it.range==custom.range && it.points.any {point->point.id=="later"}}.summary.count)
            val fixed=HistoryRange(now.minusSeconds(120),now.plusSeconds(120))
            model.mapSelection.value=MapSelection("Outing",outingRange=fixed)
            awaitWindow {it.range==fixed}
            clock.now=now.plusSeconds(600);put("backfilled-in-range",now.minusSeconds(30))
            val unchanged=awaitWindow {it.range==fixed && it.points.any {point->point.id=="backfilled-in-range"}}
            assertEquals(3L,unchanged.summary.count);assertEquals(fixed,unchanged.range)
            assertFalse(unchanged.points.any {it.id=="later"})
        } finally {collector.cancelAndJoin()}
    }
    @Test fun periodicRefreshRevealsNewlyCurrentHistoryWithoutADatabaseWrite()=runBlocking {
        val now=clock.now;put("now",now);put("future",now.plusSeconds(60))
        val values=CopyOnWriteArrayList<WindowState>()
        val collector=launch {model.mapWindow.collect {values.add(it)}}
        try {
            awaitWindow {it.summary.count==1L}
            val loadingBefore=values.count {it==WindowState.Loading}
            clock.now=now.plusSeconds(61)
            val next=withTimeout(35_000) {model.mapWindow.filterIsInstance<WindowState.Ready>().map {it.window}.first {it.summary.count==2L}}
            assertEquals(clock.now.plusMillis(1),next.range.until)
            assertEquals(loadingBefore,values.count {it==WindowState.Loading})
        } finally {collector.cancelAndJoin()}
    }
    private class MutableClock(@Volatile var now:Instant):Clock() {
        override fun instant()=now
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone)
    }
}
