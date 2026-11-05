package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How a stretch of movement was most likely travelled, from average speed alone (iOS TravelMode). */
enum class TravelMode {
    walk, run, bike, drive, transit, fly;
    companion object {
        fun infer(metersPerSecond:Double):TravelMode? {
            if(!metersPerSecond.isFinite() || metersPerSecond<=0.3) return null
            return when {
                metersPerSecond<2.0->walk
                metersPerSecond<3.4->run
                metersPerSecond<8.0->bike
                metersPerSecond<45.0->drive
                metersPerSecond<70.0->transit
                else->fly
            }
        }
        fun infer(meters:Double,seconds:Double):TravelMode? = if(seconds<=0) null else infer(meters/seconds)
    }
}

/** The stable key a place is coloured by: its name when it has one, otherwise the visit itself. */
fun placeKey(visit:Visit):String = visit.name?.trim()?.takeIf {it.isNotEmpty()} ?: visit.id

/** Places take inks in order of first arrival, as on the iOS Today dial (TE.placeInks). */
object PlaceInks {
    fun index(visits:List<Visit>):Map<String,Int> {
        val inks=LinkedHashMap<String,Int>()
        visits.sortedWith(compareBy({it.arrivedAt},{it.id})).forEach {val key=placeKey(it);if(key !in inks) inks[key]=inks.size}
        return inks
    }
}

data class DayMove(val distanceMeters:Double,val duration:Duration,val mode:TravelMode?)
data class DayStay(val visit:Visit,val name:String?,val start:Instant,val end:Instant,val current:Boolean,val ink:Int,val arrival:DayMove?=null) {
    val id:String get()=visit.id
    /** "Since" on the current-place card: when the visit began, even before midnight. */
    val since:Instant get()=visit.arrivedAt
    val duration:Duration get()=Duration.between(start,end)
}
sealed interface DayEntry {
    val id:String
    data class Stay(val stay:DayStay):DayEntry {override val id get()="stay-${stay.id}"}
    data class Move(val move:DayMove,val before:String):DayEntry {override val id get()="move-$before"}
}
/** One stay on the 24-hour dial, as fractions of the day from midnight. */
data class DaySegment(val id:String,val start:Double,val end:Double,val ink:Int)

/** iOS TodayDay: one day's stays clipped to midnight and now, the moves between them, the dial segments and time away. */
data class DayStory(val day:LocalDate,val stays:List<DayStay>,val entries:List<DayEntry>,val segments:List<DaySegment>,val awayDuration:Duration?,val distanceMeters:Double) {
    val currentStay:DayStay? get()=stays.lastOrNull {it.current}
    /** Distinct places, counted by the same key that gives them their ink. */
    val placeCount:Int get()=stays.map {placeKey(it.visit)}.distinct().size

    companion object {
        /** [points] are the day's fixes (outliers are ignored); [distanceMeters] defaults to their path length. */
        fun build(day:LocalDate,zone:ZoneId,visits:List<Visit>,points:List<TrackPoint>,now:Instant,distanceMeters:Double?=null):DayStory {
            val startOfDay=day.atStartOfDay(zone).toInstant()
            val endOfDay=day.plusDays(1).atStartOfDay(zone).toInstant()
            val limit=minOf(endOfDay,maxOf(now,startOfDay))
            val good=points.filterNot {it.outlier}.sortedWith(compareBy({it.time},{it.id}))
            val sorted=visits.filter {it.arrivedAt<limit && (it.departedAt ?: now)>startOfDay}.sortedWith(compareBy({it.arrivedAt},{it.id}))
            val inks=PlaceInks.index(sorted)
            val stays=sorted.mapNotNull {visit->
                val start=maxOf(visit.arrivedAt,startOfDay)
                val end=minOf(visit.departedAt ?: now,limit)
                if(end<=start) null
                else DayStay(visit,visit.name?.trim()?.takeIf {it.isNotEmpty()},start,end,visit.departedAt==null && now<endOfDay,inks.getValue(placeKey(visit)))
            }.toMutableList()
            val entries=mutableListOf<DayEntry>()
            stays.indices.forEach {index->
                if(index>0) {
                    val gapStart=stays[index-1].end;val gapEnd=stays[index].start
                    val gap=Duration.between(gapStart,gapEnd)
                    if(gap.seconds>=60) {
                        val window=good.filter {it.time>=gapStart && it.time<=gapEnd}
                        val distance=pathDistance(window)
                        val move=DayMove(distance,gap,if(distance>0) TravelMode.infer(distance,gap.toMillis()/1000.0) else null)
                        stays[index]=stays[index].copy(arrival=move)
                        entries+=DayEntry.Move(move,stays[index].id)
                    }
                }
                entries+=DayEntry.Stay(stays[index])
            }
            val span=Duration.between(startOfDay,endOfDay).toMillis().toDouble()
            fun fraction(at:Instant)=(Duration.between(startOfDay,at).toMillis()/span).coerceIn(0.0,1.0)
            val segments=stays.map {DaySegment(it.id,fraction(it.start),fraction(it.end),it.ink)}
            // Away runs from the first thing recorded today; without a saved Home it is the time between stays.
            val firstRecorded=listOfNotNull(stays.firstOrNull()?.start,good.firstOrNull {it.time>=startOfDay}?.time).minOrNull()
            val away=firstRecorded?.let {first->
                val elapsed=Duration.between(first,limit).coerceAtLeast(Duration.ZERO)
                (elapsed-stays.fold(Duration.ZERO) {sum,stay->sum+stay.duration}).coerceAtLeast(Duration.ZERO)
            }
            return DayStory(day,stays,entries,segments,away,distanceMeters ?: pathDistance(good.filter {it.time>=startOfDay && it.time<endOfDay}))
        }
        fun pathDistance(points:List<TrackPoint>):Double = points.zipWithNext().sumOf {(a,b)->a.coordinate.distanceTo(b.coordinate)}
        /** Moves read "28 min"; past an hour "1h 05m". */
        fun moveMinutes(duration:Duration):Long = duration.toMinutes().coerceAtLeast(0)
    }
}

private fun Duration.coerceAtLeast(minimum:Duration)=if(this<minimum) minimum else this
