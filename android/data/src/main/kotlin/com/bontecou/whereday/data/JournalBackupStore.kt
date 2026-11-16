package com.bontecou.whereday.data

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant

/** Every model Clear All Data erases that the shared Whereday backup format can carry. */
enum class JournalRecordKind {visit,point,session,place,photo,stay}

/** A photo match as stored: Android content URI, location, and when it was last matched. */
data class StoredPhotoMoment(val moment:PhotoMoment,val lastSyncedAt:Instant)

/**
 * Storage seam for the lossless backup. Reads use one WAL snapshot so a backup is a consistent point in time while
 * recording continues; a restore is one write transaction, so any failure or cancellation leaves the journal as it was.
 */
class JournalBackupStore(private val database:HistoryDatabase) {
    suspend fun <T> snapshot(block:suspend (JournalBackupSnapshot)->T):T=database.readSnapshot {dao->block(JournalBackupSnapshot(dao))}
    suspend fun <T> lookup(block:suspend (JournalBackupLookup)->T):T=database.readSnapshot {dao->block(JournalBackupLookup(dao))}
    suspend fun <T> restore(block:suspend (JournalBackupWriter)->T):T {
        val dao=database.history()
        return database.writeTransaction {block(JournalBackupWriter(dao))}
    }
}

class JournalBackupSnapshot internal constructor(private val dao:HistoryDao) {
    private suspend fun <R> pages(read:suspend (String)->List<R>,key:(R)->String,consume:suspend (R)->Unit) {
        var after=""
        while(true) {
            currentCoroutineContext().ensureActive()
            val page=read(after);if(page.isEmpty()) return
            page.forEach {consume(it)};after=key(page.last())
        }
    }
    suspend fun visits(consume:suspend (Visit)->Unit)=pages(dao::backupVisits,{it.value.id}) {consume(it.value)}
    suspend fun points(consume:suspend (TrackPoint)->Unit)=pages(dao::backupPoints,{it.value.id}) {consume(it.value)}
    suspend fun sessions(consume:suspend (RecordingSession)->Unit)=pages(dao::backupSessions,{it.value.id}) {consume(it.value)}
    suspend fun places(consume:suspend (SavedPlace)->Unit)=pages(dao::backupPlaces,{it.value.id}) {consume(it.value)}
    suspend fun stays(consume:suspend (CountryStay)->Unit)=pages(dao::backupStays,{it.value.id}) {consume(it.value)}
    /** Rows with a coordinate source this version does not know are not photo matches it can describe; skip them. */
    suspend fun photos(consume:suspend (StoredPhotoMoment)->Unit)=pages(dao::backupPhotos,{it.uri}) {row->
        PhotoCoordinateSource.entries.firstOrNull {it.wireValue==row.coordinateSource}?.let {
            consume(StoredPhotoMoment(PhotoMoment(row.uri,row.capturedAt,row.coordinate,it),row.lastSyncedAt))
        }
    }
}

open class JournalBackupLookup internal constructor(protected val dao:HistoryDao) {
    /** The subset of [ids] already stored. Bounded IN lists stay under SQLite's variable limit. */
    suspend fun existing(kind:JournalRecordKind,ids:Collection<String>):Set<String> {
        val found=HashSet<String>()
        for(chunk in ids.distinct().chunked(400)) found+=when(kind) {
            JournalRecordKind.visit->dao.existingVisitIds(chunk)
            JournalRecordKind.point->dao.existingPointIds(chunk)
            JournalRecordKind.session->dao.existingSessionIds(chunk)
            JournalRecordKind.place->dao.existingPlaceIds(chunk)
            JournalRecordKind.stay->dao.existingStayIds(chunk)
            JournalRecordKind.photo->dao.existingPhotoUris(chunk)
        }
        return found
    }
}

class JournalBackupWriter internal constructor(dao:HistoryDao):JournalBackupLookup(dao) {
    suspend fun visits(rows:List<Visit>)=dao.restoreVisits(rows.map(::VisitRow))
    /** Outing membership on Android is the session time window, so restored fixes carry no session key. */
    suspend fun points(rows:List<TrackPoint>)=dao.restorePoints(rows.map {PointRow(it,null)})
    suspend fun sessions(rows:List<RecordingSession>)=dao.restoreSessions(rows.map(::SessionRow))
    suspend fun places(rows:List<SavedPlace>)=dao.restorePlaces(rows.map(::PlaceRow))
    suspend fun stays(rows:List<CountryStay>)=dao.restoreStays(rows.map(::StayRow))
    /** A restored match is also a visible library reference; access is rechecked when the photo is opened. */
    suspend fun photos(rows:List<StoredPhotoMoment>) {
        dao.restorePhotoAssets(rows.map {PhotoAssetRow(it.moment.uri,it.moment.takenAt,it.moment.coordinate.takeIf {_->it.moment.source==PhotoCoordinateSource.photoGPS},
            selected=true,available=true,lastCheckedAt=it.lastSyncedAt)})
        dao.restorePhotos(rows.map {PhotoRow(it.moment.uri,it.moment.takenAt,it.moment.coordinate,it.moment.source.wireValue,it.lastSyncedAt)})
    }
}
