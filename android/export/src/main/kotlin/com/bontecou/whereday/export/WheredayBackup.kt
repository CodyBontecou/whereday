package com.bontecou.whereday.export

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.*
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The lossless Whereday backup, wire-compatible with iOS `WheredayBackup` (IsoMe/Utilities/WheredayBackupService.swift).
 *
 * Compatibility contract, mirrored from the Swift Codable synthesis:
 * - Top level: `backupFormat` ("whereday-backup"), `formatVersion` (1 single file, 2 multipart part), `exportedAt`,
 *   the optional `part` {backupID, index, count} and all six record arrays. Every array key is required, as
 *   synthesized Swift decoding requires them; unknown keys are ignored, as JSONDecoder ignores them.
 * - Dates are milliseconds since 1970 as JSON numbers (`.millisecondsSince1970`), possibly fractional on iOS.
 *   Wire records keep the raw millisecond value so a decoded file re-encodes without loss.
 * - Identifiers are canonical UUID strings. Android writes them uppercase like Swift's `uuidString`.
 * - Optional values are omitted when absent; record keys are written in sorted order like iOS `.sortedKeys`.
 * - Bounds: at most [WheredayBackupFormat.maximumRecords] records and [WheredayBackupFormat.maximumFileBytes]
 *   bytes per file. Larger journals are written as a complete numbered multipart set.
 *
 * Fields iOS writes that Android has no column for are still parsed into these wire records (so a file is
 * validated exactly as iOS validates it); [WheredayBackupMapping] documents which of them Android drops.
 */
object WheredayBackupFormat {
    const val identifier="whereday-backup"
    const val singleFileVersion=1
    const val multipartVersion=2
    /** iOS ImportPayload.maximumFileSize. */
    const val maximumFileBytes=50L*1024*1024
    /** iOS ImportPayload.maximumRecordCount. */
    const val maximumRecords=250_000
    const val singleFileName="Whereday Backup.json"
    fun partFileName(index:Int,count:Int)="Whereday Backup ${index+1} of $count.json"
    /** Foundation Date.distantPast / Date.distantFuture, the bounds of GoogleTimelineImportService.validDate. */
    const val earliestSeconds=-62135769600.0
    const val latestSeconds=64092211200.0
    fun validMillis(value:Double)=value.isFinite() && value/1000.0>=earliestSeconds && value/1000.0<=latestSeconds
    fun validCoordinate(latitude:Double,longitude:Double)=latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    private val uuidPattern=Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    /** Foundation `UUID(uuidString:)` accepts only the canonical 8-4-4-4-12 form; `UUID.fromString` is looser. */
    fun parseUuid(value:String):UUID?=if(uuidPattern.matches(value)) UUID.fromString(value) else null
    /** CountryDayCalculator.date(for:): strict yyyy-MM-dd that formats back to the same text. */
    fun validDay(value:String)=runCatching {LocalDate.parse(value).toString()==value}.getOrDefault(false)
}

enum class BackupProblem(val diagnostic:String) {
    malformed("This backup file is damaged or incomplete. Nothing was restored."),
    unsupportedVersion("This backup version is not supported. Update Whereday before restoring it."),
    duplicateIdentifiers("The backup contains duplicate record identifiers."),
    invalidRecords("The backup contains invalid records. Nothing was restored."),
    incompleteSet("Select all files from the same backup. Nothing was restored."),
    mixedSelection("Select one import file, or all files from the same backup."),
    fileTooLarge("Choose a file smaller than 50 MB. Larger exports must be split before importing."),
    tooManyRecords("This file contains too many records. Import files with up to 250,000 visits and points."),
}
class WheredayBackupException(val problem:BackupProblem,cause:Throwable?=null):IllegalArgumentException(problem.diagnostic,cause)

enum class BackupCollection(val key:String) {
    // Declaration order is the iOS slice order used to partition a multipart backup.
    visits("visits"),points("points"),recordingSessions("recordingSessions"),savedPlaces("savedPlaces"),photoMoments("photoMoments"),countryStays("countryStays");
    companion object {fun of(key:String)=entries.firstOrNull {it.key==key}}
}

data class BackupPart(val backupId:UUID,val index:Long,val count:Long)

sealed interface BackupRecord {val id:UUID;val collection:BackupCollection}

data class BackupVisitRecord(override val id:UUID,val latitude:Double,val longitude:Double,val arrivedAt:Double,val departedAt:Double?=null,
    val customName:String?=null,val locationName:String?=null,val address:String?=null,val notes:String?=null,val geocodingCompleted:Boolean=false,
    val sourceRaw:String?=null,val confirmationStatusRaw:String?=null,val confirmedAt:Double?=null,val updatedAt:Double?=null,
    val originalLatitude:Double?=null,val originalLongitude:Double?=null,val originalLocationName:String?=null,val originalAddress:String?=null,
    val detectedLatitude:Double?=null,val detectedLongitude:Double?=null,val detectedLocationName:String?=null,val detectedAddress:String?=null,
    val placeSourceRaw:String?=null,val placeDistanceMeters:Double?=null,val originalConfirmationStatusRaw:String?=null,val originalConfirmedAt:Double?=null,
    val originalPlaceSourceRaw:String?=null,val originalPlaceDistanceMeters:Double?=null,val countryCode:String?=null,val countryTimeZoneIdentifier:String?=null,
    val countryResolutionAttemptedAt:Double?=null,val countryDepartureIsInferred:Boolean?=null):BackupRecord {
    override val collection get()=BackupCollection.visits
}
data class BackupPointRecord(override val id:UUID,val latitude:Double,val longitude:Double,val timestamp:Double,val altitude:Double?=null,val speed:Double?=null,
    val horizontalAccuracy:Double,val isOutlier:Boolean=false,val countryCode:String?=null,val countryTimeZoneIdentifier:String?=null,
    val countryResolutionAttemptedAt:Double?=null):BackupRecord {
    override val collection get()=BackupCollection.points
}
data class BackupSessionRecord(override val id:UUID,val startedAt:Double,val endedAt:Double?=null,val customName:String?=null,val notes:String?=null,
    val receivedLocationCount:Long?=null,val distanceFilterMeters:Double?=null,val routeSamplingModeRawValue:String?=null,
    val routeSampleIntervalSeconds:Double?=null):BackupRecord {
    override val collection get()=BackupCollection.recordingSessions
}
data class BackupSavedPlaceRecord(override val id:UUID,val name:String,val latitude:Double,val longitude:Double,val address:String?=null,val radiusMeters:Double,
    val createdAt:Double,val updatedAt:Double,val kindRaw:String?=null,val notes:String?=null):BackupRecord {
    override val collection get()=BackupCollection.savedPlaces
}
data class BackupPhotoRecord(override val id:UUID,val assetLocalIdentifier:String,val takenAt:Double,val latitude:Double,val longitude:Double,
    val coordinateSourceRawValue:String,val lastSyncedAt:Double):BackupRecord {
    override val collection get()=BackupCollection.photoMoments
}
data class BackupCountryStayRecord(override val id:UUID,val countryCode:String,val firstDay:String,val lastDay:String,val note:String?=null):BackupRecord {
    override val collection get()=BackupCollection.countryStays
}

/** The checks of iOS `WheredayBackup.validate()`, applied to one record. */
object WheredayBackupValidation {
    fun valid(record:BackupRecord):Boolean=with(WheredayBackupFormat) {when(record) {
        is BackupVisitRecord->validCoordinate(record.latitude,record.longitude) && validMillis(record.arrivedAt) &&
            (record.departedAt?.let {validMillis(it) && it>=record.arrivedAt} ?: true)
        is BackupPointRecord->validCoordinate(record.latitude,record.longitude) && validMillis(record.timestamp) &&
            record.horizontalAccuracy.isFinite() && record.horizontalAccuracy>=-1 && (record.altitude?.isFinite() ?: true) && (record.speed?.isFinite() ?: true)
        is BackupSavedPlaceRecord->validCoordinate(record.latitude,record.longitude) && record.radiusMeters.isFinite() && record.radiusMeters>0
        is BackupPhotoRecord->validCoordinate(record.latitude,record.longitude) && record.assetLocalIdentifier.isNotEmpty()
        is BackupSessionRecord->validMillis(record.startedAt) && (record.endedAt?.let {validMillis(it) && it>=record.startedAt} ?: true)
        // Swift compares the day strings; for validated yyyy-MM-dd text that equals civil-date order.
        is BackupCountryStayRecord->validDay(record.firstDay) && validDay(record.lastDay) && record.firstDay<=record.lastDay
    }}
}

/** Open-addressed 128-bit identity set: a million UUIDs cost about 32 MiB rather than a String set's ~150 MiB. */
class BackupIdentitySet(initialCapacity:Int=1024) {
    private var keys=LongArray(capacity(initialCapacity)*2);private var used=BooleanArray(keys.size/2);var size=0;private set
    private fun capacity(value:Int):Int {var result=16;while(result<value*2) result=result shl 1;return result}
    /** False when already present. */
    fun add(id:UUID):Boolean {
        if((size+1)*2>used.size) grow()
        return insert(id.mostSignificantBits,id.leastSignificantBits)
    }
    private fun insert(high:Long,low:Long):Boolean {
        val mask=used.size-1;var slot=((high*-7046029254386353131L) xor low).let {(it xor (it ushr 32)).toInt()} and mask
        while(used[slot]) {
            if(keys[slot*2]==high && keys[slot*2+1]==low) return false
            slot=(slot+1) and mask
        }
        used[slot]=true;keys[slot*2]=high;keys[slot*2+1]=low;size++;return true
    }
    private fun grow() {
        val oldKeys=keys;val oldUsed=used
        keys=LongArray(oldKeys.size*2);used=BooleanArray(oldUsed.size*2);size=0
        for(slot in oldUsed.indices) if(oldUsed[slot]) insert(oldKeys[slot*2],oldKeys[slot*2+1])
    }
}

/** One validated file. Record problems are reported after the envelope so a future version says "update" first. */
data class BackupFileSummary(val exportedAtMillis:Double,val formatVersion:Long,val part:BackupPart?,val counts:Map<BackupCollection,Int>) {
    val recordCount get()=counts.values.sum()
}

object WheredayBackupReader {
    private val utf8BomLength=3

    /** iOS treats a JSON object with a top-level `backupFormat` key as a backup, never as a partial visit import.
     * Only top-level names are inspected; anything larger than a backup can be cannot be one. */
    fun isBackup(input:InputStream):Boolean=runCatching {
        val reader=JsonReader(InputStreamReader(LimitedInput(skipBom(input),WheredayBackupFormat.maximumFileBytes),Charsets.UTF_8))
        if(reader.peek()!=JsonToken.BEGIN_OBJECT) return@runCatching false
        reader.beginObject()
        while(reader.hasNext()) {if(reader.nextName()=="backupFormat") return@runCatching true;reader.skipValue()}
        false
    }.getOrDefault(false)

    private fun skipBom(input:InputStream):InputStream {
        val buffered=if(input.markSupported()) input else BufferedInputStream(input)
        buffered.mark(utf8BomLength)
        val head=ByteArray(utf8BomLength);var read=0
        while(read<utf8BomLength) {val n=buffered.read(head,read,utf8BomLength-read);if(n<0) break;read+=n}
        if(!(read==3 && head[0]==0xEF.toByte() && head[1]==0xBB.toByte() && head[2]==0xBF.toByte())) buffered.reset()
        return buffered
    }

    /**
     * Stream one file, delivering each record to [consume] in file order. [identities] are shared by every file of
     * a set so duplicates across parts are found; [fileIdentities] is per file. [check] runs between records.
     * Throws [WheredayBackupException]; on a record problem no further records are delivered.
     */
    suspend fun read(input:InputStream,identities:Map<BackupCollection,BackupIdentitySet>?=null,check:()->Unit={},consume:suspend (BackupRecord)->Unit={}):BackupFileSummary {
        val counts=BackupCollection.entries.associateWith {0}.toMutableMap()
        var format:String?=null;var version:Long?=null;var exportedAt:Double?=null;var part:BackupPart?=null
        val seen=mutableSetOf<String>()
        var recordProblem:BackupProblem?=null
        val fileIdentities=BackupCollection.entries.associateWith {BackupIdentitySet()}
        var crossFileDuplicate=false
        try {
            val reader=JsonReader(InputStreamReader(LimitedInput(skipBom(input),WheredayBackupFormat.maximumFileBytes),Charsets.UTF_8)).apply {isLenient=false}
            reader.beginObject()
            while(reader.hasNext()) {
                val name=reader.nextName()
                if(!seen.add(name)) throw WheredayBackupException(BackupProblem.malformed)
                val collection=BackupCollection.of(name)
                when {
                    name=="backupFormat"->format=reader.requiredString()
                    name=="formatVersion"->version=reader.requiredLong()
                    name=="exportedAt"->exportedAt=reader.requiredDouble()
                    name=="part"->part=reader.optional {readPart(it)}
                    collection!=null->{
                        reader.beginArray()
                        while(reader.hasNext()) {
                            passThrough {check()}
                            val record=readRecord(reader,collection)
                            counts[collection]=counts.getValue(collection)+1
                            if(recordProblem!=null) continue
                            if(counts.values.sum()>WheredayBackupFormat.maximumRecords) {recordProblem=BackupProblem.tooManyRecords;continue}
                            if(!fileIdentities.getValue(collection).add(record.id)) {recordProblem=BackupProblem.duplicateIdentifiers;continue}
                            if(!WheredayBackupValidation.valid(record)) {recordProblem=BackupProblem.invalidRecords;continue}
                            if(identities!=null && !identities.getValue(collection).add(record.id)) crossFileDuplicate=true
                            if(!crossFileDuplicate) passThrough {consume(record)}
                        }
                        reader.endArray()
                    }
                    else->reader.skipValue()
                }
            }
            reader.endObject()
            if(reader.peek()!=JsonToken.END_DOCUMENT) throw WheredayBackupException(BackupProblem.malformed)
        } catch(error:CallerFailure) {throw error.cause!!}
        catch(error:WheredayBackupException) {throw error}
        catch(error:FileTooLarge) {throw WheredayBackupException(BackupProblem.fileTooLarge,error)}
        catch(error:IOException) {throw WheredayBackupException(BackupProblem.malformed,error)}
        catch(error:IllegalStateException) {throw WheredayBackupException(BackupProblem.malformed,error)}
        catch(error:NumberFormatException) {throw WheredayBackupException(BackupProblem.malformed,error)}
        if(format==null || version==null || exportedAt==null || BackupCollection.entries.any {it.key !in seen}) throw WheredayBackupException(BackupProblem.malformed)
        // iOS validate(): the envelope first, then duplicates, then record contents; decode() then checks the count and part.
        if(format!=WheredayBackupFormat.identifier || !((version==1L && part==null) || (version==2L && part!=null))) throw WheredayBackupException(BackupProblem.unsupportedVersion)
        recordProblem?.let {throw WheredayBackupException(it)}
        part?.let {if(it.count<=1 || it.index<0 || it.index>=it.count) throw WheredayBackupException(BackupProblem.incompleteSet)}
        val summary=BackupFileSummary(exportedAt,version,part,counts)
        if(crossFileDuplicate) throw CrossFileDuplicate(summary)
        return summary
    }
    /** Cancellation and storage failures from the caller's callbacks are never reported as a damaged file. */
    private class CallerFailure(cause:Throwable):RuntimeException(cause)
    private inline fun <T> passThrough(block:()->T):T=try {block()} catch(error:Throwable) {throw CallerFailure(error)}

    /** Raised after a complete, otherwise valid file whose identifier repeats one in an earlier file of the set. */
    class CrossFileDuplicate(val summary:BackupFileSummary):RuntimeException()

    /** iOS `combine`: all parts of one backup, each exactly once, with identical metadata; or exactly one single file. */
    fun combine(files:List<BackupFileSummary>):BackupFileSummary {
        val first=files.firstOrNull() ?: throw WheredayBackupException(BackupProblem.incompleteSet)
        val part=first.part ?: if(files.size==1) return first else throw WheredayBackupException(BackupProblem.incompleteSet)
        if(files.size.toLong()!=part.count || files.mapNotNull {it.part?.index}.toSet().size.toLong()!=part.count ||
            !files.all {it.part?.backupId==part.backupId && it.part.count==part.count && it.exportedAtMillis==first.exportedAtMillis})
            throw WheredayBackupException(BackupProblem.incompleteSet)
        val counts=BackupCollection.entries.associateWith {c->files.sumOf {it.counts.getValue(c)}}
        return BackupFileSummary(first.exportedAtMillis,WheredayBackupFormat.multipartVersion.toLong(),part,counts)
    }

    /**
     * Validate a whole selection and deliver its records in part order, reporting problems in the order iOS does:
     * per-file decoding/validation, then set completeness, then identifiers repeated across parts. A multipart
     * selection is read twice (headers, then records); [consume] may see records before a later problem is found,
     * so callers only write inside a transaction that the exception rolls back.
     */
    suspend fun inspect(files:List<()->InputStream>,check:()->Unit={},consume:suspend (BackupRecord)->Unit={}):BackupFileSummary {
        if(files.isEmpty()) throw WheredayBackupException(BackupProblem.incompleteSet)
        if(files.size==1) return combine(listOf(files.single()().use {read(it,null,check,consume)}))
        val identities=BackupCollection.entries.associateWith {BackupIdentitySet()}
        val summaries=mutableListOf<BackupFileSummary>();var duplicate=false
        // Parts are delivered in index order, as iOS combines them, once every header is known.
        val order=files.map {open->open().use {read(it,null,check)}}
        val combined=combine(order)
        val indexed=files.indices.sortedBy {order[it].part?.index ?: 0}
        for(index in indexed) {
            check()
            try {summaries+=files[index]().use {read(it,identities,check,consume)}}
            catch(error:CrossFileDuplicate) {duplicate=true;summaries+=error.summary}
            if(duplicate) break
        }
        if(duplicate) throw WheredayBackupException(BackupProblem.duplicateIdentifiers)
        return combined
    }

    private fun readPart(reader:JsonReader):BackupPart {
        var id:UUID?=null;var index:Long?=null;var count:Long?=null
        reader.beginObject()
        while(reader.hasNext()) when(reader.nextName()) {
            "backupID"->id=reader.requiredUuid()
            "index"->index=reader.requiredLong()
            "count"->count=reader.requiredLong()
            else->reader.skipValue()
        }
        reader.endObject()
        return BackupPart(id ?: malformed(),index ?: malformed(),count ?: malformed())
    }

    private fun readRecord(reader:JsonReader,collection:BackupCollection):BackupRecord {
        val values=HashMap<String,Any?>(48)
        reader.beginObject()
        while(reader.hasNext()) {
            val name=reader.nextName()
            val type=fieldTypes.getValue(collection)[name]
            if(type==null) {reader.skipValue();continue}
            values[name]=when(reader.peek()) {
                JsonToken.NULL->{reader.nextNull();null}
                else->when(type) {
                    FieldType.text->reader.requiredString()
                    FieldType.number->reader.requiredDouble()
                    FieldType.integer->reader.requiredLong()
                    FieldType.flag->reader.requiredBoolean()
                    FieldType.uuid->reader.requiredUuid()
                }
            }
        }
        reader.endObject()
        fun <T> req(name:String):T {@Suppress("UNCHECKED_CAST") return (values[name] ?: malformed()) as T}
        fun <T> opt(name:String):T? {@Suppress("UNCHECKED_CAST") return values[name] as T?}
        return when(collection) {
            BackupCollection.visits->BackupVisitRecord(req("id"),req("latitude"),req("longitude"),req("arrivedAt"),opt("departedAt"),opt("customName"),
                opt("locationName"),opt("address"),opt("notes"),req("geocodingCompleted"),opt("sourceRaw"),opt("confirmationStatusRaw"),opt("confirmedAt"),
                opt("updatedAt"),opt("originalLatitude"),opt("originalLongitude"),opt("originalLocationName"),opt("originalAddress"),opt("detectedLatitude"),
                opt("detectedLongitude"),opt("detectedLocationName"),opt("detectedAddress"),opt("placeSourceRaw"),opt("placeDistanceMeters"),
                opt("originalConfirmationStatusRaw"),opt("originalConfirmedAt"),opt("originalPlaceSourceRaw"),opt("originalPlaceDistanceMeters"),
                opt("countryCode"),opt("countryTimeZoneIdentifier"),opt("countryResolutionAttemptedAt"),opt("countryDepartureIsInferred"))
            BackupCollection.points->BackupPointRecord(req("id"),req("latitude"),req("longitude"),req("timestamp"),opt("altitude"),opt("speed"),
                req("horizontalAccuracy"),req("isOutlier"),opt("countryCode"),opt("countryTimeZoneIdentifier"),opt("countryResolutionAttemptedAt"))
            BackupCollection.recordingSessions->BackupSessionRecord(req("id"),req("startedAt"),opt("endedAt"),opt("customName"),opt("notes"),
                opt("receivedLocationCount"),opt("distanceFilterMeters"),opt("routeSamplingModeRawValue"),opt("routeSampleIntervalSeconds"))
            BackupCollection.savedPlaces->BackupSavedPlaceRecord(req("id"),req("name"),req("latitude"),req("longitude"),opt("address"),req("radiusMeters"),
                req("createdAt"),req("updatedAt"),opt("kindRaw"),opt("notes"))
            BackupCollection.photoMoments->BackupPhotoRecord(req("id"),req("assetLocalIdentifier"),req("takenAt"),req("latitude"),req("longitude"),
                req("coordinateSourceRawValue"),req("lastSyncedAt"))
            BackupCollection.countryStays->BackupCountryStayRecord(req("id"),req("countryCode"),req("firstDay"),req("lastDay"),opt("note"))
        }
    }

    private enum class FieldType {text,number,integer,flag,uuid}
    private val fieldTypes:Map<BackupCollection,Map<String,FieldType>> = run {
        val t=FieldType.text;val n=FieldType.number;val i=FieldType.integer;val f=FieldType.flag;val u=FieldType.uuid
        mapOf(
            BackupCollection.visits to mapOf("id" to u,"latitude" to n,"longitude" to n,"arrivedAt" to n,"departedAt" to n,"customName" to t,"locationName" to t,
                "address" to t,"notes" to t,"geocodingCompleted" to f,"sourceRaw" to t,"confirmationStatusRaw" to t,"confirmedAt" to n,"updatedAt" to n,
                "originalLatitude" to n,"originalLongitude" to n,"originalLocationName" to t,"originalAddress" to t,"detectedLatitude" to n,"detectedLongitude" to n,
                "detectedLocationName" to t,"detectedAddress" to t,"placeSourceRaw" to t,"placeDistanceMeters" to n,"originalConfirmationStatusRaw" to t,
                "originalConfirmedAt" to n,"originalPlaceSourceRaw" to t,"originalPlaceDistanceMeters" to n,"countryCode" to t,"countryTimeZoneIdentifier" to t,
                "countryResolutionAttemptedAt" to n,"countryDepartureIsInferred" to f),
            BackupCollection.points to mapOf("id" to u,"latitude" to n,"longitude" to n,"timestamp" to n,"altitude" to n,"speed" to n,"horizontalAccuracy" to n,
                "isOutlier" to f,"countryCode" to t,"countryTimeZoneIdentifier" to t,"countryResolutionAttemptedAt" to n),
            BackupCollection.recordingSessions to mapOf("id" to u,"startedAt" to n,"endedAt" to n,"customName" to t,"notes" to t,"receivedLocationCount" to i,
                "distanceFilterMeters" to n,"routeSamplingModeRawValue" to t,"routeSampleIntervalSeconds" to n),
            BackupCollection.savedPlaces to mapOf("id" to u,"name" to t,"latitude" to n,"longitude" to n,"address" to t,"radiusMeters" to n,"createdAt" to n,
                "updatedAt" to n,"kindRaw" to t,"notes" to t),
            BackupCollection.photoMoments to mapOf("id" to u,"assetLocalIdentifier" to t,"takenAt" to n,"latitude" to n,"longitude" to n,
                "coordinateSourceRawValue" to t,"lastSyncedAt" to n),
            BackupCollection.countryStays to mapOf("id" to u,"countryCode" to t,"firstDay" to t,"lastDay" to t,"note" to t),
        )
    }

    private fun malformed():Nothing=throw WheredayBackupException(BackupProblem.malformed)
    // Strict token types: Gson coerces numbers and strings into each other; Swift's JSONDecoder does not.
    private fun JsonReader.requiredString():String=if(peek()==JsonToken.STRING) nextString() else malformed()
    private fun JsonReader.requiredDouble():Double=if(peek()==JsonToken.NUMBER) nextDouble() else malformed()
    private fun JsonReader.requiredBoolean():Boolean=if(peek()==JsonToken.BOOLEAN) nextBoolean() else malformed()
    private fun JsonReader.requiredUuid():UUID=WheredayBackupFormat.parseUuid(requiredString()) ?: malformed()
    private fun JsonReader.requiredLong():Long {
        if(peek()!=JsonToken.NUMBER) malformed()
        val text=nextString()
        return text.toLongOrNull() ?: BigDecimal(text).let {value->runCatching {value.longValueExact()}.getOrNull()} ?: malformed()
    }
    private fun <T> JsonReader.optional(read:(JsonReader)->T):T?=if(peek()==JsonToken.NULL) {nextNull();null} else read(this)

    private class FileTooLarge:IOException()
    /** Fails as soon as one byte more than the limit is read, so an oversized file is never fully buffered. */
    private class LimitedInput(source:InputStream,private val limit:Long):FilterInputStream(source) {
        private var count=0L
        override fun read():Int {val value=super.read();if(value>=0) bump(1);return value}
        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {val n=super.read(buffer,offset,length);if(n>0) bump(n.toLong());return n}
        private fun bump(n:Long) {count+=n;if(count>limit) throw FileTooLarge()}
        override fun close() {}
    }
}

/** Supplies every stored record once, in a consistent snapshot, in [BackupCollection] order. */
interface BackupRecordSource {
    suspend fun records(collection:BackupCollection,consume:suspend (BackupRecord)->Unit)
}

data class WrittenBackupFile(val file:File,val name:String,val counts:Map<BackupCollection,Int>,val bytes:Long)

/**
 * Writes a backup in bounded files while keeping one logical backup, like iOS `writeFiles`. A journal that fits one
 * file keeps the single-file version 1 format (including an empty journal); otherwise each file is a version 2 part
 * that older apps reject on its own. Records stream once: each part body is staged privately and the final file is
 * the header followed by that body, so the whole journal is never held in memory. Partitions are filled greedily
 * rather than balanced as iOS does; readers depend only on the part metadata and the per-file limits.
 */
class WheredayBackupWriter(private val maximumRecords:Int=WheredayBackupFormat.maximumRecords,private val maximumBytes:Long=WheredayBackupFormat.maximumFileBytes) {
    init {require(maximumRecords in 1..WheredayBackupFormat.maximumRecords && maximumBytes in 1..WheredayBackupFormat.maximumFileBytes)}

    suspend fun write(directory:File,exportedAtMillis:Long,source:BackupRecordSource,backupId:UUID=UUID.randomUUID(),check:()->Unit={}):List<WrittenBackupFile> {
        require(directory.isDirectory && directory.listFiles().isNullOrEmpty()) {"Backup files need a new, empty directory"}
        // Reserve the largest metadata, as iOS planning does with Int.max index/count.
        val reserved=utf8(header(exportedAtMillis,BackupPart(backupId,Long.MAX_VALUE-1,Long.MAX_VALUE)))+utf8(emptyBody())
        val bodies=mutableListOf<Body>();var current:Body?=null
        try {
            for(collection in BackupCollection.entries) source.records(collection) {record->
                check()
                require(record.collection==collection && WheredayBackupValidation.valid(record)) {"Stored history contains an invalid ${collection.key} record"}
                val text=encode(record);val size=utf8(text)+1
                if(reserved+size>maximumBytes) throw WheredayBackupException(BackupProblem.fileTooLarge)
                val body=current?.takeIf {it.records+1<=maximumRecords && it.bytes+size<=maximumBytes}
                    ?: Body(File(directory,".body-${bodies.size}"),reserved).also {current?.finish();bodies+=it;current=it}
                body.add(collection,text,size)
            }
            current?.finish()
            check()
            if(bodies.size<=1) {
                val counts=bodies.singleOrNull()?.counts ?: BackupCollection.entries.associateWith {0}
                val file=File(directory,WheredayBackupFormat.singleFileName)
                assemble(file,header(exportedAtMillis,null),bodies.singleOrNull()?.file)
                return listOf(WrittenBackupFile(file,file.name,counts,file.length()))
            }
            return bodies.mapIndexed {index,body->
                check()
                val name=WheredayBackupFormat.partFileName(index,bodies.size);val file=File(directory,name)
                assemble(file,header(exportedAtMillis,BackupPart(backupId,index.toLong(),bodies.size.toLong())),body.file)
                if(file.length()>maximumBytes) throw WheredayBackupException(BackupProblem.fileTooLarge)
                WrittenBackupFile(file,name,body.counts,file.length())
            }
        } catch(error:Throwable) {
            runCatching {current?.close()}
            directory.listFiles()?.forEach {it.delete()}
            throw error
        }
    }

    private fun assemble(target:File,header:String,body:File?) {
        BufferedOutputStream(FileOutputStream(target)).use {out->
            out.write(header.toByteArray(Charsets.UTF_8))
            if(body==null) out.write(emptyBody().toByteArray(Charsets.UTF_8)) else {body.inputStream().use {it.copyTo(out,64*1024)};body.delete()}
        }
    }

    private class Body(val file:File,var bytes:Long) {
        private val out=BufferedWriter(OutputStreamWriter(FileOutputStream(file),Charsets.UTF_8),64*1024)
        val counts=BackupCollection.entries.associateWith {0}.toMutableMap();var records=0
        private var open=-1;private var closed=false
        fun add(collection:BackupCollection,text:String,size:Long) {
            advance(collection.ordinal)
            if(counts.getValue(collection)>0) out.write(",")
            out.write(text);counts[collection]=counts.getValue(collection)+1;records++;bytes+=size
        }
        /** Close arrays through [target]-1 and open [target], writing empty arrays for skipped collections. */
        private fun advance(target:Int) {
            while(open<target) {
                if(open>=0) out.write("],")
                open++;out.write("\"${BackupCollection.entries[open].key}\":[")
            }
        }
        fun finish() {if(closed) return;advance(BackupCollection.entries.size-1);out.write("]}");close()}
        fun close() {if(!closed) {closed=true;out.close()}}
    }

    companion object {
        private fun utf8(text:String):Long {
            var bytes=0L;var index=0
            while(index<text.length) {
                val c=text[index]
                bytes+=when {c.code<0x80->1;c.code<0x800->2;Character.isHighSurrogate(c) && index+1<text.length && Character.isLowSurrogate(text[index+1])->{index++;4};else->3}
                index++
            }
            return bytes
        }
        /** `backupFormat` leads so a reader can recognise a backup from its first name. */
        internal fun header(exportedAtMillis:Long,part:BackupPart?):String=buildString {
            append("{\"backupFormat\":\"").append(WheredayBackupFormat.identifier).append("\",\"exportedAt\":").append(exportedAtMillis)
            append(",\"formatVersion\":").append(if(part==null) WheredayBackupFormat.singleFileVersion else WheredayBackupFormat.multipartVersion).append(',')
            if(part!=null) append("\"part\":{\"backupID\":\"").append(part.backupId.toString().uppercase()).append("\",\"count\":").append(part.count)
                .append(",\"index\":").append(part.index).append("},")
        }
        private fun emptyBody()=BackupCollection.entries.joinToString(",",postfix="}") {"\"${it.key}\":[]"}

        /** Swift `JSONEncoder` output for one record: sorted keys, absent optionals omitted, plain decimal numbers. */
        fun encode(record:BackupRecord):String {
            val fields=sortedMapOf<String,Any>()
            fun put(name:String,value:Any?) {if(value!=null) fields[name]=value}
            put("id",record.id)
            when(record) {
                is BackupVisitRecord->{
                    put("latitude",record.latitude);put("longitude",record.longitude);put("arrivedAt",record.arrivedAt);put("departedAt",record.departedAt)
                    put("customName",record.customName);put("locationName",record.locationName);put("address",record.address);put("notes",record.notes)
                    put("geocodingCompleted",record.geocodingCompleted);put("sourceRaw",record.sourceRaw);put("confirmationStatusRaw",record.confirmationStatusRaw)
                    put("confirmedAt",record.confirmedAt);put("updatedAt",record.updatedAt);put("originalLatitude",record.originalLatitude)
                    put("originalLongitude",record.originalLongitude);put("originalLocationName",record.originalLocationName);put("originalAddress",record.originalAddress)
                    put("detectedLatitude",record.detectedLatitude);put("detectedLongitude",record.detectedLongitude);put("detectedLocationName",record.detectedLocationName)
                    put("detectedAddress",record.detectedAddress);put("placeSourceRaw",record.placeSourceRaw);put("placeDistanceMeters",record.placeDistanceMeters)
                    put("originalConfirmationStatusRaw",record.originalConfirmationStatusRaw);put("originalConfirmedAt",record.originalConfirmedAt)
                    put("originalPlaceSourceRaw",record.originalPlaceSourceRaw);put("originalPlaceDistanceMeters",record.originalPlaceDistanceMeters)
                    put("countryCode",record.countryCode);put("countryTimeZoneIdentifier",record.countryTimeZoneIdentifier)
                    put("countryResolutionAttemptedAt",record.countryResolutionAttemptedAt);put("countryDepartureIsInferred",record.countryDepartureIsInferred)
                }
                is BackupPointRecord->{
                    put("latitude",record.latitude);put("longitude",record.longitude);put("timestamp",record.timestamp);put("altitude",record.altitude)
                    put("speed",record.speed);put("horizontalAccuracy",record.horizontalAccuracy);put("isOutlier",record.isOutlier);put("countryCode",record.countryCode)
                    put("countryTimeZoneIdentifier",record.countryTimeZoneIdentifier);put("countryResolutionAttemptedAt",record.countryResolutionAttemptedAt)
                }
                is BackupSessionRecord->{
                    put("startedAt",record.startedAt);put("endedAt",record.endedAt);put("customName",record.customName);put("notes",record.notes)
                    put("receivedLocationCount",record.receivedLocationCount);put("distanceFilterMeters",record.distanceFilterMeters)
                    put("routeSamplingModeRawValue",record.routeSamplingModeRawValue);put("routeSampleIntervalSeconds",record.routeSampleIntervalSeconds)
                }
                is BackupSavedPlaceRecord->{
                    put("name",record.name);put("latitude",record.latitude);put("longitude",record.longitude);put("address",record.address)
                    put("radiusMeters",record.radiusMeters);put("createdAt",record.createdAt);put("updatedAt",record.updatedAt);put("kindRaw",record.kindRaw);put("notes",record.notes)
                }
                is BackupPhotoRecord->{
                    put("assetLocalIdentifier",record.assetLocalIdentifier);put("takenAt",record.takenAt);put("latitude",record.latitude);put("longitude",record.longitude)
                    put("coordinateSourceRawValue",record.coordinateSourceRawValue);put("lastSyncedAt",record.lastSyncedAt)
                }
                is BackupCountryStayRecord->{put("countryCode",record.countryCode);put("firstDay",record.firstDay);put("lastDay",record.lastDay);put("note",record.note)}
            }
            val out=StringWriter()
            JsonWriter(out).use {writer->
                writer.serializeNulls=false;writer.isHtmlSafe=false
                writer.beginObject()
                for((name,value) in fields) {
                    writer.name(name)
                    when(value) {
                        is String->writer.value(value)
                        is Boolean->writer.value(value)
                        is Long->writer.value(value)
                        is Double->writer.jsonValue(number(value))
                        is UUID->writer.value(value.toString().uppercase())
                        else->error("Unsupported backup value")
                    }
                }
                writer.endObject()
            }
            return out.toString()
        }
        /** Integral values print without a fraction; others use the shortest exact decimal, never exponent notation. */
        internal fun number(value:Double):String {
            require(value.isFinite())
            if(value==Math.rint(value) && Math.abs(value)<9.007199254740992E15) return value.toLong().toString()
            return BigDecimal(value.toString()).toPlainString()
        }
    }
}
