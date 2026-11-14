package com.bontecou.whereday.platform

import androidx.sqlite.driver.AndroidSQLiteDriver
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Host-only Room/SQLite backup and restore; synthetic records only, no device, provider or UI is involved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class JournalBackupRestoreTest {
    private val context=RuntimeEnvironment.getApplication()
    private val opened=mutableListOf<HistoryDatabase>()
    private val time=Instant.parse("2026-05-01T09:00:00Z")
    private val fixture=File("../export/src/test/resources/ios-backup.json").reader().use {JsonParser.parseReader(it).asJsonObject}
    private fun database()=HistoryDatabase.create(context,name="backup-${UUID.randomUUID()}.db",driver=AndroidSQLiteDriver()).also {opened+=it}
    @After fun close() {opened.forEach {it.close()}}

    private suspend fun seed(db:HistoryDatabase) {
        val dao=db.history()
        dao.put(VisitRow(Visit(UUID.randomUUID().toString(),Coordinate(48.85,2.35),time,time.plusSeconds(1800),"Louvre","Rue de Rivoli","Notes",VisitSource.manual,
            Confirmation.corrected,Coordinate(48.86,2.36),"Old","Old address",null,null,null,time,time,"userEntered",12.0,"FR","Europe/Paris",true,time)))
        dao.put(VisitRow(Visit(UUID.randomUUID().toString(),Coordinate(1.0,2.0),time.plusSeconds(4000))))
        repeat(1_203) {dao.put(PointRow(TrackPoint(UUID.randomUUID().toString(),Coordinate(48.85,2.35+it/10_000.0),time.plusSeconds(it.toLong()),5.0,30.0,1.0,it%97==0,"FR","Europe/Paris",time),null))}
        dao.put(SessionRow(RecordingSession(UUID.randomUUID().toString(),time,time.plusSeconds(3600),"Walk","Notes")))
        dao.put(PlaceRow(SavedPlace(UUID.randomUUID().toString(),"Home",Coordinate(48.85,2.35),"Address",120.0)))
        dao.put(StayRow(CountryStay(UUID.randomUUID().toString(),"FR",LocalDate.parse("2026-05-01"),LocalDate.parse("2026-05-04"))))
        dao.put(PhotoAssetRow("content://media/external/images/media/7",time,Coordinate(48.85,2.35),true,true,time))
        dao.put(PhotoRow("content://media/external/images/media/7",time,Coordinate(48.85,2.35),PhotoCoordinateSource.photoGPS.wireValue,time))
    }

    /** Every restorable table, as rows, for whole-journal equality. */
    private suspend fun journal(db:HistoryDatabase):Map<String,Set<Any>> = JournalBackupStore(db).snapshot {s->
        val visits=mutableSetOf<Any>();val points=mutableSetOf<Any>();val sessions=mutableSetOf<Any>();val places=mutableSetOf<Any>();val stays=mutableSetOf<Any>();val photos=mutableSetOf<Any>()
        s.visits {visits+=it};s.points {points+=it};s.sessions {sessions+=it};s.places {places+=it};s.stays {stays+=it};s.photos {photos+=it}
        mapOf("visits" to visits,"points" to points,"sessions" to sessions,"places" to places,"stays" to stays,"photos" to photos)
    }
    private suspend fun backup(db:HistoryDatabase,maximumRecords:Int=WheredayBackupFormat.maximumRecords):List<File> {
        val directory=File(context.cacheDir,"backup-out-${UUID.randomUUID()}").apply {mkdirs()}
        return JournalBackupStore(db).snapshot {WheredayBackupWriter(maximumRecords=maximumRecords).write(directory,time.toEpochMilli(),SnapshotBackupSource(it,time.toEpochMilli()))}.map {it.file}
    }
    private fun write(text:String)=File(context.cacheDir,"ios-${UUID.randomUUID()}.json").apply {writeText(text)}

    @Test fun exportFilesBackupRestoresTheCompleteJournalIntoAnEmptyPhone():Unit=runBlocking {
        val source=database();seed(source)
        val exports=ExportFiles(context,source,{})
        val prepared=exports.prepareBackup()
        assertEquals(listOf(WheredayBackupFormat.singleFileName),prepared.files.map {it.relativePath})
        assertEquals(ExportCounts(2,1_203),prepared.files.single().counts)
        val target=database()
        val tally=DatabaseBackupRestoreTarget(JournalBackupStore(target)).restore(prepared.files.map {it.file}) {}
        assertEquals(1_209,tally.added);assertEquals(0,tally.existing);assertEquals(0,tally.unsupported)
        assertEquals(journal(source),journal(target))
        // The restored photo is a visible library match.
        assertEquals(1L,target.history().photoLibraryCount())
        exports.discard(prepared)
    }

    @Test fun multipartBackupRestoresAndRepeatedRestoreSkipsEveryIdentifierWithoutOverwriting():Unit=runBlocking {
        val source=database();seed(source)
        val files=backup(source,maximumRecords=500)
        assertEquals(3,files.size)
        val target=database()
        val restore=DatabaseBackupRestoreTarget(JournalBackupStore(target))
        assertEquals(1_209,restore.inspect(files.reversed()) {}.added)
        assertEquals(1_209,restore.restore(files.reversed()) {}.added)
        assertEquals(journal(source),journal(target))
        // A later edit survives a second restore of the same backup; nothing on the phone is replaced.
        val place=target.history().places().single().value
        target.history().put(PlaceRow(place.copy(name="Edited after restore")))
        val again=restore.restore(files) {}
        assertEquals(0,again.added);assertEquals(1_209,again.existing)
        assertEquals("Edited after restore",target.history().places().single().value.name)
    }

    @Test fun failureOrCancellationDuringRestoreLeavesTheJournalUntouched():Unit=runBlocking {
        val source=database();seed(source)
        val files=backup(source,maximumRecords=500)
        val target=database()
        target.history().put(VisitRow(Visit("kept",Coordinate(0.0,0.0),time)))
        val before=journal(target)
        // Count the checkpoints of a complete restore, then stop 100 before its end: inside the write transaction,
        // after the first point batches were inserted.
        var total=0
        DatabaseBackupRestoreTarget(JournalBackupStore(database())).restore(files) {total++}
        var reads=0
        val cancelled=runCatching {DatabaseBackupRestoreTarget(JournalBackupStore(target)).restore(files) {
            if(++reads>total-100) throw CancellationException("stop")
        }}
        assertTrue("${cancelled.exceptionOrNull()} after $reads of $total",cancelled.exceptionOrNull() is CancellationException)
        assertEquals(before,journal(target))
        // A damaged final part is rejected before anything is written.
        val damaged=files.last().also {file->file.writeText(file.readText().replace("\"latitude\":48.85","\"latitude\":91"))}
        val rejected=runCatching {DatabaseBackupRestoreTarget(JournalBackupStore(target)).restore(files.dropLast(1)+damaged) {}}.exceptionOrNull()
        assertEquals(BackupProblem.invalidRecords,(rejected as WheredayBackupException).problem)
        assertEquals(before,journal(target))
    }

    @Test fun iosBackupRestoresOverlappingModelsAndReportsRecordsAndroidCannotStore():Unit=runBlocking {
        val single=write(fixture["single"].asString)
        val parts=fixture["multipart"].asJsonArray.map {write(it.asJsonObject["text"].asString)}
        for(files in listOf(listOf(single),parts)) {
            val target=database()
            val tally=DatabaseBackupRestoreTarget(JournalBackupStore(target)).restore(files) {}
            assertEquals(10,tally.counts.values.sum())
            // The iOS Photos identifier is the only record without an Android representation.
            assertEquals(9,tally.added);assertEquals(1,tally.unsupported)
            val restored=journal(target)
            val visit=restored.getValue("visits").map {it as Visit}.single {it.name!=null}
            assertEquals("8e0c7c1d-3e1b-4c33-9a41-6d3c1b2a0001",visit.id);assertEquals("Café ☕️",visit.name);assertEquals(Confirmation.corrected,visit.confirmation)
            assertEquals(setOf(25.0,900.0),restored.getValue("places").map {(it as SavedPlace).radius}.toSet())
            assertEquals("content://media/external/images/media/42",(restored.getValue("photos").single() as StoredPhotoMoment).moment.uri)
            assertEquals("Outing",(restored.getValue("sessions").single() as RecordingSession).name)
            // Restoring the same iOS backup again finds every identifier already present.
            val again=DatabaseBackupRestoreTarget(JournalBackupStore(target)).restore(files) {}
            assertEquals(0,again.added);assertEquals(9,again.existing)
        }
    }

    private fun input(file:File)=object:HistoryImportInput {override fun open():InputStream=file.inputStream()}
    private suspend fun await(controller:BackupRestoreController,phase:BackupRestorePhase) {withTimeout(10_000) {while(controller.state.value.phase!=phase) delay(5)}}

    @Test fun controllerRoutesOtherFilesRejectsMixedSelectionsAndRestoresReviewedBackups():Unit=runBlocking {
        val target=database()
        val staging=File(context.cacheDir,"restore-staging-${UUID.randomUUID()}")
        val controller=BackupRestoreController(DatabaseBackupRestoreTarget(JournalBackupStore(target)),staging)
        try {
            val history=write("""{"visits":[],"points":[{"latitude":1,"longitude":2,"timestamp":"2026-01-01T00:00:00Z"}]}""")
            var handed:HistoryImportInput?=null
            val input=input(history)
            controller.select(listOf(input)) {handed=it}!!.join()
            assertSame(input,handed);assertEquals(BackupRestorePhase.idle,controller.state.value.phase)
            val backupFile=write(fixture["single"].asString)
            controller.select(listOf(input(backupFile),input(history))) {fail("A mixed selection is not imported")}!!.join()
            assertEquals(BackupRestoreFailure.mixedSelection,controller.state.value.failure);controller.discard()
            controller.select(listOf(input(write(fixture["multipart"].asJsonArray[0].asJsonObject["text"].asString)))) {fail()}!!.join()
            assertEquals(BackupRestoreFailure.incompleteSet,controller.state.value.failure);controller.discard()
            controller.select(listOf(input(backupFile))) {fail()}!!.join()
            val review=controller.state.value.review!!
            assertEquals(9,review.tally.added);assertEquals(1,review.tally.unsupported)
            assertEquals(0,target.history().totalVisitCount()) // Review never writes.
            assertNull(controller.restore("stale"))
            controller.restore(review.id)!!.join()
            assertEquals(BackupRestorePhase.complete,controller.state.value.phase);assertEquals(9,controller.state.value.result!!.added)
            assertEquals(2L,target.history().totalVisitCount())
            assertTrue(staging.listFiles().isNullOrEmpty())
            // Clear All Data cannot interleave with a pending review.
            controller.discard();controller.select(listOf(input(backupFile))) {fail()}!!.join()
            await(controller,BackupRestorePhase.review)
            controller.clearAll {}
            assertEquals(BackupRestorePhase.idle,controller.state.value.phase);assertTrue(staging.listFiles().isNullOrEmpty())
        } finally {controller.close()}
    }

    @Test fun restoreIsRefusedWhileTheJournalIsBeingCleared():Unit=runBlocking {
        val target=database();var writable=true
        val controller=BackupRestoreController(DatabaseBackupRestoreTarget(JournalBackupStore(target)),File(context.cacheDir,"restore-busy-${UUID.randomUUID()}")) {writable}
        try {
            controller.select(listOf(input(write(fixture["single"].asString)))) {fail()}!!.join()
            writable=false
            assertNull(controller.restore(controller.state.value.review!!.id))
            assertEquals(BackupRestoreFailure.journalBusy,controller.state.value.failure)
            assertEquals(0L,target.history().totalVisitCount())
        } finally {controller.close()}
    }
}
