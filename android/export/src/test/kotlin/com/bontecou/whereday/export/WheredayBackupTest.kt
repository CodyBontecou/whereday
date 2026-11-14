package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Backup format parity with the actual iOS WheredayBackupService (fixtures from generate_backup_fixtures.py). */
class WheredayBackupTest {
    private val fixture:JsonObject=requireNotNull(javaClass.getResourceAsStream("/ios-backup.json")).bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
    private val iosSingle=fixture["single"].asString
    private val iosParts=fixture["multipart"].asJsonArray.map {it.asJsonObject["name"].asString to it.asJsonObject["text"].asString}
    private val time=Instant.ofEpochMilli(1_780_000_000_125)

    private fun opener(text:String):()->InputStream={text.byteInputStream()}
    private fun collect(files:List<()->InputStream>):Pair<BackupFileSummary,List<BackupRecord>> = runBlocking {
        val records=mutableListOf<BackupRecord>()
        WheredayBackupReader.inspect(files,consume={records+=it}) to records
    }
    private fun problem(files:List<String>):BackupProblem? = try {collect(files.map(::opener));null} catch(error:WheredayBackupException) {error.problem}
    private fun mutate(text:String,change:(JsonObject)->Unit):String=JsonParser.parseString(text).asJsonObject.also(change).toString()
    private fun JsonObject.rows(key:String):JsonArray=this[key].asJsonArray
    private fun tempDir():File=Files.createTempDirectory("whereday-backup-test").toFile().also {it.deleteOnExit()}

    @Test fun iosSingleFileBackupDecodesEveryCollection() {
        val (summary,records)=collect(listOf(opener(iosSingle)))
        assertNull(summary.part);assertEquals(1L,summary.formatVersion)
        assertEquals(mapOf(BackupCollection.visits to 2,BackupCollection.points to 2,BackupCollection.recordingSessions to 1,
            BackupCollection.savedPlaces to 2,BackupCollection.photoMoments to 2,BackupCollection.countryStays to 1),summary.counts)
        val visit=records.filterIsInstance<BackupVisitRecord>().single {it.customName!=null}
        assertEquals("confirmed",visit.originalConfirmationStatusRaw);assertEquals(42.5,visit.placeDistanceMeters!!,0.0)
        assertEquals(1_780_000_000_125.0,visit.arrivedAt,0.0)
        val session=records.filterIsInstance<BackupSessionRecord>().single()
        assertEquals("time",session.routeSamplingModeRawValue);assertEquals(12L,session.receivedLocationCount)
    }

    /** Each record re-encodes to the same JSON object iOS wrote: same keys, omitted optionals and values. */
    @Test fun androidEncoderReproducesIosRecordsExactly() {
        val root=JsonParser.parseString(iosSingle).asJsonObject
        val (_,records)=collect(listOf(opener(iosSingle)))
        for(record in records) {
            val ios=root.rows(record.collection.key).map {it.asJsonObject}.single {it["id"].asString==record.id.toString().uppercase()}
            assertEquals(ios,JsonParser.parseString(WheredayBackupWriter.encode(record)))
            // Keys are written sorted, as Swift `.sortedKeys`.
            assertEquals(ios.keySet().toList(),JsonParser.parseString(WheredayBackupWriter.encode(record)).asJsonObject.keySet().toList())
        }
    }

    @Test fun iosMultipartBackupCombinesInAnyOrderToTheSingleFileRecords() {
        assertEquals(listOf("Whereday Backup 1 of 4.json","Whereday Backup 2 of 4.json","Whereday Backup 3 of 4.json","Whereday Backup 4 of 4.json"),iosParts.map {it.first})
        val (summary,records)=collect(iosParts.reversed().map {opener(it.second)})
        assertEquals(10,summary.recordCount);assertEquals(4L,summary.part!!.count)
        val (_,single)=collect(listOf(opener(iosSingle)))
        assertEquals(single.map {it.collection to it.id}.toSet(),records.map {it.collection to it.id}.toSet())
        assertEquals(single.associateBy {it.id},records.associateBy {it.id})
        // Parts are delivered in index order; iOS's first part holds the two visits.
        assertEquals(BackupCollection.visits,records.first().collection)
    }

    @Test fun iosRecordsMapToAndroidModelsWithDocumentedChoices() {
        val (_,records)=collect(listOf(opener(iosSingle)))
        val visit=WheredayBackupMapping.visit(records.filterIsInstance<BackupVisitRecord>().single {it.customName!=null})
        assertEquals("8e0c7c1d-3e1b-4c33-9a41-6d3c1b2a0001",visit.id)
        assertEquals("Café ☕️",visit.name);assertEquals(VisitSource.manual,visit.source);assertEquals(Confirmation.corrected,visit.confirmation)
        assertEquals(Coordinate(37.8,-122.5),visit.originalCoordinate);assertEquals("Detected",visit.detectedName)
        assertEquals(time.plusSeconds(600),visit.departedAt);assertTrue(visit.departureInferred);assertEquals("America/Los_Angeles",visit.timeZone)
        val open=WheredayBackupMapping.visit(records.filterIsInstance<BackupVisitRecord>().single {it.customName==null})
        assertNull(open.departedAt);assertEquals(VisitSource.automatic,open.source)
        val points=records.filterIsInstance<BackupPointRecord>().map(WheredayBackupMapping::point)
        assertEquals(setOf(8.0,-1.0),points.map {it.accuracy}.toSet());assertTrue(points.single {it.accuracy==8.0}.outlier)
        val places=records.filterIsInstance<BackupSavedPlaceRecord>().map {WheredayBackupMapping.place(it)!!}
        assertEquals(25.0,places.single {it.name=="Desk"}.radius,0.0);assertEquals(900.0,places.single {it.name=="Home"}.radius,0.0)
        val photos=records.filterIsInstance<BackupPhotoRecord>().map(WheredayBackupMapping::photo)
        // An iOS Photos identifier cannot be opened on Android; a content reference can.
        assertEquals(1,photos.count {it==null})
        assertEquals(PhotoCoordinateSource.route,photos.filterNotNull().single().moment.source)
        val stay=WheredayBackupMapping.stay(records.filterIsInstance<BackupCountryStayRecord>().single())!!
        assertEquals(LocalDate.parse("2026-04-06"),stay.firstDay);assertEquals("JP",stay.countryCode)
        assertEquals("Outing",WheredayBackupMapping.session(records.filterIsInstance<BackupSessionRecord>().single()).name)
    }

    private val androidVisit=Visit("visit-not-uuid",Coordinate(48.85,2.35),time,time.plusSeconds(1800),"Louvre","Rue de Rivoli","Notes",VisitSource.manual,Confirmation.corrected,
        Coordinate(48.86,2.36),"Old","Old address",Coordinate(48.87,2.37),"Detected","Detected address",time,time.plusSeconds(5),"userEntered",12.0,"FR","Europe/Paris",true,time)
    private val androidRecords:List<BackupRecord> by lazy {
        val exported=time.toEpochMilli()
        listOf(WheredayBackupMapping.record(androidVisit),
            WheredayBackupMapping.record(Visit(UUID.randomUUID().toString(),Coordinate(1.0,2.0),time.plusSeconds(4000))),
            WheredayBackupMapping.record(TrackPoint(UUID.randomUUID().toString(),Coordinate(48.85,2.35),time,4.5,35.0,1.25,false,"FR","Europe/Paris",time)),
            WheredayBackupMapping.record(TrackPoint(UUID.randomUUID().toString(),Coordinate(0.0,0.0),time.plusSeconds(1),Double.NaN,Double.POSITIVE_INFINITY)),
            WheredayBackupMapping.record(RecordingSession(UUID.randomUUID().toString(),time,time.plusSeconds(3600),"Walk","Notes")),
            WheredayBackupMapping.record(SavedPlace(UUID.randomUUID().toString(),"Home",Coordinate(48.85,2.35),"Address",120.0),exported),
            WheredayBackupMapping.record(PhotoMoment("content://media/external/images/media/7",time,Coordinate(48.85,2.35),PhotoCoordinateSource.visit),time),
            WheredayBackupMapping.record(CountryStay(UUID.randomUUID().toString(),"FR",LocalDate.parse("2026-05-01"),LocalDate.parse("2026-05-04"))))
    }
    private class ListSource(private val records:List<BackupRecord>):BackupRecordSource {
        override suspend fun records(collection:BackupCollection,consume:suspend (BackupRecord)->Unit) {records.filter {it.collection==collection}.forEach {consume(it)}}
    }

    @Test fun androidBackupWritesSingleFileThatRoundTripsAndroidModels():Unit=runBlocking {
        val directory=tempDir()
        val files=WheredayBackupWriter().write(directory,time.toEpochMilli(),ListSource(androidRecords))
        assertEquals(listOf(WheredayBackupFormat.singleFileName),files.map {it.name})
        val text=files.single().file.readText()
        assertTrue(text.startsWith("{\"backupFormat\":\"whereday-backup\""))
        assertTrue(WheredayBackupReader.isBackup(files.single().file.inputStream()))
        val (summary,records)=collect(listOf {files.single().file.inputStream()})
        assertEquals(1L,summary.formatVersion);assertNull(summary.part);assertEquals(8,summary.recordCount)
        assertEquals(androidRecords,records)
        val visit=WheredayBackupMapping.visit(records.first() as BackupVisitRecord)
        // Android-only identifiers become stable UUIDs; every other Android visit field survives.
        assertEquals(androidVisit.copy(id=visit.id),visit);assertEquals(WheredayBackupMapping.backupId("visit-not-uuid","visit").toString(),visit.id)
        val sanitized=records.filterIsInstance<BackupPointRecord>().single {it.latitude==0.0}
        assertEquals(-1.0,sanitized.horizontalAccuracy,0.0);assertNull(sanitized.altitude)
        assertEquals(PhotoCoordinateSource.visit,WheredayBackupMapping.photo(records.filterIsInstance<BackupPhotoRecord>().single())!!.moment.source)
    }

    @Test fun emptyJournalKeepsSingleFileFormatWithEveryArray():Unit=runBlocking {
        val files=WheredayBackupWriter().write(tempDir(),time.toEpochMilli(),ListSource(emptyList()))
        val root=JsonParser.parseString(files.single().file.readText()).asJsonObject
        BackupCollection.entries.forEach {assertEquals(0,root.rows(it.key).size())}
        assertEquals(0,collect(listOf {files.single().file.inputStream()}).first.recordCount)
    }

    @Test fun multipartSplitHonoursRecordAndByteLimitsAndJoinsCompletely():Unit=runBlocking {
        val directory=tempDir()
        val files=WheredayBackupWriter(maximumRecords=3).write(directory,time.toEpochMilli(),ListSource(androidRecords))
        assertEquals((1..3).map {"Whereday Backup $it of 3.json"},files.map {it.name})
        assertEquals(files.map {it.name}.toSet(),directory.list()!!.toSet()) // Private part bodies are removed.
        val summaries=files.map {file->WheredayBackupReader.read(file.file.inputStream())}
        assertTrue(summaries.all {it.formatVersion==2L && it.recordCount<=3 && it.part!!.count==3L})
        assertEquals(setOf(0L,1L,2L),summaries.map {it.part!!.index}.toSet());assertEquals(1,summaries.map {it.part!!.backupId}.toSet().size)
        val (combined,records)=collect(files.shuffled().map {file->{file.file.inputStream()}})
        assertEquals(8,combined.recordCount);assertEquals(androidRecords,records)
        // A byte bound smaller than the whole journal splits by size, each file within the bound.
        val bounded=tempDir();val limit=2_000L
        val bySize=WheredayBackupWriter(maximumBytes=limit).write(bounded,time.toEpochMilli(),ListSource(androidRecords))
        assertTrue(bySize.size>1);assertTrue(bySize.all {it.file.length()<=limit})
        assertEquals(androidRecords,collect(bySize.map {file->{file.file.inputStream()}}).second)
    }

    @Test fun recordLargerThanAFileFailsWithoutLeavingFiles():Unit=runBlocking {
        val directory=tempDir()
        val huge=WheredayBackupMapping.record(SavedPlace(UUID.randomUUID().toString(),"x".repeat(5_000),Coordinate(0.0,0.0)),0)
        val error=runCatching {WheredayBackupWriter(maximumBytes=4_000).write(directory,0,ListSource(listOf(huge)))}.exceptionOrNull()
        assertEquals(BackupProblem.fileTooLarge,(error as WheredayBackupException).problem)
        assertTrue(directory.list()!!.isEmpty())
    }

    @Test fun moreThanTheRecordLimitSplitsAndEachPartStaysAdmissible():Unit=runBlocking {
        val count=WheredayBackupFormat.maximumRecords+1
        val source=object:BackupRecordSource {
            override suspend fun records(collection:BackupCollection,consume:suspend (BackupRecord)->Unit) {
                if(collection==BackupCollection.points) repeat(count) {consume(BackupPointRecord(UUID(0,it.toLong()),0.0,0.0,1_780_000_000_125.0,horizontalAccuracy=5.0))}
            }
        }
        val files=WheredayBackupWriter().write(tempDir(),time.toEpochMilli(),source)
        assertEquals(2,files.size);assertEquals(WheredayBackupFormat.maximumRecords,files[0].counts.getValue(BackupCollection.points))
        var delivered=0
        val combined=WheredayBackupReader.inspect(files.map {file->{file.file.inputStream()}}) {delivered++}
        assertEquals(count,combined.recordCount);assertEquals(count,delivered)
        // The same records in one file exceed the per-file record limit, as iOS rejects.
        val oversized=File(tempDir(),"one.json");oversized.outputStream().bufferedWriter().use {out->
            out.write(WheredayBackupWriter.header(0,null));out.write("\"points\":[")
            repeat(count) {if(it>0) out.write(",");out.write(WheredayBackupWriter.encode(BackupPointRecord(UUID(1,it.toLong()),0.0,0.0,0.0,horizontalAccuracy=5.0)))}
            out.write("],\"visits\":[],\"recordingSessions\":[],\"savedPlaces\":[],\"photoMoments\":[],\"countryStays\":[]}")
        }
        assertTrue(oversized.length()<WheredayBackupFormat.maximumFileBytes)
        assertEquals(BackupProblem.tooManyRecords,runCatching {runBlocking {WheredayBackupReader.read(oversized.inputStream())}}.exceptionOrNull().let {(it as WheredayBackupException).problem})
    }

    @Test fun fileLargerThanFiftyMiBIsRejectedWhileStreaming() {
        val prefix="{\"backupFormat\":\"whereday-backup\",\"pad\":\"".toByteArray()
        val endless=object:InputStream() {
            var index=0L
            override fun read():Int=if(index<prefix.size) prefix[index++.toInt()].toInt() else {index++;'x'.code}
        }
        assertEquals(BackupProblem.fileTooLarge,runCatching {runBlocking {WheredayBackupReader.read(endless)}}.exceptionOrNull().let {(it as WheredayBackupException).problem})
    }

    /** The rejections of WheredayBackupTests.testMalformedOrFutureBackup… and testMalformedBackupAdmission…. */
    @Test fun invalidRecordsAndEnvelopesRejectTheWholeFile() {
        val distantFuture=(WheredayBackupFormat.latestSeconds+1)*1000;val distantPast=(WheredayBackupFormat.earliestSeconds-1)*1000
        fun row(key:String,change:(JsonObject)->Unit)=mutate(iosSingle) {change(it.rows(key)[0].asJsonObject)}
        assertNull(problem(listOf(iosSingle)))
        assertEquals(BackupProblem.unsupportedVersion,problem(listOf(mutate(iosSingle) {it.addProperty("formatVersion",999)})))
        assertEquals(BackupProblem.unsupportedVersion,problem(listOf(mutate(iosSingle) {it.addProperty("formatVersion",2)})))
        assertEquals(BackupProblem.unsupportedVersion,problem(listOf(mutate(iosSingle) {it.addProperty("backupFormat","other")})))
        assertEquals(BackupProblem.unsupportedVersion,problem(listOf(iosParts[0].second.let {part->mutate(part) {it.addProperty("formatVersion",1)}})))
        assertEquals(BackupProblem.malformed,problem(listOf(mutate(iosSingle) {it.remove("countryStays")})))
        assertEquals(BackupProblem.malformed,problem(listOf(mutate(iosSingle) {it.remove("exportedAt")})))
        assertEquals(BackupProblem.malformed,problem(listOf(row("visits") {it.remove("geocodingCompleted")})))
        assertEquals(BackupProblem.malformed,problem(listOf(row("points") {it.addProperty("latitude","37.7")})))
        assertEquals(BackupProblem.malformed,problem(listOf(row("points") {it.addProperty("isOutlier",1)})))
        assertEquals(BackupProblem.malformed,problem(listOf(row("savedPlaces") {it.addProperty("id","1-2-3-4-5")})))
        assertEquals(BackupProblem.malformed,problem(listOf(iosSingle.dropLast(4))))
        assertEquals(BackupProblem.invalidRecords,problem(listOf(row("savedPlaces") {it.addProperty("latitude",91)})))
        assertEquals(BackupProblem.invalidRecords,problem(listOf(row("savedPlaces") {it.addProperty("radiusMeters",0)})))
        assertEquals(BackupProblem.invalidRecords,problem(listOf(row("photoMoments") {it.addProperty("assetLocalIdentifier","")})))
        assertEquals(BackupProblem.invalidRecords,problem(listOf(row("visits") {it.addProperty("departedAt",1_780_000_000_124)})))
        assertEquals(BackupProblem.invalidRecords,problem(listOf(row("recordingSessions") {it.addProperty("endedAt",1_780_000_000_124)})))
        assertEquals(BackupProblem.invalidRecords,problem(listOf(row("points") {it.addProperty("horizontalAccuracy",-100.0)})))
        assertEquals(BackupProblem.invalidRecords,problem(listOf(row("visits") {it.addProperty("arrivedAt",distantFuture);it.remove("departedAt")})))
        assertEquals(BackupProblem.invalidRecords,problem(listOf(row("points") {it.addProperty("timestamp",distantPast)})))
        assertEquals(BackupProblem.invalidRecords,problem(listOf(row("countryStays") {it.addProperty("lastDay","2020-01-01")})))
        assertEquals(BackupProblem.invalidRecords,problem(listOf(row("countryStays") {it.addProperty("firstDay","2026-02-30")})))
        assertEquals(BackupProblem.duplicateIdentifiers,problem(listOf(mutate(iosSingle) {root->root.rows("countryStays").add(root.rows("countryStays")[0])})))
        // A future version is reported as such even when its records no longer validate here.
        assertEquals(BackupProblem.unsupportedVersion,problem(listOf(mutate(iosSingle) {it.addProperty("formatVersion",3);it.rows("countryStays")[0].asJsonObject.addProperty("lastDay","2020-01-01")})))
        // Identifier case and unknown keys follow Foundation's decoder.
        assertNull(problem(listOf(row("visits") {it.addProperty("id",it["id"].asString.lowercase());it.addProperty("futureField",true)})))
    }

    @Test fun legacyBackupWithoutNewOptionalFieldsStillDecodes() {
        val legacy=mutate(iosSingle) {root->
            root.rows("visits").forEach {row->listOf("originalConfirmationStatusRaw","originalConfirmedAt","originalPlaceSourceRaw","originalPlaceDistanceMeters").forEach {row.asJsonObject.remove(it)}}
            root.rows("recordingSessions").forEach {row->listOf("routeSamplingModeRawValue","routeSampleIntervalSeconds").forEach {row.asJsonObject.remove(it)}}
        }
        val (summary,records)=collect(listOf(opener(legacy)))
        assertEquals(10,summary.recordCount)
        assertTrue(records.filterIsInstance<BackupVisitRecord>().all {it.originalConfirmationStatusRaw==null && it.originalPlaceDistanceMeters==null})
        assertNull(records.filterIsInstance<BackupSessionRecord>().single().routeSamplingModeRawValue)
    }

    /** testIncompleteDuplicateAndMixedBackupSets…, testMalformedLastBackupPart… and testMultipartVersionRejects…. */
    @Test fun incompleteDuplicateMixedOrMalformedPartSetsAreRejected():Unit=runBlocking {
        val parts=iosParts.map {it.second}
        val other=WheredayBackupWriter(maximumRecords=3).write(tempDir(),time.toEpochMilli(),ListSource(androidRecords)).map {it.file.readText()}
        assertEquals(BackupProblem.incompleteSet,problem(parts.dropLast(1)))
        assertEquals(BackupProblem.incompleteSet,problem(listOf(parts[0])))
        assertEquals(BackupProblem.incompleteSet,problem(listOf(parts[0])+parts.dropLast(1)))
        assertEquals(BackupProblem.incompleteSet,problem(listOf(other[0])+parts.drop(1)))
        assertEquals(BackupProblem.incompleteSet,problem(listOf(iosSingle)+parts))
        assertEquals(BackupProblem.incompleteSet,problem(listOf(iosSingle,iosSingle)))
        assertEquals(BackupProblem.incompleteSet,problem(parts.mapIndexed {index,text->if(index==0) mutate(text) {it.addProperty("exportedAt",1)} else text}))
        val malformedLast=parts.dropLast(1)+mutate(parts.last()) {root->root.rows("countryStays").let {if(it.size()>0) it[0].asJsonObject.addProperty("lastDay","2020-01-01") else root.rows("photoMoments")[0].asJsonObject.addProperty("latitude",91)}}
        assertEquals(BackupProblem.invalidRecords,problem(malformedLast))
        // An identifier repeated in a different part is invalid rather than silently dropping one version.
        val firstId=JsonParser.parseString(parts[0]).asJsonObject.let {root->BackupCollection.entries.firstNotNullOf {c->root.rows(c.key).firstOrNull()?.let {c to it.asJsonObject["id"].asString}}}
        val duplicated=parts.mapIndexed {index,text->if(index!=1) text else mutate(text) {root->
            val (collection,id)=firstId
            val donor=JsonParser.parseString(parts[0]).asJsonObject.rows(collection.key)[0].asJsonObject
            root.rows(collection.key).add(donor.deepCopy().also {it.addProperty("id",id)})
        }}
        assertEquals(BackupProblem.duplicateIdentifiers,problem(duplicated))
        assertEquals(BackupProblem.incompleteSet,problem(emptyList()))
    }

    @Test fun onlyWheredayBackupsAreRecognised() {
        assertTrue(WheredayBackupReader.isBackup(iosSingle.byteInputStream()))
        assertTrue(WheredayBackupReader.isBackup(iosParts[0].second.byteInputStream()))
        assertTrue(WheredayBackupReader.isBackup((byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte())+iosSingle.toByteArray()).inputStream()))
        // Keys may appear in any order; a damaged backup is still a backup and is rejected, not imported as visits.
        assertTrue(WheredayBackupReader.isBackup("""{"visits":[],"backupFormat":"whereday-backup"""".byteInputStream()))
        assertFalse(WheredayBackupReader.isBackup("""{"visits":[],"points":[]}""".byteInputStream()))
        assertFalse(WheredayBackupReader.isBackup("""{"locations":[{"latitudeE7":1}]}""".byteInputStream()))
        assertFalse(WheredayBackupReader.isBackup("""[{"backupFormat":"whereday-backup"}]""".byteInputStream()))
        assertFalse(WheredayBackupReader.isBackup("type,date\nvisit,2026-01-01\n".byteInputStream()))
        assertFalse(WheredayBackupReader.isBackup("""{"nested":{"backupFormat":"whereday-backup"}}""".byteInputStream()))
    }

    /** Writes Android backups for `generate_backup_fixtures.py --verify-android build/backup-interop` on macOS. */
    @Test fun writeAndroidBackupsForIosAcceptance():Unit=runBlocking {
        val root=File("build/backup-interop").apply {deleteRecursively();mkdirs()}
        for((name,limit) in listOf("single" to WheredayBackupFormat.maximumRecords,"multipart" to 3)) {
            val directory=File(root,name).apply {mkdirs()}
            val files=WheredayBackupWriter(maximumRecords=limit).write(directory,time.toEpochMilli(),ListSource(androidRecords))
            assertEquals(if(name=="single") 1 else 3,files.size)
            val expected=JsonObject()
            BackupCollection.entries.forEach {c->expected.add(c.key,JsonArray().apply {androidRecords.filter {it.collection==c}.forEach {add(JsonParser.parseString(WheredayBackupWriter.encode(it)))}})}
            File(directory,"expected.json").writeText(expected.toString())
        }
    }
}
