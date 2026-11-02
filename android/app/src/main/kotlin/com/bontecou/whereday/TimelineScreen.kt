package com.bontecou.whereday

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.data.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.launch
import java.time.*

@OptIn(ExperimentalLayoutApi::class)
@Composable fun TimelineScreen(graph: AppGraph, model: JournalViewModel, metric: Boolean, showOnMap: (RecordingSession) -> Unit,onVisitOpened:((Visit)->Unit)?=null,visitInspectionActive:Boolean=false) {
    val day by model.timelineDay.collectAsStateWithLifecycle()
    val state by model.timelineWindow.collectAsStateWithLifecycle()
    val resources=LocalContext.current.resources
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val formattedDay=day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    val todayHint=stringResource(R.string.timeline_today_hint,formattedDay)
    var selectedSession by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable(stateSaver=VisitEditorVisitSaver) { mutableStateOf<Visit?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    if(selectedSession != null) {
        val header=(state as? WindowState.Ready)?.window?.inferred?.find { it.id==selectedSession }
        if(selectedSession!!.startsWith("inferred:") && header==null) {
            Column(Modifier.padding(20.dp)) { TextButton(onClick={ selectedSession=null }) { Text(stringResource(R.string.timeline_back)) }; Text(stringResource(R.string.timeline_inferred_missing)) }
        } else {
            val window=(state as? WindowState.Ready)?.window
            val chronological=(window?.sessions.orEmpty().map {it.id to it.startedAt}+window?.inferred.orEmpty().map {it.id to it.startedAt})
                .sortedWith(compareBy({it.second},{it.first}))
            val sequence=(chronological.indexOfFirst {it.first==selectedSession}+1).coerceAtLeast(1)
            OutingDetailScreen(graph,selectedSession!!,metric,{ selectedSession=null },showOnMap,header,sequence,onVisitOpened,visitInspectionActive)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("timeline-content"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            TextButton(onClick={ model.timelineDay.value=day.minusDays(1) },modifier=Modifier.testTag("timeline-previous")) { Text(stringResource(R.string.timeline_previous)) }
            TextButton(onClick={ model.timelineDay.value=LocalDate.now() },modifier=Modifier.testTag("timeline-today").semantics {contentDescription=todayHint}) { Text(formattedDay) }
            TextButton(onClick={ model.timelineDay.value=day.plusDays(1) },modifier=Modifier.testTag("timeline-next")) { Text(stringResource(R.string.timeline_next)) }
        } }
        item { OutlinedButton(onClick={ adding=true }) { Icon(Icons.Outlined.Add,null); Text(stringResource(R.string.map_add_past)) } }
        when(val current=state) {
            WindowState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            is WindowState.Failed -> item { Text(current.message); TextButton(onClick=model::retry) { Text(stringResource(R.string.map_retry_history)) } }
            is WindowState.Ready -> {
                val window=current.window
                item {
                    val outings=window.sessions.size+window.inferred.size
                    val visits=window.visits.size
                    Text(stringResource(R.string.timeline_summary,resources.getQuantityString(R.plurals.timeline_outing_count,outings,outings),resources.getQuantityString(R.plurals.timeline_visit_count,visits,visits),distanceText(window.summary.distanceMeters,metric,locale)),style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("timeline-summary"))
                }
                val chronological=(window.sessions.map {it.id to it.startedAt}+window.inferred.map {it.id to it.startedAt}).sortedWith(compareBy({it.second},{it.first}))
                val sequences=chronological.mapIndexed {index,entry->entry.first to index+1}.toMap()
                val entries=(window.visits.map { TimelineEntry(it.id,it.arrivedAt,visit=it) }+window.sessions.map { TimelineEntry(it.id,it.startedAt,session=it) }+window.inferred.map { TimelineEntry(it.id,it.startedAt,inferred=it) }).sortedByDescending { it.time }
                if(entries.isEmpty()) item { Text(stringResource(R.string.timeline_empty),style=MaterialTheme.typography.bodyLarge,modifier=Modifier.testTag("timeline-empty")) }
                items(entries,key={ if(it.visit!=null) "visit:${it.id}" else "session:${it.id}" }) { entry ->
                    val visit=entry.visit
                    if(visit!=null) {
                        Card(Modifier.fillMaxWidth().clickable { if(onVisitOpened!=null) onVisitOpened(visit) else editing=visit }) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(visit.name?.takeIf {it.isNotBlank()} ?: stringResource(R.string.timeline_visit),style=MaterialTheme.typography.titleMedium)
                            Text(timelineRange(visit.arrivedAt,visit.departedAt,R.string.visit_still_here))
                            visit.address?.let { Text(it) }; visit.notes?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            Text(confirmationLabel(visit.confirmation),style=MaterialTheme.typography.labelSmall)
                            val unknownTitle=stringResource(R.string.calendar_unknown_place)
                            CalendarEventButton(visit,makeDraft={now->CalendarEventDraft.visit(visit,unknownTitle,now)})
                        } }
                    } else {
                        val session=entry.session ?: entry.inferred!!.let { RecordingSession(it.id,it.startedAt,it.endedAt) }
                        ElevatedCard(Modifier.fillMaxWidth().clickable { selectedSession=session.id }) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(session.name?.takeIf {it.isNotBlank()} ?: stringResource(if(entry.inferred!=null) R.string.timeline_inferred else R.string.timeline_outing),style=MaterialTheme.typography.titleMedium)
                            Text(timelineRange(session.startedAt,session.endedAt,R.string.timeline_recording))
                            entry.inferred?.let { Text(stringResource(R.string.timeline_point_summary,resources.getQuantityString(R.plurals.timeline_point_count,it.summary.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),it.summary.count),distanceText(it.summary.distanceMeters,metric,locale)),style=MaterialTheme.typography.bodyMedium) }
                            session.notes?.takeIf { it.isNotBlank() }?.let { Text(it) }
                            Text(stringResource(R.string.timeline_view_outing),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
                            val calendarTitle=stringResource(R.string.calendar_outing_title,sequences[session.id] ?: 1)
                            CalendarEventButton(session,makeDraft={now->
                                val detail=entry.inferred?.let {graph.history.inferredDetail(it,displayLimit=2)} ?: graph.history.outingDetail(session.id,displayLimit=2)
                                CalendarEventDraft.outing(detail.session,detail.endpoints.first?.coordinate,calendarTitle,now)
                            })
                        } }
                    }
                }
            }
        }
    }
    if(adding || editing!=null) {
        val expected=editing
        VisitEditor(graph,expected,{adding=false;editing=null},{value,reuse,remember,overlap->graph.history.saveVisit(value,expected,reuse,remember,overlap)},expected?.let {value->{graph.history.deleteVisit(value)}})
    }
}
private data class TimelineEntry(val id: String,val time: Instant,val visit: Visit?=null,val session: RecordingSession?=null,val inferred: InferredOutingHeader?=null)

