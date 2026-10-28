package com.bontecou.whereday.platform

import android.content.Context
import com.bontecou.whereday.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class CountryAnalysisState(val running: Boolean = false, val progress: CountryAnalysisProgress = CountryAnalysisProgress())

class CountryAnalysisController(context: Context, database: HistoryDatabase, private val preferences: AppPreferences) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val engine=CountryAnalysis(database,GeographicCountryLookup(context),GeographicCountryLookup.VERSION)
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
                if(!networkAllowed) { mutable.value=CountryAnalysisState(progress=CountryAnalysisProgress(message="Enable network geocoding in Settings, or add a manual stay.")); return@launch }
                engine.run({ networkAllowed }) { mutable.value=CountryAnalysisState(true,it) }
            } catch(e:CancellationException) {
                mutable.value=mutable.value.copy(progress=mutable.value.progress.copy(message="Analysis stopped. Completed results are saved; analyze again to continue."))
                throw e
            } catch(e:Exception) {
                mutable.value=mutable.value.copy(progress=mutable.value.progress.copy(message="Country lookup is temporarily unavailable. Completed results are saved. Check your connection and try again later."))
            } finally { mutable.value=mutable.value.copy(running=false) }
        }
        job=task
        task.start()
    }
    fun stop() { synchronized(lock) {
        if(job?.isCompleted==false) mutable.value=mutable.value.copy(progress=mutable.value.progress.copy(message="Stopping analysis…"))
        job?.cancel()
    } }
    suspend fun stopAndJoin() {
        val active=synchronized(lock) { job?.also { it.cancel() } }
        active?.join()
    }
}
