package com.bontecou.whereday.platform

import com.bontecou.whereday.data.*
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
    suspend fun inspect(stage:HistoryImportStage,check:()->Unit):Int
    suspend fun apply(stage:HistoryImportStage,policy:HistoryImportPolicy,check:()->Unit):HistoryImportCounts
}
class DatabaseHistoryImportTarget(private val database:HistoryDatabase):StagedHistoryImportTarget {
    override suspend fun inspect(stage:HistoryImportStage,check:()->Unit):Int=database.readSnapshot {dao->
        var found=0
        stage.batches(check) {rows->
            matched(dao,rows,check) {batch,visits,points->
            stage.baselines(batch.map {row->
                check();val current=if(row.kind==ImportRecordKind.visit) visits[row.id] else points[row.id]
                if(current!=null) found=Math.addExact(found,1)
                row.copy(baseline=current?.let(ImportStageCodec::hash))
            })
            }
        }
        found
    }
    override suspend fun apply(stage:HistoryImportStage,policy:HistoryImportPolicy,check:()->Unit):HistoryImportCounts {
        stage.verify(check)
        return try {database.writeTransaction {
            var visitsWritten=0;var pointsWritten=0;var kept=0
            val dao=database.history()
            stage.batches(check) {rows->
                matched(dao,rows,check) {batch,visits,points->
                for(row in batch) {
                    check();val current=if(row.kind==ImportRecordKind.visit) visits[row.id] else points[row.id]
                    if(policy==HistoryImportPolicy.replaceExisting && current?.let(ImportStageCodec::hash)!=row.baseline) throw HistoryImportChanged()
                    if(policy==HistoryImportPolicy.keepExisting && current!=null) kept=Math.addExact(kept,1)
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
        records.visits.forEach {visit->
            check()
            if(policy==HistoryImportPolicy.keepExisting && visit.id in current.visits) kept++
            else {
                dao.put(VisitRow(visit));visits++
                if(visit.departedAt!=null && dao.dwell()?.state?.visitId==visit.id) dao.put(DwellRow())
            }
        }
        records.points.forEach {point->
            check()
            if(policy==HistoryImportPolicy.keepExisting && point.id in current.points) kept++
            // Replacement must not detach a point from its recording session.
            else {dao.put(PointRow(point,current.points[point.id]?.sessionId));points++}
        }
        check()
        HistoryImportCounts(visits,points,kept)
    }
}
