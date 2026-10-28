package com.bontecou.whereday.platform

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest
import com.bontecou.whereday.export.ImportRecordLimits
import com.bontecou.whereday.export.ImportRecordTooLarge

/** Separate disposable SQLite index; never opens the history database with this driver. */
internal class DiskHistoryImportStage(private val file:File,maximumPages:Int?=null):HistoryImportStage {
    private val db=SQLiteDatabase.openOrCreateDatabase(file,null)
    private val pending=linkedMapOf<Pair<ImportRecordKind,String>,ImportStageRow>()
    private var pendingBytes=0;private var recordsDone=false;private var sealed:ByteArray?=null;private var closed=false
    init {
        try {
            db.rawQuery("PRAGMA journal_mode=DELETE",null).use {check(it.moveToFirst())}
            db.execSQL("PRAGMA synchronous=OFF");db.execSQL("PRAGMA cache_size=-1024");db.execSQL("PRAGMA temp_store=FILE")
            db.execSQL("CREATE TABLE records(kind INTEGER NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,baseline TEXT,PRIMARY KEY(kind,id)) WITHOUT ROWID")
            // Internal fault-injection seam; production factories never set a page ceiling.
            maximumPages?.let {require(it in 8..100_000);db.rawQuery("PRAGMA max_page_count=$it",null).use {cursor->check(cursor.moveToFirst())}}
        } catch(error:Throwable) {db.close();SQLiteDatabase.deleteDatabase(file);throw error}
    }
    @Synchronized override fun add(row:ImportStageRow):Boolean {
        check(!closed && !recordsDone)
        if(row.payload.length>ImportRecordLimits.payloadBytes) throw ImportRecordTooLarge()
        val bytes=row.payload.toByteArray(Charsets.UTF_8).size
        if(bytes>ImportRecordLimits.payloadBytes) throw ImportRecordTooLarge()
        val key=row.kind to row.id
        val previous=pending[key]?.payload ?: db.rawQuery("SELECT payload FROM records WHERE kind=? AND id=?",arrayOf(row.kind.ordinal.toString(),row.id)).use {if(it.moveToFirst()) it.getString(0) else null}
        require(previous==null || previous==row.payload) {"Conflicting ${row.kind.name} identities in this file."}
        if(previous!=null) return false
        if(pendingBytes+bytes>ImportRecordLimits.batchBytes) flush()
        pending[key]=row;pendingBytes+=bytes
        if(pending.size>=200 || pendingBytes>=ImportRecordLimits.batchBytes) flush()
        return true
    }
    private fun flush() {
        if(pending.isEmpty()) return
        db.beginTransaction()
        try {
            db.compileStatement("INSERT INTO records(kind,id,payload) VALUES(?,?,?)").use {statement->
                pending.values.forEach {row->statement.bindLong(1,row.kind.ordinal.toLong());statement.bindString(2,row.id);statement.bindString(3,row.payload);statement.executeInsert()}
            }
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        pending.clear();pendingBytes=0
    }
    @Synchronized override fun finishRecords() {check(!closed);flush();recordsDone=true}
    @Synchronized override fun page(after:Pair<ImportRecordKind,String>?,limit:Int):List<ImportStageRow> {
        check(!closed && recordsDone);require(limit in 1..200)
        val clause=if(after==null) "" else "WHERE (kind,id)>(?,?)"
        val args=after?.let {arrayOf(it.first.ordinal.toString(),it.second)}
        return db.rawQuery("SELECT kind,id,payload,baseline FROM records $clause ORDER BY kind,id LIMIT $limit",args).use {cursor->
            buildList {
                var retained=0
                while(cursor.moveToNext()) {
                    val payload=cursor.getString(2);val bytes=payload.toByteArray(Charsets.UTF_8).size
                    if(bytes>ImportRecordLimits.payloadBytes) throw ImportRecordTooLarge()
                    if(isNotEmpty() && retained+bytes>ImportRecordLimits.batchBytes) break
                    add(ImportStageRow(ImportRecordKind.entries[cursor.getInt(0)],cursor.getString(1),payload,if(cursor.isNull(3)) null else cursor.getString(3)))
                    retained+=bytes
                }
            }
        }
    }
    @Synchronized override fun baselines(rows:List<ImportStageRow>) {
        check(!closed && recordsDone && sealed==null);require(rows.size<=200)
        db.beginTransaction()
        try {
            db.compileStatement("UPDATE records SET baseline=? WHERE kind=? AND id=?").use {statement->rows.forEach {row->
                statement.clearBindings();row.baseline?.let {statement.bindString(1,it)} ?: statement.bindNull(1)
                statement.bindLong(2,row.kind.ordinal.toLong());statement.bindString(3,row.id);check(statement.executeUpdateDelete()==1)
            }}
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    private fun digest(check:()->Unit):ByteArray {
        val digest=MessageDigest.getInstance("SHA-256")
        batches(check) {rows->rows.forEach {row->
            check()
            for(value in listOf(row.kind.name,row.id,row.payload,row.baseline)) {
                if(value==null) digest.update(0) else {
                    digest.update(1);val bytes=value.toByteArray(Charsets.UTF_8)
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
                }
            }
        }}
        return digest.digest()
    }
    @Synchronized override fun seal(check:()->Unit) {check(!closed && recordsDone && sealed==null);sealed=digest(check)}
    @Synchronized override fun verify(check:()->Unit) {check(!closed);check(MessageDigest.isEqual(checkNotNull(sealed),digest(check))) {"Import staging checksum mismatch"}}
    @Synchronized override fun close() {if(!closed) {closed=true;pending.clear();pendingBytes=0;try {db.close()} finally {SQLiteDatabase.deleteDatabase(file)}}}
}
class DiskHistoryImportWorkspaceFactory(private val directory:File):HistoryImportWorkspaceFactory {
    init {runCatching {reap()}} // A damaged cache must not prevent free recording; create/clear retry strictly.
    override fun reap()=synchronized(active) {
        directory.listFiles()?.filter {it.name.startsWith("import-") && it.absolutePath !in active}?.forEach {check(it.deleteRecursively() || !it.exists()) {"Import staging cleanup failed"}}
        Unit
    }
    override fun create():HistoryImportWorkspace=synchronized(active) {
        check(directory.mkdirs() || directory.isDirectory) {"Import staging is unavailable"}
        reap()
        val root=kotlin.io.path.createTempDirectory(directory.toPath(),"import-").toFile()
        try {
            val stage=DiskHistoryImportStage(File(root,"records.db"))
            active+=root.absolutePath
            HistoryImportWorkspace(root,stage) {synchronized(active) {active-=root.absolutePath}}
        } catch(error:Throwable) {root.deleteRecursively();throw error}
    }
    companion object {private val active=mutableSetOf<String>()}
}
