package com.bontecou.whereday.platform

import com.bontecou.whereday.domain.Coordinate
import com.bontecou.whereday.domain.PhotoAssetMetadata
import java.io.*
import java.time.Instant
import java.security.MessageDigest
import java.security.DigestOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Private, disposable metadata only. Never contains image bytes or changes journal visibility. */
internal class PhotoSyncSpool private constructor(private val file:File):Closeable {
    private val digest=MessageDigest.getInstance("SHA-256")
    private var checksum:ByteArray?=null
    private var output:DataOutputStream?=DataOutputStream(BufferedOutputStream(DigestOutputStream(FileOutputStream(file),digest)))
    var count=0; private set
    var first:Instant?=null; private set
    var last:Instant?=null; private set
    fun append(value:PhotoAssetMetadata) {
        val writer=checkNotNull(output) {"Photo staging is already sealed"}
        require(value.uri.length<=MAX_URI_BYTES) {"Photo reference is too long"}
        val uri=value.uri.toByteArray(Charsets.UTF_8)
        require(uri.size<=MAX_URI_BYTES) {"Photo reference is too long"}
        writer.writeInt(uri.size);writer.write(uri)
        writer.writeBoolean(value.identity!=null)
        value.identity?.let {identity->require(identity.length<=MAX_URI_BYTES);val bytes=identity.toByteArray(Charsets.UTF_8);require(bytes.size<=MAX_URI_BYTES);writer.writeInt(bytes.size);writer.write(bytes)}
        writer.writeBoolean(value.takenAt!=null)
        value.takenAt?.let {writer.writeLong(it.epochSecond);writer.writeInt(it.nano)}
        writer.writeBoolean(value.gps!=null)
        value.gps?.let {writer.writeDouble(it.latitude);writer.writeDouble(it.longitude)}
        value.takenAt?.let {at->if(first==null || at<first) first=at;if(last==null || at>last) last=at}
        count++
    }
    fun seal() {if(output!=null) {output!!.close();output=null;checksum=digest.digest()}}
    suspend fun batches(consume:suspend(List<PhotoAssetMetadata>)->Unit) {
        check(output==null) {"Seal photo staging before reading"}
        val expected=checkNotNull(checksum)
        val actual=MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use {input->
            val buffer=ByteArray(8192)
            while(true) {
                currentCoroutineContext().ensureActive()
                val size=input.read(buffer);if(size<0) break
                actual.update(buffer,0,size)
            }
        }
        check(MessageDigest.isEqual(expected,actual.digest())) {"Photo staging checksum mismatch"}
        DataInputStream(BufferedInputStream(FileInputStream(file))).use {reader->
            var remaining=count
            while(remaining>0) {
                currentCoroutineContext().ensureActive()
                val batch=ArrayList<PhotoAssetMetadata>(minOf(remaining,500))
                repeat(minOf(remaining,500)) {
                    val size=reader.readInt();require(size in 0..MAX_URI_BYTES) {"Invalid staged reference"}
                    val uri=ByteArray(size);reader.readFully(uri)
                    val identity=if(reader.readBoolean()) {val length=reader.readInt();require(length in 1..MAX_URI_BYTES);val bytes=ByteArray(length);reader.readFully(bytes);bytes.toString(Charsets.UTF_8)} else null
                    val at=if(reader.readBoolean()) Instant.ofEpochSecond(reader.readLong(),reader.readInt().toLong()) else null
                    val gps=if(reader.readBoolean()) Coordinate(reader.readDouble(),reader.readDouble()) else null
                    batch+=PhotoAssetMetadata(uri.toString(Charsets.UTF_8),at,gps,identity)
                }
                consume(batch);remaining-=batch.size
            }
            check(reader.read()==-1) {"Unexpected staged photo data"}
        }
    }
    override fun close() {
        try {output?.close()} finally {
            synchronized(active) {output=null;file.delete();active.remove(file.absolutePath)}
        }
    }
    companion object {
        private const val MAX_URI_BYTES=64*1024
        // The application has one process; shared controllers in that process must not reap live spools.
        private val active=mutableSetOf<String>()
        fun clearOrphans(directory:File)=synchronized(active) {
            directory.listFiles()?.filter {it.name.startsWith("photo-") && it.name.endsWith(".stage") && it.absolutePath !in active}?.forEach {check(it.delete() || !it.exists()) {"Photo staging cleanup failed"}}
            Unit
        }
        fun create(directory:File):PhotoSyncSpool=synchronized(active) {
            check(directory.mkdirs() || directory.isDirectory) {"Photo staging directory is unavailable"}
            clearOrphans(directory)
            val file=File.createTempFile("photo-",".stage",directory)
            try {PhotoSyncSpool(file).also {active.add(file.absolutePath)}} catch(e:Exception) {file.delete();throw e}
        }
    }
}
