package com.bontecou.whereday.platform

import android.content.Context
import com.bontecou.whereday.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CountryAnalysisState(val running: Boolean = false, val progress: CountryAnalysisProgress = CountryAnalysisProgress())

class CountryAnalysisController(private val preferences:AppPreferences,private val engine:CountryAnalysis) {
    constructor(context:Context,database:HistoryDatabase,preferences:AppPreferences):this(preferences,CountryAnalysis(database,GeographicCountryLookup(context),GeographicCountryLookup.VERSION))
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutable=MutableStateFlow(CountryAnalysisState())
    val state=mutable.asStateFlow()
    private val lock=Any()
    private var job: Job?=null
    @Volatile private var networkAllowed=false
    init {
        scope.launch {
            preferences.settings.map { it.networkGeocoding }.distinctUntilChanged().collect { enabled ->
                networkAllowed=enabled
                if(!enabled) stop()
            }
        }
    }
    fun start() = synchronized(lock) {
        if(job?.isCompleted==false) return@synchronized
        val task=scope.launch(start=CoroutineStart.LAZY) {
            mutable.value=CountryAnalysisState(running=true)
            try {
                networkAllowed=preferences.settings.first().networkGeocoding
                if(!networkAllowed) { mutable.value=CountryAnalysisState(progress=CountryAnalysisProgress(status=CountryAnalysisStatus.disabled)); return@launch }
                engine.run({ networkAllowed && preferences.settings.first().networkGeocoding }) { mutable.value=CountryAnalysisState(true,it) }
            } catch(e:CancellationException) {
                mutable.value=mutable.value.copy(progress=mutable.value.progress.copy(status=CountryAnalysisStatus.stopped))
                throw e
            } catch(e:Exception) {
                mutable.value=mutable.value.copy(progress=mutable.value.progress.copy(status=CountryAnalysisStatus.unavailable))
            } finally { mutable.value=mutable.value.copy(running=false) }
        }
        job=task
        task.start()
    }
    suspend fun close() {stopAndJoin();scope.coroutineContext.job.cancelAndJoin()}
    fun stop() { synchronized(lock) {
        if(job?.isCompleted==false) mutable.value=mutable.value.copy(progress=mutable.value.progress.copy(status=CountryAnalysisStatus.stopping))
        job?.cancel()
    } }
    suspend fun stopAndJoin() {
        val active=synchronized(lock) { job?.also { it.cancel() } }
        active?.join()
    }
}
