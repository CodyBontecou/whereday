package com.bontecou.whereday

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*

data class MapSelection(val preset: String = "Today", val firstDay: LocalDate? = null, val lastDay: LocalDate? = null, val outingRange: HistoryRange? = null) {
    fun range(today: LocalDate, zone: ZoneId): HistoryRange = outingRange ?: when(preset) {
        "Yesterday" -> HistoryRange.day(today.minusDays(1),zone)
        "7 days" -> HistoryRange.days(today.minusDays(6),today,zone)
        "30 days" -> HistoryRange.days(today.minusDays(29),today,zone)
        "All" -> HistoryRange.all
        "Custom" -> HistoryRange.days(requireNotNull(firstDay),requireNotNull(lastDay),zone)
        else -> HistoryRange.day(today,zone)
    }
}
sealed interface WindowState {
    data object Loading : WindowState
    data class Ready(val window: HistoryWindow): WindowState
    data class Failed(val message: String): WindowState
}

@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModel(private val graph: AppGraph): ViewModel() {
    val mapSelection=MutableStateFlow(MapSelection())
    val timelineDay=MutableStateFlow(LocalDate.now())
    private val refresh=MutableStateFlow(0)
    private var lastMapLoad:Pair<HistoryRange,Int>?=null
    val mapWindow=combine(mapSelection,refresh,graph.preferences.settings) {selection,version,settings->Triple(selection.range(LocalDate.now(),ZoneId.systemDefault()),version,settings.showOutliers)}
        .distinctUntilChanged().flatMapLatest {(range,version,outliers)->
            val key=range to version;val loading=lastMapLoad!=key;lastMapLoad=key
            windowFlow(range,includeOutliers=outliers,loading=loading)
        }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),WindowState.Loading)
    val timelineWindow=combine(timelineDay,refresh,graph.preferences.settings) { day,_,settings ->
        HistoryRange.day(day,ZoneId.systemDefault()) to settings.inferenceRules.takeIf { settings.inferredEnabled }
    }.flatMapLatest { (range,rules) -> windowFlow(range,rules) }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),WindowState.Loading)
    init {
        viewModelScope.launch {mapSelection.collect {selection->graph.automaticPhotos.mapRange(selection.range(LocalDate.now(),ZoneId.systemDefault()))}}
        viewModelScope.launch {
            var day=LocalDate.now(); var zone=ZoneId.systemDefault()
            while(isActive) {
                delay(30_000)
                if(day!=LocalDate.now() || zone!=ZoneId.systemDefault()) { day=LocalDate.now(); zone=ZoneId.systemDefault(); refresh.value++;graph.automaticPhotos.mapRange(mapSelection.value.range(day,zone)) }
            }
        }
    }
    private fun windowFlow(range: HistoryRange,rules: InferenceRules?=null,includeOutliers:Boolean=true,loading:Boolean=true): Flow<WindowState> = graph.history.observeWindow(range,rules,includeOutliers)
        .map<HistoryWindow,WindowState> { WindowState.Ready(it) }
        .onStart {if(loading) emit(WindowState.Loading)}
        .catch { error -> if(error is CancellationException) throw error; emit(WindowState.Failed("History could not be loaded. Try again.")) }
    fun retry() {refresh.value++;graph.automaticPhotos.refresh()}
    fun showOutingOnMap(session: RecordingSession) {
        mapSelection.value=MapSelection(preset="Outing",outingRange=HistoryRange(session.startedAt,(session.endedAt ?: Instant.now()).plusMillis(1)))
    }
}
