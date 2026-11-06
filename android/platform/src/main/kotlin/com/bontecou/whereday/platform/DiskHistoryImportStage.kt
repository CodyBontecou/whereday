package com.bontecou.whereday.platform

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.security.MessageDigest
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.HistoryImportSource
import com.bontecou.whereday.export.ImportRecordLimits
import com.bontecou.whereday.export.ImportRecordTooLarge

/** Separate disposable SQLite index; never opens the history database with this driver. */
internal class DiskHistoryImportStage(private val file:File,maximumPages:Int?=null):HistoryImportStage {
    private val db=SQLiteDatabase.openOrCreateDatabase(file,null)
    private val pending=linkedMapOf<Pair<ImportRecordKind,String>,ImportStageRow>()
    override var source:HistoryImportSource=HistoryImportSource.whereday
        set(value) {check(!closed && !recordsDone && !recordsStarted && pending.isEmpty());field=value}
    private var recordsStarted=false
    private var sequence=0L
    private var pendingBytes=0;private var recordsDone=false;private var sealed:ByteArray?=null;private var closed=false
    init {
        try {
            db.rawQuery("PRAGMA journal_mode=DELETE",null).use {check(it.moveToFirst())}
            db.execSQL("PRAGMA synchronous=OFF");db.execSQL("PRAGMA cache_size=-1024");db.execSQL("PRAGMA temp_store=FILE")
            db.execSQL("CREATE TABLE records(kind INTEGER NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,baseline TEXT,provided_id INTEGER NOT NULL,PRIMARY KEY(kind,id)) WITHOUT ROWID")
            db.execSQL("CREATE TABLE google_order(kind INTEGER NOT NULL,id TEXT NOT NULL,seconds INTEGER NOT NULL,nanos INTEGER NOT NULL,sequence INTEGER NOT NULL,PRIMARY KEY(kind,id)) WITHOUT ROWID")
            db.execSQL("CREATE INDEX google_chronology ON google_order(kind,seconds,nanos,sequence)")
            db.execSQL("CREATE TABLE google_matches(kind INTEGER NOT NULL,bucket INTEGER NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,time TEXT NOT NULL,departure TEXT)")
            db.execSQL("CREATE INDEX google_match_bucket ON google_matches(kind,bucket,latitude)")
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
        val previous=pending[key] ?: db.rawQuery("SELECT payload,provided_id FROM records WHERE kind=? AND id=?",arrayOf(row.kind.ordinal.toString(),row.id)).use {
            if(it.moveToFirst()) ImportStageRow(row.kind,row.id,it.getString(0),explicitIdentity=it.getInt(1)!=0) else null
        }
        require(source!=HistoryImportSource.whereday || previous==null || (!previous.explicitIdentity && !row.explicitIdentity) || previous.payload==row.payload) {"Conflicting ${row.kind.name} identities in this file."}
        if(previous!=null) {
            if(row.explicitIdentity && !previous.explicitIdentity) {
                if(key in pending) pending[key]=previous.copy(explicitIdentity=true)
                else db.execSQL("UPDATE records SET provided_id=1 WHERE kind=? AND id=?",arrayOf<Any?>(row.kind.ordinal,row.id))
            }
            return false
        }
        if(pendingBytes+bytes>ImportRecordLimits.batchBytes) flush()
        recordsStarted=true;pending[key]=row;pendingBytes+=bytes
        if(pending.size>=200 || pendingBytes>=ImportRecordLimits.batchBytes) flush()
        return true
    }
    private fun flush() {
        if(pending.isEmpty()) return
        db.beginTransaction()
        try {
            db.compileStatement("INSERT INTO records(kind,id,payload,provided_id) VALUES(?,?,?,?)").use {statement->
                db.compileStatement("INSERT INTO google_order(kind,id,seconds,nanos,sequence) VALUES(?,?,?,?,?)").use {order->
                    pending.values.forEach {row->
                        statement.bindLong(1,row.kind.ordinal.toLong());statement.bindString(2,row.id);statement.bindString(3,row.payload);statement.bindLong(4,if(row.explicitIdentity) 1 else 0);statement.executeInsert()
                        val time=if(row.kind==ImportRecordKind.visit) row.visit().arrivedAt else row.point().time
                        order.bindLong(1,row.kind.ordinal.toLong());order.bindString(2,row.id);order.bindLong(3,time.epochSecond);order.bindLong(4,time.nano.toLong());order.bindLong(5,sequence++);order.executeInsert()
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        pending.clear();pendingBytes=0
    }
    @Synchronized override fun finishRecords() {check(!closed);flush();recordsDone=true}
    @Synchronized override fun chronologicalPage(after:ImportChronologyCursor?,limit:Int):List<ImportChronologyRow> {
        check(!closed && recordsDone);require(limit in 1..200)
        val clause=if(after==null) "" else "WHERE (o.kind,o.seconds,o.nanos,o.sequence)>(?,?,?,?)"
        val args=after?.let {arrayOf(it.kind.ordinal.toString(),it.seconds.toString(),it.nanos.toString(),it.sequence.toString())}
        return db.rawQuery("SELECT o.kind,o.id,r.payload,o.seconds,o.nanos,o.sequence,r.baseline,r.provided_id FROM google_order o JOIN records r ON r.kind=o.kind AND r.id=o.id $clause ORDER BY o.kind,o.seconds,o.nanos,o.sequence LIMIT $limit",args).use {cursor->
            buildList {var bytes=0;while(cursor.moveToNext()) {
                val payload=cursor.getString(2);val size=payload.toByteArray(Charsets.UTF_8).size
                if(size>ImportRecordLimits.payloadBytes) throw ImportRecordTooLarge()
                if(isNotEmpty() && bytes+size>ImportRecordLimits.batchBytes) break
                val kind=ImportRecordKind.entries[cursor.getInt(0)]
                val row=ImportStageRow(kind,cursor.getString(1),payload,if(cursor.isNull(6)) null else cursor.getString(6),cursor.getInt(7)!=0)
                val order=ImportChronologyCursor(kind,cursor.getLong(3),cursor.getInt(4),cursor.getLong(5))
                val time=try {if(kind==ImportRecordKind.visit) row.visit().arrivedAt else row.point().time} catch(error:Exception) {
                    if(error is java.util.concurrent.CancellationException) throw error
                    throw IllegalStateException("Import chronology payload is invalid",error)
                }
                check(time.epochSecond==order.seconds && time.nano==order.nanos && order.sequence>=0) {"Import chronology mismatch"}
                add(ImportChronologyRow(row,order));bytes+=size
            }}
        }
    }
    @Synchronized override fun resetAdmission() {
        check(!closed && recordsDone && sealed==null)
        db.delete("google_matches",null,null)
    }
    @Synchronized override fun admitPreview(rows:List<ImportStageRow>,check:()->Unit):ImportStageDuplicates {
        check(!closed && recordsDone && sealed==null);require(rows.size<=200)
        var visits=0;var points=0
        db.beginTransaction()
        try {
            for(row in rows) {check()
                val match=if(row.kind==ImportRecordKind.visit) TimelineImportMatch.from(row.visit()) else TimelineImportMatch.from(row.point())
                val span=if(match.visit) 1 else 0
                val duplicate=db.rawQuery("SELECT latitude,longitude,time,departure FROM google_matches WHERE kind=? AND bucket BETWEEN ? AND ? AND latitude BETWEEN ? AND ?",arrayOf(row.kind.ordinal.toString(),(match.bucket-span).toString(),(match.bucket+span).toString(),(match.coordinate.latitude-0.001).toString(),(match.coordinate.latitude+0.001).toString())).use {cursor->
                    var found=false;while(cursor.moveToNext()) {check()
                        if(match.matches(TimelineImportMatch(match.visit,Coordinate(cursor.getDouble(0),cursor.getDouble(1)),java.time.Instant.parse(cursor.getString(2)),if(cursor.isNull(3)) null else java.time.Instant.parse(cursor.getString(3))))) {found=true;break}
                    };found
                }
                if(duplicate) {if(match.visit) visits++ else points++}
                else db.execSQL("INSERT INTO google_matches(kind,bucket,latitude,longitude,time,departure) VALUES(?,?,?,?,?,?)",arrayOf<Any?>(row.kind.ordinal,match.bucket,match.coordinate.latitude,match.coordinate.longitude,match.time.toString(),match.departure?.toString()))
            }
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        return ImportStageDuplicates(visits,points)
    }
    @Synchronized override fun page(after:Pair<ImportRecordKind,String>?,limit:Int):List<ImportStageRow> {
        check(!closed && recordsDone);require(limit in 1..200)
        val clause=if(after==null) "" else "WHERE (kind,id)>(?,?)"
        val args=after?.let {arrayOf(it.first.ordinal.toString(),it.second)}
        return db.rawQuery("SELECT kind,id,payload,baseline,provided_id FROM records $clause ORDER BY kind,id LIMIT $limit",args).use {cursor->
            buildList {
                var retained=0
                while(cursor.moveToNext()) {
                    val payload=cursor.getString(2);val bytes=payload.toByteArray(Charsets.UTF_8).size
                    if(bytes>ImportRecordLimits.payloadBytes) throw ImportRecordTooLarge()
                    if(isNotEmpty() && retained+bytes>ImportRecordLimits.batchBytes) break
                    add(ImportStageRow(ImportRecordKind.entries[cursor.getInt(0)],cursor.getString(1),payload,if(cursor.isNull(3)) null else cursor.getString(3),cursor.getInt(4)!=0))
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
        val digest=MessageDigest.getInstance("SHA-256");digest.update(source.name.toByteArray(Charsets.UTF_8))
        var recordCount=0L
        batches(check) {rows->rows.forEach {row->
            recordCount++
            check()
            for(value in listOf(row.kind.name,row.id,row.payload,row.baseline,row.explicitIdentity.toString())) {
                if(value==null) digest.update(0) else {
                    digest.update(1);val bytes=value.toByteArray(Charsets.UTF_8)
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
                }
            }
        }}
        run {
            var orderedCount=0L
            chronologicalBatches(check) {rows->for(item in rows) {
                orderedCount++;check()
                for(value in listOf(item.row.kind.name,item.row.id,item.cursor.seconds.toString(),item.cursor.nanos.toString(),item.cursor.sequence.toString())) {
                    val bytes=value.toByteArray(Charsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
                }
            }}
            check(orderedCount==recordCount) {"Import chronology is incomplete"}
        }
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
