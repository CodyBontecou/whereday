package com.bontecou.whereday.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day of a multi-day range: its distance, how many stays and places, and a capped path for the faint ink lines. */
data class MapRangeDay(val day:LocalDate,val distanceMeters:Double,val visitCount:Int,val placeCount:Int,val pointCount:Long)
/** A place inside the range, drawn as a count circle: [days] is how many days in the range it was visited. */
data class MapRangePlace(val key:String,val name:String?,val coordinate:Coordinate,val days:Int,val visits:Int,val ink:Int,val visit:Visit)
data class MapRangeSummary(val days:List<MapRangeDay>,val places:List<MapRangePlace>) {
    val distanceMeters:Double get()=days.sumOf {it.distanceMeters}
    val visitCount:Int get()=days.sumOf {it.visitCount}
    fun day(value:LocalDate)=days.firstOrNull {it.day==value}
    /** The latest day with anything recorded, which the range card selects first. */
    val latestActiveDay:LocalDate? get()=days.lastOrNull {it.distanceMeters>0 || it.visitCount>0}?.day ?: days.lastOrNull()?.day
}

/** Streams chronological fixes into per-day path distance without keeping them. Outliers do not count, as in PointSummary. */
class DayDistanceAccumulator(private val zone:ZoneId) {
    private val distances=LinkedHashMap<LocalDate,Double>()
    private val counts=LinkedHashMap<LocalDate,Long>()
    private var previous:TrackPoint?=null
    fun add(point:TrackPoint) {
        val day=point.time.atZone(zone).toLocalDate()
        counts[day]=(counts[day] ?: 0)+1
        if(point.outlier) return
        val last=previous
        // A leg that crosses midnight counts toward the day it ends on.
        if(last!=null) distances[day]=(distances[day] ?: 0.0)+last.coordinate.distanceTo(point.coordinate)
        else distances.putIfAbsent(day,0.0)
        previous=point
    }
    fun distances():Map<LocalDate,Double> = distances.toMap()
    fun counts():Map<LocalDate,Long> = counts.toMap()
}

object MapRangeAggregation {
    /** Every day from the first to the last of the range, so empty days still get a chip. */
    fun days(range:HistoryRange,zone:ZoneId,now:Instant,firstRecorded:Instant?):List<LocalDate> {
        val upper=minOf(range.until.minusMillis(1),now).atZone(zone).toLocalDate()
        val lowerInstant=maxOf(range.from,firstRecorded ?: range.from)
        var lower=lowerInstant.atZone(zone).toLocalDate()
        if(lower>upper) lower=upper
        // All-time ranges can span years; the strip shows the most recent 400 days at most.
        if(java.time.temporal.ChronoUnit.DAYS.between(lower,upper)>399) lower=upper.minusDays(399)
        return generateSequence(lower) {it.plusDays(1)}.takeWhile {it<=upper}.toList()
    }

    fun summarize(range:HistoryRange,zone:ZoneId,now:Instant,visits:List<Visit>,distances:Map<LocalDate,Double>,pointCounts:Map<LocalDate,Long> = emptyMap()):MapRangeSummary {
        val sorted=visits.sortedWith(compareBy({it.arrivedAt},{it.id}))
        val first=listOfNotNull(sorted.firstOrNull()?.arrivedAt,distances.keys.minOrNull()?.atStartOfDay(zone)?.toInstant()).minOrNull()
        val byDay=sorted.groupBy {it.arrivedAt.atZone(zone).toLocalDate()}
        val days=days(range,zone,now,first).map {day->
            val dayVisits=byDay[day].orEmpty()
            MapRangeDay(day,distances[day] ?: 0.0,dayVisits.size,dayVisits.map(::placeKey).distinct().size,pointCounts[day] ?: 0)
        }
        val inks=PlaceInks.index(sorted)
        val places=sorted.groupBy(::placeKey).map {(key,stays)->
            val visitedDays=stays.flatMap {visit->
                val start=visit.arrivedAt.atZone(zone).toLocalDate()
                val end=(visit.departedAt ?: now).atZone(zone).toLocalDate()
                generateSequence(start) {it.plusDays(1)}.takeWhile {it<=end}.toList()
            }.distinct().size
            val latest=stays.last()
            MapRangePlace(key,latest.name?.trim()?.takeIf {it.isNotEmpty()},latest.coordinate,visitedDays,stays.size,inks.getValue(key),latest)
        }.sortedByDescending {it.days}
        return MapRangeSummary(days,places)
    }

    /** Keeps at most [limit] fixes of one day's path, always including both ends, so 30D/All stays light to draw. */
    fun capDay(points:List<TrackPoint>,limit:Int=240):List<TrackPoint> {
        if(points.size<=limit) return points
        return List(limit) {index->points[((points.size-1).toDouble()*index/(limit-1)).toInt()]}
    }

    /** Groups a sampled range path into per-day lines, capped per day. */
    fun dayPaths(points:List<TrackPoint>,zone:ZoneId,limit:Int=240):Map<LocalDate,List<TrackPoint>> =
        points.filterNot {it.outlier}.sortedWith(compareBy({it.time},{it.id})).groupBy {it.time.atZone(zone).toLocalDate()}.mapValues {capDay(it.value,limit)}
}
