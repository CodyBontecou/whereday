package com.bontecou.whereday.platform

import com.bontecou.whereday.data.HistoryDao
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import java.time.Instant

internal data class SnapshotOuting(val summary:ExportableOuting,val source:ExportRecordSource)

/** All passes belong to the caller's WAL snapshot. Only outing headers/statistics are retained. */
internal class SnapshotOutingExport(private val dao:HistoryDao,private val now:Instant,private val checkpoint:()->Unit) {
    private suspend fun sessions()=dao.rangeSessions(HistoryRange.all.from,HistoryRange.all.until).map {it.value}

    suspend fun selected(options:ExportOptions,inference:InferenceRules?,split:Boolean,limit:Int=Int.MAX_VALUE):List<SnapshotOuting> {
        val exact=sessions()
        val excluded=ClosedOutingWindows(exact.map {it.startedAt to maxOf(it.startedAt,it.endedAt ?: now)})
        val headers=mutableListOf<Pair<RecordingSession,Boolean>>()
        exact.forEach {headers+=it to false}
        if(inference!=null) {
            val builder=StreamingOutingInference(emptyList(),inference)
            SnapshotExportSource(dao,ExportOptions(from=HistoryRange.all.from,until=now),onRead=checkpoint).points {if(!excluded.contains(it.time)) builder.add(it)}
            builder.finish().forEach {headers+=RecordingSession(it.id,it.startedAt,it.endedAt) to true}
        }
        val result=mutableListOf<SnapshotOuting>()
        headers.sortWith(compareBy({it.first.startedAt},{it.first.id}))
        headers.forEachIndexed {index,(session,inferred) ->
            checkpoint()
            if(result.size<limit && options.includes(session.startedAt)) result+=one(session,index+1,inferred,options,!split,excluded)
        }
        return result
    }

    suspend fun individual(id:String,sequence:Int,inferred:InferredOutingHeader?,options:ExportOptions):SnapshotOuting {
        val session=if(inferred==null) dao.session(id)?.value ?: error("This outing was deleted.")
            else RecordingSession(id,inferred.startedAt,inferred.endedAt)
        val excluded=ClosedOutingWindows(if(inferred==null) emptyList() else sessions().map {it.startedAt to maxOf(it.startedAt,it.endedAt ?: now)})
        return one(session,sequence,inferred!=null,options,false,excluded).also {
            require(inferred==null || it.summary.pointCount>0) {"This inferred outing no longer exists."}
        }
    }

    private suspend fun one(session:RecordingSession,sequence:Int,inferred:Boolean,options:ExportOptions,filterVisits:Boolean,excluded:ClosedOutingWindows):SnapshotOuting {
        val capturedEnd=maxOf(session.startedAt,session.endedAt ?: now)
        val raw=SnapshotExportSource(dao,ExportOptions(from=session.startedAt,until=capturedEnd),onRead=checkpoint)
        var effectiveEnd=capturedEnd
        val source=object:ExportRecordSource {
            override suspend fun points(consume:suspend (TrackPoint)->Unit) {raw.points {if(!inferred || !excluded.contains(it.time)) consume(it)}}
            override suspend fun visits(consume:suspend (Visit)->Unit) {raw.visits {
                if(it.arrivedAt<=effectiveEnd && (!filterVisits || options.copy(kind=DataKind.all).accepts(it))) consume(it)
            }}
        }
        val stats=OutingExportSummaryBuilder();source.points {stats.add(it)}
        val summary=stats.build(session,sequence,inferred,now,0)
        effectiveEnd=summary.effectiveEndAt
        var visits=0L;source.visits {visits++}
        return SnapshotOuting(summary.copy(visitCount=visits),source)
    }

    /** One chronological query means overlapping outings never duplicate a route point in a bundle. */
    suspend fun union(outings:List<SnapshotOuting>):ExportRecordSource {
        if(outings.size==1) return outings.single().source
        val recorded=ClosedOutingWindows(outings.filter {!it.summary.isInferred}.map {it.summary.startedAt to it.summary.effectiveEndAt})
        val inferred=ClosedOutingWindows(outings.filter {it.summary.isInferred}.map {it.summary.startedAt to it.summary.effectiveEndAt})
        val excluded=ClosedOutingWindows(if(outings.any {it.summary.isInferred}) sessions().map {it.startedAt to maxOf(it.startedAt,it.endedAt ?: now)} else emptyList())
        val raw=outings.takeIf {it.isNotEmpty()}?.let {
            SnapshotExportSource(dao,ExportOptions(from=it.minOf {o->o.summary.startedAt},until=it.maxOf {o->o.summary.effectiveEndAt}),onRead=checkpoint)
        }
        return object:ExportRecordSource {
            override suspend fun points(consume:suspend (TrackPoint)->Unit) {raw?.points {
                if(recorded.contains(it.time) || inferred.contains(it.time) && !excluded.contains(it.time)) consume(it)
            }}
            override suspend fun visits(consume:suspend (Visit)->Unit) {raw?.visits {
                if(recorded.contains(it.arrivedAt) || inferred.contains(it.arrivedAt)) consume(it)
            }}
        }
    }
}

/** Merged closed windows with logarithmic membership, independent of overlapping-session count. */
private class ClosedOutingWindows(windows:List<Pair<Instant,Instant>>) {
    private val ranges=buildList<Pair<Instant,Instant>> {
        for(window in windows.sortedBy {it.first}) {
            val previous=lastOrNull()
            if(previous!=null && window.first<=previous.second) {
                removeAt(lastIndex);add(previous.first to maxOf(previous.second,window.second))
            } else add(window)
        }
    }
    fun contains(time:Instant):Boolean {
        var low=0;var high=ranges.size
        while(low<high) {val mid=(low+high) ushr 1;if(ranges[mid].first<=time) low=mid+1 else high=mid}
        return low>0 && time<=ranges[low-1].second
    }
}
