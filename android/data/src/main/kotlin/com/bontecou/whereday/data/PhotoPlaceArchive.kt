package com.bontecou.whereday.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.File
import java.io.DataOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.nio.file.Files
import java.time.Instant
import kotlin.math.*

/** Immutable private metadata snapshot. Callers must close it on invalidation, backgrounding or clear. */
class PhotoPlaceArchive private constructor(private val directory:File,private val db:SQLiteDatabase,private val thresholdMeters:Double):Closeable {
    var identity:String=""
        private set
    private var closed=false
    val photoCount:Long get()=locked {scalar("SELECT COUNT(*) FROM photos")}
    val placeCount:Long get()=locked {scalar("SELECT COUNT(*) FROM places")}
    val markerCount:Long get()=locked {scalar("SELECT COUNT(*) FROM areas")}

    /** Directory pagination follows first-photo chronology, independent of the visible viewport. */
    fun places(after:Long=0,limit:Int=50,area:String?=null,areas:Set<String>?=null):List<PhotoPlaceSummary> = locked {
        require(limit in 1..500)
        require(area==null || areas==null)
        require(areas==null || areas.size<=500)
        val keys=areas?.sorted() ?: area?.let {listOf(it)}
        val filter=when {keys==null->"";keys.isEmpty()->" AND 0";else->" AND area IN (${keys.joinToString(",") {"?"}})"}
        val args=mutableListOf(after.toString()).apply {if(keys!=null) addAll(keys);add(limit.toString())}
        db.rawQuery("SELECT * FROM places WHERE id>?$filter ORDER BY id LIMIT ?",args.toTypedArray()).use {cursor->
            buildList {while(cursor.moveToNext()) add(cursor.place())}
        }
    }
    fun place(id:Long):PhotoPlaceSummary?=locked {
        db.rawQuery("SELECT * FROM places WHERE id=?",arrayOf(id.toString())).use {if(it.moveToFirst()) it.place() else null}
    }
    /** Previous keyset boundary: scans at most one page, never retains a cursor trail. */
    fun previousPlaceCursor(after:Long,areas:Set<String>?=null):Long=locked {
        require(after>=0 && (areas==null || areas.size<=500))
        val keys=areas?.sorted()
        val filter=when {keys==null->"";keys.isEmpty()->" AND 0";else->" AND area IN (${keys.joinToString(",") {"?"}})"}
        db.rawQuery("SELECT id FROM places WHERE id<=?$filter ORDER BY id DESC LIMIT 1 OFFSET 50",(listOf(after.toString())+keys.orEmpty()).toTypedArray()).use {if(it.moveToFirst()) it.getLong(0) else 0}
    }
    fun previousPhotoCursor(place:Long,after:Long):Long=locked {
        require(after>=0)
        db.rawQuery("SELECT seq FROM photos WHERE place=? AND seq<=? ORDER BY seq DESC LIMIT 1 OFFSET 50",arrayOf(place.toString(),after.toString())).use {if(it.moveToFirst()) it.getLong(0) else 0}
    }
    fun validPlacePage(page:Int,after:Long,areas:Set<String>?=null):Boolean=locked {
        require(page>=0 && after>=0 && (areas==null || areas.size<=500))
        if(page==0) return@locked after==0L
        val keys=areas?.sorted()
        val filter=when {keys==null->"";keys.isEmpty()->" AND 0";else->" AND area IN (${keys.joinToString(",") {"?"}})"}
        db.rawQuery("SELECT COUNT(*),MAX(id) FROM places WHERE id<=?$filter",(listOf(after.toString())+keys.orEmpty()).toTypedArray()).use {it.moveToFirst();it.getLong(0)==page*50L && it.getLong(1)==after}
    }
    fun validPhotoPage(place:Long,page:Int,after:Long):Boolean=locked {
        require(page>=0 && after>=0)
        if(page==0) return@locked after==0L
        db.rawQuery("SELECT COUNT(*),MAX(seq) FROM photos WHERE place=? AND seq<=?",arrayOf(place.toString(),after.toString())).use {it.moveToFirst();it.getLong(0)==page*50L && it.getLong(1)==after}
    }
    fun markers():List<PhotoAreaSummary> = locked {
        db.rawQuery("SELECT a.*,p.lat,p.lon FROM areas a JOIN places p ON p.id=a.anchor ORDER BY a.anchor",null).use {cursor->
            buildList {while(cursor.moveToNext()) add(PhotoAreaSummary(cursor.string("id"),cursor.long("place_count"),cursor.long("photo_count"),Coordinate(cursor.double("lat"),cursor.double("lon")),cursor.long("anchor"),cursor.string("representative_uri")))}
        }
    }
    fun photos(place:Long,after:Long=0,limit:Int=50):List<PhotoPlaceEntry> = locked {
        require(limit in 1..500)
        db.rawQuery("SELECT * FROM photos WHERE place=? AND seq>? ORDER BY seq LIMIT ?",arrayOf(place.toString(),after.toString(),limit.toString())).use {cursor->
            buildList {while(cursor.moveToNext()) add(PhotoPlaceEntry(cursor.long("seq"),PhotoMoment(cursor.string("uri"),Instant.ofEpochMilli(cursor.long("time")),Coordinate(cursor.double("lat"),cursor.double("lon")),PhotoCoordinateSource.entries.first {it.wireValue==cursor.string("source")})))}
        }
    }
    fun photoCount(place:Long):Long=locked {
        db.rawQuery("SELECT photo_count FROM places WHERE id=?",arrayOf(place.toString())).use {if(it.moveToFirst()) it.getLong(0) else 0}
    }
    fun photoRank(place:Long,uri:String):Int?=locked {
        db.rawQuery("SELECT seq FROM photos WHERE place=? AND uri=?",arrayOf(place.toString(),uri)).use {selected->
            if(!selected.moveToFirst()) return@locked null
            db.rawQuery("SELECT COUNT(*) FROM photos WHERE place=? AND seq<?",arrayOf(place.toString(),selected.getLong(0).toString())).use {count->
                count.moveToFirst();Math.toIntExact(count.getLong(0))
            }
        }
    }
    fun photoSlice(place:Long,offset:Int,limit:Int):List<PhotoPlaceEntry> = locked {
        require(offset>=0 && limit in 1..500)
        db.rawQuery("SELECT * FROM photos WHERE place=? ORDER BY seq LIMIT ? OFFSET ?",arrayOf(place.toString(),limit.toString(),offset.toString())).use {cursor->
            buildList {while(cursor.moveToNext()) add(PhotoPlaceEntry(cursor.long("seq"),PhotoMoment(cursor.string("uri"),Instant.ofEpochMilli(cursor.long("time")),Coordinate(cursor.double("lat"),cursor.double("lon")),PhotoCoordinateSource.entries.first {it.wireValue==cursor.string("source")})))}
        }
    }
    private inline fun <T> locked(block:()->T):T=synchronized(this) {check(!closed) {"Photo directory is closed"};block()}
    override fun close()=synchronized(this) {
        if(!closed) {closed=true;try {db.close()} finally {check(directory.deleteRecursively()) {"Photo directory cache could not be removed"}}}
    }
    private fun scalar(sql:String):Long=db.rawQuery(sql,null).use {it.moveToFirst();it.getLong(0)}

    companion object {
        /** Does not retain source URIs elsewhere or modify the journal; incomplete snapshots are deleted. */
        suspend fun prepare(root:File,repository:PhotoRepository,range:HistoryRange,maximumAnnotations:Int=500,thresholdMeters:Double=35.0,progress:(Long)->Unit={}):PhotoPlaceArchive {
            // withContext can reject a completed result during the return dispatcher handoff.
            // Retain ownership outside it so that cancellation cannot orphan a finished cache.
            var owned:PhotoPlaceArchive?=null
            try {return withContext(Dispatchers.IO) {
            require(maximumAnnotations in 1..500)
            require(thresholdMeters.isFinite() && thresholdMeters>=0)
            check(root.mkdirs() || root.isDirectory)
            val folder=Files.createTempDirectory(root.toPath(),"places-").toFile()
            var archive:PhotoPlaceArchive?=null
            try {
                val db=SQLiteDatabase.openOrCreateDatabase(File(folder,"directory.sqlite"),null)
                val result=PhotoPlaceArchive(folder,db,thresholdMeters);archive=result;owned=result
                db.execSQL("PRAGMA cache_size=-2048")
                db.execSQL("PRAGMA temp_store=FILE")
                db.execSQL("CREATE TABLE places(id INTEGER PRIMARY KEY,photo_count INTEGER NOT NULL,first_time INTEGER NOT NULL,last_time INTEGER NOT NULL,first_uri TEXT NOT NULL,last_uri TEXT NOT NULL,x REAL NOT NULL,y REAL NOT NULL,z REAL NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,cx INTEGER NOT NULL,cy INTEGER NOT NULL,cz INTEGER NOT NULL,area TEXT)")
                db.execSQL("CREATE INDEX place_cells ON places(cx,cy,cz)")
                db.execSQL("CREATE INDEX place_areas ON places(area,id)")
                db.execSQL("CREATE TABLE photos(seq INTEGER PRIMARY KEY,place INTEGER NOT NULL,uri TEXT NOT NULL UNIQUE,time INTEGER NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,source TEXT NOT NULL)")
                db.execSQL("CREATE INDEX place_photos ON photos(place,seq)")
                db.execSQL("CREATE TABLE areas(id TEXT PRIMARY KEY,anchor INTEGER NOT NULL,place_count INTEGER NOT NULL,photo_count INTEGER NOT NULL,representative_uri TEXT NOT NULL,last_time INTEGER NOT NULL)")
                val job=currentCoroutineContext().job
                val digest=MessageDigest.getInstance("SHA-256")
                val fingerprint=DataOutputStream(DigestOutputStream(object:OutputStream() {override fun write(value:Int) {};override fun write(buffer:ByteArray,offset:Int,length:Int) {}},digest))
                fun text(value:String) {val bytes=value.toByteArray(Charsets.UTF_8);fingerprint.writeInt(bytes.size);fingerprint.write(bytes)}
                fingerprint.writeInt(1);text(range.from.toString());text(range.until.toString())
                fingerprint.writeInt(maximumAnnotations);fingerprint.writeDouble(thresholdMeters)
                var processed=0L
                repository.forEachMomentPage(range) {page->
                    db.beginTransaction()
                    try {for(photo in page) {
                        job.ensureActive();val place=result.append(photo);fingerprint.writeLong(place)
                        text(photo.uri);text(photo.takenAt.toString());fingerprint.writeDouble(photo.coordinate.latitude);fingerprint.writeDouble(photo.coordinate.longitude);text(photo.source.wireValue)
                    };db.setTransactionSuccessful()}
                    finally {db.endTransaction()}
                    processed+=page.size
                    progress(processed)
                }
                result.coarsen(maximumAnnotations) {job.ensureActive()}
                // Bind saved numeric IDs to the produced membership/centroid/area mapping too.
                // This remains safe across clustering-code changes, even with identical inputs.
                fingerprint.writeInt(-1);fingerprint.writeLong(processed);fingerprint.writeLong(result.placeCount)
                db.rawQuery("SELECT id,photo_count,first_time,last_time,first_uri,last_uri,lat,lon,area FROM places ORDER BY id",null).use {cursor->
                    while(cursor.moveToNext()) {
                        job.ensureActive()
                        repeat(4) {fingerprint.writeLong(cursor.getLong(it))}
                        text(cursor.getString(4));text(cursor.getString(5))
                        fingerprint.writeDouble(cursor.getDouble(6));fingerprint.writeDouble(cursor.getDouble(7));text(cursor.getString(8))
                    }
                }
                fingerprint.writeLong(result.markerCount)
                db.rawQuery("SELECT id,anchor,place_count,photo_count,representative_uri,last_time FROM areas ORDER BY anchor,id",null).use {cursor->
                    while(cursor.moveToNext()) {
                        job.ensureActive();text(cursor.getString(0));for(index in 1..3) fingerprint.writeLong(cursor.getLong(index))
                        text(cursor.getString(4));fingerprint.writeLong(cursor.getLong(5))
                    }
                }
                result.identity=digest.digest().joinToString("") {"%02x".format(java.util.Locale.ROOT,it.toInt() and 255)}
                job.ensureActive()
                result
            } catch(error:Throwable) {
                try {archive?.close() ?: check(folder.deleteRecursively())} catch(cleanup:Throwable) {error.addSuppressed(cleanup)}
                throw error
            }
            }} catch(error:Throwable) {
                withContext(NonCancellable+Dispatchers.IO) {
                    try {owned?.close()} catch(cleanup:Throwable) {error.addSuppressed(cleanup)}
                }
                throw error
            }
        }
    }

    private fun append(photo:PhotoMoment):Long {
        val vector=vector(photo.coordinate)
        val cell=cell(photo.coordinate,thresholdMeters)
        var nearest:Long?=null
        var best=Double.POSITIVE_INFINITY
        var oldVector=doubleArrayOf(0.0,0.0,0.0)
        // A cursor streams dense cells; it does not materialize the candidate set.
        db.rawQuery("SELECT id,x,y,z,lat,lon FROM places WHERE cx BETWEEN ? AND ? AND cy BETWEEN ? AND ? AND cz BETWEEN ? AND ? ORDER BY id",arrayOf(cell[0]-1,cell[0]+1,cell[1]-1,cell[1]+1,cell[2]-1,cell[2]+1).map {it.toString()}.toTypedArray()).use {c->
            while(c.moveToNext()) {
                val distance=photo.coordinate.distanceTo(Coordinate(c.getDouble(4),c.getDouble(5)))
                if(distance<=thresholdMeters && distance<best) {nearest=c.getLong(0);best=distance;oldVector=doubleArrayOf(c.getDouble(1),c.getDouble(2),c.getDouble(3))}
            }
        }
        val id=nearest ?: (scalar("SELECT COALESCE(MAX(id),0) FROM places")+1)
        if(nearest==null) {
            val center=coordinate(vector)
            // Match the source builder's initial source cell, then reindex its spherical centroid after joins.
            db.execSQL("INSERT INTO places VALUES(?,1,?,?,?,?,?,?,?,?,?,?,?,?,NULL)",arrayOf(id,photo.takenAt.toEpochMilli(),photo.takenAt.toEpochMilli(),photo.uri,photo.uri,vector[0],vector[1],vector[2],center.latitude,center.longitude,cell[0],cell[1],cell[2]))
        } else {
            val sum=DoubleArray(3) {oldVector[it]+vector[it]};val center=coordinate(sum);val nextCell=cell(center,thresholdMeters)
            db.execSQL("UPDATE places SET photo_count=photo_count+1,last_time=?,last_uri=?,x=?,y=?,z=?,lat=?,lon=?,cx=?,cy=?,cz=? WHERE id=?",arrayOf(photo.takenAt.toEpochMilli(),photo.uri,sum[0],sum[1],sum[2],center.latitude,center.longitude,nextCell[0],nextCell[1],nextCell[2],id))
        }
        db.execSQL("INSERT INTO photos(place,uri,time,lat,lon,source) VALUES(?,?,?,?,?,?)",arrayOf(id,photo.uri,photo.takenAt.toEpochMilli(),photo.coordinate.latitude,photo.coordinate.longitude,photo.source.wireValue))
        return id
    }

    private fun coarsen(maximum:Int,checkpoint:()->Unit) {
        val count=placeCount
        var width=1.0/1024
        while(true) {
            checkpoint()
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM areas")
                var after=0L
                while(true) {
                    val page=places(after,500)
                    if(page.isEmpty()) break
                    for(place in page) {
                        checkpoint()
                        val key=if(count<=maximum) "place:${place.id}" else if(width>=360) "area:0:0" else "area:${floor((place.coordinate.latitude+90)/width).toLong()}:${floor((place.coordinate.longitude+180)/width).toLong()}"
                        db.execSQL("UPDATE places SET area=? WHERE id=?",arrayOf(key,place.id))
                        db.execSQL("INSERT OR IGNORE INTO areas VALUES(?,?,0,0,?,?)",arrayOf(key,place.id,place.representativeUri,place.last.toEpochMilli()))
                        db.execSQL("UPDATE areas SET place_count=place_count+1,photo_count=photo_count+?,representative_uri=CASE WHEN last_time<? OR (last_time=? AND representative_uri<?) THEN ? ELSE representative_uri END,last_time=MAX(last_time,?) WHERE id=?",arrayOf(place.photoCount,place.last.toEpochMilli(),place.last.toEpochMilli(),place.representativeUri,place.representativeUri,place.last.toEpochMilli(),key))
                    }
                    after=page.last().id
                }
                db.setTransactionSuccessful()
            } finally {db.endTransaction()}
            if(markerCount<=maximum) break
            width*=2
        }
    }
}

data class PhotoPlaceSummary(val id:Long,val photoCount:Long,val coordinate:Coordinate,val first:Instant,val last:Instant,val representativeUri:String)
data class PhotoAreaSummary(val id:String,val placeCount:Long,val photoCount:Long,val coordinate:Coordinate,val anchor:Long,val representativeUri:String?=null) {val isArea get()=id.startsWith("area:")}
data class PhotoPlaceEntry(val sequence:Long,val photo:PhotoMoment)
private fun Cursor.long(name:String)=getLong(getColumnIndexOrThrow(name))
private fun Cursor.double(name:String)=getDouble(getColumnIndexOrThrow(name))
private fun Cursor.string(name:String)=getString(getColumnIndexOrThrow(name))
private fun Cursor.place()=PhotoPlaceSummary(long("id"),long("photo_count"),Coordinate(double("lat"),double("lon")),Instant.ofEpochMilli(long("first_time")),Instant.ofEpochMilli(long("last_time")),string("last_uri"))
private fun vector(coordinate:Coordinate):DoubleArray {
    val latitude=coordinate.latitude*PI/180;val longitude=coordinate.longitude*PI/180
    return doubleArrayOf(cos(latitude)*cos(longitude),cos(latitude)*sin(longitude),sin(latitude))
}
private fun coordinate(vector:DoubleArray)=Coordinate(atan2(vector[2],sqrt(vector[0]*vector[0]+vector[1]*vector[1]))*180/PI,atan2(vector[1],vector[0])*180/PI)
private fun cell(coordinate:Coordinate,thresholdMeters:Double)=vector(coordinate).map {floor(it*6371000/max(1.0,thresholdMeters*1.02)).toLong()}.toLongArray()
