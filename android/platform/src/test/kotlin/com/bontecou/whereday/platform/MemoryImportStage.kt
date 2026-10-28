package com.bontecou.whereday.platform

/** Small lifecycle-test double. Production uses DiskHistoryImportWorkspaceFactory exclusively. */
internal class MemoryImportStage:HistoryImportStage {
    private val records=linkedMapOf<Pair<ImportRecordKind,String>,ImportStageRow>()
    private var checksum:String?=null
    override fun add(row:ImportStageRow):Boolean {
        val old=records[row.kind to row.id]
        require(old==null || old.payload==row.payload) {"Conflicting ${row.kind} identities"}
        if(old!=null) return false
        records[row.kind to row.id]=row;return true
    }
    override fun finishRecords() {}
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
