package com.bontecou.whereday.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import java.time.Instant
import java.time.LocalDate

class HistoryConverters {
    @TypeConverter fun instant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
    @TypeConverter fun millis(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter fun coordinate(value: String?): Coordinate? = value?.split('|')?.let { Coordinate(it[0].toDouble(), it[1].toDouble()) }
    @TypeConverter fun coordinateText(value: Coordinate?): String? = value?.let { "${it.latitude}|${it.longitude}" }
    @TypeConverter fun day(value: String?): LocalDate? = value?.let(LocalDate::parse)
    @TypeConverter fun dayText(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun source(value: String): VisitSource = VisitSource.valueOf(value)
    @TypeConverter fun sourceText(value: VisitSource): String = value.name
    @TypeConverter fun confirmation(value: String): Confirmation = Confirmation.valueOf(value)
    @TypeConverter fun confirmationText(value: Confirmation): String = value.name
}

data class PhotoHistoryBounds(@ColumnInfo(name="firstTime") val first:Instant?,@ColumnInfo(name="lastTime") val last:Instant?)

@Entity(tableName = "points", primaryKeys = ["value_id"], indices = [Index("value_time"), Index("sessionId"), Index(value=["value_time", "value_id"]), Index(value=["sessionId","value_time","value_id"])])
data class PointRow(@Embedded(prefix = "value_") val value: TrackPoint, val sessionId: String?)
@Entity(tableName = "visits", primaryKeys = ["value_id"], indices = [Index("value_arrivedAt"), Index("value_departedAt")])
data class VisitRow(@Embedded(prefix = "value_") val value: Visit)
@Entity(tableName = "sessions", primaryKeys = ["value_id"], indices = [Index("value_startedAt")])
data class SessionRow(@Embedded(prefix = "value_") val value: RecordingSession)
@Entity(tableName = "places", primaryKeys = ["value_id"])
data class PlaceRow(@Embedded(prefix = "value_") val value: SavedPlace)
@Entity(tableName = "country_stays", primaryKeys = ["value_id"])
data class StayRow(@Embedded(prefix = "value_") val value: CountryStay)
@Entity(tableName = "recording")
data class RecordingRow(@PrimaryKey val id: Int = 1, val requested: Boolean = false, val sessionId: String? = null, val deadline: Instant? = null, val lastFix: Instant? = null, val interruption: String? = null,val startedBootCount:Int?=null)
/** Two private detector observations, never route history or webhook/export input. */
data class VisitObservation(val coordinate:Coordinate?=null,val time:Instant?=null,val accuracy:Double?=null) {
    fun point():TrackPoint? = if(coordinate!=null && time!=null && accuracy!=null)
        TrackPoint(id="visit-observation:$time",coordinate=coordinate,time=time,accuracy=accuracy) else null
}
@Entity(tableName="visit_monitoring")
data class VisitMonitoringRow(@PrimaryKey val id:Int=1,val requested:Boolean=false,val generation:String?=null,
    val startedAt:Instant?=null,val lastFix:Instant?=null,val interruption:String?=null,
    @Embedded(prefix="recent_") val recent:VisitObservation=VisitObservation(),
    @Embedded(prefix="previous_") val previous:VisitObservation=VisitObservation(),val startedBootCount:Int?=null)
@Entity(tableName = "dwell")
data class DwellRow(@PrimaryKey val id: Int = 1, @Embedded(prefix = "state_") val state: DwellState = DwellState())
@Entity(tableName = "photo_moments", indices = [Index("capturedAt"),Index(value=["capturedAt","uri"])])
data class PhotoRow(@PrimaryKey val uri: String, val capturedAt: Instant, val coordinate: Coordinate, val coordinateSource: String, val lastSyncedAt: Instant)
@Entity(tableName="photo_assets",indices=[Index(value=["capturedAt","uri"]),Index(value=["identity","available","selected","uri"])])
data class PhotoAssetRow(@PrimaryKey val uri:String,val capturedAt:Instant?,val gps:Coordinate?,val selected:Boolean,val available:Boolean,val lastCheckedAt:Instant,val identity:String?=null)

// Keep access aliases independently; only provider-proven identity groups share a visible representative.
private const val PHOTO_PRIORITY_A="(a.available*8+(a.capturedAt IS NOT NULL)*4+(a.gps IS NOT NULL)*2+a.selected)"
private const val PHOTO_PRIORITY_B="(b.available*8+(b.capturedAt IS NOT NULL)*4+(b.gps IS NOT NULL)*2+b.selected)"
const val PHOTO_ASSET_VIEW_SQL="SELECT a.* FROM photo_assets a WHERE a.identity IS NULL OR NOT EXISTS (SELECT 1 FROM photo_assets b WHERE b.identity=a.identity AND ($PHOTO_PRIORITY_B>$PHOTO_PRIORITY_A OR ($PHOTO_PRIORITY_B=$PHOTO_PRIORITY_A AND b.uri<a.uri)))"
@DatabaseView(value=PHOTO_ASSET_VIEW_SQL,viewName="visible_photo_assets")
data class VisiblePhotoAsset(@Embedded val asset:PhotoAssetRow)

data class PhotoLibraryEntry(@Embedded val asset:PhotoAssetRow,val coordinateSource:String?)
@Entity(tableName = "export_runs")
data class ExportRunRow(@PrimaryKey val occurrence: String, val destination: String, val fireAt: Instant, val state: String, val completedAt: Instant? = null, val cursor: Instant? = null, val error: String? = null)
@Entity(tableName = "webhook_outbox", indices = [Index("createdAt")])
data class OutboxRow(@PrimaryKey val id: String, val createdAt: Instant, val payload: String, val attempts: Int = 0, val nextAttempt: Instant? = null)
@Entity(tableName = "country_lookup_cache")
data class CountryLookupRow(@PrimaryKey val coordinate: Coordinate, val countryCode: String, val timeZone: String, val version: String)
@Entity(tableName = "country_analysis")
data class CountryAnalysisRow(@PrimaryKey val id: Int = 1, val runId: String, val nextLookupAt: Instant)

/** Bounded import matching projection excludes editable text and uses numeric rowid pagination. */
data class TimelineImportCandidate(val ordinal:Long,val coordinate:Coordinate,val time:Instant,val departure:Instant?)

@Dao
interface HistoryDao {
    @Query("SELECT EXISTS(SELECT 1 FROM visits WHERE value_id=:id)") suspend fun importVisitIdentityExists(id:String):Boolean
    @Query("SELECT EXISTS(SELECT 1 FROM points WHERE value_id=:id)") suspend fun importPointIdentityExists(id:String):Boolean
    @Query("SELECT rowid AS ordinal,value_coordinate AS coordinate,value_arrivedAt AS time,value_departedAt AS departure FROM visits WHERE value_arrivedAt>=:from AND value_arrivedAt<=:until AND (:afterTime IS NULL OR (value_arrivedAt,rowid)>(:afterTime,:afterOrdinal)) ORDER BY value_arrivedAt,rowid LIMIT 200")
    suspend fun timelineImportVisits(from:Instant,until:Instant,afterTime:Instant?,afterOrdinal:Long):List<TimelineImportCandidate>
    @Query("SELECT rowid AS ordinal,value_coordinate AS coordinate,value_time AS time,NULL AS departure FROM points WHERE value_time>=:from AND value_time<=:until AND (:afterTime IS NULL OR (value_time,rowid)>(:afterTime,:afterOrdinal)) ORDER BY value_time,rowid LIMIT 200")
    suspend fun timelineImportPoints(from:Instant,until:Instant,afterTime:Instant?,afterOrdinal:Long):List<TimelineImportCandidate>

    @Query("SELECT COALESCE(SUM(COALESCE(length(CAST(value_id AS BLOB)),0)+COALESCE(length(CAST(value_name AS BLOB)),0)+COALESCE(length(CAST(value_address AS BLOB)),0)+COALESCE(length(CAST(value_notes AS BLOB)),0)+COALESCE(length(CAST(value_originalName AS BLOB)),0)+COALESCE(length(CAST(value_originalAddress AS BLOB)),0)+COALESCE(length(CAST(value_detectedName AS BLOB)),0)+COALESCE(length(CAST(value_detectedAddress AS BLOB)),0)+COALESCE(length(CAST(value_placeSource AS BLOB)),0)+COALESCE(length(CAST(value_countryCode AS BLOB)),0)+COALESCE(length(CAST(value_timeZone AS BLOB)),0)),0) FROM visits WHERE value_id IN (:ids)")
    suspend fun importVisitTextBytes(ids:List<String>):Long
    @Query("SELECT COALESCE(SUM(COALESCE(length(CAST(value_id AS BLOB)),0)+COALESCE(length(CAST(sessionId AS BLOB)),0)+COALESCE(length(CAST(value_countryCode AS BLOB)),0)+COALESCE(length(CAST(value_timeZone AS BLOB)),0)),0) FROM points WHERE value_id IN (:ids)")
    suspend fun importPointTextBytes(ids:List<String>):Long
    @Query("SELECT * FROM visits WHERE value_id IN (:ids)") suspend fun importVisits(ids:List<String>):List<VisitRow>
    @Query("SELECT * FROM points WHERE value_id IN (:ids)") suspend fun importPoints(ids:List<String>):List<PointRow>
    @Query("SELECT * FROM sessions WHERE value_endedAt IS NULL ORDER BY value_startedAt DESC,value_id DESC LIMIT 1")
    suspend fun latestOpenSession():SessionRow?
    @Query("SELECT * FROM visits WHERE value_arrivedAt>=:from AND value_arrivedAt<=:until AND (:afterTime IS NULL OR (value_arrivedAt,value_id)>(:afterTime,:afterId)) ORDER BY value_arrivedAt,value_id LIMIT 500")
    suspend fun commandVisits(from:Instant,until:Instant,afterTime:Instant?,afterId:String):List<VisitRow>
    @Query("SELECT * FROM sessions WHERE value_startedAt>=:from AND value_startedAt<=:until AND (:afterTime IS NULL OR (value_startedAt,value_id)>(:afterTime,:afterId)) ORDER BY value_startedAt,value_id LIMIT 500")
    suspend fun commandOutings(from:Instant,until:Instant,afterTime:Instant?,afterId:String):List<SessionRow>
    @Query("SELECT COUNT(*) FROM visits WHERE value_arrivedAt>=:from AND value_arrivedAt<=:until")
    suspend fun commandVisitCount(from:Instant,until:Instant):Long
    @Query("SELECT * FROM points WHERE value_time>=:from AND value_time<=:until AND (:session IS NULL OR sessionId=:session) ORDER BY value_time,value_id LIMIT 500")
    suspend fun exportFirstPoints(from:Instant,until:Instant,session:String?):List<PointRow>
    @Query("SELECT * FROM points WHERE value_time<=:until AND (value_time,value_id)>(:afterTime,:afterId) AND (:session IS NULL OR sessionId=:session) ORDER BY value_time,value_id LIMIT 500")
    suspend fun exportNextPoints(afterTime:Instant,afterId:String,until:Instant,session:String?):List<PointRow>
    @Query("SELECT * FROM visits WHERE value_arrivedAt>=:from AND value_arrivedAt<=:until ORDER BY value_arrivedAt,value_id LIMIT 500")
    suspend fun exportFirstVisits(from:Instant,until:Instant):List<VisitRow>
    @Query("SELECT * FROM visits WHERE value_arrivedAt<=:until AND (value_arrivedAt,value_id)>(:afterTime,:afterId) ORDER BY value_arrivedAt,value_id LIMIT 500")
    suspend fun exportNextVisits(afterTime:Instant,afterId:String,until:Instant):List<VisitRow>
    @Query("SELECT * FROM points WHERE value_time>=:from AND value_time<=:until AND value_time<:at AND (:goodOnly=0 OR value_outlier=0) ORDER BY value_time DESC,value_id DESC LIMIT 1")
    suspend fun photoPointBefore(at:Instant,from:Instant,until:Instant,goodOnly:Boolean):PointRow?
    @Query("SELECT * FROM points WHERE value_time>=:from AND value_time<=:until AND value_time>=:at AND (:goodOnly=0 OR value_outlier=0) ORDER BY value_time,value_id LIMIT 1")
    suspend fun photoPointAfter(at:Instant,from:Instant,until:Instant,goodOnly:Boolean):PointRow?
    @Query("SELECT * FROM visits WHERE value_arrivedAt<=:latestArrival AND COALESCE(value_departedAt,:now)>=:earliestDeparture ORDER BY value_arrivedAt,value_id LIMIT 1")
    suspend fun photoContainingVisit(earliestDeparture:Instant,latestArrival:Instant,now:Instant):VisitRow?
    @Upsert suspend fun put(row:PhotoAssetRow)
    @Query("SELECT COUNT(*) FROM visible_photo_assets") fun observePhotoAssetCount():Flow<Long>
    @Query("SELECT COUNT(*) FROM photo_assets") suspend fun photoAssetCount():Long
    @Query("SELECT a.*,CASE WHEN a.available=1 THEN p.coordinateSource ELSE NULL END AS coordinateSource FROM visible_photo_assets a LEFT JOIN photo_moments p ON p.uri=a.uri ORDER BY a.capturedAt,a.uri LIMIT 51")
    suspend fun photoLibraryFirstPage():List<PhotoLibraryEntry>
    @Query("SELECT a.*,CASE WHEN a.available=1 THEN p.coordinateSource ELSE NULL END AS coordinateSource FROM visible_photo_assets a LEFT JOIN photo_moments p ON p.uri=a.uri WHERE a.capturedAt IS NOT NULL OR (a.capturedAt IS NULL AND a.uri>:after) ORDER BY a.capturedAt,a.uri LIMIT 51")
    suspend fun photoLibraryAfterUnknown(after:String):List<PhotoLibraryEntry>
    @Query("SELECT a.*,CASE WHEN a.available=1 THEN p.coordinateSource ELSE NULL END AS coordinateSource FROM visible_photo_assets a LEFT JOIN photo_moments p ON p.uri=a.uri WHERE (a.capturedAt,a.uri)>(:at,:after) ORDER BY a.capturedAt,a.uri LIMIT 51")
    suspend fun photoLibraryNextPage(at:Instant,after:String):List<PhotoLibraryEntry>
    @Query("SELECT a.*,CASE WHEN a.available=1 THEN p.coordinateSource ELSE NULL END AS coordinateSource FROM visible_photo_assets a LEFT JOIN photo_moments p ON p.uri=a.uri ORDER BY a.capturedAt,a.uri")
    fun pagedPhotoLibrary():androidx.paging.PagingSource<Int,PhotoLibraryEntry>
    @Query("SELECT COUNT(*) FROM visible_photo_assets WHERE (:at IS NULL AND capturedAt IS NULL AND uri<:uri) OR (:at IS NOT NULL AND (capturedAt IS NULL OR capturedAt<:at OR (capturedAt=:at AND uri<:uri)))")
    suspend fun photoLibraryRank(at:Instant?,uri:String):Int
    @Query("SELECT * FROM photo_assets WHERE uri=:uri") suspend fun photoAsset(uri:String):PhotoAssetRow?
    @Query("SELECT COUNT(*) FROM visible_photo_assets") suspend fun photoLibraryCount():Long
    @Query("SELECT EXISTS(SELECT 1 FROM visible_photo_assets WHERE uri=:uri)") suspend fun isVisiblePhoto(uri:String):Boolean
    @Query("DELETE FROM photo_moments WHERE uri IN (SELECT uri FROM photo_assets WHERE identity=:identity)") suspend fun deletePhotoIdentityMoments(identity:String)
    @Query("DELETE FROM photo_assets WHERE identity=:identity") suspend fun deletePhotoIdentityAssets(identity:String)
    @Query("SELECT * FROM photo_assets WHERE uri>:after ORDER BY uri LIMIT 500") suspend fun photoAssetPage(after:String=""):List<PhotoAssetRow>
    @Query("UPDATE photo_assets SET available=0,lastCheckedAt=:at WHERE uri=:uri") suspend fun hidePhotoAsset(uri:String,at:Instant)
    @Query("DELETE FROM photo_assets WHERE uri=:uri") suspend fun deletePhotoAsset(uri:String)
    @Query("SELECT * FROM photo_moments WHERE uri=:uri") suspend fun photoMoment(uri:String):PhotoRow?
    @Query("DELETE FROM photo_moments WHERE uri=:uri") suspend fun deletePhotoMoment(uri:String)
    @Query("SELECT COUNT(*) FROM photo_moments p JOIN visible_photo_assets a ON p.uri=a.uri WHERE a.available=1 AND p.capturedAt>=:from AND p.capturedAt<:until")
    suspend fun photoCount(from:Instant,until:Instant):Long
    @Query("SELECT p.* FROM photo_moments p JOIN visible_photo_assets a ON p.uri=a.uri WHERE a.available=1 AND p.capturedAt>=:from AND p.capturedAt<:until ORDER BY p.capturedAt,p.uri")
    fun pagedPhotos(from:Instant,until:Instant):androidx.paging.PagingSource<Int,PhotoRow>
    @Query("SELECT COUNT(*) FROM photo_moments p JOIN visible_photo_assets a ON p.uri=a.uri WHERE a.available=1 AND p.capturedAt>=:from AND p.capturedAt<:until AND (p.capturedAt,p.uri)<(:at,:uri)")
    suspend fun photoRank(from:Instant,until:Instant,at:Instant,uri:String):Int
    @Query("SELECT p.* FROM photo_moments p JOIN visible_photo_assets a ON p.uri=a.uri WHERE a.available=1 AND p.capturedAt>=:from AND p.capturedAt<:until ORDER BY p.capturedAt,p.uri LIMIT 500")
    suspend fun photoFirstPage(from:Instant,until:Instant):List<PhotoRow>
    @Query("SELECT p.* FROM photo_moments p JOIN visible_photo_assets a ON p.uri=a.uri WHERE a.available=1 AND p.capturedAt<:until AND (p.capturedAt,p.uri)>(:afterTime,:afterUri) ORDER BY p.capturedAt,p.uri LIMIT 500")
    suspend fun photoNextPage(afterTime:Instant,afterUri:String,until:Instant):List<PhotoRow>
    @Query("SELECT * FROM points WHERE value_id=:id") suspend fun point(id: String): PointRow?
    @Query("SELECT * FROM points WHERE value_outlier=0 AND value_time<=:now AND (value_countryCode IS NULL OR value_timeZone IS NULL) AND (value_countryResolutionAttemptedAt IS NULL OR value_countryResolutionAttemptedAt<:retryBefore) ORDER BY value_time, value_id LIMIT 100")
    suspend fun pendingCountryPoints(now: Instant, retryBefore: Instant): List<PointRow>
    @Query("SELECT * FROM visits WHERE value_arrivedAt<=:now AND (value_countryCode IS NULL OR value_timeZone IS NULL) AND (value_countryResolutionAttemptedAt IS NULL OR value_countryResolutionAttemptedAt<:retryBefore) ORDER BY value_arrivedAt, value_id LIMIT 100")
    suspend fun pendingCountryVisits(now: Instant, retryBefore: Instant): List<VisitRow>
    @Query("SELECT * FROM country_lookup_cache WHERE coordinate=:coordinate AND version=:version") suspend fun countryLookup(coordinate: Coordinate, version: String): CountryLookupRow?
    @Query("SELECT * FROM country_analysis WHERE id=1") suspend fun countryAnalysis(): CountryAnalysisRow?
    @Upsert suspend fun put(row: CountryLookupRow)
    @Upsert suspend fun put(row: CountryAnalysisRow)
    @Query("SELECT * FROM points ORDER BY value_time DESC LIMIT :limit") fun observePoints(limit: Int = 2500): Flow<List<PointRow>>
    @Query("SELECT * FROM visits ORDER BY value_arrivedAt DESC") fun observeVisits(): Flow<List<VisitRow>>
    @Query("SELECT DISTINCT value_arrivedAt FROM visits ORDER BY value_arrivedAt") fun observeVisitArrivals(): Flow<List<Instant>>
    @Query("SELECT * FROM sessions ORDER BY value_startedAt DESC") fun observeSessions(): Flow<List<SessionRow>>
    @Query("SELECT * FROM places ORDER BY value_name COLLATE NOCASE") fun observePlaces(): Flow<List<PlaceRow>>
    @Query("SELECT * FROM country_stays") fun observeStays(): Flow<List<StayRow>>
    @Query("SELECT * FROM country_stays ORDER BY value_firstDay DESC, value_id") suspend fun stays(): List<StayRow>
    @Query("SELECT * FROM country_stays WHERE value_id=:id") suspend fun stay(id:String):StayRow?
    @Query("SELECT * FROM recording WHERE id=1") fun observeRecording(): Flow<RecordingRow?>
    @Query("SELECT * FROM recording WHERE id=1") suspend fun recording(): RecordingRow?
    @Query("SELECT * FROM visit_monitoring WHERE id=1") suspend fun visitMonitoring():VisitMonitoringRow?
    @Query("SELECT * FROM visit_monitoring WHERE id=1") fun observeVisitMonitoring():Flow<VisitMonitoringRow?>
    @Query("SELECT * FROM dwell WHERE id=1") suspend fun dwell(): DwellRow?
    @Query("SELECT * FROM points ORDER BY value_time DESC LIMIT 2") suspend fun latestPoints(): List<PointRow>
    @Query("SELECT * FROM points WHERE sessionId=:sessionId ORDER BY value_time DESC, value_id DESC LIMIT 2") suspend fun latestSessionPoints(sessionId: String): List<PointRow>
    @Query("SELECT * FROM points WHERE value_time >= :from AND value_time < :until ORDER BY value_time LIMIT :limit OFFSET :offset") suspend fun points(from: Instant, until: Instant, limit: Int = 500, offset: Int = 0): List<PointRow>
    @Query("SELECT COUNT(*) FROM points") fun pointCount(): Flow<Long>
    @Query("SELECT COUNT(*) FROM points") suspend fun totalPointCount(): Long
    @Query("SELECT COUNT(*) FROM points WHERE value_time >= :from AND value_time < :until") suspend fun rangePointCount(from: Instant, until: Instant): Long
    @Query("SELECT COUNT(*) FROM points WHERE value_time >= :from AND value_time < :until AND value_outlier=0") suspend fun rangeGoodPointCount(from:Instant,until:Instant):Long
    @Query("SELECT * FROM points WHERE value_time >= :from AND value_time < :until ORDER BY value_time, value_id LIMIT :limit") suspend fun firstPage(from: Instant, until: Instant, limit: Int = 500): List<PointRow>
    @Query("SELECT * FROM points WHERE value_time < :until AND (value_time, value_id) > (:afterTime, :afterId) ORDER BY value_time, value_id LIMIT :limit") suspend fun nextPage(afterTime: Instant, afterId: String, until: Instant, limit: Int = 500): List<PointRow>
    @Query("SELECT * FROM visits WHERE value_arrivedAt < :until AND (value_departedAt IS NULL OR value_departedAt >= :from) ORDER BY value_arrivedAt, value_id") suspend fun rangeVisits(from: Instant, until: Instant): List<VisitRow>
    @Query("SELECT * FROM sessions WHERE value_startedAt < :until AND (value_endedAt IS NULL OR value_endedAt >= :from) ORDER BY value_startedAt, value_id") suspend fun rangeSessions(from: Instant, until: Instant): List<SessionRow>
    @Query("SELECT * FROM sessions WHERE value_id=:id") suspend fun session(id: String): SessionRow?
    @Query("SELECT * FROM points WHERE sessionId=:sessionId ORDER BY value_time,value_id LIMIT 500")
    suspend fun sessionFirstPage(sessionId:String):List<PointRow>
    @Query("SELECT * FROM points WHERE sessionId=:sessionId AND (value_time,value_id)>(:afterTime,:afterId) ORDER BY value_time,value_id LIMIT 500")
    suspend fun sessionNextPage(sessionId:String,afterTime:Instant,afterId:String):List<PointRow>
    @Query("SELECT COUNT(*) FROM points WHERE sessionId=:sessionId") suspend fun sessionPointCount(sessionId: String): Long
    @Query("SELECT COUNT(*) FROM points WHERE sessionId=:sessionId AND value_outlier=0") suspend fun sessionGoodPointCount(sessionId:String):Long
    @Query("SELECT MIN(stamp) AS firstTime,MAX(stamp) AS lastTime FROM (SELECT MIN(value_time) AS stamp FROM points UNION ALL SELECT MAX(value_time) FROM points UNION ALL SELECT MIN(value_arrivedAt) FROM visits UNION ALL SELECT MAX(value_arrivedAt) FROM visits UNION ALL SELECT MIN(value_departedAt) FROM visits UNION ALL SELECT MAX(value_departedAt) FROM visits UNION ALL SELECT MIN(value_startedAt) FROM sessions UNION ALL SELECT MAX(value_startedAt) FROM sessions UNION ALL SELECT MIN(value_endedAt) FROM sessions UNION ALL SELECT MAX(value_endedAt) FROM sessions)")
    suspend fun photoHistoryBounds():PhotoHistoryBounds
    @Query("SELECT MAX(value_time) FROM points") suspend fun latestPointTime(): Instant?
    @Query("SELECT COUNT(*) FROM visits") suspend fun totalVisitCount(): Long
    @Query("SELECT COUNT(*) FROM sessions WHERE value_endedAt IS NULL") suspend fun openSessionCount(): Long
    @Query("SELECT * FROM sessions WHERE value_endedAt IS NULL") suspend fun openSessions(): List<SessionRow>
    @Query("SELECT * FROM visits WHERE value_departedAt IS NULL AND value_source='automatic'") suspend fun openVisits(): List<VisitRow>
    @Query("SELECT * FROM visits WHERE value_departedAt IS NULL AND value_source='automatic' ORDER BY value_arrivedAt DESC,value_id DESC LIMIT 1")
    suspend fun latestOpenVisit():VisitRow?
    @Query("SELECT * FROM visits WHERE value_departedAt IS NULL AND value_source='automatic' AND value_id!=:exceptId AND (:afterTime IS NULL OR (value_arrivedAt,value_id)>(:afterTime,:afterId)) ORDER BY value_arrivedAt,value_id LIMIT 500")
    suspend fun staleOpenVisitPage(exceptId:String,afterTime:Instant?,afterId:String):List<VisitRow>
    @Query("SELECT MIN(value_arrivedAt) FROM visits WHERE value_arrivedAt>:after")
    suspend fun nextVisitArrival(after:Instant):Instant?
    @Query("SELECT * FROM visits WHERE value_source='automatic' AND value_id!=:id AND (:priorOnly=0 OR (value_arrivedAt,value_id)<(:arrival,:id)) AND value_arrivedAt<=:latestArrival AND (value_arrivedAt>=:nearArrival OR COALESCE(value_departedAt,:now)>=:earliestDeparture) AND CAST(substr(value_coordinate,1,instr(value_coordinate,'|')-1) AS REAL) BETWEEN :south AND :north AND (:afterTime IS NULL OR (value_arrivedAt,value_id)>(:afterTime,:afterId)) ORDER BY value_arrivedAt,value_id LIMIT 500")
    suspend fun duplicateVisitCandidates(id:String,arrival:Instant,priorOnly:Boolean,latestArrival:Instant,nearArrival:Instant,earliestDeparture:Instant,now:Instant,south:Double,north:Double,afterTime:Instant?,afterId:String):List<VisitRow>
    @Query("SELECT * FROM visits WHERE value_departedAt IS NULL AND value_source='manual' ORDER BY value_arrivedAt DESC,value_id DESC LIMIT 2")
    suspend fun manualCheckoutCandidates():List<VisitRow>
    @Query("SELECT * FROM visits WHERE value_departedAt IS NULL AND value_source='manual' AND (:afterTime IS NULL OR (value_arrivedAt,value_id)<(:afterTime,:afterId)) ORDER BY value_arrivedAt DESC,value_id DESC LIMIT 500")
    suspend fun commandOpenManualVisits(afterTime:Instant?,afterId:String):List<VisitRow>
    @Query("SELECT * FROM places WHERE (:afterName IS NULL OR (value_name,value_id)>(:afterName,:afterId)) ORDER BY value_name,value_id LIMIT 500")
    suspend fun commandSavedPlaces(afterName:String?,afterId:String):List<PlaceRow>
    @Query("SELECT * FROM visits WHERE (:excludingId IS NULL OR value_id<>:excludingId) AND ((value_arrivedAt<:through AND COALESCE(value_departedAt,:future)>:from) OR (value_arrivedAt=:from AND COALESCE(value_departedAt,:future)=:through)) ORDER BY value_arrivedAt DESC,value_id DESC LIMIT 1")
    suspend fun manualVisitOverlap(from:Instant,through:Instant,future:Instant,excludingId:String?=null):VisitRow?
    @Query("SELECT * FROM visits WHERE value_id=:id") suspend fun visit(id: String): VisitRow?
    @Query("SELECT * FROM visits WHERE value_id=:id COLLATE NOCASE LIMIT 2") suspend fun visitLinkCandidates(id:String):List<VisitRow>
    @Query("SELECT * FROM places") suspend fun places(): List<PlaceRow>
    @Query("SELECT * FROM places WHERE value_id=:id") suspend fun place(id:String):PlaceRow?
    @Upsert suspend fun put(row: PointRow)
    @Upsert suspend fun putPoints(rows: List<PointRow>)
    @Upsert suspend fun put(row: VisitRow)
    @Upsert suspend fun put(row: SessionRow)
    @Upsert suspend fun put(row: PlaceRow)
    @Upsert suspend fun put(row: StayRow)
    @Upsert suspend fun put(row: RecordingRow)
    @Upsert suspend fun put(row: VisitMonitoringRow)
    @Upsert suspend fun put(row: DwellRow)
    @Upsert suspend fun put(row: PhotoRow)
    @Upsert suspend fun put(row: ExportRunRow)
    @Upsert suspend fun put(row: OutboxRow)
    @Query("UPDATE points SET value_outlier=1 WHERE value_id=:id") suspend fun flagPoint(id: String)
    @Query("DELETE FROM visits WHERE value_id=:id") suspend fun deleteVisit(id: String)
    @Query("DELETE FROM places WHERE value_id=:id") suspend fun deletePlace(id: String)
    @Query("DELETE FROM country_stays WHERE value_id=:id") suspend fun deleteStay(id: String)
    @Query("DELETE FROM points WHERE sessionId=:id") suspend fun deleteSessionPoints(id: String)
    @Query("DELETE FROM points WHERE value_id IN (:ids)") suspend fun deletePointsById(ids: List<String>)
    @Query("DELETE FROM points WHERE value_time >= :from AND value_time <= :until") suspend fun deletePointsInSessionWindow(from: Instant, until: Instant)
    @Query("DELETE FROM sessions WHERE value_id=:id") suspend fun deleteSession(id: String)
}

@Database(entities = [PointRow::class, VisitRow::class, SessionRow::class, PlaceRow::class, StayRow::class,
    RecordingRow::class, VisitMonitoringRow::class, DwellRow::class, PhotoRow::class, PhotoAssetRow::class, ExportRunRow::class, OutboxRow::class,
    CountryLookupRow::class, CountryAnalysisRow::class, WebhookPolicyRow::class, WebhookBatchRow::class, WebhookPointRow::class], views=[VisiblePhotoAsset::class], version = 12, exportSchema = true)
@TypeConverters(HistoryConverters::class)
abstract class HistoryDatabase : RoomDatabase() {
    private var driverBacked=false
    internal val supportsSnapshots get()=driverBacked
    abstract fun webhooks():WebhookQueueDao
    abstract fun history(): HistoryDao
    suspend fun <T> writeTransaction(block:suspend ()->T):T {
        val callerJob=currentCoroutineContext()[Job]
        // Room's pooled callback runs under the database job. Retain the caller's cancellation
        // together with the confined connection/transaction context, as the reader seam does.
        return if(driverBacked) useWriterConnection {connection->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                withContext(callerJob ?: EmptyCoroutineContext) {block()}
            }
        } else withTransaction {withContext(callerJob ?: EmptyCoroutineContext) {block()}}
    }
    companion object {
        val MIGRATION_1_2 = object : Migration(1,2) {
            override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("CREATE INDEX IF NOT EXISTS index_points_value_time_value_id ON points (value_time, value_id)") }
            override fun migrate(connection:SQLiteConnection) {connection.execSQL("CREATE INDEX IF NOT EXISTS index_points_value_time_value_id ON points (value_time, value_id)")}
        }
        val MIGRATION_2_3 = object : Migration(2,3) {
            override fun migrate(connection:SQLiteConnection) {
                connection.execSQL("ALTER TABLE points ADD COLUMN value_countryResolutionAttemptedAt INTEGER")
                connection.execSQL("ALTER TABLE visits ADD COLUMN value_countryResolutionAttemptedAt INTEGER")
                connection.execSQL("CREATE TABLE IF NOT EXISTS country_lookup_cache (coordinate TEXT NOT NULL, countryCode TEXT NOT NULL, timeZone TEXT NOT NULL, version TEXT NOT NULL, PRIMARY KEY(coordinate))")
                connection.execSQL("CREATE TABLE IF NOT EXISTS country_analysis (id INTEGER NOT NULL, runId TEXT NOT NULL, nextLookupAt INTEGER NOT NULL, PRIMARY KEY(id))")
            }
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE points ADD COLUMN value_countryResolutionAttemptedAt INTEGER")
                db.execSQL("ALTER TABLE visits ADD COLUMN value_countryResolutionAttemptedAt INTEGER")
                db.execSQL("CREATE TABLE IF NOT EXISTS country_lookup_cache (coordinate TEXT NOT NULL, countryCode TEXT NOT NULL, timeZone TEXT NOT NULL, version TEXT NOT NULL, PRIMARY KEY(coordinate))")
                db.execSQL("CREATE TABLE IF NOT EXISTS country_analysis (id INTEGER NOT NULL, runId TEXT NOT NULL, nextLookupAt INTEGER NOT NULL, PRIMARY KEY(id))")
            }
        }
        val MIGRATION_3_4 = object : Migration(3,4) {
            override fun migrate(db:SupportSQLiteDatabase) {db.execSQL("CREATE INDEX IF NOT EXISTS index_points_sessionId_value_time_value_id ON points (sessionId,value_time,value_id)")}
            override fun migrate(connection:SQLiteConnection) {connection.execSQL("CREATE INDEX IF NOT EXISTS index_points_sessionId_value_time_value_id ON points (sessionId,value_time,value_id)")}
        }
        val MIGRATION_4_5 = object : Migration(4,5) {
            override fun migrate(db:SupportSQLiteDatabase) {db.execSQL("CREATE INDEX IF NOT EXISTS index_photo_moments_capturedAt_uri ON photo_moments (capturedAt,uri)");db.execSQL("CREATE TABLE IF NOT EXISTS photo_assets (uri TEXT NOT NULL,capturedAt INTEGER,gps TEXT,selected INTEGER NOT NULL,available INTEGER NOT NULL,lastCheckedAt INTEGER NOT NULL,PRIMARY KEY(uri))")}
            override fun migrate(connection:SQLiteConnection) {connection.execSQL("CREATE INDEX IF NOT EXISTS index_photo_moments_capturedAt_uri ON photo_moments (capturedAt,uri)");connection.execSQL("CREATE TABLE IF NOT EXISTS photo_assets (uri TEXT NOT NULL,capturedAt INTEGER,gps TEXT,selected INTEGER NOT NULL,available INTEGER NOT NULL,lastCheckedAt INTEGER NOT NULL,PRIMARY KEY(uri))")}
        }
        val MIGRATION_5_6 = object : Migration(5,6) {
            override fun migrate(db:SupportSQLiteDatabase) {db.execSQL("CREATE INDEX IF NOT EXISTS index_photo_assets_capturedAt_uri ON photo_assets (capturedAt,uri)")}
            override fun migrate(connection:SQLiteConnection) {connection.execSQL("CREATE INDEX IF NOT EXISTS index_photo_assets_capturedAt_uri ON photo_assets (capturedAt,uri)")}
        }
        val MIGRATION_6_7 = object : Migration(6,7) {
            private val statements=listOf("ALTER TABLE photo_assets ADD COLUMN identity TEXT", "CREATE INDEX IF NOT EXISTS index_photo_assets_identity_available_selected_uri ON photo_assets (identity,available,selected,uri)","CREATE VIEW IF NOT EXISTS `visible_photo_assets` AS $PHOTO_ASSET_VIEW_SQL")
            override fun migrate(db:SupportSQLiteDatabase) {statements.forEach {db.execSQL(it)}}
            override fun migrate(connection:SQLiteConnection) {statements.forEach {connection.execSQL(it)}}
        }
        val MIGRATION_7_8 = object : Migration(7,8) {
            private val statements=listOf(
                "CREATE TABLE IF NOT EXISTS webhook_policy (id INTEGER NOT NULL,revision TEXT NOT NULL,enabled INTEGER NOT NULL,capture INTEGER NOT NULL,includeOutliers INTEGER NOT NULL,createdAt INTEGER NOT NULL,lastSuccess INTEGER,lastStatus INTEGER,PRIMARY KEY(id))",
                "CREATE TABLE IF NOT EXISTS webhook_batches (id TEXT NOT NULL,revision TEXT NOT NULL,createdAt INTEGER NOT NULL,pointCount INTEGER NOT NULL,kind TEXT NOT NULL,visitCount INTEGER NOT NULL,bodyId TEXT,bodyFormat TEXT,bodyBytes INTEGER,bodySha256 TEXT,attempts INTEGER NOT NULL,nextAttempt INTEGER,stopped INTEGER NOT NULL,attemptToken TEXT,leaseUntil INTEGER,lastStatus INTEGER,PRIMARY KEY(id))",
                "CREATE INDEX IF NOT EXISTS index_webhook_batches_revision_createdAt_id ON webhook_batches (revision,createdAt,id)",
                "CREATE TABLE IF NOT EXISTS webhook_points (revision TEXT NOT NULL,value_id TEXT NOT NULL,value_coordinate TEXT NOT NULL,value_time INTEGER NOT NULL,value_accuracy REAL NOT NULL,value_altitude REAL,value_speed REAL,value_outlier INTEGER NOT NULL,value_countryCode TEXT,value_timeZone TEXT,value_countryResolutionAttemptedAt INTEGER,queuedAt INTEGER NOT NULL,batchId TEXT,PRIMARY KEY(revision,value_id),FOREIGN KEY(batchId) REFERENCES webhook_batches(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                "CREATE INDEX IF NOT EXISTS index_webhook_points_revision_batchId_value_time_value_id ON webhook_points (revision,batchId,value_time,value_id)",
                "CREATE INDEX IF NOT EXISTS index_webhook_points_batchId_value_time_value_id ON webhook_points (batchId,value_time,value_id)"
            )
            override fun migrate(db:SupportSQLiteDatabase) {statements.forEach {db.execSQL(it)}}
            override fun migrate(connection:SQLiteConnection) {statements.forEach {connection.execSQL(it)}}
        }
        val MIGRATION_8_9 = object : Migration(8,9) {
            override fun migrate(db:SupportSQLiteDatabase) {db.execSQL("CREATE INDEX IF NOT EXISTS index_webhook_batches_bodyId ON webhook_batches (bodyId)")}
            override fun migrate(connection:SQLiteConnection) {connection.execSQL("CREATE INDEX IF NOT EXISTS index_webhook_batches_bodyId ON webhook_batches (bodyId)")}
        }
        val MIGRATION_9_10 = object : Migration(9,10) {
            private val statements=listOf("ALTER TABLE dwell ADD COLUMN state_nextAnchor TEXT", "ALTER TABLE dwell ADD COLUMN state_nextCandidateSince INTEGER")
            override fun migrate(db:SupportSQLiteDatabase) {statements.forEach {db.execSQL(it)}}
            override fun migrate(connection:SQLiteConnection) {statements.forEach {connection.execSQL(it)}}
        }
        val MIGRATION_10_11 = object : Migration(10,11) {
            private val sql="CREATE TABLE IF NOT EXISTS visit_monitoring (id INTEGER NOT NULL,requested INTEGER NOT NULL,generation TEXT,startedAt INTEGER,lastFix INTEGER,interruption TEXT,recent_coordinate TEXT,recent_time INTEGER,recent_accuracy REAL,previous_coordinate TEXT,previous_time INTEGER,previous_accuracy REAL,PRIMARY KEY(id))"
            override fun migrate(db:SupportSQLiteDatabase) {db.execSQL(sql)}
            override fun migrate(connection:SQLiteConnection) {connection.execSQL(sql)}
        }
        val MIGRATION_11_12 = object : Migration(11,12) {
            private val statements=listOf("ALTER TABLE recording ADD COLUMN startedBootCount INTEGER","ALTER TABLE visit_monitoring ADD COLUMN startedBootCount INTEGER")
            override fun migrate(db:SupportSQLiteDatabase) {statements.forEach {db.execSQL(it)}}
            override fun migrate(connection:SQLiteConnection) {statements.forEach {connection.execSQL(it)}}
        }
        @Volatile private var instance: HistoryDatabase? = null
        fun create(context:Context,name:String?="whereday.db",driver:androidx.sqlite.SQLiteDriver=BundledSQLiteDriver()):HistoryDatabase {
            val builder=if(name==null) Room.inMemoryDatabaseBuilder(context.applicationContext,HistoryDatabase::class.java) else Room.databaseBuilder(context.applicationContext,HistoryDatabase::class.java,name)
            return builder.setDriver(driver).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8,MIGRATION_8_9,MIGRATION_9_10,MIGRATION_10_11,MIGRATION_11_12).build().also {it.driverBacked=true}
        }
        fun open(context: Context): HistoryDatabase = instance ?: synchronized(this) {
            instance ?: create(context).also { instance = it }
        }
    }
}
