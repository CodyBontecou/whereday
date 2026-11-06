package com.bontecou.whereday.platform

/** Small lifecycle-test double. Production uses DiskHistoryImportWorkspaceFactory exclusively. */
internal class MemoryImportStage:HistoryImportStage {
    override var source=com.bontecou.whereday.export.HistoryImportSource.whereday
    private val records=linkedMapOf<Pair<ImportRecordKind,String>,ImportStageRow>()
    private var checksum:String?=null
    override fun add(row:ImportStageRow):Boolean {
        val old=records[row.kind to row.id]
        require(source!=com.bontecou.whereday.export.HistoryImportSource.whereday || old==null || (!old.explicitIdentity && !row.explicitIdentity) || old.payload==row.payload) {"Conflicting ${row.kind} identities"}
        if(old!=null) {if(row.explicitIdentity && !old.explicitIdentity) records[row.kind to row.id]=old.copy(explicitIdentity=true);return false}
        records[row.kind to row.id]=row;return true
    }
    override fun finishRecords() {}
    private val admitted=mutableListOf<com.bontecou.whereday.domain.TimelineImportMatch>()
    private fun match(row:ImportStageRow)=if(row.kind==ImportRecordKind.visit) com.bontecou.whereday.domain.TimelineImportMatch.from(row.visit()) else com.bontecou.whereday.domain.TimelineImportMatch.from(row.point())
    override fun resetAdmission() {admitted.clear()}
    override fun admitPreview(rows:List<ImportStageRow>,check:()->Unit):ImportStageDuplicates {
        var visits=0;var points=0
        for(row in rows) {check();val value=match(row)
            if(admitted.any {it.matches(value)}) {if(value.visit) visits++ else points++} else admitted+=value
        }
        return ImportStageDuplicates(visits,points)
    }
    override fun chronologicalPage(after:ImportChronologyCursor?,limit:Int):List<ImportChronologyRow> = records.values.withIndex().map {item->
        val time=match(item.value).time;ImportChronologyRow(item.value,ImportChronologyCursor(item.value.kind,time.epochSecond,time.nano,item.index.toLong()))
    }.sortedBy {it.cursor}.filter {after==null || it.cursor>after}.take(limit)
    override fun page(after:Pair<ImportRecordKind,String>?,limit:Int)=records.values.sortedWith(compareBy({it.kind.ordinal},{it.id}))
        .filter {after==null || it.kind.ordinal>after.first.ordinal || (it.kind==after.first && it.id>after.second)}.take(limit)
    override fun baselines(rows:List<ImportStageRow>) {rows.forEach {records[it.kind to it.id]=it}}
    override fun seal(check:()->Unit) {check();checksum=ImportStageCodec.hash(records.values)}
    override fun verify(check:()->Unit) {check();kotlin.check(checksum==ImportStageCodec.hash(records.values))}
    override fun close() {records.clear()}
}
internal fun memoryImportWorkspaces()=HistoryImportWorkspaceFactory {
    HistoryImportWorkspace(kotlin.io.path.createTempDirectory("whereday-import-unit-").toFile(),MemoryImportStage()) {}
}
