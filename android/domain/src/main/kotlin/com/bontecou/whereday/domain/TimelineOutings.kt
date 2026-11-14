package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant
import java.util.PriorityQueue
import java.util.Locale
import kotlin.math.abs

/** Outing distance excludes outliers unless every fix is an outlier, as in the Swift summary. */
class OutingPointSummaryBuilder {
    private val good=PointSummaryBuilder()
    private var previous:TrackPoint?=null
    private var allDistance=0.0
    fun add(point:TrackPoint) {
        good.add(point)
        previous?.let {allDistance+=it.coordinate.distanceTo(point.coordinate)}
        previous=point
    }
    fun build():PointSummary=good.build().let {if(it.count==it.outlierCount) it.copy(distanceMeters=allDistance) else it}
}

data class TimelineOutingSummary(
    val id:String,val stored:RecordingSession?,val startedAt:Instant,val endedAt:Instant?,
    val effectiveEnd:Instant,val active:Boolean,val summary:PointSummary,val sequence:Int,
)
data class TimelineOutingIndex(val outings:List<TimelineOutingSummary>) {
    val distanceMeters:Double get()=outings.sumOf {it.summary.distanceMeters}
}

/** Inclusive stored intervals, consumed against one chronological stream without overlap scans. */
class StoredOutingCursor(sessions:List<RecordingSession>,now:Instant) {
    private data class Interval(val start:Instant,var end:Instant)
    private val intervals=mutableListOf<Interval>().apply {
        for(s in sessions.sortedBy {it.startedAt}) {
            val end=s.endedAt ?: now
            if(end<s.startedAt) continue
            if(isNotEmpty() && last().end>=s.startedAt) last().end=maxOf(last().end,end)
            else add(Interval(s.startedAt,end))
        }
    }
    private var index=0
    private var previous:Instant?=null
    fun contains(time:Instant):Boolean {
        require(previous?.let {it<=time}!=false) {"Stored interval cursor requires chronological points"}
        previous=time
        while(index<intervals.size && intervals[index].end<time) index++
        return index<intervals.size && intervals[index].start<=time
    }
}

/**
 * One chronological history pass. Stored metrics use prefix differences at inclusive interval
 * boundaries; overlapping sessions never cause per-point/per-session scans. Inference sees the
 * global unconsumed stream, retaining only one chunk and selected-day headers. Raw points are not
 * retained. Memory is O(stored sessions + selected outings); time includes sorting selected headers.
 * The caller owns a consistent snapshot and supplies complete, chronological points.
 */
class TimelineOutingBuilder(
    sessions:List<RecordingSession>,private val range:HistoryRange,private val rules:InferenceRules?,
    private val now:Instant,private val activeStart:Instant?=null,
) {
    private data class Key(val time:Instant,val id:String):Comparable<Key> {
        override fun compareTo(other:Key):Int=time.compareTo(other.time).takeIf {it!=0} ?: id.compareTo(other.id)
    }
    private fun key(session:RecordingSession)=Key(session.startedAt,"stored-${session.id.uppercase(Locale.ROOT)}")
    // Keep the rounding residual when a tiny outing follows a very large history prefix.
    private class Total {
        var high=0.0;var low=0.0
        fun add(value:Double) {
            val sum=high+value
            low+=if(abs(high)>=abs(value)) (high-sum)+value else (value-sum)+high
            high=sum
        }
    }
    private data class Prefix(val count:Long=0,val outliers:Long=0,val all:Double=0.0,val allLow:Double=0.0,val good:Double=0.0,val goodLow:Double=0.0,val last:Instant?=null)
    private class Pending(val session:RecordingSession,val ordinal:Int,val end:Instant) {
        var baseline=Prefix();var first:Instant?=null
        var firstAll:Prefix?=null;var firstGood:Prefix?=null;var closed=false
        var stats=PointSummary();var inferredBefore=0
    }
    private val ordered=sessions.sortedWith(compareBy({it.startedAt},{key(it).id}))
    private val keys=ordered.map(::key)
    private val candidates=ordered.mapIndexedNotNull {i,s->
        if(s.startedAt<=range.until && maxOf(s.startedAt,s.endedAt ?: now)>=range.from) Pending(s,i+1,s.endedAt ?: now) else null
    }
    private val starts=candidates.filter {it.end>=it.session.startedAt}
    /** Inference needs global chunk boundaries; disabled inference needs only selected intervals. */
    val pointRange:HistoryRange? get()=if(rules!=null) HistoryRange.all else starts.takeIf {it.isNotEmpty()}?.let {
        val from=maxOf(it.first().session.startedAt,HistoryRange.all.from)
        val until=minOf(it.maxOf {state->state.end}.plusMillis(1),HistoryRange.all.until)
        if(from<until) HistoryRange(from,until) else null
    }
    private val ends=PriorityQueue<Pending>(compareBy({it.end},{it.ordinal}))
    private var nextStart=0
    private val waitingAll=ArrayList<Pending>();private val waitingGood=ArrayList<Pending>()
    private val allDistance=Total();private val goodDistance=Total()
    private var prefix=Prefix();private var previous:TrackPoint?=null;private var previousGood:TrackPoint?=null
    private val consumed=StoredOutingCursor(ordered,now)
    private var first:TrackPoint?=null;private var last:TrackPoint?=null
    private var chunk=OutingPointSummaryBuilder()
    private var inferredCount=0
    private var targetIndex=0
    private val selected=ArrayList<TimelineOutingSummary>()
    private var finished=false
    init {require(ordered.map {it.id}.toSet().size==ordered.size)}

    private fun beginThrough(time:Instant) {
        while(nextStart<starts.size && starts[nextStart].session.startedAt<=time) {
            val state=starts[nextStart++];state.baseline=prefix
            waitingAll.add(state);waitingGood.add(state);ends.add(state)
        }
    }
    private fun close(state:Pending) {
        state.closed=true
        val count=prefix.count-state.baseline.count
        val outliers=prefix.outliers-state.baseline.outliers
        state.stats=if(count==0L) PointSummary() else PointSummary(count,outliers,
            maxOf(0.0,if(count==outliers) checkNotNull(state.firstAll).let {(prefix.all-it.all)+(prefix.allLow-it.allLow)} else checkNotNull(state.firstGood).let {(prefix.good-it.good)+(prefix.goodLow-it.goodLow)}),
            checkNotNull(state.first),prefix.last)
    }
    fun add(point:TrackPoint) {
        check(!finished);require(previous?.let {it.time<=point.time}!=false) {"Timeline points must be chronological"}
        beginThrough(point.time)
        while(ends.peek()?.let {it.end<point.time}==true) close(ends.remove())
        allDistance.add(previous?.coordinate?.distanceTo(point.coordinate) ?: 0.0)
        if(!point.outlier) goodDistance.add(previousGood?.coordinate?.distanceTo(point.coordinate) ?: 0.0)
        prefix=Prefix(prefix.count+1,prefix.outliers+if(point.outlier) 1 else 0,allDistance.high,allDistance.low,goodDistance.high,goodDistance.low,point.time)
        for(state in waitingAll) if(!state.closed) {state.first=point.time;state.firstAll=prefix}
        waitingAll.clear()
        if(!point.outlier) {
            for(state in waitingGood) if(!state.closed) state.firstGood=prefix
            waitingGood.clear();previousGood=point
        }
        previous=point
        if(rules==null) return
        if(consumed.contains(point.time)) return
        if(last?.let {Duration.between(it.time,point.time)>rules.gap || (activeStart!=null && it.time<activeStart && point.time>=activeStart)}==true) flush()
        if(first==null) first=point
        chunk.add(point);last=point
    }
    private var storedBeforeInferred=0
    private fun storedBefore(value:Key):Int {
        while(storedBeforeInferred<keys.size && keys[storedBeforeInferred]<value) storedBeforeInferred++
        return storedBeforeInferred
    }
    private fun flush() {
        val a=first ?: return;val b=checkNotNull(last);val stats=chunk.build()
        val active=activeStart?.let {a.time>=it || (a.time<it && b.time>=it)} ?: false
        if(active || (stats.count>=checkNotNull(rules).minimumPoints && stats.duration>=rules.minimumDuration)) {
            val key=Key(a.time,"inferred-${a.id.uppercase(Locale.ROOT)}-${b.id.uppercase(Locale.ROOT)}-${stats.count}")
            while(targetIndex<candidates.size && key(candidates[targetIndex].session)<key) candidates[targetIndex++].inferredBefore=inferredCount
            inferredCount++
            if(a.time<=range.until && b.time>=range.from) selected.add(TimelineOutingSummary("inferred:${a.id}",null,a.time,if(active) null else b.time,b.time,active,stats,storedBefore(key)+inferredCount))
        }
        first=null;last=null;chunk=OutingPointSummaryBuilder()
    }
    fun finish():TimelineOutingIndex {
        check(!finished);finished=true
        beginThrough(Instant.MAX)
        while(ends.isNotEmpty()) close(ends.remove())
        flush()
        while(targetIndex<candidates.size) candidates[targetIndex++].inferredBefore=inferredCount
        for(state in candidates) {
            val s=state.session
            val effective=maxOf(s.startedAt,s.endedAt ?: state.stats.lastTime ?: now)
            if(s.startedAt<=range.until && effective>=range.from) selected.add(TimelineOutingSummary(s.id,s,s.startedAt,s.endedAt,effective,s.endedAt==null,state.stats,state.ordinal+state.inferredBefore))
        }
        return TimelineOutingIndex(selected.sortedWith(compareBy({it.startedAt},{it.sequence})))
    }
}
