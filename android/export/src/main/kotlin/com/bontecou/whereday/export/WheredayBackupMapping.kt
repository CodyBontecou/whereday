package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A photo match Android can store: an Android content reference plus when it was last matched. */
data class RestoredPhotoMoment(val moment:PhotoMoment,val lastSyncedAt:Instant)

/**
 * Deliberate translation between the shared backup records and Android's narrower models.
 *
 * iOS → Android (restore):
 * - Visits: `customName` (trimmed, non-empty) wins over `locationName` for Android's single `name`, matching iOS
 *   `Visit.displayName`/`exportLocationName`. Unknown `sourceRaw`/`confirmationStatusRaw` fall back to automatic/
 *   unconfirmed, as iOS's computed properties do. Dropped (no Android column): `geocodingCompleted`, the four
 *   `originalConfirmation*`/`originalPlace*` correction fields, and optional dates/coordinates that are outside
 *   Foundation's valid range (iOS does not validate those optional fields; Android cannot store them).
 * - Points: every field maps. Points carry no outing foreign key on iOS; Android outing membership is the
 *   session's time window (as Android's outing export/detail already use), so restored points have no sessionId.
 * - Outings: `customName`→name, notes kept; `receivedLocationCount`, `distanceFilterMeters` and route sampling are
 *   dropped (Android records sampling in its settings, not per outing).
 * - Saved places: name/coordinate/address kept; radius is clamped to Android's 25 m–10 km range; `createdAt`,
 *   `updatedAt`, `kindRaw` and `notes` are dropped. A blank name cannot be stored and is reported as unsupported.
 * - Country stays: `note` is dropped; a code Android's region list does not know is reported as unsupported.
 * - Photo matches: only Android `content://` references are restorable. An iOS Photos identifier is meaningless
 *   here and is reported as unsupported rather than creating a dangling match. Unknown coordinate sources likewise.
 * Times are stored at Android's millisecond precision (floored, so ordering is preserved).
 *
 * Android → iOS (backup):
 * - Identifiers that are not canonical UUIDs (none are written by Android today) become stable name-based UUIDs.
 * - A user-entered visit name (`placeSource` userEntered) is written as `customName`, any other as `locationName`;
 *   `geocodingCompleted` is true when Android has a name or address so iOS does not re-geocode it.
 * - Point accuracy that iOS would reject (non-finite or below -1) is written as -1, iOS's "unknown" value; non-finite
 *   altitude/speed are omitted.
 * - Saved places have no timestamps on Android; `createdAt`/`updatedAt` are the backup's `exportedAt`.
 * - Photo matches keep the Android content URI as `assetLocalIdentifier` with an id derived from it, so repeated
 *   restores of the same backup on iOS skip them by id. iOS cannot open those URIs.
 */
object WheredayBackupMapping {
    fun instant(millis:Double):Instant=Instant.ofEpochMilli(Math.floor(millis).toLong())
    private fun optionalInstant(millis:Double?)=millis?.takeIf(WheredayBackupFormat::validMillis)?.let(::instant)
    private fun millis(value:Instant)=value.toEpochMilli().toDouble()
    private fun coordinate(latitude:Double?,longitude:Double?)=if(latitude!=null && longitude!=null && WheredayBackupFormat.validCoordinate(latitude,longitude)) Coordinate(latitude,longitude) else null
    private fun finite(value:Double?)=value?.takeIf {it.isFinite()}

    /** Android stores the lowercase canonical form, as its other importers do. */
    fun androidId(id:UUID):String=id.toString()
    /** The identifier forms one record may already be stored under. */
    fun storedIdForms(id:UUID):List<String> = listOf(id.toString(),id.toString().uppercase())
    fun backupId(id:String,kind:String):UUID=WheredayBackupFormat.parseUuid(id) ?: UUID.nameUUIDFromBytes("whereday-android-$kind:$id".toByteArray(Charsets.UTF_8))
    fun photoId(uri:String):UUID=UUID.nameUUIDFromBytes("whereday-android-photo:$uri".toByteArray(Charsets.UTF_8))

    fun visit(record:BackupVisitRecord):Visit=Visit(androidId(record.id),Coordinate(record.latitude,record.longitude),instant(record.arrivedAt),record.departedAt?.let(::instant),
        name=record.customName?.trim()?.takeIf {it.isNotEmpty()} ?: record.locationName,address=record.address,notes=record.notes,
        source=VisitSource.entries.firstOrNull {it.name==record.sourceRaw} ?: VisitSource.automatic,
        confirmation=Confirmation.entries.firstOrNull {it.name==record.confirmationStatusRaw} ?: Confirmation.unconfirmed,
        originalCoordinate=coordinate(record.originalLatitude,record.originalLongitude),originalName=record.originalLocationName,originalAddress=record.originalAddress,
        detectedCoordinate=coordinate(record.detectedLatitude,record.detectedLongitude),detectedName=record.detectedLocationName,detectedAddress=record.detectedAddress,
        confirmedAt=optionalInstant(record.confirmedAt),updatedAt=optionalInstant(record.updatedAt),placeSource=record.placeSourceRaw,
        placeDistanceMeters=finite(record.placeDistanceMeters),countryCode=record.countryCode,timeZone=record.countryTimeZoneIdentifier,
        departureInferred=record.countryDepartureIsInferred ?: false,countryResolutionAttemptedAt=optionalInstant(record.countryResolutionAttemptedAt))

    fun point(record:BackupPointRecord):TrackPoint=TrackPoint(androidId(record.id),Coordinate(record.latitude,record.longitude),instant(record.timestamp),
        record.horizontalAccuracy,record.altitude,record.speed,record.isOutlier,record.countryCode,record.countryTimeZoneIdentifier,optionalInstant(record.countryResolutionAttemptedAt))

    fun session(record:BackupSessionRecord):RecordingSession=RecordingSession(androidId(record.id),instant(record.startedAt),record.endedAt?.let(::instant),record.customName,record.notes)

    fun place(record:BackupSavedPlaceRecord):SavedPlace?=record.name.takeIf {it.isNotBlank()}?.let {
        SavedPlace(androidId(record.id),it,Coordinate(record.latitude,record.longitude),record.address,record.radiusMeters.coerceIn(25.0,10_000.0))
    }

    fun stay(record:BackupCountryStayRecord):CountryStay?=if(record.countryCode !in CountryRegions.all) null
        else CountryStay(androidId(record.id),record.countryCode,LocalDate.parse(record.firstDay),LocalDate.parse(record.lastDay))

    fun photo(record:BackupPhotoRecord):RestoredPhotoMoment? {
        if(!record.assetLocalIdentifier.startsWith("content://")) return null
        val source=PhotoCoordinateSource.entries.firstOrNull {it.wireValue==record.coordinateSourceRawValue} ?: return null
        return RestoredPhotoMoment(PhotoMoment(record.assetLocalIdentifier,instant(record.takenAt),Coordinate(record.latitude,record.longitude),source),instant(record.lastSyncedAt))
    }

    fun record(visit:Visit):BackupVisitRecord {
        val custom=visit.placeSource=="userEntered"
        return BackupVisitRecord(backupId(visit.id,"visit"),visit.coordinate.latitude,visit.coordinate.longitude,millis(visit.arrivedAt),visit.departedAt?.let(::millis),
            customName=visit.name.takeIf {custom},locationName=visit.name.takeUnless {custom},address=visit.address,notes=visit.notes,
            geocodingCompleted=visit.name!=null || visit.address!=null,sourceRaw=visit.source.name,confirmationStatusRaw=visit.confirmation.name,
            confirmedAt=visit.confirmedAt?.let(::millis),updatedAt=visit.updatedAt?.let(::millis),
            originalLatitude=visit.originalCoordinate?.latitude,originalLongitude=visit.originalCoordinate?.longitude,
            originalLocationName=visit.originalName,originalAddress=visit.originalAddress,
            detectedLatitude=visit.detectedCoordinate?.latitude,detectedLongitude=visit.detectedCoordinate?.longitude,
            detectedLocationName=visit.detectedName,detectedAddress=visit.detectedAddress,placeSourceRaw=visit.placeSource,
            placeDistanceMeters=finite(visit.placeDistanceMeters),countryCode=visit.countryCode,countryTimeZoneIdentifier=visit.timeZone,
            countryResolutionAttemptedAt=visit.countryResolutionAttemptedAt?.let(::millis),countryDepartureIsInferred=visit.departureInferred)
    }

    fun record(point:TrackPoint):BackupPointRecord=BackupPointRecord(backupId(point.id,"point"),point.coordinate.latitude,point.coordinate.longitude,millis(point.time),
        finite(point.altitude),finite(point.speed),point.accuracy.takeIf {it.isFinite() && it>=-1} ?: -1.0,point.outlier,point.countryCode,point.timeZone,
        point.countryResolutionAttemptedAt?.let(::millis))

    fun record(session:RecordingSession):BackupSessionRecord=BackupSessionRecord(backupId(session.id,"session"),millis(session.startedAt),session.endedAt?.let(::millis),session.name,session.notes)

    fun record(place:SavedPlace,exportedAtMillis:Long):BackupSavedPlaceRecord=BackupSavedPlaceRecord(backupId(place.id,"place"),place.name,place.coordinate.latitude,
        place.coordinate.longitude,place.address,place.radius,exportedAtMillis.toDouble(),exportedAtMillis.toDouble())

    fun record(photo:PhotoMoment,lastSyncedAt:Instant):BackupPhotoRecord=BackupPhotoRecord(photoId(photo.uri),photo.uri,millis(photo.takenAt),photo.coordinate.latitude,
        photo.coordinate.longitude,photo.source.wireValue,millis(lastSyncedAt))

    fun record(stay:CountryStay):BackupCountryStayRecord=BackupCountryStayRecord(backupId(stay.id,"stay"),stay.countryCode,stay.firstDay.toString(),stay.lastDay.toString())
}
