package com.bontecou.whereday.data

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Clock
import java.time.Instant

/** Local metadata only. Platform code validates content access before supplying an available asset. */
class PhotoRepository(private val database:HistoryDatabase,private val clock:Clock=Clock.systemUTC()) {
    private val dao=database.history()
    suspend fun sync(assets:List<PhotoAssetMetadata>,range:HistoryRange,selected:Boolean,progress:suspend(Int)->Unit={}):Int {
        require(assets.size<=500) {"Sync photos in bounded batches"}
        require(assets.map {it.uri}.distinct().size==assets.size) {"Photo batch contains duplicate references"}
        require(assets.all {it.takenAt?.let(range::contains)!=false})
        val from=range.from.minus(PhotoCorrelation.routeWindow).coerceAtLeast(HistoryRange.all.from)
        val until=range.until.minusMillis(1).plus(PhotoCorrelation.routeWindow).coerceAtMost(HistoryRange.all.until.minusMillis(1))
        val now=clock.instant()
        val callerJob=currentCoroutineContext()[Job]
        return database.writeTransaction {withContext(callerJob ?: EmptyCoroutineContext) {
            // iOS chooses its all-outlier fallback for the full requested range, not per photo.
            val goodOnly=dao.rangeGoodPointCount(from,until.plusMillis(1))>0
            var located=0
            assets.forEachIndexed {index,asset->
                currentCoroutineContext().ensureActive()
                val old=dao.photoAsset(asset.uri)
                dao.put(PhotoAssetRow(asset.uri,asset.takenAt,asset.gps,selected || old?.selected==true,true,now,asset.identity))
                val time=asset.takenAt
                val moment=if(time==null) null else PhotoCorrelation.resolve(asset,
                    if(asset.gps==null) dao.photoPointBefore(time,from,until,goodOnly)?.value else null,
                    if(asset.gps==null) dao.photoPointAfter(time,from,until,goodOnly)?.value else null,
                    if(asset.gps==null) dao.photoContainingVisit(time.minus(PhotoCorrelation.visitGrace),time.plus(PhotoCorrelation.visitGrace),now)?.value else null,now)
                if(moment==null) dao.deletePhotoMoment(asset.uri)
                else {dao.put(PhotoRow(moment.uri,moment.takenAt,moment.coordinate,moment.source.wireValue,now));located++}
                progress(index+1)
            }
            currentCoroutineContext().ensureActive()
            located
        }}
    }
    suspend fun automaticRange(map:HistoryRange?,now:Instant,zone:java.time.ZoneId):HistoryRange=database.readSnapshot {
        val bounds=dao.photoHistoryBounds()
        AutomaticPhotoRange.build(map,bounds.first,bounds.last,now,zone)
    }
    /** Complete chronological source; Room invalidates it on either metadata table changing. */
    fun pagingSource(range:HistoryRange)=dao.pagedPhotos(range.from,range.until)
    suspend fun rank(range:HistoryRange,uri:String):Int?=database.readSnapshot {
        val row=dao.photoMoment(uri) ?: return@readSnapshot null
        if(!range.contains(row.capturedAt) || dao.photoAsset(uri)?.available!=true || !dao.isVisiblePhoto(uri)) null
        else dao.photoRank(range.from,range.until,row.capturedAt,uri)
    }
    /** Includes unavailable/unlocated references; unknown dates sort first, then timestamp and URI. */
    fun libraryPagingSource()=dao.pagedPhotoLibrary()
    suspend fun libraryRank(uri:String):Int?=database.readSnapshot {
        val row=dao.photoAsset(uri) ?: return@readSnapshot null
        if(!dao.isVisiblePhoto(uri)) return@readSnapshot null
        dao.photoLibraryRank(row.capturedAt,row.uri)
    }
    suspend fun unavailable(uri:String) {dao.hidePhotoAsset(uri,clock.instant())}
    suspend fun remove(uri:String)=database.writeTransaction {
        val identity=dao.photoAsset(uri)?.identity
        if(identity==null) {dao.deletePhotoMoment(uri);dao.deletePhotoAsset(uri)}
        else {dao.deletePhotoIdentityMoments(identity);dao.deletePhotoIdentityAssets(identity)}
    }
    fun observeLibrary(after:String="")=database.invalidationTracker.createFlow("photo_moments","photo_assets").conflate().map {
        database.readSnapshot {
            val time=after.substringBefore('|').toLongOrNull()?.let(Instant::ofEpochMilli)
            val entries=when {after.isEmpty()->dao.photoLibraryFirstPage();time==null->dao.photoLibraryAfterUnknown(after.substringAfter('|'));else->dao.photoLibraryNextPage(time,after.substringAfter('|'))}
            val shown=entries.take(50)
            val next=if(entries.size>50) shown.last().asset.let {"${it.capturedAt?.toEpochMilli()?.toString().orEmpty()}|${it.uri}"} else null
            PhotoLibraryPage(dao.photoLibraryCount(),shown,next)
        }
    }
    /** Complete validated, alias-deduplicated metadata under one reader snapshot; never a display sample. */
    suspend fun forEachMomentPage(range:HistoryRange,consume:(List<PhotoMoment>)->Unit)=database.readSnapshot {
        var page=dao.photoFirstPage(range.from,range.until)
        while(page.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            consume(page.map {row->PhotoMoment(row.uri,row.capturedAt,row.coordinate,PhotoCoordinateSource.entries.first {it.wireValue==row.coordinateSource})})
            val last=page.last()
            page=dao.photoNextPage(last.capturedAt,last.uri,range.until)
        }
    }
    fun changes()=database.invalidationTracker.createFlow("photo_moments","photo_assets").conflate().map {Unit}
    /** Eligibility/summary consumers do not need to materialize a sampled photo window. */
    fun observeCount(range:HistoryRange)=changes().map {database.readSnapshot {dao.photoCount(range.from,range.until)}}.distinctUntilChanged()
    fun observeWindow(range:HistoryRange)=database.invalidationTracker.createFlow("photo_moments","photo_assets").conflate().map {window(range)}
    suspend fun window(range:HistoryRange,limit:Int=500):PhotoWindow=database.readSnapshot {
        val count=dao.photoCount(range.from,range.until)
        val sampler=PhotoMomentSampler(count,limit)
        var page=dao.photoFirstPage(range.from,range.until)
        while(page.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            page.forEach {row->sampler.add(PhotoMoment(row.uri,row.capturedAt,row.coordinate,PhotoCoordinateSource.entries.first {it.wireValue==row.coordinateSource}))}
            val last=page.last();page=dao.photoNextPage(last.capturedAt,last.uri,range.until)
        }
        PhotoWindow(count,sampler.photos())
    }
}

data class PhotoLibraryPage(val count:Long,val entries:List<PhotoLibraryEntry>,val nextCursor:String?) {val hasMore get()=nextCursor!=null}
