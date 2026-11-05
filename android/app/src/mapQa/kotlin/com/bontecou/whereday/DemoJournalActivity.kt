package com.bontecou.whereday

import androidx.lifecycle.lifecycleScope
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/** QA-only (mapQa) demo journal, like iOS's ScreenshotFeatureDemoSeeder: replaces this disposable package's history with a
 *  synthetic San Francisco week, then opens the app. `adb shell am start -n com.bontecou.whereday.mapqa/com.bontecou.whereday.DemoJournalActivity`
 *  Extras: `online` (boolean, street tiles, default true), `suggestions` (boolean), `clear` (boolean, only clear). */
class DemoJournalActivity:androidx.activity.ComponentActivity() {
    override fun onCreate(state:android.os.Bundle?) {
        super.onCreate(state)
        check(packageName.endsWith(".mapqa")) {"Demo data is for the disposable QA package only"}
        val graph=AppGraph.from(this)
        lifecycleScope.launch {
            graph.journalClear.clear().join()
            if(!intent.getBooleanExtra("clear",false)) withContext(Dispatchers.IO) {seed(graph)}
            graph.preferences.update {it.copy(onboarded=true,accessGateCleared=true,networkGeocoding=false,inferredEnabled=false,
                map=MapLayerSettings(online=intent.getBooleanExtra("online",true),visitSuggestions=intent.getBooleanExtra("suggestions",false)))}
            startActivity(android.content.Intent(this@DemoJournalActivity,MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
    }

    private data class Stop(val name:String,val coordinate:Coordinate)
    private val home=Stop("Home",Coordinate(37.7449,-122.4156))
    private val ferry=Stop("Ferry Building",Coordinate(37.7955,-122.3937))
    private val office=Stop("Mission Office",Coordinate(37.7599,-122.4148))
    private val tartine=Stop("Tartine Manufactory",Coordinate(37.7618,-122.4115))
    private val park=Stop("Dolores Park",Coordinate(37.7596,-122.4269))
    private val market=Stop("Bi-Rite Market",Coordinate(37.7616,-122.4256))

    private suspend fun seed(graph:AppGraph) {
        val zone=ZoneId.systemDefault()
        val now=Instant.now()
        val dao=graph.history.dao
        listOf(home,ferry,office,tartine).forEach {dao.put(PlaceRow(SavedPlace(name=it.name,coordinate=it.coordinate,radius=120.0)))}
        var serial=0
        fun id(prefix:String)="demo-$prefix-${serial++}"
        // Today, scaled so the current stay at the Mission Office began about half an hour ago.
        val today=LocalDate.now(zone).atStartOfDay(zone).toInstant()
        val span=Duration.between(today,now.minus(Duration.ofMinutes(30))).toMillis().coerceAtLeast(3_600_000)
        fun at(hour:Double)=today.plusMillis((span*hour/13.83).toLong())
        val plan=listOf(home to (0.0 to 8.2),ferry to (8.67 to 9.42),office to (9.83 to 12.5),tartine to (12.75 to 13.58),office to (13.83 to Double.NaN))
        day(graph,plan.map {(stop,hours)->Triple(stop,at(hours.first),if(hours.second.isNaN()) null else at(hours.second))},::id)
        // The six days before: a commute, lunch and a park or market stop, with a little variety.
        for(back in 1..6) {
            val start=LocalDate.now(zone).minusDays(back.toLong()).atStartOfDay(zone).toInstant()
            fun h(hour:Double)=start.plusSeconds((hour*3600).toLong())
            val extra=if(back%2==0) park else market
            val stops=mutableListOf(Triple(home,h(0.0),h(8.0+back*0.1)),Triple(office,h(8.8),h(12.2)),Triple(if(back%3==0) ferry else tartine,h(12.5),h(13.4)),
                Triple(office,h(13.7),h(17.5)),Triple(extra,h(17.9),h(18.8)),Triple(home,h(19.3),h(23.99)))
            if(back==5) stops.removeAt(4)
            day(graph,stops,::id)
        }
    }

    private suspend fun day(graph:AppGraph,stops:List<Triple<Stop,Instant,Instant?>>,id:(String)->String) {
        val dao=graph.history.dao
        val points=mutableListOf<TrackPoint>()
        stops.forEachIndexed {index,(stop,from,until)->
            dao.put(VisitRow(Visit(id("visit"),stop.coordinate,from,until,name=stop.name,source=VisitSource.automatic,
                confirmation=if(index%3==1) Confirmation.unconfirmed else Confirmation.confirmed)))
            // A few quiet fixes while staying.
            val end=until ?: Instant.now()
            var t=from;var k=0
            while(t<end) {points+=TrackPoint(id("stay"),jitter(stop.coordinate,k++,0.00008),t,8.0);t=t.plus(Duration.ofMinutes(20))}
            val next=stops.getOrNull(index+1) ?: return@forEachIndexed
            val leave=until ?: return@forEachIndexed
            val travel=Duration.between(leave,next.second).seconds.coerceAtLeast(60)
            val steps=(travel/20).toInt().coerceIn(8,240)
            // An L-shaped route along the street grid, so legs read like walks and rides.
            val corner=Coordinate(stop.coordinate.latitude,next.first.coordinate.longitude)
            for(i in 0..steps) {
                val f=i.toDouble()/steps
                val c=if(f<0.5) lerp(stop.coordinate,corner,f*2) else lerp(corner,next.first.coordinate,(f-0.5)*2)
                points+=TrackPoint(id("move"),jitter(c,i,0.00004),leave.plusSeconds((travel*f).toLong()),6.0,speed=null)
            }
        }
        dao.putPoints(points.sortedBy {it.time}.map {PointRow(it,null)})
    }
    private fun lerp(a:Coordinate,b:Coordinate,f:Double)=Coordinate(a.latitude+(b.latitude-a.latitude)*f,a.longitude+(b.longitude-a.longitude)*f)
    private fun jitter(c:Coordinate,i:Int,size:Double)=Coordinate(c.latitude+sin(i*1.7)*size,c.longitude+cos(i*2.3)*size)
}
