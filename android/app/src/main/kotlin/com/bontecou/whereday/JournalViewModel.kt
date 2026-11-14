package com.bontecou.whereday

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import com.bontecou.whereday.platform.RecordingRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*

data class MapSelection(val preset: String = "Today", val firstDay: LocalDate? = null, val lastDay: LocalDate? = null, val outingRange: HistoryRange? = null,val visitFocus:VisitMapFocus?=null,val outingFocusRequest:String?=null) {
    fun contentScope()=copy(visitFocus=null,outingFocusRequest=null)
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
class JournalViewModel(private val graph: AppGraph,private val clock:Clock=Clock.systemUTC(),private val zone:()->ZoneId={ZoneId.systemDefault()},private val savedState:SavedStateHandle=SavedStateHandle()): ViewModel() {
    val mapSelection=MutableStateFlow(MapSelection())
    internal fun mapDateDraft():MapDateDraft {
        val selectedZone=zone()
        return MapDateDraft(mapSelection.value.range(clock.instant(),selectedZone),selectedZone)
    }
    private val restoredTimelineDay=(savedState.get<Any?>("timeline-v1-day") as? String)?.takeIf {it.length<=32}?.let {runCatching {LocalDate.parse(it).also {day->timelineDayRange(day,zone())}}.getOrNull()}
    val timelineDay=MutableStateFlow(restoredTimelineDay ?: timelineToday())
    private var timelineInitialized=restoredTimelineDay!=null || savedState.get<Any?>("timeline-v1-initialized")==true
    private val refresh=MutableStateFlow(0)
    private var clearGeneration=if(restoredTimelineDay!=null && savedState.contains("timeline-v1-generation")) (savedState.get<Any?>("timeline-v1-generation") as? String)?.ifEmpty {null} else graph.journalClear.state.value.generation
    private val mapTick=MutableStateFlow(0L)
    private data class MapRequest(val selection:MapSelection,val retry:Int,val outliers:Boolean,val tick:Long)
    private var lastMapLoad:Pair<MapSelection,Int>?=null
    val mapWindow=combine(mapSelection,refresh,graph.preferences.settings,mapTick) {selection,version,settings,tick->
        // A different visit on the same day changes presentation without reloading that window.
        MapRequest(selection.contentScope(),version,settings.showOutliers,if(selection.tracksNow) tick else 0)
    }.distinctUntilChanged().flatMapLatest {request->
        val key=request.selection to request.retry;val loading=lastMapLoad!=key;lastMapLoad=key
        presentWindow(graph.history.observeWindow({request.selection.range(clock.instant(),zone())},includeOutliers=request.outliers),loading)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(stopTimeoutMillis=0,replayExpirationMillis=0),WindowState.Loading)
    val timelineWindow=combine(timelineDay,refresh,graph.preferences.settings,RecordingRuntime.active) { day,_,settings,active ->
        Triple(HistoryRange.day(day,zone()),settings.inferenceRules.takeIf { settings.inferredEnabled },active)
    }.flatMapLatest { (range,rules,active) ->
        presentWindow(graph.history.observeWindow(range,rules,includeTimelineActivity=true,timelineRuntimeActive=active),true).onEach {state->
            if(state is WindowState.Ready && !timelineInitialized && state.window.range==timelineRange(timelineDay.value)) {
                timelineInitialized=true;savedState["timeline-v1-initialized"]=true
                val window=state.window
                rememberTimelineDay(initialTimelineDay(timelineDay.value,timelineToday(),window.visits.isNotEmpty() || window.sessions.isNotEmpty() || window.inferred.isNotEmpty(),window.latestTimelineActivity?.atZone(zone())?.toLocalDate()))
            }
        }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(stopTimeoutMillis=0,replayExpirationMillis=0),WindowState.Loading)
    init {
        viewModelScope.launch {timelineDay.collect {savedState["timeline-v1-day"]=it.toString();savedState["timeline-v1-generation"]=clearGeneration.orEmpty()}}
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
    fun timelineToday():LocalDate=clock.instant().atZone(zone()).toLocalDate()
    fun timelineZone():ZoneId=zone()
    fun timelineRange(day:LocalDate)=timelineDayRange(day,zone())
    fun timelineLatestDay(window:HistoryWindow)=window.latestTimelineActivity?.atZone(zone())?.toLocalDate()
    fun adjacentTimelineDay(step:Int)=adjacentTimelineDay(timelineDay.value,step,zone())
    internal fun openTimelineDate(day:LocalDate=timelineDay.value):TimelineDateOpening {
        // Opening a chooser is already an explicit choice, before its pending date is applied.
        selectTimelineDay(day)
        return TimelineDateOpening(day,zone())
    }
    fun selectTimelineDay(day:LocalDate) {
        timelineRange(day)
        timelineInitialized=true;savedState["timeline-v1-initialized"]=true
        rememberTimelineDay(day)
    }
    private fun rememberTimelineDay(day:LocalDate) {
        savedState["timeline-v1-day"]=day.toString();savedState["timeline-v1-generation"]=clearGeneration.orEmpty()
        timelineDay.value=day
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
        timelineInitialized=false;savedState["timeline-v1-initialized"]=false;savedState["timeline-v1-generation"]=generation
        mapSelection.value=MapSelection();rememberTimelineDay(timelineToday());lastMapLoad=null;refresh.value++
    }
    fun retry() {refresh.value++;graph.automaticPhotos.refresh()}
    fun showVisitOnMap(visit:Visit) {mapSelection.value=visitMapSelection(visit,zone())}
    fun showOutingOnMap(target:OutingMapTarget) {
        mapSelection.value=MapSelection(preset="Outing",outingRange=target.range,outingFocusRequest=target.requestId)
        // Serialize the new authorized scope before requesting an optional photo rescan.
        graph.automaticPhotos.mapRange(target.range);graph.automaticPhotos.refresh()
    }
}
