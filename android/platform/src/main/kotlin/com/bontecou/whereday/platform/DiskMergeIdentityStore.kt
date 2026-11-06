package com.bontecou.whereday.platform

import android.database.sqlite.SQLiteDatabase
import com.bontecou.whereday.export.MergeIdentityStore
import java.io.Closeable
import java.io.File

/** Scratch index with a bounded page cache. Never open the journal database with this driver. */
internal class DiskMergeIdentityStore(private val file:File):MergeIdentityStore,Closeable {
    private val database=SQLiteDatabase.openOrCreateDatabase(file,null)
    private val statements=try {
        database.rawQuery("PRAGMA journal_mode=OFF",null).use {check(it.moveToFirst())}
        database.execSQL("PRAGMA synchronous=OFF")
        database.execSQL("PRAGMA cache_size=-1024")
        database.execSQL("CREATE TABLE identities(namespace TEXT NOT NULL,identity TEXT NOT NULL,PRIMARY KEY(namespace,identity)) WITHOUT ROWID")
        val insert=database.compileStatement("INSERT OR IGNORE INTO identities(namespace,identity) VALUES(?,?)")
        try {database.compileStatement("SELECT changes()").let {changes ->database.beginTransaction();insert to changes}}
        catch(e:Throwable) {insert.close();throw e}
    } catch(e:Throwable) {database.close();SQLiteDatabase.deleteDatabase(file);throw e}
    override fun insert(namespace:String,identity:String):Boolean {
        val (insert,changes)=statements
        insert.clearBindings();insert.bindString(1,namespace);insert.bindString(2,identity);insert.execute()
        // WITHOUT ROWID has no meaningful last_insert_rowid; changes() reports insert success.
        return changes.simpleQueryForLong()==1L
    }
    override fun close() {
        try {statements.first.close();statements.second.close();if(database.inTransaction()) database.endTransaction()}
        finally {database.close();SQLiteDatabase.deleteDatabase(file)}
    }
}
