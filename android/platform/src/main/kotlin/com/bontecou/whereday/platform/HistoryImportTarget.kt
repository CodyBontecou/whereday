package com.bontecou.whereday.platform

import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.HistoryImportSource
import java.time.Instant
import com.bontecou.whereday.export.ImportResult
import com.bontecou.whereday.export.ImportRecordLimits

class HistoryImportCanceled:IllegalStateException("Import canceled")
class HistoryImportChanged:IllegalStateException("History changed since review")
class HistoryImportMatchTooLarge:IllegalArgumentException("A matching journal record is too large to review safely. Shorten its text before importing.")
class HistoryImportWriteFailed(cause:Exception):IllegalStateException("The reviewed import could not be written. Check available storage and choose the file again.",cause)
enum class HistoryImportPolicy {keepExisting,replaceExisting}
data class HistoryImportBaseline(val visits:Map<String,VisitRow>,val points:Map<String,PointRow>)
data class HistoryImportCounts(val visits:Int=0,val points:Int=0,val kept:Int=0)
interface HistoryImportTarget {
    suspend fun inspect(records:ImportResult,check:()->Unit):HistoryImportBaseline
    suspend fun apply(records:ImportResult,baseline:HistoryImportBaseline,policy:HistoryImportPolicy,check:()->Unit):HistoryImportCounts
}
interface StagedHistoryImportTarget:HistoryImportTarget {
    suspend fun inspectPlan(stage:HistoryImportStage,check:()->Unit):HistoryImportInspection {
        require(stage.source==HistoryImportSource.whereday) {"Google import targets require chronological admission planning"}
        return HistoryImportInspection(inspect(stage,check))
    }
    suspend fun inspect(stage:HistoryImportStage,check:()->Unit):Int
    suspend fun apply(stage:HistoryImportStage,policy:HistoryImportPolicy,check:()->Unit):HistoryImportCounts
}
class DatabaseHistoryImportTarget(private val database:HistoryDatabase):StagedHistoryImportTarget {
    override suspend fun inspect(stage:HistoryImportStage,check:()->Unit):Int=inspectPlan(stage,check).existing
    override suspend fun inspectPlan(stage:HistoryImportStage,check:()->Unit):HistoryImportInspection=database.readSnapshot {dao->
        var replaceable=0
        if(stage.source==HistoryImportSource.whereday) stage.batches(check) {rows->
            matched(dao,rows,check) {batch,visits,points->stage.baselines(batch.map {row->
                check();val current=if(row.kind==ImportRecordKind.visit) visits[row.id] else points[row.id]
                if(current!=null) replaceable=Math.addExact(replaceable,1)
                row.copy(baseline=current?.let(ImportStageCodec::hash))
            })}
        }
        val hasVisits=dao.totalVisitCount()>0;val hasPoints=dao.totalPointCount()>0
        stage.inspectTimeline(check) {row->
            if(if(row.kind==ImportRecordKind.visit) hasVisits else hasPoints) historyMatch(dao,row,check) else false
        }.copy(replaceable=replaceable)
    }
    override suspend fun apply(stage:HistoryImportStage,policy:HistoryImportPolicy,check:()->Unit):HistoryImportCounts {
        stage.verify(check)
        if(stage.source!=HistoryImportSource.whereday) require(policy==HistoryImportPolicy.keepExisting) {"Google imports keep existing journal records"}
        if(policy==HistoryImportPolicy.keepExisting) return applyKept(stage,check)
        return try {database.writeTransaction {
            var visitsWritten=0;var pointsWritten=0;var kept=0
            val dao=database.history()
            stage.chronologicalBatches(check) {ordered->
                matched(dao,ordered.map {it.row},check) {batch,visits,points->
                for(row in batch) {
                    check();val current=if(row.kind==ImportRecordKind.visit) visits[row.id] else points[row.id]
                    if(policy==HistoryImportPolicy.replaceExisting && current?.let(ImportStageCodec::hash)!=row.baseline) throw HistoryImportChanged()
                    if(current==null && historyMatch(dao,row,check)) kept=Math.addExact(kept,1)
                    else if(row.kind==ImportRecordKind.visit) {
                        val visit=row.visit();dao.put(VisitRow(visit));visitsWritten=Math.addExact(visitsWritten,1)
                        if(visit.departedAt!=null && dao.dwell()?.state?.visitId==visit.id) dao.put(DwellRow())
                    } else {
                        dao.put(PointRow(row.point(),points[row.id]?.sessionId));pointsWritten=Math.addExact(pointsWritten,1)
                    }
                }
                }
            }
            // Detect corruption or changed stage contents before the history commit decision.
            stage.verify(check);check()
            HistoryImportCounts(visitsWritten,pointsWritten,kept)
        }} catch(error:Exception) {
            if(error is HistoryImportCanceled || error is HistoryImportChanged || error is HistoryImportMatchTooLarge ||
                error is com.bontecou.whereday.export.ImportRecordTooLarge || error is java.util.concurrent.CancellationException ||
                error is android.database.sqlite.SQLiteFullException) throw error
            throw HistoryImportWriteFailed(error)
        }
    }

    private suspend fun historyMatch(dao:HistoryDao,row:ImportStageRow,check:()->Unit):Boolean {
        check()
        if(if(row.kind==ImportRecordKind.visit) dao.importVisitIdentityExists(row.id) else dao.importPointIdentityExists(row.id)) return true
        val match=if(row.kind==ImportRecordKind.visit) TimelineImportMatch.from(row.visit()) else TimelineImportMatch.from(row.point())
        val from=if(match.visit) match.time.minusSeconds(60) else Instant.ofEpochSecond(match.bucket).minusSeconds(1)
        val until=if(match.visit) match.time.plusSeconds(60) else Instant.ofEpochSecond(match.bucket).plusSeconds(1)
        var after:TimelineImportCandidate?=null
        while(true) {
            check()
            val rows=if(match.visit) dao.timelineImportVisits(from,until,after?.time,after?.ordinal ?: 0) else dao.timelineImportPoints(from,until,after?.time,after?.ordinal ?: 0)
            if(rows.isEmpty()) return false
            for(candidate in rows) {check();if(match.matches(TimelineImportMatch(match.visit,candidate.coordinate,candidate.time,candidate.departure))) return true}
            after=rows.last()
        }
    }
    private suspend fun applyKept(stage:HistoryImportStage,check:()->Unit):HistoryImportCounts=try {
        database.writeTransaction {
            val dao=database.history();var visits=0;var points=0;var kept=0
            val emptyVisits=dao.totalVisitCount()==0L;val emptyPoints=dao.totalPointCount()==0L
            var lastVisitTime:Instant?=null;var lastPointBucket:Long?=null
            stage.chronologicalBatches(check) {rows->for(item in rows) {
                val row=item.row;check()
                // Fresh matching inside the write transaction includes records added since review.
                val match=if(row.kind==ImportRecordKind.visit) TimelineImportMatch.from(row.visit()) else TimelineImportMatch.from(row.point())
                val newTimeBucket=if(match.visit) emptyVisits && (lastVisitTime==null || java.time.Duration.between(lastVisitTime,match.time)>java.time.Duration.ofSeconds(60))
                    else emptyPoints && (lastPointBucket==null || match.bucket>lastPointBucket!!)
                if(!newTimeBucket && historyMatch(dao,row,check)) kept++
                else if(row.kind==ImportRecordKind.visit) {dao.put(VisitRow(row.visit()));visits++;lastVisitTime=match.time}
                else {dao.put(PointRow(row.point(),null));points++;lastPointBucket=match.bucket}
            }}
            stage.verify(check);check();HistoryImportCounts(visits,points,kept)
        }
    } catch(error:Exception) {
        if(error is HistoryImportCanceled || error is java.util.concurrent.CancellationException || error is android.database.sqlite.SQLiteFullException) throw error
        throw HistoryImportWriteFailed(error)
    }

    /** Split before loading large local corrections into Java, not after accumulating rows. */
    private suspend fun matched(dao:HistoryDao,rows:List<ImportStageRow>,check:()->Unit,
        consume:suspend(List<ImportStageRow>,Map<String,VisitRow>,Map<String,PointRow>)->Unit) {
        check()
        val visitIds=rows.filter {it.kind==ImportRecordKind.visit}.map {it.id}
        val pointIds=rows.filter {it.kind==ImportRecordKind.point}.map {it.id}
        val bytes=dao.importVisitTextBytes(visitIds)+dao.importPointTextBytes(pointIds)
        if(bytes>ImportRecordLimits.batchBytes/2 && rows.size>1) {
            val middle=rows.size/2
            matched(dao,rows.subList(0,middle),check,consume);matched(dao,rows.subList(middle,rows.size),check,consume)
        } else {
            if(bytes>ImportRecordLimits.payloadBytes) throw HistoryImportMatchTooLarge()
            consume(rows,dao.importVisits(visitIds).associateBy {it.value.id},dao.importPoints(pointIds).associateBy {it.value.id})
        }
    }

    private suspend fun inspect(dao:HistoryDao,records:ImportResult,check:()->Unit):HistoryImportBaseline {
        val visits=linkedMapOf<String,VisitRow>();val points=linkedMapOf<String,PointRow>()
        records.visits.map {it.id}.chunked(200).forEach {ids->check();dao.importVisits(ids).forEach {visits[it.value.id]=it}}
        records.points.map {it.id}.chunked(200).forEach {ids->check();dao.importPoints(ids).forEach {points[it.value.id]=it}}
        return HistoryImportBaseline(visits,points)
    }
    override suspend fun inspect(records:ImportResult,check:()->Unit)=database.readSnapshot {dao->inspect(dao,records,check)}
    override suspend fun apply(records:ImportResult,baseline:HistoryImportBaseline,policy:HistoryImportPolicy,check:()->Unit)=database.writeTransaction {
        val dao=database.history();val current=inspect(dao,records,check)
        if(policy==HistoryImportPolicy.replaceExisting && current!=baseline) throw HistoryImportChanged()
        var visits=0;var points=0;var kept=0
        for(visit in records.visits.sortedBy {it.arrivedAt}) {
            check();val row=ImportStageRow(ImportRecordKind.visit,visit.id,ImportStageCodec.gson.toJson(visit))
            if((policy==HistoryImportPolicy.keepExisting || visit.id !in current.visits) && historyMatch(dao,row,check)) kept++
            else {
                dao.put(VisitRow(visit));visits++
                if(visit.departedAt!=null && dao.dwell()?.state?.visitId==visit.id) dao.put(DwellRow())
            }
        }
        for(point in records.points.sortedBy {it.time}) {
            check();val row=ImportStageRow(ImportRecordKind.point,point.id,ImportStageCodec.gson.toJson(point))
            if((policy==HistoryImportPolicy.keepExisting || point.id !in current.points) && historyMatch(dao,row,check)) kept++
            else {dao.put(PointRow(point,current.points[point.id]?.sessionId));points++}
        }
        check()
        HistoryImportCounts(visits,points,kept)
    }
}
