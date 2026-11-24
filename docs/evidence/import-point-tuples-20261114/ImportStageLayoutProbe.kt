package com.bontecou.whereday.platform

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.Coordinate
import com.bontecou.whereday.domain.TrackPoint
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Temporary diagnostic, not a replacement/shrunken variant of the original million-record gate. */
class ImportStageLayoutProbe {
    private val count=100_000
    private val epoch=Instant.ofEpochSecond(1_800_000_000)
    private fun point(i:Int)=TrackPoint(id=UUID.nameUUIDFromBytes("synthetic:$i".toByteArray()).toString(),
        coordinate=Coordinate(18.0,-66.0),time=epoch.plusSeconds(i.toLong()),accuracy=0.0)
    private fun hex(bytes:ByteArray):String {
        val digits="0123456789abcdef"
        return buildString {for(b in bytes) {append(digits[(b.toInt() ushr 4) and 15]);append(digits[b.toInt() and 15])}}
    }
    @Test fun storageLayoutsKeepContentsIdentical() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        var expectedDigest:String?=null
        for((iteration,without) in listOf(true,false,false,true).withIndex()) {
            val file=File(context.cacheDir,"layout-probe-${UUID.randomUUID()}.db")
            val db=SQLiteDatabase.openOrCreateDatabase(file,null)
            try {
                db.rawQuery("PRAGMA journal_mode=DELETE",null).use {assertTrue(it.moveToFirst())}
                db.execSQL("PRAGMA synchronous=OFF");db.execSQL("PRAGMA cache_size=-1024");db.execSQL("PRAGMA temp_store=FILE")
                db.execSQL("CREATE TABLE records(kind INTEGER NOT NULL,id TEXT NOT NULL,payload TEXT NOT NULL,baseline TEXT,provided_id INTEGER NOT NULL,PRIMARY KEY(kind,id))"+if(without) " WITHOUT ROWID" else "")
                db.execSQL("CREATE TABLE google_order(kind INTEGER NOT NULL,id TEXT NOT NULL,seconds INTEGER NOT NULL,nanos INTEGER NOT NULL,sequence INTEGER NOT NULL,PRIMARY KEY(kind,id)) WITHOUT ROWID")
                db.execSQL("CREATE INDEX google_chronology ON google_order(kind,seconds,nanos,sequence)")
                db.execSQL("CREATE TABLE google_matches(kind INTEGER NOT NULL,bucket INTEGER NOT NULL,latitude REAL NOT NULL,longitude REAL NOT NULL,time TEXT NOT NULL,departure TEXT)")
                db.execSQL("CREATE INDEX google_match_bucket ON google_matches(kind,bucket,latitude)")
                val insertAt=System.nanoTime()
                db.compileStatement("INSERT INTO records(kind,id,payload,provided_id) VALUES(?,?,?,?)").use {record->
                    db.compileStatement("INSERT INTO google_order(kind,id,seconds,nanos,sequence) VALUES(?,?,?,?,?)").use {order->
                        for(first in 0 until count step 200) {
                            db.beginTransaction()
                            try {
                                for(i in first until minOf(first+200,count)) {
                                    val value=point(i);val payload=ImportStageCodec.gson.toJson(value)
                                    db.rawQuery("SELECT payload,provided_id FROM records WHERE kind=? AND id=?",arrayOf("1",value.id)).use {assertFalse(it.moveToFirst())}
                                    record.bindLong(1,1);record.bindString(2,value.id);record.bindString(3,payload);record.bindLong(4,0);record.executeInsert()
                                    order.bindLong(1,1);order.bindString(2,value.id);order.bindLong(3,value.time.epochSecond);order.bindLong(4,0);order.bindLong(5,i.toLong());order.executeInsert()
                                }
                                db.setTransactionSuccessful()
                            } finally {db.endTransaction()}
                        }
                    }
                }
                val insertNanos=System.nanoTime()-insertAt
                val scans=JSONArray()
                val base="SELECT o.kind,o.id,r.payload,o.seconds,o.nanos,o.sequence,r.baseline,r.provided_id FROM google_order o JOIN records r ON r.kind=o.kind AND r.id=o.id "
                val tail=" ORDER BY o.kind,o.seconds,o.nanos,o.sequence LIMIT 200"
                val plan=JSONArray()
                db.rawQuery("EXPLAIN QUERY PLAN "+base+"WHERE (o.kind,o.seconds,o.nanos,o.sequence)>(?,?,?,?)"+tail,arrayOf("1",epoch.epochSecond.toString(),"0","0")).use {cursor->while(cursor.moveToNext())plan.put(cursor.getString(3))}
                repeat(3) {trial->
                    var after:Array<String>?=null;var seen=0;var queryNanos=0L
                    val digest=MessageDigest.getInstance("SHA-256");val started=System.nanoTime()
                    while(true) {
                        val at=System.nanoTime()
                        val rows=db.rawQuery(base+(if(after==null) "" else "WHERE (o.kind,o.seconds,o.nanos,o.sequence)>(?,?,?,?)")+tail,after).use {cursor->
                            buildList {while(cursor.moveToNext()) {
                                assertEquals(1,cursor.getInt(0));assertTrue(cursor.isNull(6));assertEquals(0,cursor.getInt(7))
                                add(Triple(ImportStageRow(ImportRecordKind.point,cursor.getString(1),cursor.getString(2),explicitIdentity=false),cursor.getLong(3),cursor.getLong(5)))
                                assertEquals(0,cursor.getInt(4))
                            }}
                        }
                        queryNanos+=System.nanoTime()-at
                        if(rows.isEmpty())break
                        assertTrue(rows.size<=200)
                        for((row,seconds,sequence) in rows) {
                            assertEquals(seen.toLong(),sequence);assertEquals(epoch.epochSecond+seen,seconds)
                            assertEquals(point(seen),row.point());digest.update(row.payload.toByteArray());seen++
                        }
                        val last=rows.last();after=arrayOf("1",last.second.toString(),"0",last.third.toString())
                    }
                    assertEquals(count,seen)
                    val checksum=hex(digest.digest())
                    if(expectedDigest==null)expectedDigest=checksum else assertEquals(expectedDigest,checksum)
                    scans.put(JSONObject().put("trial",trial).put("rows",seen).put("queryNanos",queryNanos).put("totalNanos",System.nanoTime()-started).put("payloadSha256",checksum))
                }
                val version=db.rawQuery("SELECT sqlite_version()",null).use {assertTrue(it.moveToFirst());it.getString(0)}
                android.util.Log.i("WdLayoutProbe",JSONObject().put("iteration",iteration).put("withoutRowid",without).put("rows",count).put("insertNanos",insertNanos).put("fileBytes",file.length()).put("sqliteVersion",version).put("plan",plan).put("scans",scans).toString())
            } finally {db.close();SQLiteDatabase.deleteDatabase(file);assertFalse(file.exists())}
        }
    }
}
