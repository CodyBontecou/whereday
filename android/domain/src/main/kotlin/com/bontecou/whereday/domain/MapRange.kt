package com.bontecou.whereday.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day of a multi-day range, as that day's Today shows it: its distance, how many stays arrived, its places and a fix count.
 *  [placeCount] and [places] are [DayStory.placeCount] and [DayStory.places], so a night at home that began the evening before
 *  counts on both days; [distanceMeters] is the day sheet's figure (fixes on that civil day only). */
data class MapRangeDay(val day:LocalDate,val distanceMeters:Double,val visitCount:Int,val placeCount:Int,val pointCount:Long,val places:List<String> = emptyList())
/** A place inside the range, drawn as a count circle: [days] is how many days in the range it was visited. */
data class MapRangePlace(val key:String,val name:String?,val coordinate:Coordinate,val days:Int,val visits:Int,val ink:Int,val visit:Visit)
/** [crossingMeters] is the length of the legs that cross midnight into one of [days]. No day chip counts them (each day starts
 *  fresh at midnight, as Today does), but the range total does, so it equals the range's point summary and the weekly recap. */
data class MapRangeSummary(val days:List<MapRangeDay>,val places:List<MapRangePlace>,val crossingMeters:Double=0.0) {
    val distanceMeters:Double get()=days.sumOf {it.distanceMeters}+crossingMeters
    val visitCount:Int get()=days.sumOf {it.visitCount}
    fun day(value:LocalDate)=days.firstOrNull {it.day==value}
    /** The latest day with anything recorded, which the range card selects first. */
    val latestActiveDay:LocalDate? get()=days.lastOrNull {it.distanceMeters>0 || it.visitCount>0}?.day ?: days.lastOrNull()?.day
}

/** Streams chronological fixes into per-day path distance without keeping them. Outliers do not count, as in PointSummary.
 *  A day's distance is its own fixes' path, the figure its day sheet shows; a leg from one day's last fix to the next day's
 *  first goes to [crossings] instead, keyed by the day it ends on. */
class DayDistanceAccumulator(private val zone:ZoneId) {
    private val distances=LinkedHashMap<LocalDate,Double>()
    private val crossings=LinkedHashMap<LocalDate,Double>()
    private val counts=LinkedHashMap<LocalDate,Long>()
    private var previous:TrackPoint?=null
    private var previousDay:LocalDate?=null
    fun add(point:TrackPoint) {
        val day=point.time.atZone(zone).toLocalDate()
        counts[day]=(counts[day] ?: 0)+1
        if(point.outlier) return
        val last=previous
        distances.putIfAbsent(day,0.0)
        if(last!=null) {
            val leg=last.coordinate.distanceTo(point.coordinate)
            if(previousDay==day) distances[day]=distances.getValue(day)+leg else crossings[day]=(crossings[day] ?: 0.0)+leg
        }
        previous=point;previousDay=day
    }
    fun distances():Map<LocalDate,Double> = distances.toMap()
    fun crossings():Map<LocalDate,Double> = crossings.toMap()
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

    /** [distances] and [crossings] come from [DayDistanceAccumulator]. Each day's places are that day's [DayStory], built from
     *  the visits overlapping it, so a chip never disagrees with Today, the day sheet or the evening recap. */
    fun summarize(range:HistoryRange,zone:ZoneId,now:Instant,visits:List<Visit>,distances:Map<LocalDate,Double>,pointCounts:Map<LocalDate,Long> = emptyMap(),
        crossings:Map<LocalDate,Double> = emptyMap()):MapRangeSummary {
        val sorted=visits.sortedWith(compareBy({it.arrivedAt},{it.id}))
        val first=listOfNotNull(sorted.firstOrNull()?.arrivedAt,distances.keys.minOrNull()?.atStartOfDay(zone)?.toInstant()).minOrNull()
        val byDay=sorted.groupBy {it.arrivedAt.atZone(zone).toLocalDate()}
        val rangeDays=days(range,zone,now,first)
        // Each visit is handed to every day it may overlap, so a day's story reads only its own few visits.
        val overlapping=HashMap<LocalDate,MutableList<Visit>>()
        if(rangeDays.isNotEmpty()) {
            val lower=rangeDays.first();val upper=rangeDays.last()
            sorted.forEach {visit->
                val start=maxOf(visit.arrivedAt.atZone(zone).toLocalDate(),lower)
                val end=minOf((visit.departedAt ?: now).atZone(zone).toLocalDate(),upper)
                generateSequence(start) {it.plusDays(1)}.takeWhile {it<=end}.forEach {overlapping.getOrPut(it) {mutableListOf()}+=visit}
            }
        }
        val days=rangeDays.map {day->
            val story=DayStory.build(day,zone,overlapping[day].orEmpty(),emptyList(),now,0.0)
            MapRangeDay(day,distances[day] ?: 0.0,byDay[day].orEmpty().size,story.placeCount,pointCounts[day] ?: 0,story.places)
        }
        val crossing=rangeDays.sumOf {crossings[it] ?: 0.0}
        val inks=PlaceInks.index(sorted)
        val places=sorted.groupBy(::placeKey).map {(key,stays)->
            // The range days whose Today lists this place, so a stay begun before the range or ending at midnight adds no day.
            val visitedDays=days.count {key in it.places}
            val latest=stays.last()
            MapRangePlace(key,latest.name?.trim()?.takeIf {it.isNotEmpty()},latest.coordinate,visitedDays,stays.size,inks.getValue(key),latest)
        }.sortedByDescending {it.days}
        return MapRangeSummary(days,places,crossing)
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
