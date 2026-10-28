package com.bontecou.whereday.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import java.io.IOException
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Reads only an explicit content URI. No library scan, network request, or permission request. */
class AndroidPhotoSource internal constructor(
    private val resolver:android.content.ContentResolver,
    private val canReadOriginal:()->Boolean,
    private val originalUri:(Uri)->Uri?=::originalMediaPhotoUri,
    private val identity:(Uri)->String?={null}
) {
    constructor(context:Context):this(context.applicationContext.contentResolver,AndroidPhotoAccess(context.applicationContext)::originalLocationsAllowed,identity=AndroidPhotoIdentity(context.applicationContext)::resolve)
    private fun uri(value:String)=Uri.parse(value).also {
        require(it.scheme=="content" && !it.authority.isNullOrBlank()) {"Select a photo through the system picker."}
    }
    suspend fun metadata(reference:String):PhotoAssetMetadata=withContext(Dispatchers.IO) {
        val uri=uri(reference)
        require(resolver.getType(uri)?.startsWith("image/")==true) {"Select an image."}
        var libraryTime:Instant?=null
        try {
            resolver.query(uri,arrayOf(MediaStore.Images.ImageColumns.DATE_TAKEN),null,null,null)?.use {cursor->
                val column=cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN)
                if(column>=0 && cursor.moveToFirst() && !cursor.isNull(column))
                    cursor.getLong(column).takeIf {it>0}?.let {libraryTime=Instant.ofEpochMilli(it)}
            }
        } catch(_:IllegalArgumentException) { /* Document providers need not expose MediaStore columns. */ }
        catch(_:android.database.sqlite.SQLiteException) { /* Read EXIF from the granted file instead. */ }
        var gps:Coordinate?=null
        var exifTime:Instant?=null
        val original=originalUri(uri)
        val file=if(original!=null && canReadOriginal()) {
            try {resolver.openFileDescriptor(original,"r") ?: resolver.openFileDescriptor(uri,"r")}
            catch(_:SecurityException) {resolver.openFileDescriptor(uri,"r")}
            catch(_:UnsupportedOperationException) {resolver.openFileDescriptor(uri,"r")}
            catch(_:IOException) {resolver.openFileDescriptor(uri,"r")}
        } else resolver.openFileDescriptor(uri,"r")
        file?.use {file->
            currentCoroutineContext().ensureActive()
            try {
                val exif=ExifInterface(file.fileDescriptor)
                gps=if(original!=null && !canReadOriginal()) null else exif.latLong?.let {runCatching {Coordinate(it[0],it[1])}.getOrNull()}
                exifTime=photoExifInstant(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL),exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL),exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL))
            } catch(_:IOException) { /* A readable image can legitimately have no supported EXIF. */ }
        } ?: throw java.io.FileNotFoundException("Photo is no longer available")
        currentCoroutineContext().ensureActive()
        PhotoAssetMetadata(reference,libraryTime ?: exifTime,gps,identity(uri))
    }
    suspend fun thumbnail(reference:String,maxPixels:Int=256):Bitmap=withContext(Dispatchers.IO) {
        require(maxPixels in 32..2048)
        val uri=uri(reference)
        currentCoroutineContext().ensureActive()
        val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver,uri)) {decoder,info,_->
            decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            val scale=minOf(1.0,maxPixels.toDouble()/maxOf(info.size.width,info.size.height))
            decoder.setTargetSize(maxOf(1,(info.size.width*scale).roundToInt()),maxOf(1,(info.size.height*scale).roundToInt()))
        }
        if(!currentCoroutineContext().isActive) {bitmap.recycle();currentCoroutineContext().ensureActive()}
        bitmap
    }
}

/** EXIF without an offset is a local wall time, not a UTC instant or the phone's present zone. */
internal fun photoExifInstant(date:String?,offset:String?,subseconds:String?):Instant? {
    if(date==null || offset==null) return null
    return runCatching {
        val formatter=DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss",Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
        val local=LocalDateTime.parse(date,formatter)
        val fraction=subseconds?.takeIf {it.isNotEmpty() && it.all(Char::isDigit)}?.take(9)?.padEnd(9,'0')?.toInt() ?: 0
        local.withNano(fraction).toInstant(ZoneOffset.of(offset))
    }.getOrNull()
}

/** Picker, document and redacted-proxy URIs retain their existing explicit grants/semantics. */
internal fun originalMediaPhotoUri(uri:Uri):Uri? {
    val path=uri.pathSegments
    return if(uri.authority==MediaStore.AUTHORITY && path.size==4 && path[1]=="images" && path[2]=="media" && path[3].toLongOrNull()?.let {it>=0}==true) MediaStore.setRequireOriginal(uri) else null
}
