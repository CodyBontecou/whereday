package com.bontecou.whereday.platform

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.bontecou.whereday.data.AppPreferences
import com.bontecou.whereday.data.PhotoRepository
import com.bontecou.whereday.domain.HistoryRange
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select
import java.io.Closeable
import java.time.Instant
import java.time.ZoneId

/** No URI payloads are retained; callbacks are a conflated request to query the currently allowed scope. */
internal class AndroidPhotoChanges(private val context:Context,private val collection:Uri=MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)) {
    suspend fun version():String?=withContext(Dispatchers.IO) {
        if(android.os.Build.VERSION.SDK_INT<30) return@withContext null
        try {MediaStore.getExternalVolumeNames(context).sorted().joinToString("|") {name->"$name:${MediaStore.getVersion(context,name)}:${MediaStore.getGeneration(context,name)}"}}
        catch(_:Exception) {null}
    }
    fun observe(changed:()->Unit):Closeable {
        val resolver=context.contentResolver
        val observer=object:ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange:Boolean) {changed()}
            override fun onChange(selfChange:Boolean,uri:Uri?) {changed()}
            override fun onChange(selfChange:Boolean,uri:Uri?,flags:Int) {changed()}
            override fun onChange(selfChange:Boolean,uris:MutableCollection<Uri>,flags:Int) {changed()}
        }
        resolver.registerContentObserver(collection,true,observer)
        return Closeable {resolver.unregisterContentObserver(observer)}
    }
}

data class AutomaticPhotoState(val loaded:Boolean=false,val enabled:Boolean=false,val running:Boolean=false,val watching:Boolean=false,val lastSuccess:Instant?=null,val error:String?=null,val message:String?=null)

/** Serial control messages can cancel/join a scan without waiting for it to finish normally. */
class AutomaticPhotoSync internal constructor(
    enabled:Flow<Boolean>,private val save:suspend(Boolean)->Unit,
    private val access:()->PhotoLibraryAccess,private val original:()->Boolean,
    private val observe:((()->Unit)->Closeable),private val resolveRange:suspend(HistoryRange?)->HistoryRange,
    private val scan:suspend(HistoryRange)->Boolean,
    private val version:suspend()->String?={"stable"},
    private val elapsed:()->Long={android.os.SystemClock.elapsedRealtime()},private val now:()->Instant={Instant.now()},
    private val debounceMillis:Long=300,dispatcher:CoroutineDispatcher=Dispatchers.Main.immediate
) {
    constructor(context:Context,preferences:AppPreferences,photos:PhotoRepository,library:PhotoLibraryController):this(
        preferences.settings.map {it.automaticPhotoSync},
        {value->preferences.update {it.copy(automaticPhotoSync=value,showPhotoMarkers=value)}},
        AndroidPhotoAccess(context.applicationContext)::current,AndroidPhotoAccess(context.applicationContext)::originalLocationsAllowed,
        AndroidPhotoChanges(context.applicationContext)::observe,
        {map->photos.automaticRange(map,Instant.now(),ZoneId.systemDefault())},
        {range->library.syncLibrary(range);library.state.value.validated},AndroidPhotoChanges(context.applicationContext)::version)
    private sealed interface Command {
        data object Options:Command
        data class Visible(val value:Boolean):Command
        data class Range(val value:HistoryRange?):Command
        data class Configure(val enabled:Boolean,val reply:CompletableDeferred<Unit>,val request:Long):Command
        data class Finished(val generation:Long,val success:Boolean,val didSync:Boolean,val version:String?):Command
        data class LibraryWake(val epoch:Long):Command
        data object Refresh:Command
        data object Stop:Command
        data class Failure(val message:String):Command
    }
    private val options=enabled
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private val commands=Channel<Command>(Channel.UNLIMITED)
    private val changes=Channel<Long>(Channel.CONFLATED)
    private val running=java.util.concurrent.atomic.AtomicReference<Job?>(null)
    private val configuration=java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var wantedForeground=false
    @Volatile private var inhibited=false
    private val mutable=MutableStateFlow(AutomaticPhotoState())
    val state=mutable.asStateFlow()
    private var loaded=false
    private var isEnabled=false
    private var visible=false
    private var map:HistoryRange?=null
    private var subscription:Closeable?=null
    private var worker:Job?=null
    private var debounce:Job?=null
    private var epoch=0L
    private var generation=0L
    private var pending=false
    private var retry=false
    private var lastVersion:String?=null
    private var lastElapsed:Long?=null
    private var lastSuccess:Instant?=null
    private var capabilities:Pair<PhotoLibraryAccess,Boolean>?=null
    init {
        scope.launch {try {enabled.distinctUntilChanged().collect {commands.send(Command.Options)}} catch(e:CancellationException) {throw e} catch(_:Exception) {commands.trySend(Command.Failure("Automatic photo settings could not be loaded."))}}
        scope.launch {
            try {
                while(isActive) select<Unit> {
                    commands.onReceive {command->
                        try {handle(command)} catch(e:CancellationException) {throw e} catch(_:Exception) {
                            publish(error="Automatic photo settings or sync could not be updated. Try again.")
                            if(command is Command.Configure) command.reply.completeExceptionally(IllegalStateException("Automatic photo settings could not be saved."))
                        }
                    }
                    changes.onReceive {token->if(token==epoch && visible && isEnabled) {
                        debounce?.cancel();debounce=scope.launch {delay(debounceMillis);commands.send(Command.LibraryWake(token))}
                    }}
                }
            } finally {withContext(NonCancellable) {
                stopWorker();unwatch();commands.close();changes.close()
                while(true) {val command=commands.tryReceive().getOrNull() ?: break;if(command is Command.Configure) command.reply.completeExceptionally(CancellationException("Photo controller closed"))}
            }}
        }
    }
    fun foreground() {wantedForeground=true;commands.trySend(Command.Visible(true))}
    fun background() {wantedForeground=false;running.get()?.cancel();commands.trySend(Command.Visible(false))}
    fun mapRange(value:HistoryRange?) {commands.trySend(Command.Range(value))}
    fun refresh() {commands.trySend(Command.Refresh)}
    fun stopCurrent() {commands.trySend(Command.Stop)}
    suspend fun configure(enabled:Boolean) {
        val request=configuration.incrementAndGet();inhibited=true;running.get()?.cancel()
        val reply=CompletableDeferred<Unit>()
        check(commands.trySend(Command.Configure(enabled,reply,request)).isSuccess) {"Photo controller is closed"}
        reply.await()
    }
    suspend fun clearAll()=configure(false)
    private suspend fun handle(command:Command) {when(command) {
        Command.Options->{val value=options.first();loaded=true;if(isEnabled!=value) {isEnabled=value;if(!isEnabled) {stopWorker();unwatch()}};attempt(false)}
        is Command.Visible->{val wasVisible=visible;visible=command.value;if(!visible) {stopWorker();unwatch();publish(message=if(isEnabled) "Automatic photo sync resumes when Whereday is open." else null)} else if(!wasVisible) attempt(false)}
        is Command.Range->{if(map!=command.value) {map=command.value;attempt(true)}}
        is Command.Configure->{
            stopWorker();unwatch()
            if(command.enabled && access()==PhotoLibraryAccess.None) throw IllegalStateException("Choose library access first.")
            save(command.enabled);isEnabled=command.enabled;loaded=true
            if(configuration.get()==command.request) inhibited=false
            attempt(command.enabled);command.reply.complete(Unit)
        }
        is Command.Finished->{if(command.generation==generation) {
            worker=null;running.set(null);retry=!command.success
            if(command.success && command.didSync) {lastElapsed=elapsed();lastSuccess=now();lastVersion=command.version}
            val again=pending;pending=false
            if(again) attempt(true) else publish(error=if(command.success) null else "Automatic photo sync could not finish. Try again.")
        }}
        is Command.LibraryWake->{if(command.epoch==epoch) attempt(true)}
        Command.Refresh->attempt(true)
        Command.Stop->{stopWorker();unwatch();if(visible && isEnabled && access()!=PhotoLibraryAccess.None) watch();publish(message="Photo sync stopped. Automatic sync remains ${if(isEnabled) "enabled" else "off"}.")}
        is Command.Failure->{stopWorker();unwatch();publish(error=command.message)}
    }}
    private suspend fun attempt(force:Boolean) {
        if(!loaded || !isEnabled || !visible || !wantedForeground || inhibited) {publish();return}
        val current=access() to original()
        val changed=current!=capabilities;capabilities=current
        if(current.first==PhotoLibraryAccess.None) {stopWorker();unwatch();publish(message="Choose photo library access to use automatic sync.");return}
        if(subscription==null) watch()
        if(worker!=null) {pending=pending || force || changed;return}
        val since=lastElapsed?.let {elapsed()-it}
        val maySkip=!force && !changed && !retry && since!=null && since in 0 until 600_000 && current.first!=PhotoLibraryAccess.Partial
        val previousVersion=lastVersion
        val token=++generation;val selectedMap=map
        worker=scope.launch(Dispatchers.IO,start=CoroutineStart.LAZY) {
            var success=false;var didSync=false;var stamp:String?=null
            try {
                if(!wantedForeground || inhibited) throw CancellationException("Automatic sync paused")
                stamp=version()
                if(maySkip && stamp!=null && stamp==previousVersion) success=true
                else {val range=resolveRange(selectedMap);didSync=true;success=scan(range)}
            } catch(e:CancellationException) {throw e} catch(_:Exception) { /* Exposed through the completion state. */ }
            finally {commands.trySend(Command.Finished(token,success,didSync,stamp))}
        }
        running.set(worker);publish();worker!!.start()
    }
    private suspend fun stopWorker() {
        generation++;pending=false;debounce?.cancel();debounce=null
        while(changes.tryReceive().isSuccess) { /* Drop only already queued wakeups. */ }
        val current=worker;worker=null;running.set(null);current?.cancelAndJoin()
    }
    private fun watch() {val token=++epoch;subscription=observe {changes.trySend(token)}}
    private fun unwatch() {epoch++;val old=subscription;subscription=null;old?.close()}
    private fun publish(error:String?=null,message:String?=null) {mutable.value=AutomaticPhotoState(loaded,isEnabled,worker!=null,subscription!=null,lastSuccess,error,message)}
    internal suspend fun close() {val job=scope.coroutineContext[Job]!!;job.cancelAndJoin()}
}
