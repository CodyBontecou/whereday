package com.bontecou.whereday.platform

import com.bontecou.whereday.data.HistoryDao
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

class SnapshotExportSource(private val dao:HistoryDao,options:ExportOptions,private val session:String?=null,private val onRead:()->Unit={}):ExportRecordSource {
    private val from=maxOf(options.from,Instant.ofEpochMilli(Long.MIN_VALUE))
    private val until=minOf(options.until,Instant.ofEpochMilli(Long.MAX_VALUE))
    override suspend fun visits(consume:suspend (Visit)->Unit) {
        var page=dao.exportFirstVisits(from,until)
        while(page.isNotEmpty()) {
            for(row in page) {currentCoroutineContext().ensureActive();onRead();consume(row.value)}
            val last=page.last().value;page=dao.exportNextVisits(last.arrivedAt,last.id,until)
        }
    }
    override suspend fun points(consume:suspend (TrackPoint)->Unit) {
        var page=dao.exportFirstPoints(from,until,session)
        while(page.isNotEmpty()) {
            for(row in page) {currentCoroutineContext().ensureActive();onRead();consume(row.value)}
            val last=page.last().value;page=dao.exportNextPoints(last.time,last.id,until,session)
        }
    }
}
