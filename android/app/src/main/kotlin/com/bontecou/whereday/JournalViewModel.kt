package com.bontecou.whereday

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*

data class MapSelection(val preset: String = "Today", val firstDay: LocalDate? = null, val lastDay: LocalDate? = null, val outingRange: HistoryRange? = null,val visitFocus:VisitMapFocus?=null) {
    val tracksNow:Boolean get()=outingRange==null && preset!="Custom" && preset!="Yesterday"
    fun range(reference:Instant,zone:ZoneId):HistoryRange = outingRange ?: when(preset) {
        "Custom"->HistoryRange.days(requireNotNull(firstDay),requireNotNull(lastDay),zone)
        else->mapPresetRange(preset,reference,zone)
    }

}
sealed interface WindowState {
    data object Loading : WindowState
    data class Ready(val window: HistoryWindow): WindowState
    data class Failed(val message: String): WindowState
}

@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModel(private val graph: AppGraph,private val clock:Clock=Clock.systemUTC(),private val zone:()->ZoneId={ZoneId.systemDefault()}): ViewModel() {
    val mapSelection=MutableStateFlow(MapSelection())
    internal fun mapDateDraft():MapDateDraft {
        val selectedZone=zone()
        return MapDateDraft(mapSelection.value.range(clock.instant(),selectedZone),selectedZone)
    }
    val timelineDay=MutableStateFlow(clock.instant().atZone(zone()).toLocalDate())
    private val refresh=MutableStateFlow(0)
    private var clearGeneration=graph.journalClear.state.value.generation
    private val mapTick=MutableStateFlow(0L)
    private data class MapRequest(val selection:MapSelection,val retry:Int,val outliers:Boolean,val tick:Long)
    private var lastMapLoad:Pair<MapSelection,Int>?=null
    val mapWindow=combine(mapSelection,refresh,graph.preferences.settings,mapTick) {selection,version,settings,tick->
        // A different visit on the same day changes presentation without reloading that window.
        MapRequest(selection.copy(visitFocus=null),version,settings.showOutliers,if(selection.tracksNow) tick else 0)
    }.distinctUntilChanged().flatMapLatest {request->
        val key=request.selection to request.retry;val loading=lastMapLoad!=key;lastMapLoad=key
        presentWindow(graph.history.observeWindow({request.selection.range(clock.instant(),zone())},includeOutliers=request.outliers),loading)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(stopTimeoutMillis=0,replayExpirationMillis=0),WindowState.Loading)
    val timelineWindow=combine(timelineDay,refresh,graph.preferences.settings) { day,_,settings ->
        HistoryRange.day(day,zone()) to settings.inferenceRules.takeIf { settings.inferredEnabled }
    }.flatMapLatest { (range,rules) -> windowFlow(range,rules) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(stopTimeoutMillis=0,replayExpirationMillis=0),WindowState.Loading)
    init {
        viewModelScope.launch {mapSelection.collect {selection->graph.automaticPhotos.mapRange(photoRange(selection))}}
        viewModelScope.launch {
            var day=clock.instant().atZone(zone()).toLocalDate();var previousZone=zone()
            while(isActive) {
                delay(30_000)
                val currentZone=zone();val currentDay=clock.instant().atZone(currentZone).toLocalDate()
                if(day!=currentDay || previousZone!=currentZone) {
                    day=currentDay;previousZone=currentZone;refresh.value++;graph.automaticPhotos.mapRange(photoRange(mapSelection.value))
                } else mapTick.value++
            }
        }
    }
    // Automatic library sync still treats All as its explicit all-history scope.
    private fun photoRange(selection:MapSelection)=if(selection.preset=="All" && selection.outingRange==null) HistoryRange.all else selection.range(clock.instant(),zone())
    private fun windowFlow(range: HistoryRange,rules: InferenceRules?=null,includeOutliers:Boolean=true,loading:Boolean=true)=presentWindow(graph.history.observeWindow(range,rules,includeOutliers),loading)
    private fun presentWindow(windows:Flow<HistoryWindow>,loading:Boolean):Flow<WindowState> = windows
        .map<HistoryWindow,WindowState> { WindowState.Ready(it) }
        .onStart {if(loading) emit(WindowState.Loading)}
        .catch { error -> if(error is CancellationException) throw error; emit(WindowState.Failed("History could not be loaded. Try again.")) }
    fun journalCleared(generation:String?) {
        if(generation==null || generation==clearGeneration) return
        clearGeneration=generation
        mapSelection.value=MapSelection();timelineDay.value=clock.instant().atZone(zone()).toLocalDate();lastMapLoad=null;refresh.value++
    }
    fun retry() {refresh.value++;graph.automaticPhotos.refresh()}
    fun showVisitOnMap(visit:Visit) {mapSelection.value=visitMapSelection(visit,zone())}
    fun showOutingOnMap(session: RecordingSession) {
        mapSelection.value=MapSelection(preset="Outing",outingRange=HistoryRange(session.startedAt,(session.endedAt ?: clock.instant()).plusMillis(1)))
    }
}
