package com.bontecou.whereday.data

import com.bontecou.whereday.domain.HistoryRange
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** One AppGraph owner per private cache root. Clear-data closes every registered directory. */
class PhotoPlaceRepository(private val root:File,private val photos:PhotoRepository) {
    private val initialization=Mutex()
    private val ownership=Mutex()
    private val clearLock=Mutex()
    private var initialized=false
    private var generation=0L
    private val preparing=mutableSetOf<Job>()
    private val active=mutableSetOf<PhotoPlaceDirectory>()
    private val mutableClearing=MutableStateFlow(false)
    val clearing=mutableClearing.asStateFlow()

    suspend fun prepare(range:HistoryRange,progress:(Long)->Unit={}):PhotoPlaceDirectory {
        var owned:PhotoPlaceDirectory?=null
        try {return coroutineScope {
            val operation=currentCoroutineContext().job
            val epoch=ownership.withLock {
                check(!mutableClearing.value) {"Journal data is being cleared."}
                preparing.add(operation)
                generation
            }
            try {withContext(Dispatchers.IO) {
                initialization.withLock {
                    if(!initialized) {
                        check(root.mkdirs() || root.isDirectory)
                        // A previous process cannot retain a live directory in this AppGraph-owned root.
                        root.listFiles()?.forEach {check(it.deleteRecursively()) {"Old photo directory cache could not be removed"}}
                        initialized=true
                    }
                }
                val archive=PhotoPlaceArchive.prepare(root,photos,range,progress=progress)
                val directory=PhotoPlaceDirectory(archive) {closed->ownership.withLock {active.remove(closed)};Unit}
                owned=directory
                ownership.withLock {
                    check(!mutableClearing.value && generation==epoch) {"Journal data was cleared."}
                    currentCoroutineContext().ensureActive()
                    active.add(directory)
                }
                directory
            }} finally {withContext(NonCancellable) {ownership.withLock {preparing.remove(operation)}}}
        }} catch(error:Throwable) {
            withContext(NonCancellable) {try {owned?.close()} catch(cleanup:Throwable) {error.addSuppressed(cleanup)}}
            throw error
        }
    }

    /** Excludes new snapshots through history deletion and joins all old preparations before erasing files. */
    suspend fun clearCache(clearHistory:suspend ()->Unit={})=clearLock.withLock {
        val caller=currentCoroutineContext().job
        withContext(NonCancellable+Dispatchers.IO) {
            val (jobs,directories)=ownership.withLock {
                mutableClearing.value=true;generation++
                preparing.toList() to active.toList()
            }
            try {
                jobs.filter {it!==caller}.forEach {it.cancelAndJoin()}
                directories.forEach {it.close()}
                root.listFiles()?.forEach {check(it.deleteRecursively()) {"Photo directory cache could not be removed"}}
                clearHistory()
            } finally {ownership.withLock {mutableClearing.value=false}}
        }
    }

    /** The caller supplies current foreground/access eligibility; false closes before Unavailable is emitted. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(range:HistoryRange,available:Flow<Boolean>):Flow<PhotoPlaceDirectoryState> =
        combine(available,clearing) {allowed,clearing->allowed && !clearing}.distinctUntilChanged().flatMapLatest {allowed->
            if(!allowed) flowOf<PhotoPlaceDirectoryState>(PhotoPlaceDirectoryState.Unavailable)
            else photos.changes().transformLatest<Unit,PhotoPlaceDirectoryState> {
                emit(PhotoPlaceDirectoryState.Loading)
                var directory:PhotoPlaceDirectory?=null
                try {
                    directory=prepare(range)
                    emit(PhotoPlaceDirectoryState.Ready(directory))
                    awaitCancellation()
                } catch(error:CancellationException) {throw error}
                catch(_:Exception) {emit(PhotoPlaceDirectoryState.Failed)}
                finally {directory?.close()}
            }
        }
}

sealed interface PhotoPlaceDirectoryState {
    data object Unavailable:PhotoPlaceDirectoryState
    data object Loading:PhotoPlaceDirectoryState
    data object Failed:PhotoPlaceDirectoryState
    data class Ready(val directory:PhotoPlaceDirectory):PhotoPlaceDirectoryState
}

/** All disk reads and cleanup run off the UI thread; active fences retained Ready states after a clear. */
class PhotoPlaceDirectory internal constructor(private val archive:PhotoPlaceArchive,private val onClosed:suspend (PhotoPlaceDirectory)->Unit) {
    val identity get()=archive.identity
    private val lock=Mutex()
    private val sources=mutableSetOf<PagingSource<Int,PhotoPlaceEntry>>()
    private val mutableActive=MutableStateFlow(true)
    val active=mutableActive.asStateFlow()
    suspend fun counts():PhotoPlaceCounts=read {PhotoPlaceCounts(photoCount,placeCount,markerCount)}
    suspend fun places(after:Long=0,limit:Int=50,area:String?=null,areas:Set<String>?=null)=read {places(after,limit,area,areas)}
    suspend fun place(id:Long)=read {place(id)}
    suspend fun previousPlaceCursor(after:Long,areas:Set<String>?=null)=read {previousPlaceCursor(after,areas)}
    suspend fun previousPhotoCursor(place:Long,after:Long)=read {previousPhotoCursor(place,after)}
    suspend fun validPlacePage(page:Int,after:Long,areas:Set<String>?=null)=read {validPlacePage(page,after,areas)}
    suspend fun validPhotoPage(place:Long,page:Int,after:Long)=read {validPhotoPage(place,page,after)}
    suspend fun markers()=read {markers()}
    suspend fun photos(place:Long,after:Long=0,limit:Int=50)=read {photos(place,after,limit)}
    suspend fun photoRank(place:Long,uri:String)=read {photoRank(place,uri)}
    fun photoPagingSource(place:Long):PagingSource<Int,PhotoPlaceEntry> {
        val source=object:PagingSource<Int,PhotoPlaceEntry>() {
            override val jumpingSupported=true
            override fun getRefreshKey(state:PagingState<Int,PhotoPlaceEntry>)=state.anchorPosition
            override suspend fun load(params:LoadParams<Int>):LoadResult<Int,PhotoPlaceEntry> = try {
                val offset=params.key ?: 0
                val (total,entries)=read {Math.toIntExact(photoCount(place)) to photoSlice(place,offset,params.loadSize.coerceAtMost(500))}
                LoadResult.Page(entries,if(offset==0) null else (offset-params.loadSize).coerceAtLeast(0),
                    if(offset+entries.size>=total) null else offset+entries.size,offset,(total-offset-entries.size).coerceAtLeast(0))
            } catch(error:CancellationException) {throw error}
            catch(error:Exception) {LoadResult.Error(error)}
        }
        source.registerInvalidatedCallback {synchronized(sources) {sources.remove(source)}}
        synchronized(sources) {if(mutableActive.value) sources.add(source) else source.invalidate()}
        return source
    }
    private suspend fun <T> read(block:PhotoPlaceArchive.()->T):T=withContext(Dispatchers.IO) {
        lock.withLock {check(mutableActive.value) {"Photo directory is closed"};archive.block()}
    }
    suspend fun close()=withContext(NonCancellable+Dispatchers.IO) {
        lock.withLock {
            if(mutableActive.value) {
                mutableActive.value=false
                synchronized(sources) {sources.toList().forEach {it.invalidate()};sources.clear()}
                try {archive.close()} finally {onClosed(this@PhotoPlaceDirectory)}
            }
        }
    }
}

data class PhotoPlaceCounts(val photos:Long,val places:Long,val markers:Long)
