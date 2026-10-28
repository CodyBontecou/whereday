package com.bontecou.whereday.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.io.File

data class PhotoSyncState(val running:Boolean=false,val processed:Int=0,val total:Int=0,val message:String?=null,val validated:Boolean=false)

/** Owns selected-reference and explicit library-sync jobs/grants. Never requests permission or scans implicitly. */
class PhotoLibraryController internal constructor(
    private val database:HistoryDatabase,private val photos:PhotoRepository,
    private val read:suspend(String)->PhotoAssetMetadata,
    private val grants:()->Set<String>,private val retain:(String)->Unit,private val release:(String)->Unit,
    private val stagingDirectory:File,
    private val enumerateLibrary:(suspend(suspend(String)->Unit)->Unit)?=null,
    private val enumerateRange:(suspend(HistoryRange,suspend(String)->Unit)->Unit)?=null,
    private val now:()->Instant={Instant.now()},
    private val selectionEpoch:suspend()->String={""},
    private val invalidateSelections:suspend()->Unit={},
    private val decode:suspend(String,Int)->android.graphics.Bitmap={_,_->error("Photo image reader is unavailable")}
) {
    constructor(context:Context,database:HistoryDatabase,photos:PhotoRepository,source:AndroidPhotoSource,preferences:AppPreferences):this(database,photos,source::metadata,
        {context.contentResolver.persistedUriPermissions.filter {it.isReadPermission}.map {it.uri.toString()}.toSet()},
        {context.contentResolver.takePersistableUriPermission(Uri.parse(it),Intent.FLAG_GRANT_READ_URI_PERMISSION)},
        {context.contentResolver.releasePersistableUriPermission(Uri.parse(it),Intent.FLAG_GRANT_READ_URI_PERMISSION)},File(context.cacheDir,"photo-sync"),enumerateLibrary={consume->AndroidPhotoLibrary(context).references(consume)},enumerateRange={range,consume->AndroidPhotoLibrary(context).references(range,consume)},selectionEpoch={preferences.settings.first().photoEpoch},invalidateSelections={preferences.update {it.copy(photoEpoch=java.util.UUID.randomUUID().toString())}},decode=source::thumbnail)
    private val guard=Any()
    private var clearing=false
    private val active=mutableSetOf<Job>()
    private val commands=Mutex()
    private val mutable=MutableStateFlow(PhotoSyncState())
    val state=mutable.asStateFlow()
    private suspend fun <T> tracked(block:suspend()->T):T {
        val job=currentCoroutineContext()[Job] ?: error("Photo operations require a caller lifetime")
        synchronized(guard) {check(!clearing) {"Photo data is being cleared."};active.add(job)}
        try {return commands.withLock {currentCoroutineContext().ensureActive();block()}}
        finally {synchronized(guard) {active.remove(job)}}
    }
    suspend fun captureSelection():String=selectionEpoch()
    suspend fun addSelected(references:List<String>,expectedEpoch:String?=null)=tracked {
        check(expectedEpoch==null || expectedEpoch==selectionEpoch()) {"Photo selection expired. Select the photos again."}
        require(references.size<=500)
        val unique=references.distinct()
        syncReferences(unique.size,true) {consume->unique.forEach {consume(it)}}
    }
    suspend fun refresh()=tracked {refreshReferences()}
    private suspend fun refreshReferences(publishReady:Boolean=true):Boolean {
        val count=database.history().photoAssetCount()
        require(count<=Int.MAX_VALUE) {"Too many photo references"}
        return syncReferences(count.toInt(),false,publishReady=publishReady) {consume->
            var after=""
            while(true) {
                currentCoroutineContext().ensureActive()
                val page=database.history().photoAssetPage(after)
                if(page.isEmpty()) break
                page.forEach {consume(it.uri)};after=page.last().uri
            }
        }
    }
    /** Explicit access-scoped scan; unavailable saved references are revalidated first. */
    suspend fun syncLibrary(range:HistoryRange?=null)=tracked {
        val enumerate:suspend(suspend(String)->Unit)->Unit=if(range==null) checkNotNull(enumerateLibrary) {"Photo library access is unavailable"} else {consume->checkNotNull(enumerateRange) {"Photo range access is unavailable"}(range,consume)}
        if(refreshReferences(false)) syncReferences(0,false,range,enumerate=enumerate)
    }
    private suspend fun syncReferences(total:Int,newSelection:Boolean,correlationRange:HistoryRange?=null,publishReady:Boolean=true,enumerate:suspend(suspend(String)->Unit)->Unit):Boolean {
        mutable.value=PhotoSyncState(true,0,total)
        // Only explicit picker batches acquire grants; each such batch is limited to 500 references.
        val newGrants=mutableSetOf<String>()
        var spool:PhotoSyncSpool?=null
        var success=false
        try {
            val staged=withContext(NonCancellable+Dispatchers.IO) {PhotoSyncSpool.create(stagingDirectory).also {spool=it}}
            val existingGrants=if(newSelection) withContext(Dispatchers.IO) {grants()} else emptySet()
            var failed=0;var processed=0
            enumerate {reference->
                currentCoroutineContext().ensureActive()
                val value=try {
                    read(reference).also {value->
                        require(value.uri==reference)
                        if(newSelection) withContext(NonCancellable+Dispatchers.IO) {
                            retain(reference)
                            // Register ownership before a canceled caller can resume from grant acquisition.
                            if(reference !in existingGrants) newGrants.add(reference)
                        }
                    }
                } catch(e:CancellationException) {throw e}
                catch(_:Exception) {failed++;photos.unavailable(reference);null}
                if(value!=null && (correlationRange==null || value.takenAt?.let(correlationRange::contains)==true)) withContext(Dispatchers.IO) {currentCoroutineContext().ensureActive();staged.append(value)}
                processed++;mutable.value=PhotoSyncState(true,processed,total)
            }
            withContext(Dispatchers.IO) {staged.seal()}
            val first=staged.first ?: now()
            val range=correlationRange ?: HistoryRange(first,(staged.last ?: first).plusMillis(1))
            var located=0
            // Reading and committing suspend between batches, retaining only one metadata batch.
            withContext(Dispatchers.IO) {
                staged.batches {batch->
                    currentCoroutineContext().ensureActive()
                    located+=photos.sync(batch,range,newSelection)
                }
            }
            mutable.value=PhotoSyncState(message="${staged.count} ${if(staged.count==1) "photo" else "photos"} checked · $located located · $failed unavailable.",validated=publishReady);success=true
        } catch(e:CancellationException) {
            mutable.value=PhotoSyncState(message="Photo sync stopped. Completed references are kept.");throw e
        } catch(_:Exception) {mutable.value=PhotoSyncState(message="Photos could not be updated. Check access and try again.")}
        finally {
            withContext(NonCancellable+Dispatchers.IO) {
                try {
                    // A late cancellation can arrive after a committed batch. Its owned grants stay valid.
                    newGrants.forEach {uri->if(runCatching {database.history().photoAsset(uri)==null}.getOrDefault(false)) runCatching {release(uri)}}
                } finally {spool?.close()}
            }
            if(mutable.value.running) mutable.value=mutable.value.copy(running=false)
        }
        return success
    }
    suspend fun image(uri:String,pixels:Int)=tracked {decode(uri,pixels)}
    suspend fun unavailable(uri:String)=tracked {photos.unavailable(uri)}
    suspend fun remove(uri:String)=tracked {
        val target=database.history().photoAsset(uri)
        val owned=withContext(Dispatchers.IO) {grants()}.filter {reference->
            val row=database.history().photoAsset(reference)
            row?.selected==true && (reference==uri || (target?.identity!=null && row.identity==target.identity))
        }
        try {photos.remove(uri)} finally {
            withContext(NonCancellable+Dispatchers.IO) {owned.forEach {reference->
                if(runCatching {database.history().photoAsset(reference)==null}.getOrDefault(false)) runCatching {release(reference)}
            }}
        }
    }
    suspend fun clearAll(block:suspend()->Unit) {
        val caller=currentCoroutineContext()[Job]
        val jobs=synchronized(guard) {check(!clearing);clearing=true;active.filter {it!==caller}}
        try {
            invalidateSelections()
            jobs.forEach {it.cancel()};jobs.joinAll()
            commands.withLock {
                val held=withContext(Dispatchers.IO) {grants()}
                val owned=held.filter {database.history().photoAsset(it)?.selected==true}
                withContext(Dispatchers.IO) {PhotoSyncSpool.clearOrphans(stagingDirectory)}
                try {block()} finally {
                    withContext(NonCancellable+Dispatchers.IO) {owned.forEach {uri->
                        if(runCatching {database.history().photoAsset(uri)==null}.getOrDefault(false)) runCatching {release(uri)}
                    }}
                }
                mutable.value=PhotoSyncState()
            }
        } finally {synchronized(guard) {clearing=false}}
    }
}
